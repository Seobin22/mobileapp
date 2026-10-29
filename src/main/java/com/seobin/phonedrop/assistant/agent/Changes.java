package com.seobin.phonedrop.assistant.agent;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.seobin.phonedrop.assistant.AssistantClock;
import com.seobin.phonedrop.assistant.AssistantDb;
import com.seobin.phonedrop.assistant.InterventionService;
import com.seobin.phonedrop.assistant.Json;
import com.seobin.phonedrop.assistant.PlanService;
import com.seobin.phonedrop.assistant.Store;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 엔티티 변경 기록과 되돌리기(빌드문서 v2 §7).
 * before/after 는 행 스냅샷(JSON). 생성이면 before 가 null.
 */
@Component
public class Changes {

    /** 엔티티 이름 → 테이블. */
    private static final Map<String, String> TABLES = Map.of(
            "goal", "goals", "task", "tasks", "event", "events", "note", "notes", "block", "blocks");

    /** 스냅샷·복원에서 빼는 컬럼. */
    private static final Set<String> SKIP = Set.of("id", "created_at", "updated_at", "version");

    /** 시각 컬럼(스냅샷에는 ISO 문자열로 둔다). */
    private static final Set<String> TIME_COLUMNS = Set.of(
            "start_at", "end_at", "deadline", "actual_start", "actual_end", "waiting_until");

    public record UndoResult(int restored, int skipped, boolean partial) {
    }

    private final AssistantDb db;
    private final AssistantClock clock;
    private final Store store;
    private final InterventionService interventions;
    private final PlanService plan;

    public Changes(AssistantDb db, AssistantClock clock, Store store, InterventionService interventions,
                   PlanService plan) {
        this.db = db;
        this.clock = clock;
        this.store = store;
        this.interventions = interventions;
        this.plan = plan;
    }

    /** 지금 행의 스냅샷. 행이 없으면 null. */
    public ObjectNode snapshot(String entity, long id) {
        String table = TABLES.get(entity);
        List<Map<String, Object>> rows = db.jdbc().sql("SELECT * FROM " + table + " WHERE id = ?").param(id)
                .query().listOfRows();
        if (rows.isEmpty()) {
            return null;
        }
        ObjectNode snap = Json.object();
        for (Map.Entry<String, Object> e : rows.getFirst().entrySet()) {
            String column = e.getKey().toLowerCase();
            if (SKIP.contains(column)) {
                continue;
            }
            Object v = e.getValue();
            if (v == null) {
                snap.putNull(column);
            } else if (v instanceof OffsetDateTime t) {
                snap.put(column, t.toInstant().toString());
            } else if (v instanceof Boolean b) {
                snap.put(column, b);
            } else if (v instanceof Number n) {
                snap.put(column, n.longValue());
            } else {
                snap.put(column, v.toString());
            }
        }
        return snap;
    }

    public void record(long turnId, String entity, long id, JsonNode before, JsonNode after) {
        db.insert("INSERT INTO changes(turn_id, entity, entity_id, before_value, after_value) VALUES (?, ?, ?, ?, ?)",
                turnId, entity, id, before == null ? null : Json.write(before), after == null ? null : Json.write(after));
    }

    /**
     * user_message turn 중 지금 turn 직전의 가장 최근 turn 의 변경을 역순으로 되돌린다.
     * 현재 값이 after 와 같은 필드만 before 로 되돌리고, 다른 필드는 건너뛴다(partial).
     * 블록은 시작 시각이 이미 지났으면 복원하지 않는다.
     */
    public UndoResult undoLast(long currentTurnId) {
        Long target = db.jdbc().sql("""
                        SELECT id FROM turns WHERE trigger = 'user_message' AND id < ? AND status <> 'queued'
                        ORDER BY id DESC LIMIT 1""")
                .param(currentTurnId).query(Long.class).optional().orElse(null);
        if (target == null) {
            return new UndoResult(0, 0, false);
        }
        record Change(long id, String entity, long entityId, String before, String after) {
        }
        List<Change> list = db.jdbc().sql("""
                        SELECT id, entity, entity_id, before_value, after_value FROM changes
                        WHERE turn_id = ? AND undone = FALSE ORDER BY id DESC""")
                .param(target)
                .query((rs, n) -> new Change(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4),
                        rs.getString(5)))
                .list();

        int restored = 0;
        int skipped = 0;
        boolean partial = false;
        List<Long> touchedBlocks = new ArrayList<>();
        Instant now = clock.now();

        for (Change c : list) {
            JsonNode before = c.before() == null ? null : Json.parse(c.before());
            JsonNode after = c.after() == null ? null : Json.parse(c.after());
            ObjectNode current = snapshot(c.entity(), c.entityId());

            if ("block".equals(c.entity())) {
                // 되돌릴 모습(before, 생성이면 after)의 시작 시각이 이미 지났으면 손대지 않는다.
                JsonNode restoreTo = before != null ? before : after;
                if (restoreTo != null && Instant.parse(restoreTo.path("start_at").asString()).isBefore(now)) {
                    skipped++;
                    markUndone(c.id());
                    continue;
                }
            }

            if (current == null) {
                skipped++;
            } else if (before == null) {
                // 생성 되돌리기 = 취소. 그 뒤로 상태가 바뀌었으면(예: 이미 시작) 건너뛴다.
                if (after != null && current.path("status").equals(after.path("status"))) {
                    String cancelled = "note".equals(c.entity()) ? "dismissed" : "cancelled";
                    db.update("UPDATE " + TABLES.get(c.entity()) + " SET status = ?, updated_at = CURRENT_TIMESTAMP"
                            + " WHERE id = ?", cancelled, c.entityId());
                    restored++;
                } else {
                    skipped++;
                    partial = true;
                }
            } else {
                Map<String, Object> set = new LinkedHashMap<>();
                boolean anySkipped = false;
                for (Map.Entry<String, JsonNode> f : after.properties()) {
                    JsonNode was = before.path(f.getKey());
                    if (f.getValue().equals(was)) {
                        continue; // 이 변경에서 바뀌지 않은 필드
                    }
                    if (current.path(f.getKey()).equals(f.getValue())) {
                        set.put(f.getKey(), toColumnValue(f.getKey(), was));
                    } else {
                        anySkipped = true;
                    }
                }
                if (!set.isEmpty()) {
                    apply(c.entity(), c.entityId(), set);
                    restored++;
                } else {
                    skipped++;
                }
                partial |= anySkipped;
            }
            if ("block".equals(c.entity())) {
                touchedBlocks.add(c.entityId());
            }
            markUndone(c.id());
        }

        for (long blockId : touchedBlocks) {
            store.block(blockId).ifPresent(b -> {
                if ("planned".equals(b.status())) {
                    interventions.scheduleBlockJobs(b);
                } else {
                    interventions.cancelBlockJobs(b.id());
                }
            });
        }
        if (!touchedBlocks.isEmpty()) {
            plan.bumpAndSync();
        }
        return new UndoResult(restored, skipped, partial);
    }

    private void apply(String entity, long id, Map<String, Object> set) {
        StringBuilder sql = new StringBuilder("UPDATE " + TABLES.get(entity) + " SET ");
        List<Object> params = new ArrayList<>();
        for (Map.Entry<String, Object> e : set.entrySet()) {
            sql.append(e.getKey()).append(" = ?, ");
            params.add(e.getValue());
        }
        if ("block".equals(entity) && (set.containsKey("start_at") || set.containsKey("end_at"))) {
            sql.append("version = version + 1, "); // 시각이 바뀌면 블록 version +1
        }
        sql.append("updated_at = CURRENT_TIMESTAMP WHERE id = ?");
        params.add(id);
        db.update(sql.toString(), params.toArray());
    }

    private static Object toColumnValue(String column, JsonNode v) {
        if (v == null || v.isNull() || v.isMissingNode()) {
            return null;
        }
        if (TIME_COLUMNS.contains(column)) {
            return Instant.parse(v.asString());
        }
        if (v.isBoolean()) {
            return v.asBoolean();
        }
        if (v.isNumber()) {
            return v.asLong();
        }
        return v.asString();
    }

    private void markUndone(long changeId) {
        db.update("UPDATE changes SET undone = TRUE WHERE id = ?", changeId);
    }
}
