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
import com.seobin.phonedrop.assistant.BlockLogs;
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
    private final BlockLogs logs;
    private final com.seobin.phonedrop.assistant.TimeUtil time;

    public FixedBlockService(AssistantDb db, AssistantClock clock, Store store, SettingsService settings,
                             Validator validator, InterventionService interventions, PlanService plan, Changes changes,
                             GoalCoverage coverage, TurnQueue turns, BlockLogs logs,
                             com.seobin.phonedrop.assistant.TimeUtil time) {
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
        this.logs = logs;
        this.time = time;
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
            // v3.2 §3.1: 메모는 사용자만 쓴다(앱). 도구로 만들 때는 비워 둔다.
            String memo = turnId == null ? memoOf(in) : "";
            blockId = db.insert("""
                            INSERT INTO blocks(kind, title, task_id, goal_id, start_at, end_at, status, screen_text,
                            lead_minutes, buffer_after_min, location, source, sensitive, prep_set_by, memo, memo_updated_at)
                            VALUES ('fixed', ?, ?, ?, ?, ?, 'planned', ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    title, taskId, goalId, start, end, screen, lead, bufferAfter, location, source, sensitive,
                    prepSetBy, memo, memo.isEmpty() ? null : now);
            record(turnId, blockId, null);
            logs.add(blockId, "created", actor(turnId), turnId == null ? "사용자가 앱에서 생성" : "비서가 생성");
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
            if (!start.equals(existing.start()) || !end.equals(existing.end())) {
                ObjectNode data = Json.object();
                data.put("from_start", time.iso(existing.start()));
                data.put("from_end", time.iso(existing.end()));
                data.put("to_start", time.iso(start));
                data.put("to_end", time.iso(end));
                logs.add(blockId, "moved", actor(turnId), time.hm(existing.start()) + "→" + time.hm(start)
                        + (turnId == null ? " (사용자가 앱에서)" : " (비서)"), data);
                // v3.2 §2.2: 반복 회차의 시각을 바꾸면 '이 날만'(moved 예외).
                recordMoved(existing);
                List<Long> linked = linkedFuture(blockId);
                if (!linked.isEmpty()) {
                    notifyFixedChanged(blockId, linked, "moved");
                }
            }
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
        logs.add(id, "note", actor(turnId), turnId == null ? "사용자가 앱에서 삭제" : "비서가 삭제");
        // v3.2 §2.2: 반복 회차를 지우면 '이 날만' 없음(skip 예외, 다시 만들지 않는다).
        recordSkipped(b);
        // v3.2 §6: 연결된 미래 계획 블록과 그 할 일을 취소하고 비서가 알리게 한다.
        List<Long> linked = linkedFuture(id);
        for (long linkedId : linked) {
            Store.Block lb = store.block(linkedId).orElseThrow();
            cancel(lb, turnId);
            logs.add(linkedId, "note", "system", "연결된 고정 일정#" + id + "이 삭제되어 취소");
            if (lb.taskId() != null) {
                db.update("UPDATE tasks SET status = 'cancelled', updated_at = CURRENT_TIMESTAMP WHERE id = ? AND status = 'active'",
                        lb.taskId());
            }
        }
        if (!linked.isEmpty()) {
            notifyFixedChanged(id, linked, "cancelled");
        }
        plan.bumpAndSync();
        if (turnId == null) {
            coverage.onBlockClosed(id);
        }
        return new Result(id, List.of(), List.of(), null);
    }

    /** 고정 블록에 밀려난 계획 블록을 취소한다(반복 회차 생성 등 다른 서비스가 쓴다). */
    public void displace(Store.Block b, Long turnId) {
        cancel(b, turnId);
        logs.add(b.id(), "note", "system", "고정 일정에 밀려 취소");
    }

    /** 이 고정 블록에 연결된, 아직 시작 전인 미래 계획 블록. */
    public List<Long> linkedFuture(long fixedBlockId) {
        return db.jdbc().sql("""
                        SELECT id FROM blocks WHERE linked_fixed_block_id = ? AND status IN ('planned', 'nudging')
                        AND end_at > ? ORDER BY start_at""")
                .param(fixedBlockId).param(AssistantDb.ts(clock.now())).query(Long.class).list();
    }

    /** v3.2 §6: 고정 일정이 바뀌거나 지워지면 fixed_changed turn(priority 5, ref = 고정 블록 + 연결 블록). */
    public void notifyFixedChanged(long fixedBlockId, List<Long> linked, String change) {
        ObjectNode ref = Json.object();
        ref.put("block_id", fixedBlockId);
        ref.set("linked", Json.MAPPER.valueToTree(linked));
        ref.put("change", change);
        turns.enqueueIfAbsent("fixed_changed", ref, TurnQueue.PRIORITY_DEFAULT, clock.now());
    }

    private void recordMoved(Store.Block occurrence) {
        if (occurrence.seriesId() == null || occurrence.occurrenceDate() == null) {
            return;
        }
        int n = db.jdbc().sql("""
                        SELECT COUNT(*) FROM series_exceptions WHERE series_id = ? AND occurrence_date = ? AND action = 'moved'""")
                .param(occurrence.seriesId()).param(occurrence.occurrenceDate()).query(Integer.class).single();
        if (n == 0) {
            db.insert("""
                            INSERT INTO series_exceptions(series_id, occurrence_date, action, moved_block_id)
                            VALUES (?, ?, 'moved', ?)""",
                    occurrence.seriesId(), occurrence.occurrenceDate(), occurrence.id());
        }
    }

    private void recordSkipped(Store.Block occurrence) {
        if (occurrence.seriesId() == null || occurrence.occurrenceDate() == null) {
            return;
        }
        db.insert("INSERT INTO series_exceptions(series_id, occurrence_date, action) VALUES (?, ?, 'skip')",
                occurrence.seriesId(), occurrence.occurrenceDate());
    }

    private String memoOf(JsonNode in) {
        String memo = in.path("memo").asString("").strip();
        int max = settings.getInt("memo.max_chars");
        if (memo.length() > max) {
            throw ApiException.unprocessable("메모는 " + max + "자 이하여야 합니다");
        }
        return memo;
    }

    private static String actor(Long turnId) {
        return turnId == null ? "user" : "assistant";
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
