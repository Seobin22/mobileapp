package com.seobin.phonedrop.assistant;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;

import tools.jackson.databind.JsonNode;

/** v1 수동 등록(POST/GET/PATCH/DELETE /v1/admin/tasks). 할 일 하나에 블록 하나. */
@Service
public class TaskAdminService {

    private static final String SCREEN_PREFIX = "지금 할 건 하나예요. ";

    private final AssistantDb db;
    private final AssistantClock clock;
    private final Store store;
    private final TimeUtil time;
    private final PlanService plan;
    private final InterventionService interventions;

    public TaskAdminService(AssistantDb db, AssistantClock clock, Store store, TimeUtil time, PlanService plan,
                            InterventionService interventions) {
        this.db = db;
        this.clock = clock;
        this.store = store;
        this.time = time;
        this.plan = plan;
        this.interventions = interventions;
    }

    public Map<String, Object> create(JsonNode req) {
        String title = required(req, "title");
        String firstAction = required(req, "first_action");
        Instant start = TimeUtil.parse(required(req, "start"));
        int duration = req.path("duration_min").asInt(0);
        Instant end = start.plus(Duration.ofMinutes(duration));
        validate(start, end, duration, null);

        long taskId = db.insert("INSERT INTO tasks(title, first_action, est_minutes) VALUES (?, ?, ?)",
                title, firstAction, duration);
        long blockId = db.insert("INSERT INTO blocks(task_id, start_at, end_at, screen_text) VALUES (?, ?, ?, ?)",
                taskId, start, end, SCREEN_PREFIX + firstAction);
        interventions.scheduleBlockJobs(store.block(blockId).orElseThrow());
        plan.bumpAndSync();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("task_id", taskId);
        result.put("block_id", blockId);
        return result;
    }

    /** 검증 실패는 422 와 사람이 읽을 사유. */
    private void validate(Instant start, Instant end, int duration, Long excludeBlockId) {
        if (start.isBefore(clock.now())) {
            throw ApiException.unprocessable("시작 시각(" + time.shortText(start) + ")이 이미 지났습니다");
        }
        if (duration < 5) {
            throw ApiException.unprocessable("길이는 5분 이상이어야 합니다");
        }
        for (Store.Block other : store.activeBlocksOverlapping(start, end)) {
            if (excludeBlockId == null || other.id() != excludeBlockId) {
                String title = store.task(other.taskId()).map(Store.Task::title).orElse("");
                throw ApiException.unprocessable("다른 블록(" + time.shortText(other.start()) + "–"
                        + time.hm(other.end()) + ", " + title + ")과 겹칩니다");
            }
        }
        Optional<TimeUtil.Window> quiet = time.firstQuietOverlap(start, end);
        if (quiet.isPresent()) {
            throw ApiException.unprocessable("수면·방해 금지 시간(" + time.shortText(quiet.get().start()) + "–"
                    + time.hm(quiet.get().end()) + ")과 겹칩니다");
        }
    }

    public Map<String, Object> list() {
        LocalDate today = time.logicalDay(clock.now());
        Instant from = today.atStartOfDay(time.zone()).toInstant();
        Instant to = today.plusDays(7).atStartOfDay(time.zone()).toInstant();

        Map<Long, List<Store.Block>> byTask = new LinkedHashMap<>();
        for (Store.Block b : store.blocksStartingBetween(from, to)) {
            byTask.computeIfAbsent(b.taskId(), k -> new ArrayList<>()).add(b);
        }
        List<Long> activeTasks = db.jdbc().sql("SELECT id FROM tasks WHERE status = 'active' ORDER BY id")
                .query(Long.class).list();
        for (long id : activeTasks) {
            byTask.computeIfAbsent(id, k -> store.blocksOfTask(id));
        }

        List<Map<String, Object>> tasks = new ArrayList<>();
        for (Map.Entry<Long, List<Store.Block>> e : byTask.entrySet()) {
            Store.Task t = store.task(e.getKey()).orElseThrow();
            Map<String, Object> task = new LinkedHashMap<>();
            task.put("id", t.id());
            task.put("title", t.title());
            task.put("first_action", t.firstAction());
            task.put("est_minutes", t.estMinutes());
            task.put("status", t.status());
            List<Map<String, Object>> blocks = new ArrayList<>();
            for (Store.Block b : e.getValue()) {
                Map<String, Object> block = new LinkedHashMap<>();
                block.put("id", b.id());
                block.put("start", time.iso(b.start()));
                block.put("end", time.iso(b.end()));
                block.put("status", b.status());
                block.put("screen_text", b.screenText());
                blocks.add(block);
            }
            task.put("blocks", blocks);
            tasks.add(task);
        }
        tasks.sort((a, b) -> String.valueOf(firstStart(a)).compareTo(String.valueOf(firstStart(b))));
        return Map.of("tasks", tasks);
    }

    @SuppressWarnings("unchecked")
    private static Object firstStart(Map<String, Object> task) {
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) task.get("blocks");
        return blocks.isEmpty() ? "~" : blocks.getFirst().get("start");
    }

    public Map<String, Object> patch(long taskId, JsonNode req) {
        Store.Task t = store.task(taskId).orElseThrow(() -> ApiException.notFound("할 일이 없습니다: " + taskId));
        if (!"active".equals(t.status())) {
            throw ApiException.unprocessable("끝났거나 취소된 할 일은 바꿀 수 없습니다");
        }
        Store.Block b = currentBlock(taskId)
                .orElseThrow(() -> ApiException.unprocessable("바꿀 블록이 없습니다"));

        String title = req.hasNonNull("title") ? req.get("title").asString().trim() : t.title();
        String firstAction = req.hasNonNull("first_action") ? req.get("first_action").asString().trim()
                : t.firstAction();
        if (title.isEmpty() || firstAction.isEmpty()) {
            throw ApiException.unprocessable("제목과 첫 행동은 비울 수 없습니다");
        }
        Instant start = req.hasNonNull("start") ? TimeUtil.parse(req.get("start").asString()) : b.start();
        int duration = req.hasNonNull("duration_min") ? req.get("duration_min").asInt()
                : (int) TimeUtil.minutesBetween(b.start(), b.end());
        Instant end = start.plus(Duration.ofMinutes(duration));
        boolean timeChanged = !start.equals(b.start()) || !end.equals(b.end());

        if (timeChanged) {
            if (!List.of("planned", "nudging").contains(b.status())) {
                throw ApiException.unprocessable("시작했거나 끝난 블록은 시각을 바꿀 수 없습니다");
            }
            validate(start, end, duration, b.id());
            if ("nudging".equals(b.status())) {
                interventions.cancelRunningForBlock(b.id());
            }
        }

        db.update("""
                        UPDATE tasks SET title = ?, first_action = ?, est_minutes = ?, version = version + 1,
                        updated_at = CURRENT_TIMESTAMP WHERE id = ?""",
                title, firstAction, req.hasNonNull("duration_min") ? duration : t.estMinutes(), taskId);
        String screenText = List.of("planned", "nudging").contains(b.status()) ? SCREEN_PREFIX + firstAction
                : b.screenText();
        if (timeChanged) {
            db.update("""
                            UPDATE blocks SET start_at = ?, end_at = ?, status = 'planned', screen_text = ?,
                            version = version + 1, updated_at = CURRENT_TIMESTAMP WHERE id = ?""",
                    start, end, screenText, b.id());
            interventions.scheduleBlockJobs(store.block(b.id()).orElseThrow());
        } else {
            db.update("UPDATE blocks SET screen_text = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                    screenText, b.id());
        }
        int version = plan.bumpAndSync();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("task_id", taskId);
        result.put("block_id", b.id());
        result.put("plan_version", version);
        return result;
    }

    public Map<String, Object> delete(long taskId) {
        store.task(taskId).orElseThrow(() -> ApiException.notFound("할 일이 없습니다: " + taskId));
        db.update("UPDATE tasks SET status = 'cancelled', updated_at = CURRENT_TIMESTAMP WHERE id = ?", taskId);
        for (Store.Block b : store.blocksOfTask(taskId)) {
            if (List.of("planned", "nudging", "started").contains(b.status())) {
                interventions.cancelRunningForBlock(b.id());
                store.setBlockStatus(b.id(), "cancelled");
                interventions.cancelBlockJobs(b.id());
            }
        }
        int version = plan.bumpAndSync();
        return Map.of("ok", true, "plan_version", version);
    }

    /** 할 일의 현재 블록(취소되지 않은 것 중 마지막). */
    private Optional<Store.Block> currentBlock(long taskId) {
        List<Store.Block> blocks = store.blocksOfTask(taskId).stream()
                .filter(b -> !"cancelled".equals(b.status())).toList();
        return blocks.isEmpty() ? Optional.empty() : Optional.of(blocks.getLast());
    }

    private static String required(JsonNode req, String field) {
        String value = req.path(field).asString("").trim();
        if (value.isEmpty()) {
            throw ApiException.unprocessable(field + " 가 필요합니다");
        }
        return value;
    }
}
