package com.seobin.phonedrop.assistant.agent;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.seobin.phonedrop.assistant.AssistantClock;
import com.seobin.phonedrop.assistant.AssistantDb;
import com.seobin.phonedrop.assistant.Jobs;
import com.seobin.phonedrop.assistant.Json;
import com.seobin.phonedrop.assistant.PlanService;
import com.seobin.phonedrop.assistant.SettingsService;
import com.seobin.phonedrop.assistant.Store;
import com.seobin.phonedrop.assistant.TimeUtil;
import com.seobin.phonedrop.assistant.TurnQueue;
import com.seobin.phonedrop.assistant.Tx;
import com.seobin.phonedrop.assistant.push.PushService;

import tools.jackson.databind.node.ObjectNode;

/**
 * 시간대가 열려 있는 동안의 동작(빌드문서 v3.6 §2). 작업 하나(time_window_tick)가 필요한 시각마다 스스로 다시 예약한다.
 * - dnd: 비서가 먼저 보내는 메시지는 InterruptPolicy 가 held_window_id 로 모아 두고, 시간대가 끝나면 여기서 묶음 1개로 보낸다.
 * - active: 사용자가 반응할 때까지 active.alarm_repeat_min 마다 알람(active.alarm 푸시). 반응(알람 끄기·채팅·개입 조작) 뒤에는
 *   아직 시작한 블록이 없으면 비서가 먼저 말 건다(active_window turn): 최소 llm_min_interval_min 간격, 회차당 llm_max 번.
 */
@Component
public class TimeWindowRunner {

    public static final String TICK = "time_window_tick";
    /** 시간대 안에서는 최소 이 간격으로 다시 본다(첫 블록 이동으로 끝이 바뀌는 것 등). */
    static final Duration IN_WINDOW_RECHECK = Duration.ofMinutes(5);
    static final Duration IDLE_RECHECK = Duration.ofHours(1);

    record Run(long id, long windowId, String kind, Instant occStart, int alarmCount, Instant alarmLastAt,
               Instant ackedAt, int llmCount, Instant llmLastAt) {
    }

    private final AssistantDb db;
    private final AssistantClock clock;
    private final Tx tx;
    private final Jobs jobs;
    private final Store store;
    private final TimeUtil time;
    private final SettingsService settings;
    private final TimeWindows windows;
    private final TurnQueue turns;
    private final PushService push;
    private final InterruptPolicy interrupts;
    private final PlanService plan;
    private final boolean enabled;

    public TimeWindowRunner(AssistantDb db, AssistantClock clock, Tx tx, Jobs jobs, Store store, TimeUtil time,
                            SettingsService settings, TimeWindows windows, TurnQueue turns, PushService push,
                            InterruptPolicy interrupts, PlanService plan,
                            @Value("${assistant.worker.enabled:true}") boolean enabled) {
        this.db = db;
        this.clock = clock;
        this.tx = tx;
        this.jobs = jobs;
        this.store = store;
        this.time = time;
        this.settings = settings;
        this.windows = windows;
        this.turns = turns;
        this.push = push;
        this.interrupts = interrupts;
        this.plan = plan;
        this.enabled = enabled;
        jobs.register(TICK, job -> {
            tick();
            return true;
        });
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (enabled) {
            tx.exec(this::reschedule);
        }
    }

    /** 시간대 설정이 바뀌거나 시작할 때: 지금 한 번 본다. */
    public void reschedule() {
        db.update("UPDATE jobs SET status = 'cancelled', updated_at = CURRENT_TIMESTAMP WHERE status = 'scheduled' AND type = ?",
                TICK);
        jobs.schedule(TICK, 0, clock.now(), null);
    }

    // ------------------------------------------------------------------ tick

    public void tick() {
        Instant now = clock.now();
        List<TimeWindows.Occ> current = windows.occurrences(now.minus(Duration.ofDays(2)), now.plusSeconds(1)).stream()
                .filter(o -> o.contains(now)).toList();

        // 끝난 회차: 기록을 닫고, 방해 금지였으면 모아 둔 메시지를 묶어 보낸다.
        for (Run r : openRuns()) {
            boolean still = current.stream().anyMatch(o -> o.windowId() == r.windowId() && o.start().equals(r.occStart()));
            if (!still) {
                db.update("UPDATE time_window_runs SET ended_at = ? WHERE id = ?", now, r.id());
                if ("active".equals(r.kind())) {
                    push.pushAll(Map.of("type", "active.alarm_end", "window_id", String.valueOf(r.windowId())));
                }
            }
        }
        releaseFinishedDnd(current);

        Instant next = now.plus(IDLE_RECHECK);
        for (TimeWindows.Occ o : current) {
            Run r = ensureRun(o);
            next = min(next, o.end());
            next = min(next, now.plus(IN_WINDOW_RECHECK));
            if (o.dnd()) {
                continue;
            }
            Instant due = active(o, r, now);
            if (due != null) {
                next = min(next, due);
            }
        }
        for (TimeWindows.Occ o : windows.occurrences(now, now.plus(IDLE_RECHECK))) {
            if (o.start().isAfter(now)) {
                next = min(next, o.start());
            }
        }
        if (next.isBefore(now.plusSeconds(30))) {
            next = now.plusSeconds(30);
        }
        db.update("UPDATE jobs SET status = 'cancelled', updated_at = CURRENT_TIMESTAMP WHERE status = 'scheduled' AND type = ?",
                TICK);
        jobs.schedule(TICK, 0, next, null);
    }

    /** 적극 개입 회차 처리. 다음에 볼 시각을 돌려준다(없으면 null). */
    private Instant active(TimeWindows.Occ o, Run r, Instant now) {
        if (!windows.activeEffective(now, store)) {
            // [10분만 조용히]·진행 중 고정 일정: 기본 동작. 멈춤이 끝나는 시각에 다시 본다.
            return windows.pausedUntil().filter(now::isBefore).orElse(null);
        }
        if (r.ackedAt() == null && !store.startedBlocks().isEmpty()) {
            // 이미 일을 시작했으면 반응한 것으로 본다(알람을 더 울리지 않는다).
            db.update("UPDATE time_window_runs SET acked_at = ?, llm_last_at = ? WHERE id = ?", now, now, r.id());
            push.pushAll(Map.of("type", "active.alarm_end", "window_id", String.valueOf(o.windowId())));
            return null;
        }
        if (r.ackedAt() == null && !store.runningInterventions().isEmpty()) {
            // 블록 개입 화면이 이미 반복해서 뜨고 있다(그쪽이 알람 역할).
            return now.plus(Duration.ofMinutes(Math.max(1, settings.getInt("active.alarm_repeat_min"))));
        }
        if (r.ackedAt() == null) {
            Duration repeat = Duration.ofMinutes(Math.max(1, settings.getInt("active.alarm_repeat_min")));
            if (r.alarmLastAt() == null || !now.isBefore(r.alarmLastAt().plus(repeat))) {
                sendAlarm(o, r);
                return now.plus(repeat);
            }
            return r.alarmLastAt().plus(repeat);
        }
        Duration interval = Duration.ofMinutes(Math.max(1, settings.getInt("active.llm_min_interval_min")));
        if (r.llmCount() >= settings.getInt("active.llm_max")) {
            return null;
        }
        Instant due = r.llmLastAt() == null ? now : r.llmLastAt().plus(interval);
        if (now.isBefore(due)) {
            return due;
        }
        if (!store.startedBlocks().isEmpty() || !store.runningInterventions().isEmpty() || recentTurn(now, interval)) {
            return now.plus(interval);
        }
        enqueueNudge(o, r, "nudge");
        return now.plus(interval);
    }

    private void sendAlarm(TimeWindows.Occ o, Run r) {
        Instant now = clock.now();
        db.update("UPDATE time_window_runs SET alarm_count = alarm_count + 1, alarm_last_at = ? WHERE id = ?", now, r.id());
        Map<String, String> data = new LinkedHashMap<>();
        data.put("type", "active.alarm");
        data.put("window_id", String.valueOf(o.windowId()));
        data.put("name", o.name());
        data.put("text", o.name() + " 시간이에요");
        data.put("until", time.iso(o.end()));
        data.put("repeat_minutes", String.valueOf(settings.getInt("active.alarm_repeat_min")));
        data.put("count", String.valueOf(r.alarmCount() + 1));
        push.pushAll(data);
    }

    private void enqueueNudge(TimeWindows.Occ o, Run r, String reason) {
        db.update("UPDATE time_window_runs SET llm_count = llm_count + 1, llm_last_at = ? WHERE id = ?", clock.now(), r.id());
        ObjectNode ref = Json.object();
        ref.put("window_id", o.windowId());
        ref.put("name", o.name());
        ref.put("until", time.iso(o.end()));
        ref.put("reason", reason);
        ref.put("count", r.llmCount() + 1);
        turns.enqueue("active_window", ref, TurnQueue.PRIORITY_DEFAULT);
    }

    /** 직전 interval 안에 다른 turn(사용자 대화 포함)이 있었으면 먼저 말 걸지 않는다. */
    private boolean recentTurn(Instant now, Duration interval) {
        return db.jdbc().sql("""
                        SELECT COUNT(*) FROM turns WHERE status NOT IN ('skipped') AND started_at > ?
                        AND trigger NOT IN ('document_process', 'memory_job', 'active_window')""")
                .param(AssistantDb.ts(now.minus(interval))).query(Integer.class).single() > 0;
    }

    // ------------------------------------------------------------------ 사용자 반응·멈춤

    /**
     * 사용자가 반응했다. viaAlarm 이면(알람 [확인]) 비서가 바로 먼저 말 건다(브리핑·첫 할 일 밀기). 채팅·개입 조작이면 그 turn 이
     * 따로 돌므로 간격만 새로 센다.
     */
    public boolean ack(boolean viaAlarm) {
        Instant now = clock.now();
        boolean any = false;
        for (TimeWindows.Occ o : windows.occurrences(now, now.plusSeconds(1))) {
            if (o.dnd() || !o.contains(now)) {
                continue;
            }
            Run r = ensureRun(o);
            if (r.ackedAt() != null) {
                continue;
            }
            any = true;
            db.update("UPDATE time_window_runs SET acked_at = ? WHERE id = ?", now, r.id());
            push.pushAll(Map.of("type", "active.alarm_end", "window_id", String.valueOf(o.windowId())));
            if (viaAlarm && windows.activeEffective(now, store) && r.llmCount() < settings.getInt("active.llm_max")) {
                enqueueNudge(o, r, "ack");
            } else {
                db.update("UPDATE time_window_runs SET llm_last_at = ? WHERE id = ?", now, r.id());
            }
        }
        if (any) {
            reschedule();
        }
        return any;
    }

    /** [10분만 조용히] (§2.3): active.pause_min 동안 기본 동작. 하루 횟수 제한 없음. */
    public Instant pause() {
        Instant until = clock.now().plus(Duration.ofMinutes(Math.max(1, settings.getInt("active.pause_min"))));
        settings.put(TimeWindows.PAUSED_UNTIL, tools.jackson.databind.node.JsonNodeFactory.instance.stringNode(time.iso(until)));
        push.pushAll(Map.of("type", "active.alarm_end", "window_id", "0"));
        reschedule();
        plan.bumpAndSync();
        return until;
    }

    // ------------------------------------------------------------------ 회차 기록

    private Run ensureRun(TimeWindows.Occ o) {
        db.update("""
                        MERGE INTO time_window_runs(window_id, kind, occ_start) KEY(window_id, occ_start)
                        VALUES (?, ?, ?)""",
                o.windowId(), o.kind(), AssistantDb.ts(o.start()));
        return db.jdbc().sql("SELECT * FROM time_window_runs WHERE window_id = ? AND occ_start = ?")
                .param(o.windowId()).param(AssistantDb.ts(o.start()))
                .query(TimeWindowRunner::run).single();
    }

    private List<Run> openRuns() {
        return db.jdbc().sql("SELECT * FROM time_window_runs WHERE ended_at IS NULL")
                .query(TimeWindowRunner::run).list();
    }

    private static Run run(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Run(rs.getLong("id"), rs.getLong("window_id"), rs.getString("kind"),
                AssistantDb.instant(rs, "occ_start"), rs.getInt("alarm_count"), AssistantDb.instant(rs, "alarm_last_at"),
                AssistantDb.instant(rs, "acked_at"), rs.getInt("llm_count"), AssistantDb.instant(rs, "llm_last_at"));
    }

    /** 모아 둔 메시지 중, 지금 열려 있지 않은 방해 금지 시간대의 것은 묶어서 보낸다. */
    private void releaseFinishedDnd(List<TimeWindows.Occ> current) {
        List<Long> held = db.jdbc().sql("SELECT DISTINCT held_window_id FROM messages WHERE held_window_id IS NOT NULL")
                .query(Long.class).list();
        List<Long> open = new ArrayList<>();
        current.stream().filter(TimeWindows.Occ::dnd).forEach(o -> open.add(o.windowId()));
        for (Long id : held) {
            if (!open.contains(id)) {
                String name = windows.list().stream().filter(w -> w.id() == id).map(TimeWindows.Window::name)
                        .findFirst().orElse("방해 금지 시간");
                interrupts.releaseWindow(id, name);
            }
        }
    }

    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }
}
