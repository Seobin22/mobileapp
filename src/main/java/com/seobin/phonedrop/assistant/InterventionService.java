package com.seobin.phonedrop.assistant;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.seobin.phonedrop.assistant.push.PushService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 개입 상태 머신(빌드문서 v1 §6).
 * 블록 시각 → start_intervention → (reshow 반복) → start 로 acked / expire / suspend_check.
 * 개입은 동시에 하나만 둔다.
 */
@Service
public class InterventionService {

    public static final String START = "start_intervention";
    public static final String RESHOW = "reshow";
    public static final String EXPIRE = "expire";
    public static final String SUSPEND = "suspend_check";
    public static final String BLOCK_END = "block_end";
    /** 풀스크린 대화 무응답(v3.1 §3.6). v3 의 negotiation_timeout 작업도 같은 처리기로 받는다(남아 있던 작업 호환). */
    public static final String TALK_TIMEOUT = "talk_timeout";
    static final String LEGACY_NEGOTIATION_TIMEOUT = "negotiation_timeout";
    /** 새 고정 블록의 금지 구간 시작에 진행 중 블록을 partial 로 끝낸다(v3.1 §3.1). */
    public static final String BLOCK_CUTOFF = "block_cutoff";
    /** 사용자가 [미루기 요청](구 버튼)을 누르면 이 말로 풀스크린 대화를 시작한다(v3.1 §3.6-7). */
    public static final String LEGACY_SNOOZE_TEXT = "미루고 싶어요";

    /** 로컬 백업·이동 블록의 단계(풀스크린). v4 에서도 L3 번호를 그대로 쓴다. */
    public static final int LEVEL = 3;
    /** v4 §2.1: 단계 올리기 작업(expected_version = 개입 version). */
    public static final String ESCALATE = "escalate";
    /** v4 §2.5: 마지막 하트비트가 이보다 오래되면 무응답을 '기기 연결 끊김'으로 본다. */
    static final Duration UNREACHABLE_AFTER = Duration.ofMinutes(30);

    private final AssistantDb db;
    private final AssistantClock clock;
    private final Jobs jobs;
    private final Store store;
    private final TimeUtil time;
    private final SettingsService settings;
    private final PlanService plan;
    private final PushService push;
    private final TurnQueue turns;
    private final GoalCoverage coverage;
    private final BlockLogs logs;
    private final org.springframework.beans.factory.ObjectProvider<com.seobin.phonedrop.assistant.agent.Replans> replans;
    private final org.springframework.beans.factory.ObjectProvider<com.seobin.phonedrop.assistant.agent.TimeWindows> windows;
    private final org.springframework.beans.factory.ObjectProvider<com.seobin.phonedrop.assistant.agent.TimeWindowRunner> windowRunner;

    /** v3.6 §2.2: 적극 개입 시간대(멈춤·진행 중 고정 일정 아님)인가. */
    private boolean activeNow() {
        return activeNow(null);
    }

    /** blockId: 개입 중인 블록 자신(고정 일정이어도 적극 개입 시간대를 막지 않는다). */
    private boolean activeNow(Long blockId) {
        com.seobin.phonedrop.assistant.agent.TimeWindows tw = windows.getIfAvailable();
        return tw != null && tw.activeEffective(clock.now(), store, blockId);
    }

    /** 화면 다시 띄우기 간격. 적극 개입 시간대에는 active.alarm_repeat_min(반응할 때까지 반복). */
    private int reshowMinutes() {
        return activeNow() ? Math.max(1, settings.getInt("active.alarm_repeat_min"))
                : settings.getInt("fullscreen.reshow_minutes");
    }

    public InterventionService(AssistantDb db, AssistantClock clock, Jobs jobs, Store store, TimeUtil time,
                               SettingsService settings, PlanService plan, PushService push, TurnQueue turns,
                               GoalCoverage coverage, BlockLogs logs,
                               org.springframework.beans.factory.ObjectProvider<com.seobin.phonedrop.assistant.agent.Replans> replans,
                               org.springframework.beans.factory.ObjectProvider<com.seobin.phonedrop.assistant.agent.TimeWindows> windows,
                               org.springframework.beans.factory.ObjectProvider<com.seobin.phonedrop.assistant.agent.TimeWindowRunner> windowRunner) {
        this.windows = windows;
        this.windowRunner = windowRunner;
        this.replans = replans;
        this.db = db;
        this.clock = clock;
        this.jobs = jobs;
        this.store = store;
        this.time = time;
        this.settings = settings;
        this.plan = plan;
        this.push = push;
        this.turns = turns;
        this.coverage = coverage;
        this.logs = logs;
        jobs.register(START, this::onStartIntervention);
        jobs.register(RESHOW, this::onReshow);
        jobs.register(EXPIRE, this::onExpire);
        jobs.register(SUSPEND, this::onSuspendCheck);
        jobs.register(BLOCK_END, this::onBlockEnd);
        jobs.register(TALK_TIMEOUT, this::onTalkTimeout);
        jobs.register(LEGACY_NEGOTIATION_TIMEOUT, this::onTalkTimeout);
        jobs.register(BLOCK_CUTOFF, this::onBlockCutoff);
        jobs.register(ESCALATE, this::onEscalate);
    }

    // ---------------------------------------------------------------- v4 단계(L1 알림 → L3 풀스크린 → L4 앱 잠금)

    /** intervention.levels (오르는 순서). 2 는 쓰지 않는다. */
    List<Integer> levels() {
        List<Integer> r = new java.util.ArrayList<>();
        for (tools.jackson.databind.JsonNode v : settings.get("intervention.levels")) {
            int l = v.asInt();
            if ((l == 1 || l == 3 || l == 4) && !r.contains(l)) {
                r.add(l);
            }
        }
        return r.isEmpty() ? List.of(LEVEL) : r;
    }

    /** v4 §2.3: 가장 최근 하트비트 기기의 능력. */
    private tools.jackson.databind.JsonNode capabilities() {
        return db.jdbc().sql("SELECT capabilities FROM devices ORDER BY last_heartbeat DESC NULLS LAST, id DESC LIMIT 1")
                .query(String.class).optional().map(Json::parse).orElse(Json.object());
    }

    /** 옛 앱은 accessibility 를 보고하지 않는다 → L4 불가. 알림·풀스크린은 보고가 없으면 된다고 본다. */
    static boolean capable(int level, tools.jackson.databind.JsonNode caps) {
        return switch (level) {
            case 1 -> caps.path("notifications").asBoolean(true);
            case 3 -> caps.path("fullscreen_intent").asBoolean(true);
            case 4 -> caps.path("accessibility").asBoolean(false);
            default -> false;
        };
    }

    /** 시작 단계: start_level 이상에서 처음 되는 단계. 없으면 되는 것 중 가장 높은 단계. 이동 블록은 L3 고정. */
    int startLevel(Store.Block b) {
        if (b.travel()) {
            return LEVEL;
        }
        tools.jackson.databind.JsonNode caps = capabilities();
        int start = settings.getInt("intervention.start_level");
        List<Integer> list = levels();
        for (int l : list) {
            if (l >= start && capable(l, caps)) {
                return l;
            }
        }
        return list.stream().filter(l -> capable(l, caps)).reduce((a, c) -> c).orElse(LEVEL);
    }

    /** 요청한 단계 이상에서 처음 되는 단계(없으면 되는 것 중 가장 높은 단계). */
    int capableAtLeast(int wanted) {
        tools.jackson.databind.JsonNode caps = capabilities();
        List<Integer> list = levels();
        for (int l : list) {
            if (l >= wanted && capable(l, caps)) {
                return l;
            }
        }
        return list.stream().filter(l -> capable(l, caps)).reduce((a, c) -> c).orElse(LEVEL);
    }

    /** v4 §5.5: 사용자가 딴짓을 끝냈다 → 딴짓 개입을 끝내고(acked, returned) 모든 효과를 푼다. */
    public boolean endDistraction(long blockId) {
        Optional<Store.Intervention> i = store.runningForBlock(blockId);
        if (i.isEmpty() || !"distraction".equals(interventionReason(i.get().id()))) {
            return false;
        }
        end(i.get(), "acked", "returned", "acked");
        store.log(i.get().id(), blockId, i.get().level(), clock.now(), "returned");
        logs.add(blockId, "note", "system", "딴짓에서 돌아옴(개입 자동 종료)");
        return true;
    }

    public String interventionReason(long interventionId) {
        return db.jdbc().sql("SELECT reason FROM interventions WHERE id = ?").param(interventionId)
                .query(String.class).optional().orElse(null);
    }

    /** 다음 단계(되는 것만). 없으면 null(지금 단계에서 멈춤). */
    Integer nextLevel(int current) {
        tools.jackson.databind.JsonNode caps = capabilities();
        List<Integer> list = levels();
        for (int l : list) {
            if (l > current && capable(l, caps)) {
                return l;
            }
        }
        return null;
    }

    /** 지금 단계에서 다음 단계까지 기다릴 분. 적극 개입 시간대는 active.wait_minutes(v3.6 "한 칸 빨리"). */
    int waitMinutes(int current, Long blockId) {
        tools.jackson.databind.JsonNode arr = settings.get(activeNow(blockId) ? "active.wait_minutes" : "intervention.wait_minutes");
        int idx = Math.max(0, levels().indexOf(current));
        int n = arr.size();
        return n == 0 ? 5 : Math.max(1, arr.get(Math.min(idx, n - 1)).asInt());
    }

    /** v4 §1 L1: kind=intervention 채팅 메시지(알림은 intervention.level(1) 이 맡으므로 silent). */
    private void postL1Message(Store.Block b) {
        String text = b.screenText() == null || b.screenText().isBlank() ? "지금 " + store.blockTitle(b) + " 시간이에요"
                : b.screenText();
        long mid = db.insert("""
                        INSERT INTO messages(role, content, kind, importance, silent, created_at)
                        VALUES ('assistant', ?, 'intervention', 'high', TRUE, ?)""", text, clock.now());
        Map<String, String> data = new LinkedHashMap<>();
        data.put("type", "chat.message");
        data.put("message_id", String.valueOf(mid));
        data.put("silent", "true");
        data.put("channel", "chat");
        data.put("preview", text.length() > 60 ? text.substring(0, 60) : text);
        push.pushAll(data);
    }

    private void scheduleEscalate(Store.Intervention i) {
        jobs.cancelForTarget(ESCALATE, i.id());
        Store.Block b = store.block(i.blockId()).orElse(null);
        if (b == null || b.travel() || nextLevel(i.level()) == null) {
            return;
        }
        jobs.schedule(ESCALATE, i.id(), clock.now().plus(Duration.ofMinutes(waitMinutes(i.level(), b.id()))), i.version());
    }

    private boolean onEscalate(Jobs.Job job) {
        Store.Intervention i = store.intervention(job.targetId()).orElse(null);
        // 협상(talking) 중·끝남·version 다름이면 skipped
        if (i == null || !i.running() || job.expectedVersion() == null || i.version() != job.expectedVersion()) {
            return false;
        }
        if (suspendForCommitment(i)) {
            return true;
        }
        Integer next = nextLevel(i.level());
        if (next == null) {
            return false;
        }
        db.update("""
                        UPDATE interventions SET level = ?, version = version + 1, updated_at = CURRENT_TIMESTAMP
                        WHERE id = ?""", next, i.id());
        Store.Intervention up = store.intervention(i.id()).orElseThrow();
        logs.add(up.blockId(), "note", "system", "L" + i.level() + " → L" + next + " (무응답)");
        sendLevel(up);
        if (next >= LEVEL) {
            jobs.cancelForTarget(RESHOW, up.id());
            jobs.schedule(RESHOW, up.id(), clock.now().plus(Duration.ofMinutes(reshowMinutes())), null);
        }
        scheduleEscalate(up);
        return true;
    }

    /** 단계를 바로 올린다(딴짓 감지 §5.5: L1 이면 L3 로). 이미 그 이상이면 그대로. */
    public boolean raiseTo(Store.Intervention i, int level) {
        if (!i.running() || i.level() >= level) {
            return false;
        }
        db.update("""
                        UPDATE interventions SET level = ?, version = version + 1, updated_at = CURRENT_TIMESTAMP
                        WHERE id = ?""", level, i.id());
        Store.Intervention up = store.intervention(i.id()).orElseThrow();
        sendLevel(up);
        jobs.cancelForTarget(RESHOW, up.id());
        jobs.schedule(RESHOW, up.id(), clock.now().plus(Duration.ofMinutes(reshowMinutes())), null);
        scheduleEscalate(up);
        return true;
    }

    /** 고정 블록 화면 기본 문구(v3.1 §3.2). 비서가 screen_text 를 정하지 않았을 때. */
    public static String fixedScreenText(String title, int leadMinutes) {
        return leadMinutes > 0
                ? "곧 " + title + "이에요. " + leadMinutes + "분 뒤 시작"
                : "지금 " + title + " 시간이에요";
    }

    // ---------------------------------------------------------------- 블록 예약 (§6.1)

    /** 블록 생성·시각 변경 뒤에 부른다. 기존 블록 작업을 지우고 다시 예약한다. 개입 시각 = start − lead (v3.1 §3.2). */
    public void scheduleBlockJobs(Store.Block b) {
        cancelBlockJobs(b.id());
        Instant at = b.interventionAt();
        boolean screen = true;
        if (b.travel()) {
            // v3.4 §5: 이동 블록은 시작 시각에 출발 화면(arrive). 앞쪽 준비 시간(lead)은 가벼운 알림이 맡는다. 귀가는 화면 없음.
            at = b.start();
            screen = "arrive".equals(b.travelDirection());
        } else if (b.fixed() && store.activeArriveTravel(b.id()).isPresent()) {
            // 이동 블록이 출발 화면을 넘겨받았다(두 번 뜨지 않게).
            screen = false;
        }
        if (screen && !at.isBefore(clock.now())) {
            jobs.schedule(START, b.id(), at, b.version());
        }
        jobs.schedule(BLOCK_END, b.id(), b.end(), null);
    }

    /** v3.4 §4: 이동 블록은 놓침 없이 끝 시각에(또는 도착한 일정이 시작되면) done 으로 끝낸다. */
    public void finishTravel(Store.Block t, String why) {
        if (!t.travel() || !java.util.List.of("planned", "nudging", "started").contains(t.status())) {
            return;
        }
        store.runningForBlock(t.id()).ifPresent(i -> end(i, "expired", "travel_done", "expired"));
        Instant now = clock.now();
        db.update("""
                        UPDATE blocks SET status = 'done', actual_end = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?""",
                now.isBefore(t.end()) ? now : t.end(), t.id());
        cancelBlockJobs(t.id());
        logs.add(t.id(), "ended", "system", why);
        plan.bumpAndSync();
    }

    /** 고정 일정이 시작되면 그 일정으로 가는 이동 블록은 끝난 것이다. */
    private void finishTravelFor(long fixedId) {
        store.activeArriveTravel(fixedId).ifPresent(t -> finishTravel(t, "도착한 일정이 시작되어 자동 완료"));
    }

    /**
     * v3.3 §2: 진행 중 블록의 끝 시각이 바뀌면 block_end 와 진행 중 개입의 만료를 새 끝 시각으로 다시 잡는다
     * (시작 화면 개입 시각은 이미 지났으므로 다시 예약하지 않는다).
     */
    public void rescheduleEnd(Store.Block b) {
        jobs.cancelForTarget(BLOCK_END, b.id());
        jobs.schedule(BLOCK_END, b.id(), b.end(), null);
        store.runningForBlock(b.id()).ifPresent(i -> {
            jobs.cancelForTarget(EXPIRE, i.id());
            jobs.schedule(EXPIRE, i.id(), expireAt(b), null);
        });
    }

    public void cancelBlockJobs(long blockId) {
        jobs.cancelForTarget(START, blockId);
        jobs.cancelForTarget(BLOCK_END, blockId);
        jobs.cancelForTarget(BLOCK_CUTOFF, blockId);
    }

    /** v3.1 §3.1: 진행 중 블록이 새 고정 블록 금지 구간과 겹치면 그 시작에 partial 로 끝낸다. */
    public void scheduleCutoff(long startedBlockId, Instant at) {
        jobs.cancelForTarget(BLOCK_CUTOFF, startedBlockId);
        jobs.schedule(BLOCK_CUTOFF, startedBlockId, at.isAfter(clock.now()) ? at : clock.now(), null);
    }

    private boolean onBlockCutoff(Jobs.Job job) {
        Store.Block b = store.block(job.targetId()).orElse(null);
        if (b == null || !"started".equals(b.status())) {
            return false;
        }
        db.update("UPDATE blocks SET status = 'partial', actual_end = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                clock.now(), b.id());
        jobs.cancelForTarget(BLOCK_END, b.id());
        logs.add(b.id(), "ended", "system", "부분 완료(고정 일정 준비 시간이 되어 끝남)");
        ObjectNode ref = Json.object();
        ref.put("block_id", b.id());
        ref.put("reason", "fixed_block");
        turns.enqueue("block_ended", ref, TurnQueue.PRIORITY_DEFAULT);
        plan.bumpAndSync();
        return true;
    }

    // ---------------------------------------------------------------- 작업 처리기 (§6.2~6.6)

    private boolean onStartIntervention(Jobs.Job job) {
        Store.Block b = store.block(job.targetId()).orElse(null);
        if (b == null || !"planned".equals(b.status()) || job.expectedVersion() == null
                || b.version() != job.expectedVersion()) {
            return false;
        }
        return begin(b).isPresent();
    }

    /**
     * v4 §5.5 딴짓으로 시작하는 개입(reason=distraction). level 이 null 이면 보통 시작 단계. 이미 시작한(started) 블록이면
     * 블록 상태는 그대로 둔다. L1 메시지는 DistractionService 가 보낸다(비서 turn 또는 코드 문장).
     */
    public Optional<Store.Intervention> beginForDistraction(Store.Block b, Integer level) {
        return begin(b, level, "distraction");
    }

    private Optional<Store.Intervention> begin(Store.Block b) {
        return begin(b, null, null);
    }

    /** 블록 개입을 시작한다. 조용한 시간이거나 블록이 끝났으면 시작하지 않는다. */
    private Optional<Store.Intervention> begin(Store.Block b, Integer forcedLevel, String reason) {
        Instant now = clock.now();
        if (time.isQuiet(now) || !now.isBefore(b.end())) {
            return Optional.empty();
        }
        // v3.3 §1.2·v4 §2.2: 고정 일정이 진행 중이면(방해 허용 정도와 상관없이) 다른 블록의 개입은 시작하지 않는다.
        if (store.dndInProgress(now, b.id()).isPresent() || store.fixedInProgress(now).stream()
                .anyMatch(f -> f.id() != b.id() && !f.travel() && !"started".equals(f.status()) && !b.fixed())) {
            return Optional.empty();
        }

        for (Store.Intervention running : store.runningInterventions()) {
            Store.Block rb = store.block(running.blockId()).orElse(null);
            if (rb != null && rb.travel()) {
                finishTravel(rb, "다음 화면이 떠서 자동 완료");
                continue;
            }
            if (b.fixed() && !b.travel()) {
                end(running, "suspended", "fixed_block", "suspended"); // v4 §2.2: 고정 일정이 시작됨
            } else {
                end(running, "expired", "superseded", "superseded");
            }
            if (store.block(running.blockId()).filter(x -> "nudging".equals(x.status())).isPresent()) {
                logs.add(running.blockId(), "missed", "system", "다음 블록 개입이 시작되어 놓침");
            }
            markMissedIfNudging(running.blockId());
            coverage.onBlockClosed(running.blockId());
        }
        // v3 §3.5: 이전 블록이 아직 started 면 partial 로 끝낸다(할 일 remaining 은 그대로).
        for (Store.Block started : store.startedBlocks()) {
            if (started.travel()) {
                finishTravel(started, "다음 블록이 시작되어 자동 완료");
                continue;
            }
            if (started.id() != b.id()) {
                db.update("""
                                UPDATE blocks SET status = 'partial', actual_end = ?, updated_at = CURRENT_TIMESTAMP
                                WHERE id = ?""",
                        now, started.id());
                cancelBlockJobs(started.id());
                logs.add(started.id(), "ended", "system", "부분 완료(다음 블록이 시작됨)");
                coverage.onBlockClosed(started.id());
            }
        }

        int level = forcedLevel != null ? capableAtLeast(forcedLevel) : startLevel(b);
        long id = db.insert("""
                        INSERT INTO interventions(block_id, status, level, block_version, started_at, reason)
                        VALUES (?, 'running', ?, ?, ?, ?)""",
                b.id(), level, b.version(), now, reason);
        // 상태만 바뀔 때는 블록 version 을 올리지 않는다. 진행 중 블록의 딴짓 개입은 상태를 그대로 둔다.
        if (!"started".equals(b.status())) {
            store.setBlockStatus(b.id(), "nudging");
        }
        Store.Intervention intervention = store.intervention(id).orElseThrow();

        sendLevel(intervention);
        logs.add(b.id(), "intervention_shown", "system", "L" + level + " 표시");

        if (level >= LEVEL) {
            jobs.schedule(RESHOW, id, now.plus(Duration.ofMinutes(reshowMinutes())), null);
        } else if (reason == null) {
            postL1Message(b);
        }
        scheduleEscalate(intervention);
        Instant expireAt = expireAt(b);
        jobs.schedule(EXPIRE, id, expireAt, null);
        time.firstQuietStart(now, expireAt).ifPresent(at -> jobs.schedule(SUSPEND, id, at, null));
        scheduleCommitmentChecks(intervention, b, expireAt);
        return Optional.of(intervention);
    }

    private boolean onReshow(Jobs.Job job) {
        Store.Intervention i = store.intervention(job.targetId()).orElse(null);
        if (i == null || !i.running()) {
            return false;
        }
        if (suspendForCommitment(i)) {
            return true; // 그 사이 생긴 고정 일정·이동이 시작됐다
        }
        sendLevel(i);
        jobs.schedule(RESHOW, i.id(),
                clock.now().plus(Duration.ofMinutes(reshowMinutes())), null);
        return true;
    }

    private boolean onExpire(Jobs.Job job) {
        Store.Intervention i = store.intervention(job.targetId()).orElse(null);
        if (i == null || !i.active()) {
            return false;
        }
        Store.Block tb = store.block(i.blockId()).orElse(null);
        if (tb != null && tb.travel()) {
            finishTravel(tb, "자동 완료");
            return true;
        }
        // v4 §2.5: 하트비트가 30분 넘게 끊겼으면 무시가 아니라 기기 연결 끊김
        boolean unreachable = lastHeartbeat().map(h -> h.isBefore(clock.now().minus(UNREACHABLE_AFTER))).orElse(false);
        end(i, "expired", unreachable ? "unreachable" : "no_response", "expired");
        store.setBlockStatus(i.blockId(), "missed");
        logs.add(i.blockId(), "missed", "system", unreachable ? "기기 연결 끊김으로 놓침" : "무응답으로 놓침");
        enqueueMissed(i.blockId(), unreachable ? "unreachable" : "no_response");
        coverage.onBlockClosed(i.blockId());
        return true;
    }

    /**
     * v4 §2.2(2026-11-07 수정): 개입이 도는 중에 다른 고정 일정의 금지 구간(준비 시간 포함)이나 이동 블록이 시작되면 멈춘다.
     * 예전에는 방해 금지·수면 시간에만 멈춰서, 알바 준비 시간에도 다른 블록의 알람·잠금이 계속됐다.
     */
    private boolean otherCommitmentNow(Store.Intervention i) {
        Instant now = clock.now();
        Store.Block own = store.block(i.blockId()).orElse(null);
        if (own == null || own.travel()) {
            return false;
        }
        boolean fixed = store.fixedInProgress(now).stream()
                .anyMatch(f -> f.id() != own.id() && !f.travel() && (own.linkedFixedBlockId() == null || f.id() != own.linkedFixedBlockId()));
        boolean travel = store.activeBlocksOverlapping(now, now.plusSeconds(1)).stream()
                .anyMatch(t -> t.travel() && t.id() != own.id() && !now.isBefore(t.start()));
        return fixed || travel;
    }

    /** 다른 일정 때문에 멈춘다: suspended(fixed_block), 블록 missed, 비서가 다시 배치. */
    private boolean suspendForCommitment(Store.Intervention i) {
        if (!i.active() || !otherCommitmentNow(i)) {
            return false;
        }
        end(i, "suspended", "fixed_block", "suspended");
        Store.Block b = store.block(i.blockId()).orElse(null);
        if (b != null && !"started".equals(b.status())) {
            store.setBlockStatus(i.blockId(), "missed");
            logs.add(i.blockId(), "missed", "system", "다른 고정 일정(준비 포함)·이동이 시작되어 중단");
            enqueueMissed(i.blockId(), "fixed_block");
            coverage.onBlockClosed(i.blockId());
        }
        return true;
    }

    /** 개입이 끝나기 전에 시작하는 다른 고정 일정(금지 구간 시작)·이동 블록 시각에 멈춤 확인을 잡는다. */
    private void scheduleCommitmentChecks(Store.Intervention i, Store.Block b, Instant until) {
        Instant now = clock.now();
        for (Store.Block f : store.activeFixedBlocksOverlapping(now, until)) {
            if (f.id() != b.id() && !f.travel() && f.blockedStart().isAfter(now)) {
                jobs.schedule(SUSPEND, i.id(), f.blockedStart(), null);
            }
        }
        for (Store.Block t : store.activeBlocksOverlapping(now, until)) {
            if (t.travel() && t.id() != b.id() && t.start().isAfter(now)) {
                jobs.schedule(SUSPEND, i.id(), t.start(), null);
            }
        }
    }

    private boolean onSuspendCheck(Jobs.Job job) {
        Store.Intervention i = store.intervention(job.targetId()).orElse(null);
        if (i != null && i.active() && !time.isQuiet(clock.now())) {
            return suspendForCommitment(i);
        }
        if (i == null || !i.active() || !time.isQuiet(clock.now())) {
            return false;
        }
        Store.Block tb = store.block(i.blockId()).orElse(null);
        if (tb != null && tb.travel()) {
            end(i, "suspended", "quiet", "suspended");
            return true;
        }
        end(i, "suspended", "quiet", "suspended");
        store.setBlockStatus(i.blockId(), "missed");
        logs.add(i.blockId(), "missed", "system", "방해 금지·수면 시간이라 중단");
        enqueueMissed(i.blockId(), "quiet");
        coverage.onBlockClosed(i.blockId());
        return true;
    }

    /** [시작]으로 작업이 시작되면 비서가 체크인을 예약하도록 block_started turn (v3 §6 작업 중 동행, DECISIONS 참고). */
    private void enqueueStarted(long blockId, boolean already) {
        ObjectNode ref = Json.object();
        ref.put("block_id", blockId);
        if (already) {
            ref.put("already", true);
        }
        turns.enqueue("block_started", ref, TurnQueue.PRIORITY_DEFAULT);
    }

    /** [시작] 뒤: 이동 블록이면 '출발함'(체크인 없음), 고정 일정이면 그리로 가는 이동 블록을 끝낸다. */
    private void afterStart(long blockId) {
        afterStart(blockId, false);
    }

    private void afterStart(long blockId, boolean already) {
        Store.Block b = store.block(blockId).orElse(null);
        if (b != null && b.travel()) {
            return;
        }
        if (b != null && b.fixed()) {
            finishTravelFor(b.id());
        }
        enqueueStarted(blockId, already);
    }

    /** v3 §3.6: 블록이 missed 가 되면 block_missed turn. */
    private void enqueueMissed(long blockId, String reason) {
        ObjectNode ref = Json.object();
        ref.put("block_id", blockId);
        ref.put("reason", reason);
        turns.enqueue("block_missed", ref, TurnQueue.PRIORITY_DEFAULT);
    }

    private boolean onBlockEnd(Jobs.Job job) {
        Store.Block b = store.block(job.targetId()).orElse(null);
        if (b == null || !job.fireAt().equals(b.end())) {
            return false;
        }
        if (b.travel()) {
            finishTravel(b, "자동 완료");
            return true;
        }
        switch (b.status()) {
            case "planned" -> {
                store.setBlockStatus(b.id(), "missed");
                logs.add(b.id(), "missed", "system", "시작하지 않고 끝 시각이 지남");
                enqueueMissed(b.id(), "never_started");
                coverage.onBlockClosed(b.id());
            }
            case "started" -> {
                // v3 §3.5(변경): 자동 done 을 없앴다. 상태는 그대로 두고 비서가 진행을 묻는다.
                ObjectNode ref = Json.object();
                ref.put("block_id", b.id());
                turns.enqueue("block_ended", ref, TurnQueue.PRIORITY_DEFAULT);
            }
            default -> {
                // nudging 은 expire 가 처리한다. 끝난 블록은 그대로.
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- 개입 종료 공통

    /** 개입을 끝내고 남은 개입 작업을 취소한 뒤 intervention.end 를 보낸다. */
    public void end(Store.Intervention i, String status, String endReason, String pushReason) {
        db.update("""
                        UPDATE interventions SET status = ?, end_reason = ?, ended_at = ?, version = version + 1,
                        updated_at = CURRENT_TIMESTAMP WHERE id = ?""",
                status, endReason, clock.now(), i.id());
        jobs.cancelForTarget(RESHOW, i.id());
        jobs.cancelForTarget(EXPIRE, i.id());
        jobs.cancelForTarget(SUSPEND, i.id());
        jobs.cancelForTarget(TALK_TIMEOUT, i.id());
        jobs.cancelForTarget(LEGACY_NEGOTIATION_TIMEOUT, i.id());
        jobs.cancelForTarget(ESCALATE, i.id());
        Map<String, String> data = new LinkedHashMap<>();
        data.put("type", "intervention.end");
        data.put("intervention_id", String.valueOf(i.id()));
        data.put("block_id", String.valueOf(i.blockId()));
        data.put("reason", pushReason);
        push.pushAll(data);
    }

    /** 블록이 취소·이동될 때. 진행 중 개입을 cancelled 로 끝낸다. */
    public void cancelRunningForBlock(long blockId) {
        store.runningForBlock(blockId).ifPresent(i -> end(i, "cancelled", "cancelled", "cancelled"));
    }

    /**
     * v3 §3.4(변경): move_block 이 nudging 블록을 옮길 때 진행 중 개입을 snoozed(end_reason=moved)로 끝낸다.
     * movedMinutes = 새 start − 기존 start (get_facts 의 미룬 분 합).
     */
    public void snoozeForMove(long blockId, long movedMinutes) {
        store.runningForBlock(blockId).ifPresent(i -> {
            end(i, "snoozed", "moved", "snoozed");
            db.update("UPDATE interventions SET snoozed_minutes = ? WHERE id = ?", (int) movedMinutes, i.id());
        });
    }

    // ---------------------------------------------------------------- 풀스크린 대화 (v3.1 §3.6, v3 협상을 대체)

    private boolean onTalkTimeout(Jobs.Job job) {
        Store.Intervention i = store.intervention(job.targetId()).orElse(null);
        if (i == null || !"talking".equals(i.status()) || job.expectedVersion() == null
                || i.version() != job.expectedVersion()) {
            return false;
        }
        db.update("""
                        UPDATE interventions SET status = 'running', talk_until = NULL, version = version + 1,
                        updated_at = CURRENT_TIMESTAMP WHERE id = ?""",
                i.id());
        Store.Intervention back = store.intervention(i.id()).orElseThrow();
        sendLevel(back);
        if (back.level() >= LEVEL) {
            jobs.schedule(RESHOW, i.id(), clock.now().plus(Duration.ofMinutes(reshowMinutes())), null);
        }
        scheduleEscalate(back); // v4 §2.1: 협상에서 돌아오면 escalate 를 새로 예약
        return true;
    }

    /** 대화 중 사용자 메시지가 오면 대화 시간을 연장한다(§3.6-4). */
    public void extendTalk() {
        for (Store.Intervention i : store.runningInterventions()) {
            if ("talking".equals(i.status())) {
                talk(i);
            }
        }
    }

    /**
     * 풀스크린에서 사용자가 말하면: running·talking 개입을 talking 으로 두고 talk_until = now + timeout,
     * version +1, reshow 중단, talk_timeout (재)예약. 개입이 이미 끝났으면 아무것도 하지 않는다.
     */
    public Optional<Store.Intervention> startTalk(long interventionId) {
        return store.intervention(interventionId).filter(Store.Intervention::active).map(this::talk);
    }

    private Store.Intervention talk(Store.Intervention i) {
        Instant until = clock.now().plus(Duration.ofMinutes(settings.getInt("talk.timeout_minutes")));
        db.update("""
                        UPDATE interventions SET status = 'talking', talk_until = ?, version = version + 1,
                        updated_at = CURRENT_TIMESTAMP WHERE id = ?""",
                until, i.id());
        Store.Intervention updated = store.intervention(i.id()).orElseThrow();
        jobs.cancelForTarget(RESHOW, i.id());
        jobs.cancelForTarget(TALK_TIMEOUT, i.id());
        jobs.cancelForTarget(LEGACY_NEGOTIATION_TIMEOUT, i.id());
        jobs.schedule(TALK_TIMEOUT, i.id(), until, updated.version());
        return updated;
    }

    /** 사용자 메시지(풀스크린) 저장 + 대화 시작 + intervention_talk turn. ChatService 와 구 snooze_request 액션이 쓴다. */
    public long[] userTalk(long interventionId, String text, String source, String clientMsgId) {
        Store.Intervention i = store.intervention(interventionId)
                .orElseThrow(() -> ApiException.notFound("개입이 없습니다: " + interventionId));
        long messageId = db.insert("""
                        INSERT INTO messages(role, content, kind, source, client_msg_id, intervention_id, created_at)
                        VALUES ('user', ?, 'reply', ?, ?, ?, ?)""",
                text, source, clientMsgId, i.id(), clock.now());
        startTalk(i.id());
        extendTalk();
        ObjectNode ref = Json.object();
        ref.put("message_id", messageId);
        ref.put("intervention_id", i.id());
        ref.put("block_id", i.blockId());
        long turnId = turns.enqueue("intervention_talk", ref, TurnQueue.PRIORITY_USER);
        return new long[]{messageId, turnId};
    }

    /** 구 [미루기 요청] 액션: "미루고 싶어요" 풀스크린 대화 시작으로 처리한다(§3.6-7). */
    private String snoozeRequest(Long interventionId, Long blockId) {
        Optional<Store.Intervention> found = resolve(interventionId, blockId);
        store.log(found.map(Store.Intervention::id).orElse(null),
                found.map(Store.Intervention::blockId).orElse(blockId),
                found.map(Store.Intervention::level).orElse(null), clock.now(), "snooze_request");
        if (found.isEmpty() || !found.get().active()) {
            return "ok";
        }
        userTalk(found.get().id(), LEGACY_SNOOZE_TEXT, "fullscreen", null);
        return "ok";
    }

    private String staleLocalAlarm(Store.Block b, Integer blockVersion) {
        if (blockVersion != null && blockVersion != b.version()) {
            return "블록이 바뀜";
        }
        if (b.travel() && !"arrive".equals(b.travelDirection())) {
            return "귀가 블록";
        }
        if (b.fixed() && store.activeArriveTravel(b.id()).isPresent()) {
            return "이동 블록이 출발 화면을 맡음";
        }
        Instant alarmAt = b.travel() ? b.start() : b.interventionAt();
        Instant now = clock.now();
        if (now.isBefore(alarmAt.minusSeconds(120)) || !now.isBefore(b.end())) {
            return "알람 시각이 아님";
        }
        return null;
    }

    private void markMissedIfNudging(long blockId) {
        store.block(blockId).filter(b -> "nudging".equals(b.status()))
                .ifPresent(b -> store.setBlockStatus(b.id(), "missed"));
    }

    private Instant expireAt(Store.Block b) {
        return b.end().plus(Duration.ofMinutes(settings.getInt("intervention.expire_grace_minutes")));
    }

    private void sendLevel(Store.Intervention i) {
        Store.Block b = store.block(i.blockId()).orElseThrow();
        String title = store.blockTitle(b);
        Store.Goal goal = coverage.goalOf(b).flatMap(store::goal).orElse(null);
        Map<String, String> data = new LinkedHashMap<>();
        data.put("type", "intervention.level");
        data.put("intervention_id", String.valueOf(i.id()));
        data.put("block_id", String.valueOf(b.id()));
        data.put("level", String.valueOf(i.level()));
        // v4 §1: L4 = 풀스크린 + 딴짓 앱 잠금
        data.put("lock_apps", String.valueOf(i.level() >= 4));
        data.put("task_title", title);
        data.put("kind", b.kind());
        data.put("block_start", time.iso(b.start()));
        data.put("lead_minutes", String.valueOf(b.leadMinutes()));
        data.put("location", b.location() == null ? "" : b.location());
        data.put("goal_label", goal == null ? "" : goal.label());
        data.put("goal_color", goal == null ? "" : goal.color());
        data.put("talk_timeout_minutes", String.valueOf(settings.getInt("talk.timeout_minutes")));
        String screen = b.screenText();
        if ("distraction".equals(interventionReason(i.id()))) {
            screen = db.jdbc().sql("""
                            SELECT content FROM messages WHERE role = 'assistant' AND kind IN ('proactive', 'intervention')
                            AND created_at >= ? ORDER BY id DESC LIMIT 1""")
                    .param(AssistantDb.ts(i.startedAt())).query(String.class).optional().orElse(screen);
        }
        data.put("screen_text", screen);
        data.put("reason", "distraction".equals(interventionReason(i.id())) ? "distraction" : "");
        // v3.2 §3.3: 고정 블록 메모를 화면에 그대로 보여주고, TTS 는 screen_text 다음에 메모 첫 문장까지 읽는다.
        Store.Block memoOf = b.travel() && b.linkedFixedBlockId() != null
                ? store.block(b.linkedFixedBlockId()).orElse(b) : b;
        String memo = memoOf.fixed() ? store.fullMemo(memoOf) : "";
        data.put("memo", memo);
        data.put("tts_text", memo.isBlank() ? b.screenText() : b.screenText() + " " + firstSentence(memo));
        data.put("reshow_minutes", String.valueOf(reshowMinutes()));
        data.put("block_end", time.iso(b.end()));
        data.put("expire_at", time.iso(expireAt(b)));
        // v3.6 §2.3: 적극 개입 시간대면 화면에 [10분만 조용히] 버튼
        data.put("active_window", String.valueOf(activeNow()));
        if (b.workProductId() != null) {
            data.put("work_product_id", String.valueOf(b.workProductId()));
        }
        data.put("plan_version", String.valueOf(plan.version()));
        push.pushAll(data);
        store.log(i.id(), b.id(), i.level(), clock.now(), "sent");
    }

    static String firstSentence(String text) {
        String t = text.strip();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[.!?。\\n]").matcher(t);
        return m.find() ? t.substring(0, m.end()).strip() : t;
    }

    /** 설정(수면·방해 금지)이 바뀌면 진행 중 개입의 suspend_check 를 다시 계산한다. */
    public void onSettingsChanged() {
        Instant now = clock.now();
        for (Store.Intervention i : store.runningInterventions()) {
            jobs.cancelForTarget(SUSPEND, i.id());
            Store.Block b = store.block(i.blockId()).orElseThrow();
            time.firstQuietStart(now, expireAt(b)).ifPresent(at -> jobs.schedule(SUSPEND, i.id(), at, null));
        }
    }

    // ---------------------------------------------------------------- 사용자 액션 (§6.7)

    public Map<String, Object> action(JsonNode req) {
        String key = req.path("idempotency_key").asString("");
        if (key.isBlank()) {
            throw ApiException.badRequest("idempotency_key 가 필요합니다");
        }
        Optional<String> previous = db.jdbc().sql("SELECT result FROM processed_actions WHERE idempotency_key = ?")
                .param(key).query(String.class).optional();
        if (previous.isPresent()) {
            Map<String, Object> result = new LinkedHashMap<>();
            Json.parse(previous.get()).properties().forEach(e -> result.put(e.getKey(), e.getValue()));
            result.put("status", "duplicate");
            return result;
        }

        String type = req.path("type").asString("");
        Long interventionId = numericId(req.path("intervention_id"));
        Long blockId = numericId(req.path("block_id"));
        com.seobin.phonedrop.assistant.agent.TimeWindowRunner runner = windowRunner.getIfAvailable();
        if (runner != null && !"local_fired".equals(type)) {
            runner.ack(false); // v3.6 §2.3: 개입 화면 조작은 적극 개입 알람에 반응한 것
        }
        String status = switch (type) {
            case "start" -> start(interventionId, blockId, false);
            case "already_started" -> start(interventionId, blockId, true);
            case "already_done" -> alreadyDone(interventionId, blockId);
            case "dismiss" -> dismiss(interventionId, blockId);
            case "emergency_unlock" -> emergencyUnlock(interventionId, blockId);
            case "snooze_request" -> snoozeRequest(interventionId, blockId);
            case "local_fired" -> localFired(blockId, req.hasNonNull("block_version") ? Integer.valueOf(req.get("block_version").asInt()) : null);
            default -> throw ApiException.badRequest("알 수 없는 액션입니다: " + type);
        };

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", status);
        result.put("plan_version", plan.version());
        db.update("INSERT INTO processed_actions(idempotency_key, result) VALUES (?, ?)", key, Json.write(result));
        return result;
    }

    /** 숫자 id 만 받는다. 앱의 로컬 개입 id("local:9")는 null. */
    private static Long numericId(JsonNode node) {
        if (node.isNumber()) {
            return node.asLong();
        }
        String text = node.asString("");
        return text.matches("\\d+") ? Long.parseLong(text) : null;
    }

    private Optional<Store.Intervention> resolve(Long interventionId, Long blockId) {
        Optional<Store.Intervention> byId = interventionId == null ? Optional.empty()
                : store.intervention(interventionId);
        if (byId.isPresent() && byId.get().active()) {
            return byId;
        }
        if (blockId != null) {
            Optional<Store.Intervention> byBlock = store.runningForBlock(blockId);
            if (byBlock.isPresent()) {
                return byBlock;
            }
        }
        return byId;
    }

    /** already = [이미 하고 있음]: 알림 전에 시작했다. 실제 시작 = 예정 시작(지금보다 앞이면). */
    private String start(Long interventionId, Long blockId, boolean already) {
        Instant now = clock.now();
        Optional<Store.Intervention> found = resolve(interventionId, blockId);
        if (found.isPresent() && found.get().active() && "distraction".equals(interventionReason(found.get().id()))
                && store.block(found.get().blockId()).filter(x -> "started".equals(x.status())).isPresent()) {
            // v4: 진행 중 블록의 딴짓 개입에서 [시작] = 돌아감(블록은 이미 시작됨)
            end(found.get(), "acked", "returned", "acked");
            store.log(found.get().id(), found.get().blockId(), found.get().level(), now, "acked");
            return "ok";
        }
        if (found.isPresent() && found.get().active()) {
            ack(found.get());
            if (already) {
                markAlready(found.get().blockId());
            }
            afterStart(found.get().blockId(), already);
            return "ok";
        }

        // 개입이 이미 끝났거나 없어도 거절하지 않는다.
        Long targetBlock = blockId != null ? blockId : found.map(Store.Intervention::blockId).orElse(null);
        if (targetBlock == null) {
            return "ok";
        }
        Store.Block b = store.block(targetBlock).orElse(null);
        if (b == null || !List.of("planned", "nudging", "missed").contains(b.status())) {
            return "ok";
        }
        // 고정 블록은 할 일 없이도 시작할 수 있다.
        Store.Task t = b.taskId() == null ? null : store.task(b.taskId()).orElse(null);
        if (t != null && !"active".equals(t.status())) {
            return "ok";
        }
        startBlockNow(b, t, now);
        if (already) {
            markAlready(b.id());
        }
        afterStart(b.id(), already);
        return "accepted_as_start_now";
    }

    /** [이미 하고 있음]: 실제 시작을 예정 시작으로 당기고(지금보다 앞일 때만) 기록을 남긴다. */
    private void markAlready(long blockId) {
        db.update("""
                        UPDATE blocks SET actual_start = start_at, updated_at = CURRENT_TIMESTAMP
                        WHERE id = ? AND status = 'started' AND start_at < actual_start""", blockId);
        logs.add(blockId, "note", "user", "이미 하고 있었음(알림 전에 시작)");
    }

    /** 진행 중 개입을 acked 로 끝내고 블록을 started 로. [시작] 액션과 start_now 도구가 쓴다. */
    public void ack(Store.Intervention i) {
        Instant now = clock.now();
        end(i, "acked", "acked", "acked");
        store.log(i.id(), i.blockId(), i.level(), now, "acked");
        logs.add(i.blockId(), "started", "user", "시작(반응 " + Math.max(0, java.time.Duration.between(i.startedAt(), now).toSeconds()) + "초)");
        db.update("""
                        UPDATE blocks SET status = 'started', actual_start = ?, updated_at = CURRENT_TIMESTAMP
                        WHERE id = ? AND status <> 'started'""",
                now, i.blockId());
    }

    private void startBlockNow(Store.Block b, Store.Task t, Instant now) {
        logs.add(b.id(), "started", "user", "시작(개입이 끝난 뒤 [시작])");
        if (now.isBefore(b.end())) {
            db.update("""
                            UPDATE blocks SET status = 'started', actual_start = ?, updated_at = CURRENT_TIMESTAMP
                            WHERE id = ?""",
                    now, b.id());
            cancelBlockJobs(b.id());
            jobs.schedule(BLOCK_END, b.id(), b.end(), null);
        } else {
            int minutes = t != null ? t.estMinutes() : (int) Duration.between(b.start(), b.end()).toMinutes();
            Instant end = now.plus(Duration.ofMinutes(Math.max(5, minutes)));
            List<Long> displaced = new java.util.ArrayList<>();
            for (Store.Block other : store.activeBlocksOverlapping(now, end)) {
                if (other.id() != b.id() && "planned".equals(other.status())) {
                    store.setBlockStatus(other.id(), "cancelled");
                    cancelBlockJobs(other.id());
                    if (other.travel()) {
                        continue; // 이동 블록은 기존대로(시스템이 다시 계산)
                    }
                    ObjectNode d = Json.object();
                    d.put("replanned", true);
                    logs.add(other.id(), "note", "system", "늦게 시작한 블록#" + b.id() + "과 겹쳐 취소", d);
                    displaced.add(other.id());
                }
            }
            if (!displaced.isEmpty() && !replans.getObject().detectDropped(displaced).isEmpty()) {
                ObjectNode ref = Json.object();
                ref.put("block_id", b.id());
                ref.set("displaced", Json.MAPPER.valueToTree(displaced));
                ref.put("reason", "late_start");
                turns.enqueue("blocks_displaced", ref, TurnQueue.PRIORITY_DEFAULT);
            }
            db.update("""
                            UPDATE blocks SET status = 'started', actual_start = ?, start_at = ?, end_at = ?,
                            version = version + 1, updated_at = CURRENT_TIMESTAMP WHERE id = ?""",
                    now, now, end, b.id());
            scheduleBlockJobs(store.block(b.id()).orElseThrow());
        }
        plan.bumpAndSync();
    }

    /**
     * 2026-11-10(사용자 요청) [이미 완료함]: 알림 전에 이미 끝냈다. 개입을 끝내고(acked, already_done) 블록을 done 으로,
     * 실제 시작 = 예정 시작(지금보다 앞이면), 실제 끝 = 지금. 비서가 다음 일을 챙기도록 block_ended(reason=already_done).
     */
    private String alreadyDone(Long interventionId, Long blockId) {
        Optional<Store.Intervention> found = resolve(interventionId, blockId);
        Long target = blockId != null ? blockId : found.map(Store.Intervention::blockId).orElse(null);
        if (target == null) {
            return "ok";
        }
        Store.Block b = store.block(target).orElse(null);
        if (b == null || !List.of("planned", "nudging", "started", "missed").contains(b.status())) {
            return "ok";
        }
        Instant now = clock.now();
        found.filter(Store.Intervention::active).ifPresent(i -> {
            end(i, "acked", "already_done", "acked");
            store.log(i.id(), i.blockId(), i.level(), now, "already_done");
        });
        db.update("""
                        UPDATE blocks SET status = 'done', actual_start = COALESCE(actual_start, LEAST(start_at, ?)),
                        actual_end = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?""",
                AssistantDb.ts(now), AssistantDb.ts(now), target);
        cancelBlockJobs(target);
        logs.add(target, "ended", "user", "이미 완료함(알림 전에 끝냄)");
        coverage.onBlockClosed(target);
        ObjectNode ref = Json.object();
        ref.put("block_id", target);
        ref.put("reason", "already_done");
        turns.enqueue("block_ended", ref, TurnQueue.PRIORITY_DEFAULT);
        plan.bumpAndSync();
        return "ok";
    }

    /** v4 §2.4: 비상 해제. 확인 없이 바로: suspended(emergency), 블록 missed, block_missed(reason=emergency). */
    private String emergencyUnlock(Long interventionId, Long blockId) {
        Optional<Store.Intervention> found = resolve(interventionId, blockId);
        if (found.isEmpty() || !found.get().active()) {
            return "ok";
        }
        Store.Intervention i = found.get();
        end(i, "suspended", "emergency", "emergency");
        store.log(i.id(), i.blockId(), i.level(), clock.now(), "emergency_unlock");
        store.setBlockStatus(i.blockId(), "missed");
        logs.add(i.blockId(), "missed", "user", "비상 해제");
        enqueueMissed(i.blockId(), "emergency");
        coverage.onBlockClosed(i.blockId());
        plan.bumpAndSync();
        return "ok";
    }

    private java.util.Optional<Instant> lastHeartbeat() {
        List<Instant> hb = db.jdbc().sql("SELECT MAX(last_heartbeat) AS hb FROM devices")
                .query((rs, n) -> AssistantDb.instant(rs, "hb")).list();
        return hb.isEmpty() ? java.util.Optional.empty() : java.util.Optional.ofNullable(hb.get(0));
    }

    private String dismiss(Long interventionId, Long blockId) {
        Optional<Store.Intervention> i = resolve(interventionId, blockId);
        Long target = i.map(Store.Intervention::blockId).orElse(blockId);
        if (target != null && store.block(target).isPresent()) {
            String reaction = i.map(x -> "(반응 " + Math.max(0, Duration.between(x.startedAt(), clock.now()).toSeconds()) + "초)")
                    .orElse("");
            logs.add(target, "dismissed", "user", "닫음" + reaction);
        }
        store.log(i.map(Store.Intervention::id).orElse(null),
                i.map(Store.Intervention::blockId).orElse(blockId), i.map(Store.Intervention::level).orElse(null),
                clock.now(), "dismissed");
        return "ok";
    }

    /**
     * 휴대폰 백업 알람. 2026-11-06: 옛 알람(블록이 바뀐 뒤 남은 것)은 무시한다 — 블록 version 이 다르거나(앱 3.4.1+),
     * 지금이 그 블록의 알람 시각 2분 전 ~ 끝 시각 밖이면. 이동 블록이 출발 화면을 맡은 고정 일정·귀가 블록도 무시.
     */
    private String localFired(Long blockId, Integer blockVersion) {
        if (blockId == null) {
            throw ApiException.badRequest("local_fired 에는 block_id 가 필요합니다");
        }
        Optional<Store.Intervention> i = store.runningForBlock(blockId);
        if (i.isEmpty()) {
            Store.Block b = store.block(blockId).orElse(null);
            String stale = b == null ? null : staleLocalAlarm(b, blockVersion);
            if (stale != null) {
                logs.add(b.id(), "note", "system", "휴대폰 백업 알람 무시(" + stale + ")");
                store.log(null, blockId, null, clock.now(), "local_fired_ignored");
                return "ignored";
            }
            if (b != null && "planned".equals(b.status())) {
                // 앱 화면은 이미 떠 있다. 서버 개입을 바로 시작한다(앱은 id 만 교체).
                i = begin(b);
            }
        }
        store.log(i.map(Store.Intervention::id).orElse(null), blockId, i.map(Store.Intervention::level).orElse(null),
                clock.now(), "local_fired");
        return "ok";
    }
}
