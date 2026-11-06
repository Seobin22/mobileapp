package com.seobin.phonedrop.assistant.api;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.seobin.phonedrop.assistant.ApiException;
import com.seobin.phonedrop.assistant.AssistantClock;
import com.seobin.phonedrop.assistant.AssistantDb;
import com.seobin.phonedrop.assistant.SettingsService;
import com.seobin.phonedrop.assistant.Store;
import com.seobin.phonedrop.assistant.TimeUtil;
import com.seobin.phonedrop.assistant.Tx;
import com.seobin.phonedrop.assistant.insight.BlockFacts;
import com.seobin.phonedrop.assistant.insight.Cards;
import com.seobin.phonedrop.assistant.insight.Categories;
import com.seobin.phonedrop.assistant.insight.DayFacts;
import com.seobin.phonedrop.assistant.insight.MemoryJobs;
import com.seobin.phonedrop.assistant.insight.MemoryStore;
import com.seobin.phonedrop.assistant.insight.PatternStats;

import tools.jackson.databind.JsonNode;

/** v3.5 §10.4: 카드 제출, "비서가 아는 나" 화면(프로필·기억·패턴·지난 기록), 관리 화면. */
@RestController
@RequestMapping("/api/assistant/v1")
public class InsightController {

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.beans.factory.ObjectProvider<com.seobin.phonedrop.assistant.distract.DistractionService> distractions;


    private final Tx tx;
    private final AssistantDb db;
    private final AssistantClock clock;
    private final TimeUtil time;
    private final SettingsService settings;
    private final Store store;
    private final Cards cards;
    private final BlockFacts facts;
    private final DayFacts days;
    private final MemoryStore memory;
    private final PatternStats stats;
    private final Categories categories;
    private final MemoryJobs memoryJobs;

    public InsightController(Tx tx, AssistantDb db, AssistantClock clock, TimeUtil time, SettingsService settings,
                             Store store, Cards cards, BlockFacts facts, DayFacts days, MemoryStore memory,
                             PatternStats stats, Categories categories, MemoryJobs memoryJobs) {
        this.tx = tx;
        this.db = db;
        this.clock = clock;
        this.time = time;
        this.settings = settings;
        this.store = store;
        this.cards = cards;
        this.facts = facts;
        this.days = days;
        this.memory = memory;
        this.stats = stats;
        this.categories = categories;
        this.memoryJobs = memoryJobs;
    }

    // ------------------------------------------------------------------ 카드

    @PostMapping("/outcome-cards/{id}")
    public Map<String, Object> submitCard(@PathVariable long id, @RequestBody JsonNode body) {
        return tx.run(() -> cards.submit(id, body));
    }

    @PostMapping("/outcome-cards/{id}/later")
    public Map<String, Object> later(@PathVariable long id) {
        return tx.run(() -> cards.later(id));
    }

    @PostMapping("/day-cards/{day}")
    public Map<String, Object> submitDay(@PathVariable String day, @RequestBody JsonNode body) {
        LocalDate d = parseDay(day);
        return tx.run(() -> cards.submitDay(d, body));
    }

    @GetMapping("/categories")
    public Map<String, Object> categories() {
        List<Map<String, Object>> list = new ArrayList<>();
        Categories.LABELS.forEach((k, v) -> {
            if (!"travel".equals(k)) {
                list.add(Map.of("value", k, "label", v));
            }
        });
        return Map.of("categories", list);
    }

    // ------------------------------------------------------------------ 프로필·기억

    @GetMapping("/memory/profile")
    public Map<String, Object> profile() {
        return tx.run(() -> {
            List<Map<String, Object>> list = new ArrayList<>();
            for (MemoryStore.Section s : memory.profile()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("section", s.section());
                m.put("label", MemoryStore.SECTION_LABELS.get(s.section()));
                m.put("content", s.content());
                m.put("updated_by", s.updatedBy());
                m.put("updated_at", time.iso(s.updatedAt()));
                list.add(m);
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("sections", list);
            r.put("max_chars", MemoryStore.SECTION_MAX);
            r.put("memory_enabled", memory.enabled());
            return r;
        });
    }

    /** {section, content}: 사용자가 섹션 전체를 고친다(updated_by=user). */
    @PatchMapping("/memory/profile")
    public Map<String, Object> patchProfile(@RequestBody JsonNode body) {
        return tx.run(() -> {
            MemoryStore.Section s = memory.edit(body.path("section").asString(""), "rewrite",
                    body.path("content").asString(""), null, "user");
            return Map.<String, Object>of("ok", true, "section", s.section(), "content", s.content());
        });
    }

    @GetMapping("/memory/items")
    public Map<String, Object> items(@RequestParam(required = false) String kind,
                                     @RequestParam(required = false) String category,
                                     @RequestParam(required = false) String query,
                                     @RequestParam(required = false) String status) {
        return tx.run(() -> Map.of("items", memory.search(query, kind, category, null, status == null ? "active" : status, 200)
                .stream().map(i -> MemoryStore.toMap(i, time)).toList()));
    }

    /** {content?} 고치기 — 사용자가 고친 항목은 사용자 항목이 된다(추출·정리가 바꾸지 않는다). */
    @PatchMapping("/memory/items/{id}")
    public Map<String, Object> patchItem(@PathVariable long id, @RequestBody JsonNode body) {
        return tx.run(() -> {
            memory.item(id).orElseThrow(() -> ApiException.notFound("기억#" + id + "이 없습니다"));
            if (body.hasNonNull("content")) {
                memory.update(id, body.get("content").asString(""), null, null, "user");
                db.update("UPDATE memory_items SET created_by = 'user' WHERE id = ?", id);
            }
            if (body.hasNonNull("status")) {
                String st = body.get("status").asString("");
                if (!List.of("active", "archived", "deleted").contains(st)) {
                    throw ApiException.badRequest("status 는 active·archived·deleted 중 하나입니다");
                }
                memory.setStatus(id, st, "user");
            }
            return Map.<String, Object>of("ok", true, "item", MemoryStore.toMap(memory.item(id).orElseThrow(), time));
        });
    }

    @DeleteMapping("/memory/items/{id}")
    public Map<String, Object> deleteItem(@PathVariable long id) {
        return tx.run(() -> {
            memory.setStatus(id, "deleted", "user");
            return Map.<String, Object>of("ok", true, "id", id);
        });
    }

    /** 패턴 [틀렸어요]: archived 로 두고, 비서가 알도록 사용자 기억으로 남긴다. */
    @PostMapping("/memory/items/{id}/wrong")
    public Map<String, Object> wrong(@PathVariable long id) {
        return tx.run(() -> {
            MemoryStore.Item it = memory.item(id).orElseThrow(() -> ApiException.notFound("기억#" + id + "이 없습니다"));
            memory.setStatus(id, "archived", "user");
            String text = "사용자는 이 패턴이 맞지 않다고 했다: " + it.content();
            memory.add("fact", "preference", text.length() > MemoryStore.ITEM_MAX ? text.substring(0, MemoryStore.ITEM_MAX) : text,
                    null, 0.9, it.goalId(), "user");
            return Map.<String, Object>of("ok", true, "id", id);
        });
    }

    // ------------------------------------------------------------------ 패턴

    @GetMapping("/patterns/summary")
    public Map<String, Object> summary() {
        return tx.run(() -> {
            int min = settings.getInt("memory.min_samples");
            if (stats.computedAt() == null) {
                stats.compute();
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("computed_at", time.iso(stats.computedAt()));
            r.put("min_samples", min);
            r.put("band_completion", stats.list("completion_rate", "band", null).stream()
                    .map(s -> PatternStats.toMap(s, min)).toList());
            r.put("sleep_completion", stats.list("completion_rate", "sleep", null).stream()
                    .map(s -> PatternStats.toMap(s, min)).toList());
            r.put("condition_completion", stats.list("completion_rate", "condition", null).stream()
                    .map(s -> PatternStats.toMap(s, min)).toList());
            r.put("goal_pace", stats.list("pace_ratio", "goal", null).stream().map(s -> {
                Map<String, Object> m = PatternStats.toMap(s, min);
                m.put("title", store.goal(Long.parseLong(s.key())).map(Store.Goal::title).orElse(""));
                return m;
            }).toList());
            r.put("notable", stats.notable().stream().map(s -> PatternStats.toMap(s, min)).toList());
            r.put("insights", memory.search(null, "insight", null, null, "active", 50).stream()
                    .map(i -> MemoryStore.toMap(i, time)).toList());
            r.put("guidelines", memory.search(null, "guideline", null, null, "active", 50).stream()
                    .map(i -> MemoryStore.toMap(i, time)).toList());
            return r;
        });
    }

    // ------------------------------------------------------------------ 지난 기록

    @GetMapping("/facts/blocks")
    public Map<String, Object> factBlocks(@RequestParam(required = false) String day) {
        LocalDate d = day == null || day.isBlank() ? time.logicalDay(clock.now()) : parseDay(day);
        return tx.run(() -> {
            facts.refreshDay(d);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("day", d.toString());
            r.put("blocks", facts.ofDay(d).stream().filter(f -> !"travel".equals(f.kind())).map(f -> {
                Map<String, Object> m = new LinkedHashMap<>(facts.toMap(f));
                List<Map<String, Object>> logs = distractions.getObject().logsForBlock(f.blockId());
                if (!logs.isEmpty()) {
                    m.put("distractions", logs); // v4 §7 지난 기록: 딴짓 시간·앱(7일 안이면 제목)
                }
                return m;
            }).toList());
            r.put("day_facts", days.get(d).map(DayFacts::toMap).orElse(null));
            r.put("reason_chips", Map.of("done", BlockFacts.DONE_REASONS, "not_done", BlockFacts.NOT_DONE_REASONS));
            return r;
        });
    }

    /** 지난 기록에서 고친 값은 user 값이 된다. {outcome?, focus?, reason_tags?, progress_done?, *_note?} */
    @PatchMapping("/facts/blocks/{id}")
    public Map<String, Object> patchFact(@PathVariable long id, @RequestBody JsonNode body) {
        return tx.run(() -> {
            facts.refresh(id);
            BlockFacts.Fact f = facts.get(id).orElseThrow(() -> ApiException.notFound("블록#" + id + " 기록이 없습니다"));
            String outcome = body.path("outcome").asString("");
            if (List.of("done", "partial", "missed").contains(outcome)) {
                facts.setHuman(id, "outcome", outcome, "user");
            }
            int focus = body.path("focus").asInt(0);
            if (focus >= 1 && focus <= 3) {
                facts.setHuman(id, "focus", focus, "user");
            }
            if (body.path("reason_tags").isArray()) {
                List<String> tags = new ArrayList<>();
                body.get("reason_tags").forEach(t -> {
                    if (BlockFacts.validReason(t.asString(""))) {
                        tags.add(t.asString());
                    }
                });
                facts.setHuman(id, "reason", tags, "user");
            }
            if (body.path("progress_done").isNumber()) {
                facts.setHuman(id, "progress", body.get("progress_done").asInt(), "user");
            }
            for (String note : List.of("outcome_note", "focus_note", "reason_note", "extra_note")) {
                if (body.hasNonNull(note)) {
                    facts.setNote(id, note, body.get(note).asString(""));
                }
            }
            return Map.<String, Object>of("ok", true, "fact", facts.toMap(facts.get(f.blockId()).orElseThrow()));
        });
    }

    // ------------------------------------------------------------------ 관리 화면

    @GetMapping("/admin/insight")
    public Map<String, Object> admin() {
        return tx.run(() -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("background_tokens_7d", memoryJobs.tokenSummary());
            r.put("other_category_hints", categories.otherHints());
            r.put("memory_enabled", memory.enabled());
            r.put("state", db.jdbc().sql("SELECT state_key, state_value FROM memory_state ORDER BY state_key")
                    .query((rs, n) -> rs.getString(1) + "=" + rs.getString(2)).list());
            return r;
        });
    }

    private static LocalDate parseDay(String day) {
        try {
            return LocalDate.parse(day);
        } catch (RuntimeException e) {
            throw ApiException.badRequest("날짜는 YYYY-MM-DD 입니다");
        }
    }
}
