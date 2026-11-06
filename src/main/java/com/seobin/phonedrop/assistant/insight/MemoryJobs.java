package com.seobin.phonedrop.assistant.insight;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.seobin.phonedrop.assistant.AssistantClock;
import com.seobin.phonedrop.assistant.AssistantDb;
import com.seobin.phonedrop.assistant.Json;
import com.seobin.phonedrop.assistant.SettingsService;
import com.seobin.phonedrop.assistant.Store;
import com.seobin.phonedrop.assistant.TimeUtil;
import com.seobin.phonedrop.assistant.TurnQueue;
import com.seobin.phonedrop.assistant.Tx;
import com.seobin.phonedrop.assistant.agent.llm.ConfiguredLlm;
import com.seobin.phonedrop.assistant.agent.llm.LlmClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 백그라운드 작업(빌드문서 v3.5 §0-5): 서술 추출(outcome_notes), 사실 뽑기(memory_extract), 밤 정리(memory_reflection),
 * 분류 추정(category_backfill), 기존 memory 이전(memory_migrate). 대화 turn 이 아니다: 사용자에게 메시지를 보내지 않고
 * daily_turn_hard 에 세지 않는다. turn 대기열(trigger=memory_job)로 돌려 LLM 을 기다리는 동안 DB 락을 잡지 않고,
 * 토큰은 그 turns 행에 따로 남는다. 모델은 memory.model(비면 Haiku 계열).
 */
@Component
public class MemoryJobs {

    public static final String TRIGGER = "memory_job";
    public static final String DEFAULT_MODEL = "claude-haiku-5-5";
    private static final Logger log = LoggerFactory.getLogger(MemoryJobs.class);

    public record Usage(long inputTokens, long outputTokens) {
        static final Usage NONE = new Usage(0, 0);

        Usage plus(LlmClient.LlmResponse r) {
            return new Usage(inputTokens + r.inputTokens() + r.cacheReadTokens() + r.cacheWriteTokens(),
                    outputTokens + r.outputTokens());
        }
    }

    private final AssistantDb db;
    private final AssistantClock clock;
    private final Tx tx;
    private final TimeUtil time;
    private final SettingsService settings;
    private final Store store;
    private final TurnQueue turns;
    private final LlmClient llm;
    private final BlockFacts facts;
    private final DayFacts days;
    private final PatternStats stats;
    private final MemoryStore memory;
    private final Categories categories;
    /** 테스트는 false(직접 부른다). 운영은 true. */
    private volatile boolean autoJobs;

    public MemoryJobs(AssistantDb db, AssistantClock clock, Tx tx, TimeUtil time, SettingsService settings, Store store,
                      TurnQueue turns, LlmClient llm, BlockFacts facts, DayFacts days, PatternStats stats,
                      MemoryStore memory, Categories categories,
                      @Value("${assistant.memory.auto-jobs:true}") boolean autoJobs) {
        this.db = db;
        this.clock = clock;
        this.tx = tx;
        this.time = time;
        this.settings = settings;
        this.store = store;
        this.turns = turns;
        this.llm = llm;
        this.facts = facts;
        this.days = days;
        this.stats = stats;
        this.memory = memory;
        this.categories = categories;
        this.autoJobs = autoJobs;
    }

    public void setAutoJobs(boolean on) {
        this.autoJobs = on;
    }

    // ------------------------------------------------------------------ 예약

    /** 같은 작업이 이미 기다리고 있으면 다시 넣지 않는다. */
    public boolean enqueue(String job, ObjectNode extra) {
        if (!autoJobs) {
            return false;
        }
        ObjectNode ref = extra == null ? Json.object() : extra.deepCopy();
        ref.put("job", job);
        String refText = Json.write(ref);
        boolean waiting = db.jdbc().sql("SELECT COUNT(*) FROM turns WHERE trigger = ? AND status = 'queued' AND trigger_ref = ?")
                .param(TRIGGER).param(refText).query(Integer.class).single() > 0;
        if (waiting) {
            return false;
        }
        turns.enqueue(TRIGGER, ref, TurnQueue.PRIORITY_PULSE);
        return true;
    }

    /** 사용자 대화 turn 이 끝날 때마다(§7.3): memory.extract_every_turns 번마다 사실 뽑기. */
    public void onUserTurn() {
        if (!memory.enabled()) {
            return;
        }
        int n = parse(memory.state("turns_since_extract")) + 1;
        if (n >= settings.getInt("memory.extract_every_turns")) {
            if (enqueue("memory_extract", null)) {
                n = 0;
            }
        }
        memory.setState("turns_since_extract", String.valueOf(n));
    }

    /** 시작할 때: 한 번만 하는 작업(기존 블록 채우기는 코드, memory 이전·분류 추정은 백그라운드). */
    public void ensureOneTimeJobs() {
        if (memory.state("facts_backfill_v1") == null) {
            int n = facts.backfill();
            memory.setState("facts_backfill_v1", "done:" + n);
        }
        if (memory.state("memory_migrate_v1") == null) {
            String legacy = db.jdbc().sql("SELECT content FROM memory WHERE id = 1").query(String.class).optional().orElse("");
            if (legacy.isBlank()) {
                memory.setState("memory_migrate_v1", "empty");
            } else {
                enqueue("memory_migrate", null);
            }
        }
        if (memory.state("category_backfill_v1") == null) {
            if (uncategorized(400).isEmpty()) {
                memory.setState("category_backfill_v1", "empty");
            } else {
                enqueue("category_backfill", null);
            }
        }
    }

    private static int parse(String s) {
        try {
            return s == null ? 0 : Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ 실행

    /** turn 대기열에서 부른다(트랜잭션 밖). */
    public Usage run(JsonNode ref) {
        String job = ref.path("job").asString("");
        return switch (job) {
            case "outcome_notes" -> outcomeNotes(ref.path("card_id").asLong(-1));
            case "day_notes" -> dayNotes(LocalDate.parse(ref.path("day").asString()));
            case "memory_extract" -> memory.enabled() ? extract() : Usage.NONE;
            case "memory_reflection" -> memory.enabled() ? reflection() : Usage.NONE;
            case "category_backfill" -> categoryBackfill();
            case "memory_migrate" -> migrate();
            default -> Usage.NONE;
        };
    }

    private String model() {
        String m = settings.getString("memory.model");
        return m == null || m.isBlank() ? DEFAULT_MODEL : m.strip();
    }

    /** 저렴한 모델로 한 번 묻는다. 그 모델이 안 되면 기본 모델로 한 번 더. */
    public LlmClient.LlmResponse ask(String system, String text) {
        if (llm instanceof ConfiguredLlm configured) {
            try {
                return configured.once(system, text, List.of(), model());
            } catch (RuntimeException e) {
                log.warn("memory model {} failed, falling back: {}", model(), e.getMessage());
                return configured.once(system, text, List.of());
            }
        }
        return llm.once(system, text, List.of());
    }

    /** 응답에서 JSON 객체를 꺼낸다(앞뒤 글·코드 블록 무시). 없으면 빈 객체. */
    public static JsonNode json(String text) {
        if (text == null) {
            return Json.object();
        }
        int s = text.indexOf('{');
        int e = text.lastIndexOf('}');
        if (s < 0 || e <= s) {
            return Json.object();
        }
        try {
            JsonNode n = Json.parse(text.substring(s, e + 1));
            return n.isObject() ? n : Json.object();
        } catch (RuntimeException ex) {
            return Json.object();
        }
    }

    private static final String JSON_RULE = "\n반드시 JSON 객체 하나만 답한다(설명·코드 블록 없이). 모르면 null 로 둔다.";

    // ------------------------------------------------------------------ §3.4 서술 추출

    static final String NOTES_SYSTEM = """
            너는 일정 기록 정리기다. 사용자가 블록 정리 카드에 쓴 짧은 글을 읽고, 비어 있는 칸만 채운다.
            - outcome: done(다 함) | partial(일부) | missed(못 함)
            - focus: 1(안 됨) | 2(보통) | 3(잘 됨)
            - reason_tags: 다 했을 때 [good_condition, deadline, fun, good_env, easy], 일부·못 했을 때 [tired, stuck, other_task, unwilling, forgot, no_time, unwell] 중에서만
            - progress_done: 진도 단위가 있을 때 이번에 한 양(정수)
            - conflict: 사용자가 누른 버튼과 글이 서로 다르면 한 줄로 설명, 아니면 null
            - request: 글에 일정을 바꿔 달라거나 비서에게 하는 요청이 있으면 그 요청 문장(원문 그대로), 없으면 null
            글에 없는 것은 추측하지 말고 null.
            형식: {"blocks":[{"block_id":1,"outcome":null,"focus":null,"reason_tags":null,"progress_done":null,"conflict":null}],"sleep":null,"condition":null,"request":null}
            """ + JSON_RULE;

    Usage outcomeNotes(long cardId) {
        record Input(String text, List<Long> blockIds, LocalDate day) {
        }
        Input input = tx.run(() -> {
            JsonNode card = db.jdbc().sql("SELECT block_ids, logical_day FROM cards WHERE id = ?").param(cardId)
                    .query((rs, n) -> {
                        ObjectNode o = Json.object();
                        o.set("ids", Json.parse(rs.getString(1)));
                        o.put("day", rs.getObject(2, LocalDate.class).toString());
                        return (JsonNode) o;
                    }).optional().orElse(null);
            if (card == null) {
                return null;
            }
            List<Long> ids = new ArrayList<>();
            card.path("ids").forEach(n -> ids.add(n.asLong()));
            StringBuilder sb = new StringBuilder("# 블록\n");
            boolean any = false;
            for (long id : ids) {
                BlockFacts.Fact f = facts.get(id).orElse(null);
                if (f == null) {
                    continue;
                }
                String title = store.block(id).map(store::blockTitle).orElse("");
                sb.append("- block_id ").append(id).append(" '").append(title).append("' ")
                        .append("fixed".equals(f.kind()) ? "(고정 일정) " : "")
                        .append("버튼: 결과=").append(human(f.outcome(), f.outcomeSrc()))
                        .append(", 집중=").append(human(f.focus(), f.focusSrc()))
                        .append(", 이유=").append(human(f.reasonTags(), f.reasonSrc())).append('\n');
                for (String[] note : List.of(new String[]{"결과 글", f.outcomeNote()}, new String[]{"집중 글", f.focusNote()},
                        new String[]{"이유 글", f.reasonNote()}, new String[]{"추가 내용", f.extraNote()})) {
                    if (note[1] != null && !note[1].isBlank()) {
                        sb.append("  ").append(note[0]).append(": ").append(note[1]).append('\n');
                        any = true;
                    }
                }
            }
            LocalDate day = LocalDate.parse(card.path("day").asString());
            String dayNote = days.get(day).map(DayFacts.Day::dayNote).orElse(null);
            if (dayNote != null && !dayNote.isBlank()) {
                sb.append("# 오늘 하루 한마디\n").append(dayNote).append('\n');
                any = true;
            }
            return any ? new Input(sb.toString(), ids, day) : null;
        });
        if (input == null) {
            return Usage.NONE;
        }
        LlmClient.LlmResponse r = ask(NOTES_SYSTEM, input.text());
        JsonNode out = json(r.text());
        tx.exec(() -> {
            for (JsonNode b : out.path("blocks")) {
                long id = b.path("block_id").asLong(-1);
                if (!input.blockIds().contains(id)) {
                    continue;
                }
                applyText(id, b);
                if (b.hasNonNull("conflict") && !b.get("conflict").asString("").isBlank()) {
                    String title = store.block(id).map(store::blockTitle).orElse("블록");
                    memory.addCheck("'" + title + "' 기록: " + b.get("conflict").asString());
                }
            }
            applyDay(input.day(), out);
            handleRequest(out);
        });
        if (memory.enabled()) {
            tx.exec(() -> enqueue("memory_extract", null));
        }
        return Usage.NONE.plus(r);
    }

    private static String human(Object v, String src) {
        return v == null ? "비어 있음" : v + (src == null ? "" : "(" + src + ")");
    }

    private void applyText(long blockId, JsonNode b) {
        String outcome = b.path("outcome").asString("");
        if (List.of("done", "partial", "missed").contains(outcome)) {
            facts.setHuman(blockId, "outcome", outcome, "text");
        }
        int focus = b.path("focus").asInt(0);
        if (focus >= 1 && focus <= 3) {
            facts.setHuman(blockId, "focus", focus, "text");
        }
        if (b.path("reason_tags").isArray()) {
            List<String> tags = new ArrayList<>();
            b.get("reason_tags").forEach(t -> {
                if (BlockFacts.validReason(t.asString(""))) {
                    tags.add(t.asString());
                }
            });
            if (!tags.isEmpty()) {
                facts.setHuman(blockId, "reason", tags, "text");
            }
        }
        if (b.path("progress_done").isNumber() && b.get("progress_done").asInt() > 0) {
            facts.setHuman(blockId, "progress", b.get("progress_done").asInt(), "text");
        }
    }

    private void applyDay(LocalDate day, JsonNode out) {
        int sleep = out.path("sleep").asInt(0);
        if (sleep >= 1 && sleep <= 3) {
            days.setLevel(day, "sleep", sleep, "text");
        }
        int condition = out.path("condition").asInt(0);
        if (condition >= 1 && condition <= 3) {
            days.setLevel(day, "condition", condition, "text");
        }
    }

    /** 글에 요청·일정 변경이 있으면 그 글로 user_message turn 하나(§3.4). 그 외에는 답장하지 않는다. */
    private void handleRequest(JsonNode out) {
        String request = out.path("request").isString() ? out.get("request").asString("").strip() : "";
        if (request.isEmpty() || "null".equals(request)) {
            return;
        }
        String text = request.length() > 2000 ? request.substring(0, 2000) : request;
        long messageId = db.insert("""
                        INSERT INTO messages(role, content, kind, source, created_at) VALUES ('user', ?, 'chat', 'card', ?)""",
                text, clock.now());
        ObjectNode ref = Json.object();
        ref.put("message_id", messageId);
        turns.enqueue("user_message", ref, TurnQueue.PRIORITY_USER);
    }

    /** §4: 아침 카드 서술(잠·컨디션 글). */
    static final String DAY_SYSTEM = """
            너는 하루 기록 정리기다. 사용자가 아침 카드에 쓴 잠·컨디션 글을 읽고 비어 있는 값만 채운다.
            - sleep: 1(못 잠) | 2(보통) | 3(잘 잠)
            - condition: 1(나쁨) | 2(보통) | 3(좋음)
            - request: 비서에게 하는 요청이나 일정 변경이 있으면 그 문장, 없으면 null
            형식: {"sleep":null,"condition":null,"request":null}
            """ + JSON_RULE;

    Usage dayNotes(LocalDate day) {
        String text = tx.run(() -> days.get(day).map(d -> {
            StringBuilder sb = new StringBuilder();
            sb.append("버튼: 잠=").append(human(d.sleep(), d.sleepSrc())).append(", 컨디션=")
                    .append(human(d.condition(), d.conditionSrc())).append('\n');
            if (d.sleepNote() != null) {
                sb.append("잠 글: ").append(d.sleepNote()).append('\n');
            }
            if (d.conditionNote() != null) {
                sb.append("컨디션 글: ").append(d.conditionNote()).append('\n');
            }
            return d.sleepNote() == null && d.conditionNote() == null ? null : sb.toString();
        }).orElse(null));
        if (text == null) {
            return Usage.NONE;
        }
        LlmClient.LlmResponse r = ask(DAY_SYSTEM, text);
        JsonNode out = json(r.text());
        tx.exec(() -> {
            applyDay(day, out);
            handleRequest(out);
            if (memory.enabled()) {
                enqueue("memory_extract", null);
            }
        });
        return Usage.NONE.plus(r);
    }

    // ------------------------------------------------------------------ §7.3 사실 뽑기

    static final String EXTRACT_SYSTEM = """
            너는 사용자 기억 관리자다(Mem0 방식). 새 대화·기록에서 이 사용자에 대한 오래 쓸 사실(fact)과 의미 있는 일화(episode)를 뽑아
            기존 기억과 비교해 항목마다 ADD | UPDATE | DELETE | NOOP 하나를 고른다.
            - fact: 생활·성향·선호·건강·일하는 방식처럼 오래 가는 사실. 사용자가 분명히 말한 것만.
            - episode: 미룬 뒤의 결과, 막힘과 해결, 단계 완료, 지각·이동 문제, 감정이 드러난 순간, 생활 사건(예: "배 아파서 30분만 함")
            - 일정·할 일 자체(언제 무엇을 한다)는 기억이 아니다(시간표에 있다). 사용자를 평가하거나 꼬리표를 붙이지 않는다.
            - content 는 200자 이내 한국어 한 문장. category 는 study|work|class|exercise|chores|selfcare|rest|social|travel|other|snooze|persuasion|health|preference
            - UPDATE·DELETE 는 기존 항목 id 를 쓴다. 기존 것과 같으면 NOOP(목록에 넣지 않아도 된다).
            형식: {"ops":[{"op":"ADD","item_id":null,"kind":"fact","category":"health","content":"...","goal_id":null}]}
            """ + JSON_RULE;

    Usage extract() {
        record Input(String text, long lastMessage, long lastLog, String noteSince) {
        }
        Input input = tx.run(() -> {
            long fromMsg = parseLong(memory.state("extract_message_id"));
            long fromLog = parseLong(memory.state("extract_log_id"));
            String since = memory.state("extract_notes_at");
            Instant notesSince = since == null ? clock.now().minus(Duration.ofDays(1)) : Instant.parse(since);
            StringBuilder sb = new StringBuilder();
            List<String[]> msgs = db.jdbc().sql("""
                            SELECT id, role, content FROM messages WHERE id > ? AND no_memory = FALSE AND held_block_id IS NULL AND held_window_id IS NULL
                            AND kind NOT IN ('outcome_card', 'day_card') ORDER BY id LIMIT 80""")
                    .param(fromMsg)
                    .query((rs, n) -> new String[]{String.valueOf(rs.getLong(1)), rs.getString(2), rs.getString(3)}).list();
            long lastMsg = fromMsg;
            if (!msgs.isEmpty()) {
                sb.append("# 새 대화\n");
                for (String[] m : msgs) {
                    sb.append("[").append("user".equals(m[1]) ? "사용자" : "비서").append("] ").append(cut(m[2], 500)).append('\n');
                    lastMsg = Long.parseLong(m[0]);
                }
            }
            List<String[]> notes = db.jdbc().sql("""
                            SELECT block_id, outcome_note, focus_note, reason_note, extra_note FROM block_facts WHERE updated_at > ?
                            AND (outcome_note IS NOT NULL OR focus_note IS NOT NULL OR reason_note IS NOT NULL OR extra_note IS NOT NULL)
                            ORDER BY updated_at LIMIT 40""")
                    .param(AssistantDb.ts(notesSince))
                    .query((rs, n) -> new String[]{String.valueOf(rs.getLong(1)), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getString(5)}).list();
            if (!notes.isEmpty()) {
                sb.append("# 정리 카드 서술\n");
                for (String[] n : notes) {
                    String title = store.block(Long.parseLong(n[0])).map(store::blockTitle).orElse("");
                    sb.append("- '").append(title).append("': ");
                    for (int i = 1; i < n.length; i++) {
                        if (n[i] != null) {
                            sb.append(n[i]).append(" / ");
                        }
                    }
                    sb.append('\n');
                }
            }
            db.jdbc().sql("""
                            SELECT logical_day, sleep_note, condition_note, day_note FROM day_facts WHERE updated_at > ?
                            AND (sleep_note IS NOT NULL OR condition_note IS NOT NULL OR day_note IS NOT NULL)""")
                    .param(AssistantDb.ts(notesSince))
                    .query((rs, n) -> sb.append("- ").append(rs.getObject(1, LocalDate.class)).append(" 하루: ")
                            .append(nz(rs.getString(2))).append(' ').append(nz(rs.getString(3))).append(' ')
                            .append(nz(rs.getString(4))).append('\n')).list();
            List<String[]> logs = db.jdbc().sql("""
                            SELECT id, block_id, type, content FROM block_logs WHERE id > ?
                            AND type IN ('talk_summary', 'progress', 'snooze_reason', 'note', 'missed') ORDER BY id LIMIT 60""")
                    .param(fromLog)
                    .query((rs, n) -> new String[]{String.valueOf(rs.getLong(1)), String.valueOf(rs.getLong(2)),
                            rs.getString(3), rs.getString(4)}).list();
            long lastLog = fromLog;
            if (!logs.isEmpty()) {
                sb.append("# 새 블록 기록\n");
                for (String[] l : logs) {
                    String title = store.block(Long.parseLong(l[1])).map(store::blockTitle).orElse("");
                    sb.append("- '").append(title).append("' ").append(l[2]).append(": ").append(cut(l[3], 200)).append('\n');
                    lastLog = Long.parseLong(l[0]);
                }
            }
            if (sb.isEmpty()) {
                memory.setState("extract_notes_at", clock.now().toString());
                return null;
            }
            sb.append("# 기존 기억(최근 30개)\n");
            for (MemoryStore.Item it : memory.search(null, null, null, null, "active", 200).stream()
                    .filter(i -> List.of("fact", "episode").contains(i.kind())).limit(30).toList()) {
                sb.append("- #").append(it.id()).append(" [").append(it.kind()).append('/').append(it.category()).append(']')
                        .append("user".equals(it.createdBy()) ? "(사용자가 만듦)" : "").append(' ').append(it.content()).append('\n');
            }
            return new Input(sb.toString(), lastMsg, lastLog, clock.now().toString());
        });
        if (input == null) {
            return Usage.NONE;
        }
        LlmClient.LlmResponse r = ask(EXTRACT_SYSTEM, input.text());
        JsonNode out = json(r.text());
        tx.exec(() -> {
            for (JsonNode op : out.path("ops")) {
                applyOp(op, "extraction");
            }
            memory.setState("extract_message_id", String.valueOf(input.lastMessage()));
            memory.setState("extract_log_id", String.valueOf(input.lastLog()));
            memory.setState("extract_notes_at", input.noteSince());
        });
        return Usage.NONE.plus(r);
    }

    /** ADD·UPDATE·DELETE(archived)·NOOP. 사용자가 만든 항목은 바꾸지 않는다(충돌하면 확인 필요). */
    void applyOp(JsonNode op, String actor) {
        String kind = op.path("op").asString("").toUpperCase(java.util.Locale.ROOT);
        String content = op.path("content").asString("").strip();
        try {
            switch (kind) {
                case "ADD" -> {
                    String k = op.path("kind").asString("fact");
                    if (!List.of("fact", "episode").contains(k) || content.isEmpty()) {
                        return;
                    }
                    memory.add(k, op.path("category").asString("other"), cut(content, MemoryStore.ITEM_MAX), null, 0.6,
                            op.hasNonNull("goal_id") ? op.get("goal_id").asLong() : null, actor);
                }
                case "UPDATE", "DELETE" -> {
                    long id = op.path("item_id").asLong(-1);
                    MemoryStore.Item it = memory.item(id).orElse(null);
                    if (it == null || !"active".equals(it.status())) {
                        return;
                    }
                    if ("user".equals(it.createdBy())) {
                        memory.logOp(id, "noop", it.content(), content, actor);
                        memory.addCheck("기억 '" + cut(it.content(), 60) + "' 과 다른 내용: " + cut(content.isEmpty()
                                ? "(지우자는 제안)" : content, 120));
                        return;
                    }
                    if ("UPDATE".equals(kind) && !content.isEmpty()) {
                        memory.update(id, cut(content, MemoryStore.ITEM_MAX), null, null, actor);
                    } else if ("DELETE".equals(kind)) {
                        memory.setStatus(id, "archived", actor);
                    }
                }
                default -> memory.logOp(op.path("item_id").isNumber() ? op.get("item_id").asLong() : null, "noop", null,
                        content.isEmpty() ? null : content, actor);
            }
        } catch (RuntimeException e) {
            log.info("memory op skipped: {}", e.getMessage());
        }
    }

    // ------------------------------------------------------------------ §8 밤 정리

    static final String REFLECTION_SYSTEM = """
            너는 사용자 맞춤 비서의 밤 정리 담당이다(MemGPT·LangMem 방식). 오늘 기록과 통계를 보고 기억을 정리한다. 사용자에게 말하지 않는다.
            1. insights: '눈에 띄는 통계' 목록에 있는 숫자로만 결론을 만든다. 각 항목에 근거 stats(metric,dimension,key)를 그대로 적는다.
               예: "잠 못 잔 날 완료율 0.41(n=11) vs 평소 0.72 → 그런 날은 가벼운 일 먼저". 근거 목록에 없는 것은 쓰지 않는다.
            2. guidelines: 이 사용자를 대하는 법(설득 방식 성공률, 사용자 반응). 예: "피곤 이유엔 micro_step 0.70(n=10)". 근거가 있으면 stats 에 적는다.
            3. goal insights: 속도가 부족하거나 막힌 목표에 짧은 조언(insights 에 goal_id 와 함께).
            4. profile: 고칠 섹션만 새 전체 내용으로(섹션당 400자 이내). '사용자가 씀' 섹션은 쓰지 않는다. now 섹션은 요즘 상황으로 갱신.
            5. merge: 같은 내용의 기억을 합친다 {"keep_id":1,"remove_ids":[2],"content":"합친 문장"}. 모순이면 낡은 것을 archive_ids 에.
            6. categories: 분류가 없는 블록·목표·할 일 제목을 보고 study|work|class|exercise|chores|selfcare|rest|social|other 중 하나.
            7. checks: 사용자에게 확인이 필요한 것(서로 다른 기록 등). 다음 아침에 한 번 묻는다. 없으면 [].
            사용자를 평가하거나 꼬리표를 붙이지 않는다. 기존 항목을 고칠 때는 op=update 와 item_id, 버릴 때는 op=archive.
            형식: {"insights":[{"op":"add","item_id":null,"content":"...","category":"study","goal_id":null,"confidence":0.7,"stats":[{"metric":"completion_rate","dimension":"sleep","key":"1"}]}],
            "guidelines":[{"op":"add","item_id":null,"content":"...","category":"persuasion","confidence":0.6,"stats":[]}],
            "profile":{"now":"..."},"merge":[],"archive_ids":[],"categories":[{"type":"block","id":1,"category":"study"}],"checks":[]}
            """ + JSON_RULE;

    Usage reflection() {
        String input = tx.run(() -> {
            LocalDate today = time.logicalDay(clock.now());
            Instant computed = stats.computedAt();
            if (computed == null || Duration.between(computed, clock.now()).toHours() >= 1) {
                stats.compute();
            }
            days.computeCounts(today);
            int min = settings.getInt("memory.min_samples");
            StringBuilder sb = new StringBuilder();
            sb.append("# 눈에 띄는 통계(근거로 쓸 수 있는 것)\n");
            List<PatternStats.Stat> notable = stats.notable();
            sb.append(notable.isEmpty() ? "없음\n" : "");
            notable.forEach(s -> sb.append("- ").append(stats.line(s)).append('\n'));
            sb.append("# 설득 방식 성공률\n");
            stats.list("tactic_success_rate", null, null).stream().filter(s -> s.n() >= min)
                    .forEach(s -> sb.append("- ").append(stats.line(s)).append('\n'));
            sb.append("# 오늘 하루(").append(today).append(")\n");
            days.get(today).ifPresent(d -> sb.append(Json.write(Json.MAPPER.valueToTree(DayFacts.toMap(d)))).append('\n'));
            sb.append("# 오늘 블록 기록\n");
            for (BlockFacts.Fact f : facts.ofDay(today)) {
                if ("travel".equals(f.kind()) || "cancelled".equals(f.statusFinal())) {
                    continue;
                }
                sb.append("- ").append(store.block(f.blockId()).map(store::blockTitle).orElse("")).append(" [")
                        .append(f.category()).append("] 결과=").append(nz(f.outcome())).append(" 집중=")
                        .append(f.focus() == null ? "-" : f.focus()).append(" 이유=").append(f.reasons())
                        .append(" 미룸=").append(f.snoozeCount());
                for (String note : new String[]{f.outcomeNote(), f.focusNote(), f.reasonNote(), f.extraNote()}) {
                    if (note != null) {
                        sb.append(" / ").append(cut(note, 200));
                    }
                }
                sb.append('\n');
            }
            sb.append("# 오늘 일화\n");
            memory.search(null, "episode", null, null, "active", 20).stream()
                    .filter(i -> i.createdAt() != null && time.logicalDay(i.createdAt()).equals(today))
                    .forEach(i -> sb.append("- #").append(i.id()).append(' ').append(i.content()).append('\n'));
            sb.append("# 현재 프로필\n");
            for (MemoryStore.Section s : memory.profile()) {
                sb.append("- ").append(s.section()).append("user".equals(s.updatedBy()) ? "(사용자가 씀)" : "").append(": ")
                        .append(s.content().replace("\n", " / ")).append('\n');
            }
            sb.append("# 지금 insight·guideline\n");
            for (String k : List.of("insight", "guideline")) {
                memory.search(null, k, null, null, "active", 40).forEach(i -> sb.append("- #").append(i.id()).append(" [")
                        .append(k).append("] ").append(i.content()).append('\n'));
            }
            sb.append("# 다른 기억(합칠 것 찾기)\n");
            memory.search(null, null, null, null, "active", 200).stream()
                    .filter(i -> List.of("fact", "episode").contains(i.kind())).limit(60)
                    .forEach(i -> sb.append("- #").append(i.id()).append(" [").append(i.kind()).append('/')
                            .append(i.category()).append("] ").append(i.content()).append('\n'));
            sb.append("# 점검할 목표\n");
            for (PatternStats.Stat s : stats.list("pace_ratio", null, null)) {
                if (s.value() != null && s.value() < 1) {
                    long goalId = Long.parseLong(s.key());
                    sb.append("- 목표#").append(goalId).append(' ')
                            .append(store.goal(goalId).map(Store.Goal::title).orElse("")).append(" 속도 ")
                            .append(PatternStats.fmt(s.value())).append("(필요 속도 대비, 최근 7일 블록 ").append(s.n()).append("개)\n");
                }
            }
            for (PatternStats.Stat s : stats.list("stall_steps", null, null)) {
                String[] ids = s.key().split(":");
                sb.append("- 목표#").append(ids[0]).append(" 할 일 '")
                        .append(store.task(Long.parseLong(ids[1])).map(Store.Task::title).orElse("")).append("'에서 막힘 ")
                        .append(s.n()).append("번\n");
            }
            sb.append("# 분류가 없는 제목\n");
            uncategorized(50).forEach(u -> sb.append("- ").append(u.type()).append('#').append(u.id()).append(' ')
                    .append(u.title()).append('\n'));
            return sb.toString();
        });
        LlmClient.LlmResponse r = ask(REFLECTION_SYSTEM, input);
        JsonNode out = json(r.text());
        tx.exec(() -> applyReflection(out));
        return Usage.NONE.plus(r);
    }

    public void applyReflection(JsonNode out) {
        int min = settings.getInt("memory.min_samples");
        for (String kind : List.of("insight", "guideline")) {
            for (JsonNode it : out.path(kind + "s")) {
                try {
                    applyLearned(kind, it, min);
                } catch (RuntimeException e) {
                    log.info("reflection {} skipped: {}", kind, e.getMessage());
                }
            }
        }
        // 프로필: 사용자가 쓴 섹션은 건너뛴다(rewrite 거절).
        JsonNode profile = out.path("profile");
        if (profile.isObject()) {
            for (String section : MemoryStore.SECTIONS) {
                if (profile.hasNonNull(section) && profile.get(section).isString()) {
                    String text = profile.get(section).asString().strip();
                    MemoryStore.Section s = memory.section(section);
                    if (text.isEmpty() || "user".equals(s.updatedBy()) || text.equals(s.content())) {
                        continue;
                    }
                    try {
                        memory.edit(section, "rewrite", cut(text, MemoryStore.SECTION_MAX), null, "reflection");
                    } catch (RuntimeException e) {
                        log.info("profile {} skipped: {}", section, e.getMessage());
                    }
                }
            }
        }
        for (JsonNode m : out.path("merge")) {
            long keep = m.path("keep_id").asLong(-1);
            MemoryStore.Item k = memory.item(keep).orElse(null);
            if (k == null || !"active".equals(k.status())) {
                continue;
            }
            boolean removed = false;
            for (JsonNode rid : m.path("remove_ids")) {
                MemoryStore.Item it = memory.item(rid.asLong()).orElse(null);
                if (it != null && it.id() != keep && "active".equals(it.status()) && !"user".equals(it.createdBy())) {
                    memory.setStatus(it.id(), "archived", "reflection");
                    removed = true;
                }
            }
            String content = m.path("content").asString("").strip();
            if (removed && !content.isEmpty() && !"user".equals(k.createdBy())) {
                memory.update(keep, cut(content, MemoryStore.ITEM_MAX), null, null, "reflection");
            }
        }
        for (JsonNode id : out.path("archive_ids")) {
            memory.item(id.asLong()).filter(i -> "active".equals(i.status()) && !"user".equals(i.createdBy()))
                    .ifPresent(i -> memory.setStatus(i.id(), "archived", "reflection"));
        }
        // 30일 넘게 안 쓰였으면서 근거가 약한 항목(코드가 정한다)
        Instant cutoff = clock.now().minus(Duration.ofDays(30));
        db.jdbc().sql("""
                        SELECT id FROM memory_items WHERE status = 'active' AND kind IN ('fact', 'episode') AND created_by <> 'user'
                        AND confidence < 0.5 AND COALESCE(last_used_at, created_at) < ?""")
                .param(AssistantDb.ts(cutoff)).query(Long.class).list()
                .forEach(id -> memory.setStatus(id, "archived", "reflection"));
        applyCategories(out.path("categories"));
        for (JsonNode c : out.path("checks")) {
            memory.addCheck(cut(c.asString(""), 300));
        }
    }

    /** insight 는 근거 통계(n ≥ min_samples)가 있어야 저장한다. evidence 숫자는 코드가 채운다. */
    private void applyLearned(String kind, JsonNode it, int min) {
        String op = it.path("op").asString("add");
        String content = cut(it.path("content").asString("").strip(), MemoryStore.ITEM_MAX);
        if ("archive".equals(op)) {
            long id = it.path("item_id").asLong(-1);
            memory.item(id).filter(i -> kind.equals(i.kind()) && "active".equals(i.status()) && !"user".equals(i.createdBy()))
                    .ifPresent(i -> memory.setStatus(i.id(), "archived", "reflection"));
            return;
        }
        if (content.isEmpty()) {
            return;
        }
        ArrayNode evStats = Json.array();
        for (JsonNode ref : it.path("stats")) {
            List<PatternStats.Stat> found = stats.list(ref.path("metric").asString(""), ref.path("dimension").asString(""),
                    ref.path("key").asString(""));
            for (PatternStats.Stat s : found) {
                if (s.n() >= min) {
                    ObjectNode e = evStats.addObject();
                    e.put("metric", s.metric());
                    e.put("dimension", s.dimension());
                    e.put("key", s.key());
                    e.put("value", s.value());
                    e.put("n", s.n());
                }
            }
        }
        boolean goalInsight = "insight".equals(kind) && it.hasNonNull("goal_id");
        if ("insight".equals(kind) && evStats.isEmpty() && !goalInsight) {
            return; // 근거 없는 결론은 만들지 않는다(§8-1)
        }
        ObjectNode evidence = Json.object();
        evidence.set("stats", evStats);
        evidence.set("refs", Json.array());
        double confidence = it.path("confidence").isNumber() ? it.get("confidence").asDouble() : 0.6;
        String category = it.path("category").asString("other");
        Long goalId = it.hasNonNull("goal_id") ? it.get("goal_id").asLong() : null;
        if ("update".equals(op) && it.hasNonNull("item_id")) {
            MemoryStore.Item old = memory.item(it.get("item_id").asLong()).orElse(null);
            if (old != null && kind.equals(old.kind()) && "active".equals(old.status()) && !"user".equals(old.createdBy())) {
                memory.update(old.id(), content, Json.write(evidence), confidence, "reflection");
                return;
            }
        }
        boolean same = memory.search(null, kind, null, null, "active", 200).stream().anyMatch(i -> i.content().equals(content));
        if (!same) {
            memory.add(kind, category, content, Json.write(evidence), confidence, goalId, "reflection");
        }
    }

    // ------------------------------------------------------------------ 분류 추정

    public record Uncategorized(String type, long id, String title) {
    }

    /** category_src 가 없고(물려받을 곳도 없는) 제목. 이동 블록·취소된 블록은 뺀다. */
    List<Uncategorized> uncategorized(int limit) {
        List<Uncategorized> out = new ArrayList<>();
        db.jdbc().sql("SELECT id, title FROM goals WHERE category_src IS NULL ORDER BY id DESC LIMIT ?").param(limit)
                .query((rs, n) -> out.add(new Uncategorized("goal", rs.getLong(1), rs.getString(2)))).list();
        db.jdbc().sql("SELECT id, title FROM tasks WHERE category_src IS NULL AND goal_id IS NULL ORDER BY id DESC LIMIT ?")
                .param(limit).query((rs, n) -> out.add(new Uncategorized("task", rs.getLong(1), rs.getString(2)))).list();
        db.jdbc().sql("SELECT id, title FROM fixed_series WHERE category_src IS NULL ORDER BY id DESC LIMIT ?").param(limit)
                .query((rs, n) -> out.add(new Uncategorized("series", rs.getLong(1), rs.getString(2)))).list();
        db.jdbc().sql("""
                        SELECT id, title FROM blocks WHERE category_src IS NULL AND kind = 'fixed' AND series_id IS NULL
                        AND goal_id IS NULL AND task_id IS NULL AND status <> 'cancelled' AND (link_role IS NULL OR link_role <> 'travel')
                        AND title <> '' ORDER BY id DESC LIMIT ?""")
                .param(limit).query((rs, n) -> out.add(new Uncategorized("block", rs.getLong(1), rs.getString(2)))).list();
        return out.size() > limit ? out.subList(0, limit) : out;
    }

    void applyCategories(JsonNode list) {
        for (JsonNode c : list) {
            String type = c.path("type").asString("");
            String table = switch (type) {
                case "goal" -> "goals";
                case "task" -> "tasks";
                case "series" -> "fixed_series";
                case "block" -> "blocks";
                default -> null;
            };
            String category = c.path("category").asString("");
            if (table == null || !Categories.LABELS.containsKey(category) || "travel".equals(category)) {
                continue;
            }
            try {
                categories.set(table, c.path("id").asLong(), category, "inferred");
            } catch (RuntimeException e) {
                log.info("category skipped: {}", e.getMessage());
            }
        }
    }

    static final String CATEGORY_SYSTEM = """
            너는 일정 분류기다. 제목마다 study(공부) | work(일) | class(수업) | exercise(운동) | chores(집안일·잡무) |
            selfcare(씻기·식사·준비) | rest(휴식) | social(약속) | other(기타) 중 하나를 고른다. 애매하면 other.
            형식: {"categories":[{"type":"goal","id":1,"category":"study"}]}
            """ + JSON_RULE;

    Usage categoryBackfill() {
        List<Uncategorized> list = tx.run(() -> uncategorized(80));
        if (list.isEmpty()) {
            tx.exec(() -> memory.setState("category_backfill_v1", "done"));
            return Usage.NONE;
        }
        StringBuilder sb = new StringBuilder("# 제목\n");
        list.forEach(u -> sb.append("- ").append(u.type()).append('#').append(u.id()).append(' ').append(u.title()).append('\n'));
        LlmClient.LlmResponse r = ask(CATEGORY_SYSTEM, sb.toString());
        JsonNode out = json(r.text());
        tx.exec(() -> {
            applyCategories(out.path("categories"));
            // 답에 없던 것은 other 로 확정해 다시 묻지 않는다(실패하면 other, §1).
            for (Uncategorized u : list) {
                String table = switch (u.type()) {
                    case "goal" -> "goals";
                    case "task" -> "tasks";
                    case "series" -> "fixed_series";
                    default -> "blocks";
                };
                db.update("UPDATE " + table + " SET category_src = 'inferred' WHERE id = ? AND category_src IS NULL", u.id());
            }
            if (uncategorized(1).isEmpty()) {
                memory.setState("category_backfill_v1", "done");
            } else {
                enqueue("category_backfill", null);
            }
        });
        return Usage.NONE.plus(r);
    }

    // ------------------------------------------------------------------ 기존 memory 이전(한 번)

    static final String MIGRATE_SYSTEM = """
            너는 사용자 기억 정리기다. 예전 비서 memory 글(한 덩어리)을 새 구조로 나눈다.
            - profile: life(생활) | personality(성향) | likes(좋아하는 것) | now(요즘) | work_style(일하는 방식), 섹션당 400자 이내 요약
            - facts: 그 밖의 오래 쓸 사실, 항목당 200자 이내. category 는 study|work|class|exercise|chores|selfcare|rest|social|other|snooze|persuasion|health|preference
            원문에 없는 내용을 만들지 않는다.
            형식: {"profile":{"life":"","personality":"","likes":"","now":"","work_style":""},"facts":[{"category":"health","content":"..."}]}
            """ + JSON_RULE;

    Usage migrate() {
        String legacy = tx.run(() -> memory.state("memory_migrate_v1") != null ? null
                : db.jdbc().sql("SELECT content FROM memory WHERE id = 1").query(String.class).optional().orElse(""));
        if (legacy == null) {
            return Usage.NONE;
        }
        if (legacy.isBlank()) {
            tx.exec(() -> memory.setState("memory_migrate_v1", "empty"));
            return Usage.NONE;
        }
        LlmClient.LlmResponse r = ask(MIGRATE_SYSTEM, legacy);
        JsonNode out = json(r.text());
        tx.exec(() -> {
            if (memory.state("memory_migrate_v1") != null) {
                return; // 다시 실행돼도 중복 없음
            }
            JsonNode profile = out.path("profile");
            for (String section : MemoryStore.SECTIONS) {
                String text = profile.path(section).asString("").strip();
                if (!text.isEmpty() && memory.section(section).content().isBlank()) {
                    memory.edit(section, "rewrite", cut(text, MemoryStore.SECTION_MAX), null, "assistant");
                }
            }
            for (JsonNode f : out.path("facts")) {
                String content = cut(f.path("content").asString("").strip(), MemoryStore.ITEM_MAX);
                if (!content.isEmpty()) {
                    memory.add("fact", f.path("category").asString("other"), content, null, 0.7, null, "extraction");
                }
            }
            long archived = memory.add("fact", "other", "예전 memory 원문: " + legacy, null, 0.5, null, "archive");
            memory.setStatus(archived, "archived", "extraction");
            memory.setState("memory_migrate_v1", "done");
        });
        return Usage.NONE.plus(r);
    }

    // ------------------------------------------------------------------ 도우미

    private static long parseLong(String s) {
        try {
            return s == null ? 0 : Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static String cut(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** 관리 화면: 백그라운드 작업 토큰(최근 7일, 작업별). */
    public List<Map<String, Object>> tokenSummary() {
        Instant since = clock.now().minus(Duration.ofDays(7));
        return db.jdbc().sql("""
                        SELECT trigger_ref, input_tokens, output_tokens, status FROM turns WHERE trigger = ? AND started_at >= ?""")
                .param(TRIGGER).param(AssistantDb.ts(since))
                .query((rs, n) -> new Object[]{Json.parse(rs.getString(1)).path("job").asString(""),
                        rs.getObject(2) == null ? 0 : rs.getInt(2), rs.getObject(3) == null ? 0 : rs.getInt(3), rs.getString(4)})
                .list().stream()
                .collect(java.util.stream.Collectors.groupingBy(o -> (String) o[0], LinkedHashMap::new,
                        java.util.stream.Collectors.toList()))
                .entrySet().stream().map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("job", e.getKey());
                    m.put("runs", e.getValue().size());
                    m.put("failed", e.getValue().stream().filter(o -> "failed".equals(o[3])).count());
                    m.put("input_tokens", e.getValue().stream().mapToInt(o -> (Integer) o[1]).sum());
                    m.put("output_tokens", e.getValue().stream().mapToInt(o -> (Integer) o[2]).sum());
                    return m;
                }).toList();
    }

    static final Set<String> JOBS = Set.of("outcome_notes", "day_notes", "memory_extract", "memory_reflection",
            "category_backfill", "memory_migrate");
}
