package com.seobin.phonedrop.assistant.agent;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import com.seobin.phonedrop.assistant.AssistantClock;
import com.seobin.phonedrop.assistant.AssistantDb;
import com.seobin.phonedrop.assistant.Jobs;
import com.seobin.phonedrop.assistant.Json;
import com.seobin.phonedrop.assistant.SettingsService;
import com.seobin.phonedrop.assistant.Store;
import com.seobin.phonedrop.assistant.TimeUtil;
import com.seobin.phonedrop.assistant.TurnQueue;
import com.seobin.phonedrop.assistant.Tx;

import tools.jackson.databind.node.ObjectNode;

/**
 * 먼저 말 걸기(빌드문서 v3 §5). timing worker 의 작업이 turn 을 만든다. 판단(무엇을 말할지)은 LLM 이 한다.
 * - pulse: pulse.interval_minutes 마다. quiet·일정 중·작업 중·직전 30분 turn·soft 상한이면 건너뛴다.
 * - morning: 기상 후 첫 first_unlock, 없으면 기상 + morning.fallback_minutes. 하루 1회(daily_flags).
 * - evening: 취침 − evening.before_sleep_minutes. 하루 1회.
 * - wakeup_fire: schedule_wakeup 으로 예약한 시각.
 */
@Service
public class ProactiveService {

    public static final String PULSE = "pulse";
    public static final String MORNING_FALLBACK = "morning_fallback";
    public static final String EVENING = "evening";
    public static final String WAKEUP_FIRE = "wakeup_fire";

    /** wakeup 이 이보다 늦게 실행되면 건너뛴다(v3 §4.3). */
    static final Duration WAKEUP_MAX_LATE = Duration.ofHours(2);
    /** 직전 이 시간 안에 다른 turn 이 실행됐으면 pulse 를 건너뛴다(v3 §5.1). */
    static final Duration PULSE_QUIET_AFTER_TURN = Duration.ofMinutes(30);

    private final AssistantDb db;
    private final AssistantClock clock;
    private final Tx tx;
    private final Jobs jobs;
    private final Store store;
    private final TimeUtil time;
    private final SettingsService settings;
    private final TurnQueue turns;
    private final ChatService chat;
    private final boolean enabled;

    public ProactiveService(AssistantDb db, AssistantClock clock, Tx tx, Jobs jobs, Store store, TimeUtil time,
                            SettingsService settings, TurnQueue turns, ChatService chat,
                            @Value("${assistant.worker.enabled:true}") boolean enabled) {
        this.db = db;
        this.clock = clock;
        this.tx = tx;
        this.jobs = jobs;
        this.store = store;
        this.time = time;
        this.settings = settings;
        this.turns = turns;
        this.chat = chat;
        this.enabled = enabled;
        jobs.register(PULSE, this::onPulse);
        jobs.register(MORNING_FALLBACK, this::onMorningFallback);
        jobs.register(EVENING, this::onEvening);
        jobs.register(WAKEUP_FIRE, this::onWakeupFire);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (enabled) {
            tx.exec(this::ensureJobs);
        }
    }

    /** pulse·아침·저녁 작업을 다시 예약한다. 시작할 때와 설정(기상·취침·간격)이 바뀔 때. */
    public void ensureJobs() {
        cancelScheduled(PULSE);
        cancelScheduled(MORNING_FALLBACK);
        cancelScheduled(EVENING);
        Instant now = clock.now();
        jobs.schedule(PULSE, 0, now.plus(Duration.ofMinutes(settings.getInt("pulse.interval_minutes"))), null);
        LocalDate today = time.logicalDay(now);
        Instant morning = morningFallbackAt(today);
        jobs.schedule(MORNING_FALLBACK, 0, morning.isAfter(now) ? morning : morningFallbackAt(today.plusDays(1)), null);
        Instant evening = eveningAt(today);
        jobs.schedule(EVENING, 0, evening.isAfter(now) ? evening : eveningAt(today.plusDays(1)), null);
    }

    private void cancelScheduled(String type) {
        db.update("UPDATE jobs SET status = 'cancelled', updated_at = CURRENT_TIMESTAMP WHERE status = 'scheduled' AND type = ?",
                type);
    }

    private Instant morningFallbackAt(LocalDate day) {
        return time.activeWindow(day).start().plus(Duration.ofMinutes(settings.getInt("morning.fallback_minutes")));
    }

    private Instant eveningAt(LocalDate day) {
        return time.activeWindow(day).end().minus(Duration.ofMinutes(settings.getInt("evening.before_sleep_minutes")));
    }

    // ------------------------------------------------------------------ pulse (§5.1)

    private boolean onPulse(Jobs.Job job) {
        Instant now = clock.now();
        jobs.schedule(PULSE, 0, now.plus(Duration.ofMinutes(settings.getInt("pulse.interval_minutes"))), null);
        String skip = pulseSkipReason(now);
        if (skip != null) {
            return false;
        }
        turns.enqueue("pulse", Json.object(), TurnQueue.PRIORITY_PULSE);
        return true;
    }

    /** pulse 를 건너뛸 이유. 없으면 null. */
    String pulseSkipReason(Instant now) {
        if (time.isQuiet(now)) {
            return "quiet";
        }
        if (eventInProgress(now)) {
            return "event";
        }
        if (!store.startedBlocks().isEmpty()) {
            return "started_block";
        }
        int recent = db.jdbc().sql("""
                        SELECT COUNT(*) FROM turns WHERE status NOT IN ('queued', 'skipped') AND started_at >= ?""")
                .param(AssistantDb.ts(now.minus(PULSE_QUIET_AFTER_TURN))).query(Integer.class).single();
        if (recent > 0) {
            return "recent_turn";
        }
        if (overSoftLimit()) {
            return "soft_limit";
        }
        return null;
    }

    /** agent.daily_turn_soft 를 넘었으면 pulse·웹 검색·작업물 생성을 멈춘다(v3 §1.1). */
    public boolean overSoftLimit() {
        return chat.turnsToday() >= settings.getInt("agent.daily_turn_soft");
    }

    /** 고정 일정 진행 중(버퍼 제외). */
    public boolean eventInProgress(Instant now) {
        return store.activeEventsOverlapping(now, now.plusSeconds(1)).stream()
                .anyMatch(e -> !now.isBefore(e.start()) && now.isBefore(e.end()));
    }

    // ------------------------------------------------------------------ 아침·저녁 (§5.2)

    private boolean onMorningFallback(Jobs.Job job) {
        LocalDate day = time.logicalDay(job.fireAt());
        jobs.schedule(MORNING_FALLBACK, 0, morningFallbackAt(day.plusDays(1)), null);
        return morning(day);
    }

    /** heartbeat events 의 first_unlock. 오늘 기상 이후이고 아직 아침 브리핑 전이면 morning turn. */
    public void onFirstUnlock(Instant at) {
        Instant now = clock.now();
        LocalDate today = time.logicalDay(now);
        // 오늘 기상~취침 안의 잠금 해제만 본다(수면 중 새벽 확인은 아침이 아니다).
        if (at == null || at.isAfter(now.plusSeconds(60)) || !time.activeWindow(today).contains(at)) {
            return;
        }
        morning(today);
    }

    private boolean morning(LocalDate day) {
        if (flag(day, "morning_done")) {
            return false;
        }
        setFlag(day, "morning_done");
        ObjectNode ref = Json.object();
        ref.put("logical_day", day.toString());
        turns.enqueue("morning", ref, TurnQueue.PRIORITY_DEFAULT);
        return true;
    }

    private boolean onEvening(Jobs.Job job) {
        LocalDate day = time.logicalDay(job.fireAt());
        jobs.schedule(EVENING, 0, eveningAt(day.plusDays(1)), null);
        if (flag(day, "evening_done")) {
            return false;
        }
        setFlag(day, "evening_done");
        ObjectNode ref = Json.object();
        ref.put("logical_day", day.toString());
        turns.enqueue("evening", ref, TurnQueue.PRIORITY_DEFAULT);
        return true;
    }

    private boolean flag(LocalDate day, String column) {
        return db.jdbc().sql("SELECT " + column + " FROM daily_flags WHERE logical_day = ?").param(day)
                .query(Boolean.class).optional().orElse(false);
    }

    private void setFlag(LocalDate day, String column) {
        db.update("MERGE INTO daily_flags(logical_day) KEY(logical_day) VALUES (?)", day);
        db.update("UPDATE daily_flags SET " + column + " = TRUE WHERE logical_day = ?", day);
    }

    // ------------------------------------------------------------------ wakeup (§4.3)

    private boolean onWakeupFire(Jobs.Job job) {
        long id = job.targetId();
        record Wakeup(String status, Instant fireAt, String reason, Long taskId) {
        }
        Wakeup w = db.jdbc().sql("SELECT status, fire_at, reason, task_id FROM wakeups WHERE id = ?").param(id)
                .query((rs, n) -> new Wakeup(rs.getString(1), AssistantDb.instant(rs, "fire_at"), rs.getString(3),
                        AssistantDb.nullableLong(rs, "task_id")))
                .optional().orElse(null);
        if (w == null || !"scheduled".equals(w.status())) {
            return false;
        }
        Instant now = clock.now();
        boolean taskClosed = w.taskId() != null && store.task(w.taskId())
                .map(t -> !"active".equals(t.status())).orElse(true);
        boolean tooLate = Duration.between(w.fireAt(), now).compareTo(WAKEUP_MAX_LATE) > 0;
        if (taskClosed || tooLate) {
            setWakeupStatus(id, "skipped");
            return false;
        }
        setWakeupStatus(id, "done");
        ObjectNode ref = Json.object();
        ref.put("wakeup_id", id);
        ref.put("reason", w.reason());
        if (w.taskId() != null) {
            ref.put("task_id", w.taskId());
        }
        turns.enqueue("wakeup", ref, TurnQueue.PRIORITY_DEFAULT);
        return true;
    }

    private void setWakeupStatus(long id, String status) {
        db.update("UPDATE wakeups SET status = ?, updated_at = ? WHERE id = ?", status, clock.now(), id);
    }
}
