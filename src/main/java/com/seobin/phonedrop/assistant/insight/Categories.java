package com.seobin.phonedrop.assistant.insight;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.seobin.phonedrop.assistant.AssistantDb;

import tools.jackson.databind.JsonNode;

/**
 * 분류(빌드문서 v3.5 §1). 목록은 코드에 고정한다(서버가 스스로 바꾸지 않는다). 목록 밖 값은 other + hint.
 * 블록의 분류는 블록 자신(정해졌으면) → 반복 일정 → 할 일 → 목표 순으로 물려받는다(category_src 가 NULL 이면 미정).
 */
@Component
public class Categories {

    public static final Map<String, String> LABELS;

    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("study", "공부");
        m.put("work", "일");
        m.put("class", "수업");
        m.put("exercise", "운동");
        m.put("chores", "집안일·잡무");
        m.put("selfcare", "씻기·식사·준비");
        m.put("rest", "휴식");
        m.put("social", "약속");
        m.put("travel", "이동");
        m.put("other", "기타");
        LABELS = java.util.Collections.unmodifiableMap(m);
    }

    public static final List<String> VALUES = List.copyOf(LABELS.keySet());
    public static final Set<String> SOURCES = Set.of("user", "assistant", "inferred", "system");
    private static final Set<String> TABLES = Set.of("goals", "tasks", "blocks", "fixed_series");

    public record Normalized(String category, String hint) {
    }

    private final AssistantDb db;

    public Categories(AssistantDb db) {
        this.db = db;
    }

    /** 목록 값이면 그대로, 한글 이름이면 값으로, 그 밖은 other + 원래 표현(50자). */
    public static Normalized normalize(String raw) {
        String v = raw == null ? "" : raw.trim();
        if (v.isEmpty()) {
            return new Normalized("other", null);
        }
        String lower = v.toLowerCase(java.util.Locale.ROOT);
        if (LABELS.containsKey(lower)) {
            return new Normalized(lower, null);
        }
        for (Map.Entry<String, String> e : LABELS.entrySet()) {
            if (e.getValue().equals(v) || List.of(e.getValue().split("·")).contains(v)) {
                return new Normalized(e.getKey(), null);
            }
        }
        return new Normalized("other", v.length() > 50 ? v.substring(0, 50) : v);
    }

    /** 입력에 category 가 있으면 그 행에 쓴다. 없으면 아무것도 안 한다. */
    public void applyFrom(String table, long id, JsonNode in, String src) {
        if (in == null || !in.hasNonNull("category")) {
            return;
        }
        set(table, id, in.get("category").asString(""), src);
    }

    public void set(String table, long id, String raw, String src) {
        if (!TABLES.contains(table) || !SOURCES.contains(src)) {
            throw new IllegalArgumentException("bad category target");
        }
        Normalized n = normalize(raw);
        if (!"user".equals(src)) {
            // 사용자가 정한 분류는 비서·추정이 바꾸지 않는다.
            String current = db.jdbc().sql("SELECT category_src FROM " + table + " WHERE id = ?").param(id)
                    .query(String.class).optional().orElse(null);
            if ("user".equals(current)) {
                return;
            }
        }
        db.update("UPDATE " + table + " SET category = ?, category_src = ?, category_hint = ? WHERE id = ?",
                n.category(), src, n.hint(), id);
    }

    /** 블록의 실제 분류(물려받기 포함). 이동 블록은 항상 travel. */
    public String effective(long blockId) {
        record Row(String linkRole, String cat, String src, Long seriesId, Long taskId, Long goalId) {
        }
        Row r = db.jdbc().sql("SELECT link_role, category, category_src, series_id, task_id, goal_id FROM blocks WHERE id = ?")
                .param(blockId)
                .query((rs, n) -> new Row(rs.getString(1), rs.getString(2), rs.getString(3),
                        AssistantDb.nullableLong(rs, "series_id"), AssistantDb.nullableLong(rs, "task_id"),
                        AssistantDb.nullableLong(rs, "goal_id")))
                .optional().orElse(null);
        if (r == null) {
            return "other";
        }
        if ("travel".equals(r.linkRole())) {
            return "travel";
        }
        if (r.src() != null) {
            return r.cat();
        }
        String inherited = own("fixed_series", r.seriesId());
        if (inherited == null) {
            inherited = own("tasks", r.taskId());
        }
        Long goalId = r.goalId();
        if (goalId == null && r.taskId() != null) {
            goalId = db.jdbc().sql("SELECT goal_id FROM tasks WHERE id = ?").param(r.taskId())
                    .query((rs, n) -> AssistantDb.nullableLong(rs, "goal_id")).optional().orElse(null);
        }
        if (inherited == null) {
            inherited = own("goals", goalId);
        }
        return inherited == null ? r.cat() : inherited;
    }

    /** 그 행에 정해진 분류(src 가 있을 때만). */
    private String own(String table, Long id) {
        if (id == null) {
            return null;
        }
        return db.jdbc().sql("SELECT category, category_src FROM " + table + " WHERE id = ?").param(id)
                .query((rs, n) -> rs.getString(2) == null ? null : rs.getString(1))
                .optional().orElse(null);
    }

    /** 관리 화면: other 로 남은 원래 표현(hint) 목록. */
    public List<Map<String, Object>> otherHints() {
        return db.jdbc().sql("""
                        SELECT hint, COUNT(*) AS n FROM (
                          SELECT category_hint AS hint FROM goals WHERE category = 'other' AND category_hint IS NOT NULL
                          UNION ALL SELECT category_hint FROM tasks WHERE category = 'other' AND category_hint IS NOT NULL
                          UNION ALL SELECT category_hint FROM blocks WHERE category = 'other' AND category_hint IS NOT NULL
                          UNION ALL SELECT category_hint FROM fixed_series WHERE category = 'other' AND category_hint IS NOT NULL
                        ) GROUP BY hint ORDER BY n DESC, hint LIMIT 100""")
                .query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("hint", rs.getString(1));
                    m.put("count", rs.getInt(2));
                    return m;
                }).list();
    }
}
