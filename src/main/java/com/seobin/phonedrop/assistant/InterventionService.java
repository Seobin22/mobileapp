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
    public static final String NEGOTIATION_TIMEOUT = "negotiation_timeout";

    public static final int LEVEL = 3;

    private final AssistantDb db;
    private final AssistantClock clock;
    private final Jobs jobs;
    private final Store store;
    private final TimeUtil time;
    private final SettingsService settings;
    private final PlanService plan;
    private final PushService push;
    private final TurnQueue turns;

    public InterventionService(AssistantDb db, AssistantClock clock, Jobs jobs, Store store, TimeUtil time,
                               SettingsService settings, PlanService plan, PushService push, TurnQueue turns) {
        this.db = db;
        this.clock = clock;
        this.jobs = jobs;
        this.store = store;
        this.time = time;
        this.settings = settings;
        this.plan = plan;
        this.push = push;
        this.turns = turns;
        jobs.register(START, this::onStartIntervention);
        jobs.register(RESHOW, this::onReshow);
        jobs.register(EXPIRE, this::onExpire);
        jobs.register(SUSPEND, this::onSuspendCheck);
        jobs.register(BLOCK_END, this::onBlockEnd);
        jobs.register(NEGOTIATION_TIMEOUT, this::onNegotiationTimeout);
    }

    // ---------------------------------------------------------------- 블록 예약 (§6.1)

    /** 블록 생성·시각 변경 뒤에 부른다. 기존 블록 작업을 지우고 다시 예약한다. */
    public void scheduleBlockJobs(Store.Block b) {
        cancelBlockJobs(b.id());
        if (!b.start().isBefore(clock.now())) {
            jobs.schedule(START, b.id(), b.start(), b.version());
        }
        jobs.schedule(BLOCK_END, b.id(), b.end(), null);
    }

    public void cancelBlockJobs(long blockId) {
        jobs.cancelForTarget(START, blockId);
        jobs.cancelForTarget(BLOCK_END, blockId);
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

    /** 블록 개입을 시작한다. 조용한 시간이거나 블록이 끝났으면 시작하지 않는다. */
    private Optional<Store.Intervention> begin(Store.Block b) {
        Instant now = clock.now();
        if (time.isQuiet(now) || !now.isBefore(b.end())) {
            return Optional.empty();
        }

        for (Store.Intervention running : store.runningInterventions()) {
            end(running, "expired", "superseded", "superseded");
            markMissedIfNudging(running.blockId());
        }
        // v3 §3.5: 이전 블록이 아직 started 면 partial 로 끝낸다(할 일 remaining 은 그대로).
        for (Store.Block started : store.startedBlocks()) {
            if (started.id() != b.id()) {
                db.update("""
                                UPDATE blocks SET status = 'partial', actual_end = ?, updated_at = CURRENT_TIMESTAMP
                                WHERE id = ?""",
                        now, started.id());
                cancelBlockJobs(started.id());
            }
        }

        long id = db.insert("""
                        INSERT INTO interventions(block_id, status, level, block_version, started_at)
                        VALUES (?, 'running', ?, ?, ?)""",
                b.id(), LEVEL, b.version(), now);
        // 상태만 바뀔 때는 블록 version 을 올리지 않는다.
        store.setBlockStatus(b.id(), "nudging");
        Store.Intervention intervention = store.intervention(id).orElseThrow();

        sendLevel(intervention);

        jobs.schedule(RESHOW, id, now.plus(Duration.ofMinutes(settings.getInt("fullscreen.reshow_minutes"))), null);
        Instant expireAt = expireAt(b);
        jobs.schedule(EXPIRE, id, expireAt, null);
        time.firstQuietStart(now, expireAt).ifPresent(at -> jobs.schedule(SUSPEND, id, at, null));
        return Optional.of(intervention);
    }

    private boolean onReshow(Jobs.Job job) {
        Store.Intervention i = store.intervention(job.targetId()).orElse(null);
        if (i == null || !i.running()) {
            return false;
        }
        sendLevel(i);
        jobs.schedule(RESHOW, i.id(),
                clock.now().plus(Duration.ofMinutes(settings.getInt("fullscreen.reshow_minutes"))), null);
        return true;
    }

    private boolean onExpire(Jobs.Job job) {
        Store.Intervention i = store.intervention(job.targetId()).orElse(null);
        if (i == null || !i.active()) {
            return false;
        }
        end(i, "expired", "no_response", "expired");
        store.setBlockStatus(i.blockId(), "missed");
        enqueueMissed(i.blockId(), "no_response");
        return true;
    }

    private boolean onSuspendCheck(Jobs.Job job) {
        Store.Intervention i = store.intervention(job.targetId()).orElse(null);
        if (i == null || !i.active() || !time.isQuiet(clock.now())) {
            return false;
        }
        end(i, "suspended", "quiet", "suspended");
        store.setBlockStatus(i.blockId(), "missed");
        enqueueMissed(i.blockId(), "quiet");
        return true;
    }

    /** [시작]으로 작업이 시작되면 비서가 체크인을 예약하도록 block_started turn (v3 §6 작업 중 동행, DECISIONS 참고). */
    private void enqueueStarted(long blockId) {
        ObjectNode ref = Json.object();
        ref.put("block_id", blockId);
        turns.enqueue("block_started", ref, TurnQueue.PRIORITY_DEFAULT);
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
        switch (b.status()) {
            case "planned" -> {
                store.setBlockStatus(b.id(), "missed");
                enqueueMissed(b.id(), "never_started");
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
        jobs.cancelForTarget(NEGOTIATION_TIMEOUT, i.id());
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

    // ---------------------------------------------------------------- 미루기 협상 (v3 §3.1~3.3)

    private boolean onNegotiationTimeout(Jobs.Job job) {
        Store.Intervention i = store.intervention(job.targetId()).orElse(null);
        if (i == null || !"negotiating".equals(i.status()) || job.expectedVersion() == null
                || i.version() != job.expectedVersion()) {
            return false;
        }
        db.update("""
                        UPDATE interventions SET status = 'running', negotiate_until = NULL, version = version + 1,
                        updated_at = CURRENT_TIMESTAMP WHERE id = ?""",
                i.id());
        sendLevel(store.intervention(i.id()).orElseThrow());
        jobs.schedule(RESHOW, i.id(),
                clock.now().plus(Duration.ofMinutes(settings.getInt("fullscreen.reshow_minutes"))), null);
        return true;
    }

    /** 협상 중 사용자 메시지가 오면 협상 시간을 연장한다(v3 §3.2). */
    public void extendNegotiation() {
        for (Store.Intervention i : store.runningInterventions()) {
            if ("negotiating".equals(i.status())) {
                startNegotiation(i);
            }
        }
    }

    /** negotiate_until = now + timeout, version +1, reshow 중단, timeout 작업 (재)예약. */
    private Store.Intervention startNegotiation(Store.Intervention i) {
        Instant until = clock.now().plus(Duration.ofMinutes(settings.getInt("negotiation.timeout_minutes")));
        db.update("""
                        UPDATE interventions SET status = 'negotiating', negotiate_until = ?, version = version + 1,
                        updated_at = CURRENT_TIMESTAMP WHERE id = ?""",
                until, i.id());
        Store.Intervention updated = store.intervention(i.id()).orElseThrow();
        jobs.cancelForTarget(RESHOW, i.id());
        jobs.cancelForTarget(NEGOTIATION_TIMEOUT, i.id());
        jobs.schedule(NEGOTIATION_TIMEOUT, i.id(), until, updated.version());
        return updated;
    }

    private String snoozeRequest(Long interventionId, Long blockId) {
        Optional<Store.Intervention> found = resolve(interventionId, blockId);
        store.log(found.map(Store.Intervention::id).orElse(null),
                found.map(Store.Intervention::blockId).orElse(blockId),
                found.map(Store.Intervention::level).orElse(null), clock.now(), "snooze_request");
        if (found.isEmpty() || !found.get().running()) {
            return "ok";
        }
        Store.Intervention i = startNegotiation(found.get());
        ObjectNode ref = Json.object();
        ref.put("intervention_id", i.id());
        ref.put("block_id", i.blockId());
        turns.enqueue("snooze_request", ref, TurnQueue.PRIORITY_USER);
        return "ok";
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
        String title = store.task(b.taskId()).map(Store.Task::title).orElse("");
        Map<String, String> data = new LinkedHashMap<>();
        data.put("type", "intervention.level");
        data.put("intervention_id", String.valueOf(i.id()));
        data.put("block_id", String.valueOf(b.id()));
        data.put("level", String.valueOf(i.level()));
        data.put("task_title", title);
        data.put("screen_text", b.screenText());
        data.put("tts_text", b.screenText());
        data.put("reshow_minutes", String.valueOf(settings.getInt("fullscreen.reshow_minutes")));
        data.put("block_end", time.iso(b.end()));
        data.put("expire_at", time.iso(expireAt(b)));
        if (b.workProductId() != null) {
            data.put("work_product_id", String.valueOf(b.workProductId()));
        }
        data.put("plan_version", String.valueOf(plan.version()));
        push.pushAll(data);
        store.log(i.id(), b.id(), i.level(), clock.now(), "sent");
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
        String status = switch (type) {
            case "start" -> start(interventionId, blockId);
            case "dismiss" -> dismiss(interventionId, blockId);
            case "snooze_request" -> snoozeRequest(interventionId, blockId);
            case "local_fired" -> localFired(blockId);
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

    private String start(Long interventionId, Long blockId) {
        Instant now = clock.now();
        Optional<Store.Intervention> found = resolve(interventionId, blockId);
        if (found.isPresent() && found.get().active()) {
            ack(found.get());
            enqueueStarted(found.get().blockId());
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
        Store.Task t = store.task(b.taskId()).orElseThrow();
        if (!"active".equals(t.status())) {
            return "ok";
        }
        startBlockNow(b, t, now);
        enqueueStarted(b.id());
        return "accepted_as_start_now";
    }

    /** 진행 중 개입을 acked 로 끝내고 블록을 started 로. [시작] 액션과 start_now 도구가 쓴다. */
    public void ack(Store.Intervention i) {
        Instant now = clock.now();
        end(i, "acked", "acked", "acked");
        store.log(i.id(), i.blockId(), i.level(), now, "acked");
        db.update("""
                        UPDATE blocks SET status = 'started', actual_start = ?, updated_at = CURRENT_TIMESTAMP
                        WHERE id = ?""",
                now, i.blockId());
    }

    private void startBlockNow(Store.Block b, Store.Task t, Instant now) {
        if (now.isBefore(b.end())) {
            db.update("""
                            UPDATE blocks SET status = 'started', actual_start = ?, updated_at = CURRENT_TIMESTAMP
                            WHERE id = ?""",
                    now, b.id());
            cancelBlockJobs(b.id());
            jobs.schedule(BLOCK_END, b.id(), b.end(), null);
        } else {
            Instant end = now.plus(Duration.ofMinutes(t.estMinutes()));
            for (Store.Block other : store.activeBlocksOverlapping(now, end)) {
                if (other.id() != b.id() && "planned".equals(other.status())) {
                    store.setBlockStatus(other.id(), "cancelled");
                    cancelBlockJobs(other.id());
                }
            }
            db.update("""
                            UPDATE blocks SET status = 'started', actual_start = ?, start_at = ?, end_at = ?,
                            version = version + 1, updated_at = CURRENT_TIMESTAMP WHERE id = ?""",
                    now, now, end, b.id());
            scheduleBlockJobs(store.block(b.id()).orElseThrow());
        }
        plan.bumpAndSync();
    }

    private String dismiss(Long interventionId, Long blockId) {
        Optional<Store.Intervention> i = resolve(interventionId, blockId);
        store.log(i.map(Store.Intervention::id).orElse(null),
                i.map(Store.Intervention::blockId).orElse(blockId), i.map(Store.Intervention::level).orElse(null),
                clock.now(), "dismissed");
        return "ok";
    }

    private String localFired(Long blockId) {
        if (blockId == null) {
            throw ApiException.badRequest("local_fired 에는 block_id 가 필요합니다");
        }
        Optional<Store.Intervention> i = store.runningForBlock(blockId);
        if (i.isEmpty()) {
            Store.Block b = store.block(blockId).orElse(null);
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
