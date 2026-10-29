package com.seobin.phonedrop.assistant.agent;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.seobin.phonedrop.assistant.ApiException;
import com.seobin.phonedrop.assistant.AssistantClock;
import com.seobin.phonedrop.assistant.AssistantDb;
import com.seobin.phonedrop.assistant.GoalCoverage;
import com.seobin.phonedrop.assistant.InterventionService;
import com.seobin.phonedrop.assistant.Json;
import com.seobin.phonedrop.assistant.PlanService;
import com.seobin.phonedrop.assistant.SettingsService;
import com.seobin.phonedrop.assistant.Store;
import com.seobin.phonedrop.assistant.TimeUtil;
import com.seobin.phonedrop.assistant.TurnQueue;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 고정 블록 만들기·바꾸기·취소(빌드문서 v3.1 §3.3 upsert_fixed_block, §4.4 /v1/fixed_blocks).
 * 도구(turnId 있음)는 changes 에 기록하고, 앱 API(turnId 없음)는 사용자 행동이라 바로 반영한 뒤 비서에게 알린다.
 */
@Service
public class FixedBlockService {

    /** 결과: error 가 있으면 실패. displaced = 밀려나 취소된 계획 블록. */
    public record Result(long id, List<Long> displaced, List<Long> cutoff, String error) {

        static Result fail(String error) {
            return new Result(0, List.of(), List.of(), error);
        }

        public boolean ok() {
            return error == null;
        }
    }

    private final AssistantDb db;
    private final AssistantClock clock;
    private final Store store;
    private final SettingsService settings;
    private final Validator validator;
    private final InterventionService interventions;
    private final PlanService plan;
    private final Changes changes;
    private final GoalCoverage coverage;
    private final TurnQueue turns;

    public FixedBlockService(AssistantDb db, AssistantClock clock, Store store, SettingsService settings,
                             Validator validator, InterventionService interventions, PlanService plan, Changes changes,
                             GoalCoverage coverage, TurnQueue turns) {
        this.db = db;
        this.clock = clock;
        this.store = store;
        this.settings = settings;
        this.validator = validator;
        this.interventions = interventions;
        this.plan = plan;
        this.changes = changes;
        this.coverage = coverage;
        this.turns = turns;
    }

    /**
     * in: {id?, title, start, end | duration_min, lead_minutes?, buffer_after_min?, location?, goal_id?, task_id?,
     * screen_text? | first_action?, sensitive?}. turnId 가 null 이면 앱 API(source=manual).
     */
    public Result upsert(JsonNode in, Long turnId) {
        Long id = in.hasNonNull("id") ? in.get("id").asLong() : null;
        Store.Block existing = null;
        if (id != null) {
            existing = store.block(id).orElse(null);
            if (existing == null || !existing.fixed()) {
                return Result.fail("고정 블록#" + id + "이 없습니다");
            }
            if (!List.of("planned", "nudging").contains(existing.status())) {
                return Result.fail("블록#" + id + "은 " + existing.status() + " 상태라 바꿀 수 없습니다");
            }
        }
        String title = in.hasNonNull("title") ? in.get("title").asString("").trim()
                : existing == null ? "" : existing.title();
        if (title.isEmpty()) {
            return Result.fail("고정 블록에는 title 이 필요합니다");
        }
        Instant start = in.hasNonNull("start") ? TimeUtil.parse(in.get("start").asString())
                : existing == null ? null : existing.start();
        if (start == null) {
            return Result.fail("고정 블록에는 start 가 필요합니다");
        }
        Instant end;
        if (in.hasNonNull("end")) {
            end = TimeUtil.parse(in.get("end").asString());
        } else if (in.hasNonNull("duration_min")) {
            end = start.plus(Duration.ofMinutes(in.get("duration_min").asInt()));
        } else if (existing != null) {
            end = start.plus(Duration.between(existing.start(), existing.end()));
        } else {
            return Result.fail("고정 블록에는 end 또는 duration_min 이 필요합니다");
        }
        if (!end.isAfter(start)) {
            return Result.fail("end 는 start 보다 뒤여야 합니다");
        }
        // v3.1 §3.2.1: 준비 시간은 기본값이 없다. 비워 두면 아직 안 정함(lead 0 으로 계산).
        // 앱(turnId 없음)에서 넣은 값은 user, 비서 도구가 넣은 값은 assistant(사용자가 직접 말한 값이면 prep_from_user).
        boolean prepGiven = in.hasNonNull("lead_minutes") || in.hasNonNull("buffer_after_min");
        int lead = in.hasNonNull("lead_minutes") ? Math.max(0, in.get("lead_minutes").asInt())
                : existing != null ? existing.leadMinutes() : 0;
        int bufferAfter = in.hasNonNull("buffer_after_min") ? Math.max(0, in.get("buffer_after_min").asInt())
                : existing != null ? existing.bufferAfterMin() : 0;
        String prepSetBy = existing == null ? null : existing.prepSetBy();
        if (prepGiven) {
            boolean byUser = turnId == null || in.path("prep_from_user").asBoolean(false);
            boolean prepChanged = existing == null || lead != existing.leadMinutes()
                    || bufferAfter != existing.bufferAfterMin();
            if (!byUser && prepChanged && existing != null && "user".equals(existing.prepSetBy())) {
                return Result.fail("사용자가 정한 준비 시간(출발 준비 " + existing.leadMinutes() + "분, 끝난 뒤 "
                        + existing.bufferAfterMin() + "분)이라 바꾸지 않습니다. 필요하면 사용자에게 말로 제안하세요.");
            }
            if (prepChanged || prepSetBy == null) {
                prepSetBy = byUser ? "user" : "assistant";
            }
        }
        Long goalId = in.has("goal_id") ? (in.get("goal_id").isNull() ? null : in.get("goal_id").asLong())
                : existing == null ? null : existing.goalId();
        if (goalId != null && store.goal(goalId).filter(g -> "active".equals(g.status())).isEmpty()) {
            return Result.fail("활성 목표#" + goalId + "이 없습니다");
        }
        Long taskId = in.has("task_id") ? (in.get("task_id").isNull() ? null : in.get("task_id").asLong())
                : existing == null ? null : existing.taskId();
        if (taskId != null && store.task(taskId).isEmpty()) {
            return Result.fail("할일#" + taskId + "이 없습니다");
        }
        String location = in.has("location") ? emptyToNull(in.path("location").asString(""))
                : existing == null ? null : existing.location();
        boolean sensitive = in.hasNonNull("sensitive") ? in.get("sensitive").asBoolean()
                : existing != null && existing.sensitive();
        String screen = screenText(in, title, lead, existing);

        Instant now = clock.now();
        boolean timeChanged = existing == null || !start.equals(existing.start()) || !end.equals(existing.end())
                || lead != existing.leadMinutes() || bufferAfter != existing.bufferAfterMin();
        Validator.FixedCheck check = timeChanged
                ? validator.validateFixed(id, start, end, lead, bufferAfter, now)
                : new Validator.FixedCheck(List.of(), List.of(), List.of());
        if (!check.errors().isEmpty()) {
            return Result.fail(String.join("\n", check.errors()));
        }

        long blockId;
        if (existing == null) {
            String source = turnId == null ? "manual" : "chat";
            blockId = db.insert("""
                            INSERT INTO blocks(kind, title, task_id, goal_id, start_at, end_at, status, screen_text,
                            lead_minutes, buffer_after_min, location, source, sensitive, prep_set_by)
                            VALUES ('fixed', ?, ?, ?, ?, ?, 'planned', ?, ?, ?, ?, ?, ?, ?)""",
                    title, taskId, goalId, start, end, screen, lead, bufferAfter, location, source, sensitive,
                    prepSetBy);
            record(turnId, blockId, null);
        } else {
            blockId = existing.id();
            JsonNode before = changes.snapshot("block", blockId);
            if (timeChanged && "nudging".equals(existing.status())) {
                // 사용자가 시각을 바꿨다: 지금 개입은 끝내고 새 시각에 다시 개입한다.
                interventions.cancelRunningForBlock(blockId);
            }
            db.update("""
                            UPDATE blocks SET title = ?, task_id = ?, goal_id = ?, start_at = ?, end_at = ?,
                            screen_text = ?, lead_minutes = ?, buffer_after_min = ?, location = ?, sensitive = ?,
                            prep_set_by = ?, status = CASE WHEN ? THEN 'planned' ELSE status END,
                            version = version + CASE WHEN ? THEN 1 ELSE 0 END, updated_at = CURRENT_TIMESTAMP
                            WHERE id = ?""",
                    title, taskId, goalId, start, end, screen, lead, bufferAfter, location, sensitive, prepSetBy,
                    timeChanged, timeChanged, blockId);
            record(turnId, blockId, before);
        }

        List<Long> displaced = new ArrayList<>();
        for (Store.Block b : check.displaced()) {
            cancel(b, turnId);
            displaced.add(b.id());
        }
        List<Long> cutoff = new ArrayList<>();
        Instant blockedStart = start.minus(Duration.ofMinutes(lead));
        for (Store.Block b : check.cutoff()) {
            interventions.scheduleCutoff(b.id(), blockedStart);
            cutoff.add(b.id());
        }
        if (timeChanged) {
            interventions.scheduleBlockJobs(store.block(blockId).orElseThrow());
        }
        plan.bumpAndSync();
        return new Result(blockId, displaced, cutoff, null);
    }

    /** 고정 블록 취소(cancel entity=fixed_block, DELETE /v1/fixed_blocks/{id}). */
    public Result cancel(long id, Long turnId) {
        Store.Block b = store.block(id).orElse(null);
        if (b == null || !b.fixed()) {
            return Result.fail("고정 블록#" + id + "이 없습니다");
        }
        if (!List.of("planned", "nudging", "started").contains(b.status())) {
            return Result.fail("블록#" + id + "은 이미 " + b.status() + " 상태입니다");
        }
        cancel(b, turnId);
        plan.bumpAndSync();
        if (turnId == null) {
            coverage.onBlockClosed(id);
        }
        return new Result(id, List.of(), List.of(), null);
    }

    private void cancel(Store.Block b, Long turnId) {
        JsonNode before = changes.snapshot("block", b.id());
        interventions.cancelRunningForBlock(b.id());
        store.setBlockStatus(b.id(), "cancelled");
        interventions.cancelBlockJobs(b.id());
        record(turnId, b.id(), before);
    }

    /**
     * 앱에서 직접 바꾼 결과를 비서에게 알린다(§4.4): displaced 가 있으면 blocks_displaced turn.
     */
    public void notifyDisplaced(Result r) {
        if (r.ok() && !r.displaced().isEmpty()) {
            ObjectNode ref = Json.object();
            ref.put("block_id", r.id());
            ref.set("displaced", Json.MAPPER.valueToTree(r.displaced()));
            turns.enqueue("blocks_displaced", ref, TurnQueue.PRIORITY_DEFAULT);
        }
    }

    /**
     * 앱에서 준비 시간을 비워 두고 만든 고정 블록이면 fixed_block_added turn(priority 10, §3.2.1).
     * 비서가 lead·buffer 를 정하거나 준비용 계획 블록을 만든다.
     */
    public void notifyPrepNeeded(Result r) {
        if (r.ok() && store.block(r.id()).map(b -> b.prepSetBy() == null).orElse(false)) {
            ObjectNode ref = Json.object();
            ref.put("block_id", r.id());
            turns.enqueue("fixed_block_added", ref, TurnQueue.PRIORITY_USER);
        }
    }

    public Map<String, Object> toResponse(Result r) {
        if (!r.ok()) {
            throw ApiException.unprocessable(r.error());
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("id", r.id());
        m.put("displaced", r.displaced());
        m.put("plan_version", plan.version());
        return m;
    }

    private void record(Long turnId, long blockId, JsonNode before) {
        if (turnId != null) {
            changes.record(turnId, "block", blockId, before, changes.snapshot("block", blockId));
        }
    }

    private static String screenText(JsonNode in, String title, int lead, Store.Block existing) {
        String given = in.path("screen_text").asString("").trim();
        if (!given.isEmpty()) {
            return given;
        }
        String firstAction = in.path("first_action").asString("").trim();
        if (!firstAction.isEmpty()) {
            return InterventionService.fixedScreenText(title, lead) + " 첫 행동: " + firstAction;
        }
        // 기본 문구였으면 제목·lead 에 맞춰 다시 만든다. 직접 정한 문구는 유지.
        if (existing != null && !existing.screenText().equals(
                InterventionService.fixedScreenText(existing.title(), existing.leadMinutes()))) {
            return existing.screenText();
        }
        return InterventionService.fixedScreenText(title, lead);
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
