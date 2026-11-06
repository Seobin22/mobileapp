package com.seobin.phonedrop.assistant.insight;

import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.seobin.phonedrop.assistant.AssistantDb;
import com.seobin.phonedrop.assistant.Json;
import com.seobin.phonedrop.assistant.SettingsService;
import com.seobin.phonedrop.assistant.TimeUtil;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** v3.5 §7.2 비서 도구: profile_edit · memory_save · memory_forget · memory_search · get_pattern_stats. */
@Component
public class InsightTools {

    public static final Set<String> NAMES = Set.of("profile_edit", "memory_save", "memory_forget", "memory_search",
            "get_pattern_stats");

    private final AssistantDb db;
    private final MemoryStore memory;
    private final PatternStats stats;
    private final SettingsService settings;
    private final TimeUtil time;

    public InsightTools(AssistantDb db, MemoryStore memory, PatternStats stats, SettingsService settings, TimeUtil time) {
        this.db = db;
        this.memory = memory;
        this.stats = stats;
        this.settings = settings;
        this.time = time;
    }

    /** fromUser: 사용자 말로 시작된 turn(memory_forget 이 그 메시지를 사실 뽑기에서 뺀다). */
    public ObjectNode dispatch(String name, JsonNode in, boolean fromUser) {
        return switch (name) {
            case "profile_edit" -> profileEdit(in);
            case "memory_save" -> save(in);
            case "memory_forget" -> forget(in, fromUser);
            case "memory_search" -> search(in);
            case "get_pattern_stats" -> patternStats(in);
            default -> error("알 수 없는 도구입니다: " + name);
        };
    }

    private ObjectNode profileEdit(JsonNode in) {
        MemoryStore.Section s = memory.edit(in.path("section").asString(""), in.path("op").asString("insert"),
                in.path("text").asString(""), in.hasNonNull("old_text") ? in.get("old_text").asString() : null, "assistant");
        ObjectNode r = ok();
        r.put("section", s.section());
        r.put("chars", s.content().length());
        return r;
    }

    private ObjectNode save(JsonNode in) {
        String kind = in.path("kind").asString("fact");
        if (!List.of("fact", "episode", "guideline").contains(kind)) {
            return error("kind 는 fact·episode·guideline 중 하나입니다");
        }
        String category = in.path("category").asString("other");
        if (!MemoryStore.validCategory(category)) {
            category = "other";
        }
        String evidence = null;
        if (in.path("refs").isArray() && !in.get("refs").isEmpty()) {
            ObjectNode ev = Json.object();
            ev.set("stats", Json.array());
            ev.set("refs", in.get("refs"));
            evidence = Json.write(ev);
        }
        // 사용자가 자신에 대해 분명히 말한 것(§7.2) → 사용자 항목으로 본다(추출·정리가 바꾸지 않는다).
        long id = memory.add(kind, category, in.path("content").asString(""), evidence, 0.9,
                in.hasNonNull("goal_id") ? in.get("goal_id").asLong() : null, "user");
        ObjectNode r = ok();
        r.put("id", id);
        return r;
    }

    private ObjectNode forget(JsonNode in, boolean fromUser) {
        ArrayNode removed = Json.array();
        if (in.hasNonNull("item_id")) {
            long id = in.get("item_id").asLong();
            memory.setStatus(id, "deleted", "user");
            removed.add(id);
        } else if (in.hasNonNull("query") && !in.get("query").asString("").isBlank()) {
            for (MemoryStore.Item it : memory.search(in.get("query").asString(), null, null, null, "active", 20)) {
                memory.setStatus(it.id(), "deleted", "user");
                removed.add(it.id());
            }
        }
        int marked = 0;
        if (fromUser) {
            // "이건 기억하지 마": 그 말과 바로 앞 사용자 메시지를 사실 뽑기에서 뺀다.
            List<Long> ids = db.jdbc().sql("SELECT id FROM messages WHERE role = 'user' ORDER BY id DESC LIMIT 2")
                    .query(Long.class).list();
            for (long id : ids) {
                marked += db.update("UPDATE messages SET no_memory = TRUE WHERE id = ?", id);
            }
        }
        if (removed.isEmpty() && marked == 0) {
            return error("item_id 또는 query 가 필요합니다");
        }
        ObjectNode r = ok();
        r.set("deleted", removed);
        r.put("messages_excluded", marked);
        return r;
    }

    private ObjectNode search(JsonNode in) {
        int limit = Math.min(Math.max(in.path("limit").asInt(10), 1), 50);
        List<MemoryStore.Item> items = memory.search(in.hasNonNull("query") ? in.get("query").asString() : null,
                in.hasNonNull("kind") ? in.get("kind").asString() : null,
                in.hasNonNull("category") ? in.get("category").asString() : null,
                in.hasNonNull("goal_id") ? in.get("goal_id").asLong() : null, "active", limit);
        memory.markUsed(items);
        ObjectNode r = ok();
        ArrayNode arr = r.putArray("items");
        items.forEach(i -> arr.add(Json.MAPPER.valueToTree(MemoryStore.toMap(i, time))));
        return r;
    }

    private ObjectNode patternStats(JsonNode in) {
        int min = settings.getInt("memory.min_samples");
        List<PatternStats.Stat> list = stats.list(in.hasNonNull("metric") ? in.get("metric").asString() : null,
                in.hasNonNull("dimension") ? in.get("dimension").asString() : null,
                in.hasNonNull("key") ? in.get("key").asString() : null);
        ObjectNode r = ok();
        ArrayNode arr = r.putArray("stats");
        list.stream().limit(80).forEach(s -> arr.add(Json.MAPPER.valueToTree(PatternStats.toMap(s, min))));
        r.put("note", "insufficient=true 는 근거 부족(n<" + min + "): 결론으로 쓰지 않는다");
        return r;
    }

    private static ObjectNode ok() {
        ObjectNode r = Json.object();
        r.put("ok", true);
        return r;
    }

    private static ObjectNode error(String message) {
        ObjectNode r = Json.object();
        r.put("ok", false);
        r.put("error", message);
        return r;
    }
}
