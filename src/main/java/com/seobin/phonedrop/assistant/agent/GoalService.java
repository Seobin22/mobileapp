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
import com.seobin.phonedrop.assistant.Store;
import com.seobin.phonedrop.assistant.TimeUtil;
import com.seobin.phonedrop.assistant.TurnQueue;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 목표 탭(빌드문서 v3.1 §3.7)과 목표 끝내기(cancel·complete) 공통 처리.
 * 탭에서 누른 것은 사용자 행동이라 권한 검사 없이 바로 적용하고, changes 에 남긴 뒤 goal_changed_by_user turn 으로 비서에게 알린다.
 */
@Service
public class GoalService {

    private final AssistantDb db;
    private final AssistantClock clock;
    private final Store store;
    private final TimeUtil time;
    private final Timeline timeline;
    private final GoalCoverage coverage;
    private final InterventionService interventions;
    private final PlanService plan;
    private final Changes changes;
    private final TurnQueue turns;

    public GoalService(AssistantDb db, AssistantClock clock, Store store, TimeUtil time, Timeline timeline,
                       GoalCoverage coverage, InterventionService interventions, PlanService plan, Changes changes,
                       TurnQueue turns) {
        this.db = db;
        this.clock = clock;
        this.store = store;
        this.time = time;
        this.timeline = timeline;
        this.coverage = coverage;
        this.interventions = interventions;
        this.plan = plan;
        this.changes = changes;
        this.turns = turns;
    }

    // ------------------------------------------------------------------ 조회

    /** GET /v1/goals: 활성 목표 먼저, 끝난 목표는 최근 30일. */
    public List<Map<String, Object>> list() {
        Instant now = clock.now();
        Map<Long, String> load = new LinkedHashMap<>();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> groups = (List<Map<String, Object>>) timeline.loadSummary().get("groups");
        for (Map<String, Object> g : groups) {
            if (g.get("goal_id") != null) {
                load.put(((Number) g.get("goal_id")).longValue(), (String) g.get("status"));
            }
        }
        List<Store.Goal> goals = db.jdbc().sql("""
                        SELECT * FROM goals WHERE status = 'active' OR updated_at >= ?
                        ORDER BY CASE WHEN status = 'active' THEN 0 ELSE 1 END, priority, id""")
                .param(AssistantDb.ts(now.minus(Duration.ofDays(30))))
                .query(Store::goal).list();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Store.Goal g : goals) {
            Map<String, Object> m = view(g);
            m.put("load_status", "active".equals(g.status()) ? load.getOrDefault(g.id(), "no_deadline") : null);
            m.put("next_block_start", time.iso(coverage.nextBlockStart(g.id(), now)));
            m.put("upcoming_block_count", store.blocksOfGoal(g.id()).stream()
                    .filter(b -> "planned".equals(b.status()) && b.start().isAfter(now)).count());
            m.put("coverage", "active".equals(g.status()) ? coverage.status(g, now) : null);
            result.add(m);
        }
        return result;
    }

    public Map<String, Object> view(Store.Goal g) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", g.id());
        m.put("title", timeline.goalTitle(g));
        m.put("label", g.label());
        m.put("color", g.color());
        m.put("deadline", time.iso(g.deadline()));
        m.put("status", g.status());
        m.put("priority", g.priority());
        m.put("progress", g.progress() == null ? null : Json.parse(g.progress()));
        m.put("strategy_summary", g.strategySummary());
        m.put("waiting_until", time.iso(g.waitingUntil()));
        m.put("waiting_reason", g.waitingReason());
        return m;
    }

    /** GET /v1/goals/{id}/blocks: 최근 7일 지난 것 + 앞으로. 할 일을 통해 연결된 계획 블록도 goal_id 가 채워져 있다. */
    public Map<String, Object> blocks(long goalId) {
        Store.Goal g = store.goal(goalId).orElseThrow(() -> ApiException.notFound("목표가 없습니다: " + goalId));
        Instant since = clock.now().minus(Duration.ofDays(7));
        List<Map<String, Object>> blocks = new ArrayList<>();
        for (Store.Block b : store.blocksOfGoal(goalId)) {
            if (b.end().isBefore(since) || "cancelled".equals(b.status())) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", b.id());
            m.put("kind", b.kind());
            m.put("title", store.blockTitle(b));
            m.put("start", time.iso(b.start()));
            m.put("end", time.iso(b.end()));
            m.put("status", b.status());
            m.put("task_id", b.taskId());
            blocks.add(m);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("goal", view(g));
        r.put("blocks", blocks);
        return r;
    }

    // ------------------------------------------------------------------ 변경 (탭)

    /** PATCH /v1/goals/{id} {deadline?, priority?, label?, color?, title?} */
    public Map<String, Object> patch(long goalId, JsonNode req) {
        Store.Goal g = store.goal(goalId).orElseThrow(() -> ApiException.notFound("목표가 없습니다: " + goalId));
        Map<String, Object> set = new LinkedHashMap<>();
        if (req.has("deadline")) {
            set.put("deadline", req.get("deadline").isNull() || req.get("deadline").asString("").isBlank()
                    ? null : TimeUtil.parse(req.get("deadline").asString()));
        }
        if (req.hasNonNull("priority")) {
            int p = req.get("priority").asInt();
            if (p < 1 || p > 5) {
                throw ApiException.unprocessable("priority 는 1~5 입니다");
            }
            set.put("priority", p);
        }
        if (req.hasNonNull("label")) {
            String label = req.get("label").asString("").trim();
            set.put("label", label.isEmpty() ? GoalCoverage.defaultLabel(g.title()) : GoalCoverage.defaultLabel(label));
        }
        if (req.hasNonNull("color")) {
            String color = req.get("color").asString("").trim();
            if (!color.matches("#[0-9A-Fa-f]{6}")) {
                throw ApiException.unprocessable("color 는 #RRGGBB 형식입니다");
            }
            set.put("color", color.toUpperCase());
        }
        if (req.hasNonNull("title")) {
            String title = req.get("title").asString("").trim();
            if (title.isEmpty()) {
                throw ApiException.unprocessable("제목은 비울 수 없습니다");
            }
            set.put("title", title);
        }
        if (set.isEmpty()) {
            throw ApiException.badRequest("바꿀 값이 없습니다");
        }
        long turnId = userTurn(goalId, "patch", set.keySet().toString());
        JsonNode before = changes.snapshot("goal", goalId);
        update(goalId, set);
        changes.record(turnId, "goal", goalId, before, changes.snapshot("goal", goalId));
        return Map.of("ok", true, "goal", view(store.goal(goalId).orElseThrow()));
    }

    public Map<String, Object> completeByUser(long goalId) {
        requireActive(goalId);
        long turnId = userTurn(goalId, "complete", null);
        return finish(goalId, "done", turnId);
    }

    public Map<String, Object> cancelByUser(long goalId) {
        requireActive(goalId);
        long turnId = userTurn(goalId, "cancel", null);
        return finish(goalId, "cancelled", turnId);
    }

    private void requireActive(long goalId) {
        Store.Goal g = store.goal(goalId).orElseThrow(() -> ApiException.notFound("목표가 없습니다: " + goalId));
        if (!"active".equals(g.status())) {
            throw ApiException.unprocessable("이미 " + g.status() + " 상태인 목표입니다");
        }
    }

    /**
     * 목표 끝내기(cancel → cancelled, complete → done). 할 일 cancelled/done, 미래 계획 블록 cancelled,
     * 연결된 고정 블록은 지우지 않고 goal_id 만 비운다(사용자가 직접 잡은 약속). 진행 중 개입이 그 목표 블록이면
     * cancelled + intervention.end. 도구 cancel(entity=goal)도 이것을 쓴다.
     */
    public Map<String, Object> finish(long goalId, String status, long turnId) {
        JsonNode before = changes.snapshot("goal", goalId);
        db.update("UPDATE goals SET status = ?, waiting_until = NULL, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                status, goalId);
        changes.record(turnId, "goal", goalId, before, changes.snapshot("goal", goalId));
        coverage.scheduleWaitingCheck(goalId, null);

        String taskStatus = "done".equals(status) ? "done" : "cancelled";
        List<Long> taskIds = db.jdbc().sql("SELECT id FROM tasks WHERE goal_id = ? AND status = 'active'")
                .param(goalId).query(Long.class).list();
        Instant now = clock.now();
        List<Long> cancelledBlocks = new ArrayList<>();
        List<Long> releasedFixed = new ArrayList<>();
        for (long taskId : taskIds) {
            JsonNode tb = changes.snapshot("task", taskId);
            db.update("""
                            UPDATE tasks SET status = ?, remaining_minutes = CASE WHEN ? = 'done' THEN 0 ELSE remaining_minutes END,
                            updated_at = CURRENT_TIMESTAMP WHERE id = ?""",
                    taskStatus, taskStatus, taskId);
            changes.record(turnId, "task", taskId, tb, changes.snapshot("task", taskId));
        }
        List<Store.Block> related = new ArrayList<>(store.blocksOfGoal(goalId));
        for (long taskId : taskIds) {
            store.blocksOfTask(taskId).stream().filter(b -> related.stream().noneMatch(r -> r.id() == b.id()))
                    .forEach(related::add);
        }
        for (Store.Block b : related) {
            boolean future = "nudging".equals(b.status()) || ("planned".equals(b.status()) && b.end().isAfter(now));
            if (!future) {
                continue;
            }
            JsonNode bb = changes.snapshot("block", b.id());
            if (b.fixed()) {
                db.update("UPDATE blocks SET goal_id = NULL, updated_at = CURRENT_TIMESTAMP WHERE id = ?", b.id());
                releasedFixed.add(b.id());
            } else {
                interventions.cancelRunningForBlock(b.id());
                store.setBlockStatus(b.id(), "cancelled");
                interventions.cancelBlockJobs(b.id());
                cancelledBlocks.add(b.id());
            }
            changes.record(turnId, "block", b.id(), bb, changes.snapshot("block", b.id()));
        }
        plan.bumpAndSync();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true);
        r.put("id", goalId);
        r.put("cancelled_blocks", cancelledBlocks);
        r.put("released_fixed_blocks", releasedFixed);
        return r;
    }

    /** 탭 변경용 turn: 먼저 만들고(changes 의 turn_id) 비서가 시간표를 정리하도록 예약한다(priority 10). */
    private long userTurn(long goalId, String action, String detail) {
        ObjectNode ref = Json.object();
        ref.put("goal_id", goalId);
        ref.put("action", action);
        if (detail != null) {
            ref.put("fields", detail);
        }
        return turns.enqueue("goal_changed_by_user", ref, TurnQueue.PRIORITY_USER);
    }

    private void update(long id, Map<String, Object> set) {
        StringBuilder sql = new StringBuilder("UPDATE goals SET ");
        List<Object> params = new ArrayList<>();
        set.forEach((k, v) -> {
            sql.append(k).append(" = ?, ");
            params.add(v);
        });
        sql.append("updated_at = CURRENT_TIMESTAMP WHERE id = ?");
        params.add(id);
        db.update(sql.toString(), params.toArray());
    }
}
