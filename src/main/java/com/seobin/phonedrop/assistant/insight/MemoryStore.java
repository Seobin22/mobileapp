package com.seobin.phonedrop.assistant.insight;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.seobin.phonedrop.assistant.ApiException;
import com.seobin.phonedrop.assistant.AssistantClock;
import com.seobin.phonedrop.assistant.AssistantDb;
import com.seobin.phonedrop.assistant.SettingsService;

/**
 * 기억 층(빌드문서 v3.5 §7): 핵심 프로필(섹션 5개) + 기억 항목(fact·episode·guideline·insight) + 변경 기록.
 * 모든 추가·수정·삭제는 memory_ops_log 에 남긴다.
 */
@Component
public class MemoryStore {

    public static final List<String> SECTIONS = List.of("life", "personality", "likes", "now", "work_style");
    public static final Map<String, String> SECTION_LABELS = Map.of("life", "생활", "personality", "성향", "likes", "좋아하는 것",
            "now", "요즘", "work_style", "일하는 방식");
    public static final Set<String> KINDS = Set.of("fact", "episode", "guideline", "insight");
    public static final Set<String> EXTRA_CATEGORIES = Set.of("snooze", "persuasion", "health", "preference");
    public static final int SECTION_MAX = 400;
    public static final int ITEM_MAX = 200;

    public record Section(String section, String content, String updatedBy, Instant updatedAt) {
    }

    public record Item(long id, String kind, String category, String content, String evidence, double confidence,
                       Long goalId, String status, String createdBy, Instant createdAt, Instant updatedAt,
                       Instant lastUsedAt) {
    }

    private final AssistantDb db;
    private final AssistantClock clock;
    private final SettingsService settings;

    public MemoryStore(AssistantDb db, AssistantClock clock, SettingsService settings) {
        this.db = db;
        this.clock = clock;
        this.settings = settings;
    }

    public static boolean validCategory(String c) {
        return Categories.LABELS.containsKey(c) || EXTRA_CATEGORIES.contains(c);
    }

    // ------------------------------------------------------------------ 프로필

    public List<Section> profile() {
        List<Section> rows = db.jdbc().sql("SELECT * FROM memory_profile").query((rs, n) -> new Section(
                rs.getString("section"), rs.getString("content"), rs.getString("updated_by"),
                AssistantDb.instant(rs, "updated_at"))).list();
        List<Section> ordered = new ArrayList<>();
        for (String s : SECTIONS) {
            rows.stream().filter(r -> r.section().equals(s)).findFirst()
                    .ifPresentOrElse(ordered::add, () -> ordered.add(new Section(s, "", null, null)));
        }
        return ordered;
    }

    public Section section(String name) {
        return profile().stream().filter(s -> s.section().equals(name)).findFirst()
                .orElseThrow(() -> ApiException.badRequest("section 은 " + String.join("·", SECTIONS) + " 중 하나입니다"));
    }

    /**
     * profile_edit(§7.2). op: insert(끝에 덧붙임) | replace(old_text → text) | rewrite(전체 교체).
     * 400자를 넘으면 거절. 사용자가 쓴 섹션(updated_by=user)은 비서가 insert 만 할 수 있다.
     */
    public Section edit(String sectionName, String op, String text, String oldText, String actor) {
        Section s = section(sectionName);
        String t = text == null ? "" : text.strip();
        boolean byUser = "user".equals(actor);
        if (!byUser && "user".equals(s.updatedBy()) && !"insert".equals(op)) {
            throw ApiException.unprocessable("사용자가 직접 쓴 섹션이라 덧붙이기(insert)만 할 수 있습니다");
        }
        String next = switch (op) {
            case "insert" -> {
                if (t.isEmpty()) {
                    throw ApiException.badRequest("text 가 비었습니다");
                }
                yield s.content().isBlank() ? t : s.content().strip() + "\n" + t;
            }
            case "replace" -> {
                if (oldText == null || oldText.isEmpty() || !s.content().contains(oldText)) {
                    throw ApiException.unprocessable("old_text 를 섹션에서 찾지 못했습니다");
                }
                yield s.content().replace(oldText, t);
            }
            case "rewrite" -> t;
            default -> throw ApiException.badRequest("op 는 insert·replace·rewrite 중 하나입니다");
        };
        if (next.length() > SECTION_MAX) {
            throw ApiException.unprocessable("섹션은 " + SECTION_MAX + "자 이하여야 합니다(지금 " + next.length()
                    + "자). 줄여서 다시 쓰세요");
        }
        // 사용자가 쓴 섹션에 비서가 덧붙여도 '사용자 섹션'은 유지한다(사용자 내용 보호).
        String by = byUser || "user".equals(s.updatedBy()) ? "user" : actor;
        db.update("MERGE INTO memory_profile(section, content, updated_by, updated_at) KEY(section) VALUES (?, ?, ?, ?)",
                sectionName, next, by, clock.now());
        logOp(null, "update", "profile." + sectionName + ": " + s.content(), next, actor);
        return section(sectionName);
    }

    public String profileText() {
        StringBuilder sb = new StringBuilder();
        for (Section s : profile()) {
            if (!s.content().isBlank()) {
                sb.append("- ").append(SECTION_LABELS.get(s.section())).append("(").append(s.section()).append("): ")
                        .append(s.content().replace("\n", " / ")).append('\n');
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ 항목

    public long add(String kind, String category, String content, String evidence, double confidence, Long goalId,
                    String createdBy) {
        if (!KINDS.contains(kind)) {
            throw ApiException.badRequest("kind 는 fact·episode·guideline·insight 중 하나입니다");
        }
        String c = content == null ? "" : content.strip();
        if (c.isEmpty()) {
            throw ApiException.badRequest("content 가 비었습니다");
        }
        if (c.length() > ITEM_MAX && !"archive".equals(createdBy)) {
            throw ApiException.unprocessable("기억 항목은 " + ITEM_MAX + "자 이하여야 합니다(지금 " + c.length() + "자)");
        }
        String cat = category != null && validCategory(category) ? category : "other";
        Instant now = clock.now();
        long id = db.insert("""
                        INSERT INTO memory_items(kind, category, content, evidence, confidence, goal_id, status, created_by,
                        created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, 'active', ?, ?, ?)""",
                kind, cat, c.length() > 4000 ? c.substring(0, 4000) : c, evidence, Math.max(0, Math.min(1, confidence)),
                goalId, "archive".equals(createdBy) ? "extraction" : createdBy, now, now);
        logOp(id, "add", null, c, createdBy);
        return id;
    }

    public void update(long id, String content, String evidence, Double confidence, String actor) {
        Item it = item(id).orElseThrow(() -> ApiException.notFound("기억#" + id + "이 없습니다"));
        String c = content == null ? it.content() : content.strip();
        if (c.length() > ITEM_MAX) {
            throw ApiException.unprocessable("기억 항목은 " + ITEM_MAX + "자 이하여야 합니다");
        }
        db.update("UPDATE memory_items SET content = ?, evidence = ?, confidence = ?, updated_at = ? WHERE id = ?",
                c, evidence == null ? it.evidence() : evidence,
                confidence == null ? it.confidence() : Math.max(0, Math.min(1, confidence)), clock.now(), id);
        logOp(id, "update", it.content(), c, actor);
    }

    public void setStatus(long id, String status, String actor) {
        Item it = item(id).orElseThrow(() -> ApiException.notFound("기억#" + id + "이 없습니다"));
        db.update("UPDATE memory_items SET status = ?, updated_at = ? WHERE id = ?", status, clock.now(), id);
        logOp(id, "deleted".equals(status) ? "delete" : "update", it.content() + " [" + it.status() + "]",
                "[" + status + "]", actor);
    }

    public Optional<Item> item(long id) {
        return db.jdbc().sql("SELECT * FROM memory_items WHERE id = ?").param(id).query(MemoryStore::row).optional();
    }

    /** memory_search: 키워드(공백으로 나눈 낱말이 모두 들어간 것)·종류·분류·목표. */
    public List<Item> search(String query, String kind, String category, Long goalId, String status, int limit) {
        StringBuilder sql = new StringBuilder("SELECT * FROM memory_items WHERE status = ?");
        List<Object> params = new ArrayList<>();
        params.add(status == null ? "active" : status);
        if (kind != null && !kind.isBlank()) {
            sql.append(" AND kind = ?");
            params.add(kind);
        }
        if (category != null && !category.isBlank()) {
            sql.append(" AND category = ?");
            params.add(category);
        }
        if (goalId != null) {
            sql.append(" AND goal_id = ?");
            params.add(goalId);
        }
        if (query != null) {
            for (String w : query.strip().split("\\s+")) {
                if (!w.isEmpty()) {
                    sql.append(" AND LOWER(content) LIKE ?");
                    params.add("%" + w.toLowerCase(java.util.Locale.ROOT) + "%");
                }
            }
        }
        sql.append(" ORDER BY confidence DESC, updated_at DESC, id DESC LIMIT ?");
        params.add(Math.max(1, Math.min(limit, 200)));
        var spec = db.jdbc().sql(sql.toString());
        for (Object p : params) {
            spec = spec.param(p);
        }
        return spec.query(MemoryStore::row).list();
    }

    public List<Item> guidelines(int limit) {
        return search(null, "guideline", null, null, "active", limit);
    }

    /** 맥락에 넣을 사실·일화: 분류·목표가 맞는 것 먼저, 그다음 최근 쓴 것. */
    public List<Item> relevant(Set<String> categories, Long goalId, int limit) {
        List<Item> all = db.jdbc().sql("""
                        SELECT * FROM memory_items WHERE status = 'active' AND kind IN ('fact', 'episode')
                        ORDER BY COALESCE(last_used_at, created_at) DESC, id DESC LIMIT 300""")
                .query(MemoryStore::row).list();
        List<Item> scored = new ArrayList<>(all);
        scored.sort((a, b) -> Integer.compare(score(b, categories, goalId), score(a, categories, goalId)));
        return scored.subList(0, Math.min(limit, scored.size()));
    }

    private static int score(Item it, Set<String> categories, Long goalId) {
        int s = 0;
        if (goalId != null && goalId.equals(it.goalId())) {
            s += 2;
        }
        if (categories.contains(it.category())) {
            s += 1;
        }
        return s;
    }

    public void markUsed(List<Item> items) {
        Instant now = clock.now();
        for (Item it : items) {
            db.update("UPDATE memory_items SET last_used_at = ? WHERE id = ?", now, it.id());
        }
    }

    public void logOp(Long itemId, String op, String before, String after, String actor) {
        db.update("INSERT INTO memory_ops_log(item_id, op, before_text, after_text, actor, at) VALUES (?, ?, ?, ?, ?, ?)",
                itemId, op, cut(before), cut(after), actor, clock.now());
    }

    private static String cut(String s) {
        return s == null ? null : s.length() > 4000 ? s.substring(0, 4000) : s;
    }

    static Item row(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Item(rs.getLong("id"), rs.getString("kind"), rs.getString("category"), rs.getString("content"),
                rs.getString("evidence"), rs.getDouble("confidence"), AssistantDb.nullableLong(rs, "goal_id"),
                rs.getString("status"), rs.getString("created_by"), AssistantDb.instant(rs, "created_at"),
                AssistantDb.instant(rs, "updated_at"), AssistantDb.instant(rs, "last_used_at"));
    }

    public static Map<String, Object> toMap(Item it, com.seobin.phonedrop.assistant.TimeUtil time) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", it.id());
        m.put("kind", it.kind());
        m.put("category", it.category());
        m.put("content", it.content());
        m.put("evidence", it.evidence() == null ? null : com.seobin.phonedrop.assistant.Json.parse(it.evidence()));
        m.put("confidence", it.confidence());
        m.put("goal_id", it.goalId());
        m.put("status", it.status());
        m.put("created_by", it.createdBy());
        m.put("created_at", time.iso(it.createdAt()));
        m.put("updated_at", time.iso(it.updatedAt()));
        m.put("last_used_at", time.iso(it.lastUsedAt()));
        return m;
    }

    // ------------------------------------------------------------------ 확인 필요·상태

    public void addCheck(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        String t = text.strip();
        boolean exists = db.jdbc().sql("SELECT COUNT(*) FROM memory_checks WHERE text = ? AND status = 'open'").param(t)
                .query(Integer.class).single() > 0;
        if (!exists) {
            db.update("INSERT INTO memory_checks(text, created_at) VALUES (?, ?)", t.length() > 500 ? t.substring(0, 500) : t,
                    clock.now());
        }
    }

    public List<String> openChecks(int limit) {
        return db.jdbc().sql("SELECT text FROM memory_checks WHERE status = 'open' ORDER BY id LIMIT ?").param(limit)
                .query(String.class).list();
    }

    /** 아침 브리핑에서 한 번 물었다(다시 묻지 않는다). */
    public void markChecksAsked(int limit) {
        List<Long> ids = db.jdbc().sql("SELECT id FROM memory_checks WHERE status = 'open' ORDER BY id LIMIT ?").param(limit)
                .query(Long.class).list();
        for (long id : ids) {
            db.update("UPDATE memory_checks SET status = 'asked', asked_at = ? WHERE id = ?", clock.now(), id);
        }
    }

    public String state(String key) {
        return db.jdbc().sql("SELECT state_value FROM memory_state WHERE state_key = ?").param(key).query(String.class)
                .optional().orElse(null);
    }

    public void setState(String key, String value) {
        db.update("MERGE INTO memory_state(state_key, state_value) KEY(state_key) VALUES (?, ?)", key, value);
    }

    public boolean enabled() {
        return settings.getBoolean("memory.enabled");
    }
}
