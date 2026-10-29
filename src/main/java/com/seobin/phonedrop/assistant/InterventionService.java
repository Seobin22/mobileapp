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

    public static final int LEVEL = 3;

    private final AssistantDb db;
    private final AssistantClock clock;
    private final Jobs jobs;
    private final Store store;
    private final TimeUtil time;
    private final SettingsService settings;
    private final PlanService plan;
    private final PushService push;

    public InterventionService(AssistantDb db, AssistantClock clock, Jobs jobs, Store store, TimeUtil time,
                               SettingsService settings, PlanService plan, PushService push) {
        this.db = db;
        this.clock = clock;
        this.jobs = jobs;
        this.store = store;
        this.time = time;
        this.settings = settings;
        this.plan = plan;
        this.push = push;
        jobs.register(START, this::onStartIntervention);
        jobs.register(RESHOW, this::onReshow);
        jobs.register(EXPIRE, this::onExpire);
        jobs.register(SUSPEND, this::onSuspendCheck);
        jobs.register(BLOCK_END, this::onBlockEnd);
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
        if (i == null || !i.running()) {
            return false;
        }
        end(i, "expired", "no_response", "expired");
        store.setBlockStatus(i.blockId(), "missed");
        return true;
    }

    private boolean onSuspendCheck(Jobs.Job job) {
        Store.Intervention i = store.intervention(job.targetId()).orElse(null);
        if (i == null || !i.running() || !time.isQuiet(clock.now())) {
            return false;
        }
        end(i, "suspended", "quiet", "suspended");
        store.setBlockStatus(i.blockId(), "missed");
        return true;
    }

    private boolean onBlockEnd(Jobs.Job job) {
        Store.Block b = store.block(job.targetId()).orElse(null);
        if (b == null || !job.fireAt().equals(b.end())) {
            return false;
        }
        switch (b.status()) {
            case "planned" -> store.setBlockStatus(b.id(), "missed");
            case "started" -> db.update("""
                            UPDATE blocks SET status = 'done', actual_end = ?, updated_at = CURRENT_TIMESTAMP
                            WHERE id = ?""",
                    clock.now(), b.id());
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
            case "dismiss", "snooze_request" -> dismiss(interventionId, blockId);
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
        if (byId.isPresent() && byId.get().running()) {
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
        if (found.isPresent() && found.get().running()) {
            Store.Intervention i = found.get();
            end(i, "acked", "acked", "acked");
            store.log(i.id(), i.blockId(), i.level(), now, "acked");
            db.update("""
                            UPDATE blocks SET status = 'started', actual_start = ?, updated_at = CURRENT_TIMESTAMP
                            WHERE id = ?""",
                    now, i.blockId());
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
        return "accepted_as_start_now";
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
