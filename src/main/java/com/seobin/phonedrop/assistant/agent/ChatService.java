package com.seobin.phonedrop.assistant.agent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.seobin.phonedrop.assistant.ApiException;
import com.seobin.phonedrop.assistant.AssistantClock;
import com.seobin.phonedrop.assistant.AssistantDb;
import com.seobin.phonedrop.assistant.InterventionService;
import com.seobin.phonedrop.assistant.TurnQueue;
import com.seobin.phonedrop.assistant.Json;
import com.seobin.phonedrop.assistant.TimeUtil;
import com.seobin.phonedrop.assistant.push.PushService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** 채팅 메시지·질문·turn 예약(빌드문서 v2 §5.4, §8.1, §10). */
@Service
public class ChatService {

    public static final int PRIORITY_USER = TurnQueue.PRIORITY_USER;
    public static final int PRIORITY_DEFAULT = TurnQueue.PRIORITY_DEFAULT;

    /** 비서가 먼저 보내는 메시지 종류(예산 대상, 빌드문서 v3 §4.5). */
    public static final List<String> PROACTIVE_KINDS = List.of("proactive", "briefing", "checkin");

    private final AssistantDb db;
    private final AssistantClock clock;
    private final TurnQueue turns;
    private final PushService push;
    private final TimeUtil time;
    private final InterventionService interventions;
    private final org.springframework.beans.factory.ObjectProvider<TimeWindows> windows;
    private final org.springframework.beans.factory.ObjectProvider<TimeWindowRunner> windowRunner;
    private final org.springframework.beans.factory.ObjectProvider<com.seobin.phonedrop.assistant.Store> store;

    public ChatService(AssistantDb db, AssistantClock clock, TurnQueue turns, PushService push, TimeUtil time,
                       InterventionService interventions,
                       org.springframework.beans.factory.ObjectProvider<TimeWindows> windows,
                       org.springframework.beans.factory.ObjectProvider<TimeWindowRunner> windowRunner,
                       org.springframework.beans.factory.ObjectProvider<com.seobin.phonedrop.assistant.Store> store) {
        this.windows = windows;
        this.windowRunner = windowRunner;
        this.store = store;
        this.db = db;
        this.clock = clock;
        this.turns = turns;
        this.push = push;
        this.time = time;
        this.interventions = interventions;
    }

    /** turns 행(queued)을 만들고 turn 작업을 예약한다. */
    public long enqueueTurn(String trigger, JsonNode ref, int priority) {
        return turns.enqueue(trigger, ref, priority);
    }

    /** POST /v1/messages. 같은 client_msg_id 면 처음 결과를 돌려준다. */
    public Map<String, Object> receive(JsonNode req) {
        String clientMsgId = req.path("client_msg_id").asString("").trim();
        String text = req.path("text").asString("").trim();
        if (text.isEmpty()) {
            throw ApiException.badRequest("text 가 필요합니다");
        }
        if (!clientMsgId.isEmpty()) {
            Optional<Map<String, Object>> existing = db.jdbc().sql("""
                            SELECT m.id, t.id FROM messages m LEFT JOIN turns t
                            ON t.trigger = 'user_message' AND t.trigger_ref = CONCAT('{"message_id":', m.id, '}')
                            WHERE m.client_msg_id = ?""")
                    .param(clientMsgId)
                    .query((rs, n) -> {
                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("message_id", rs.getLong(1));
                        r.put("turn_id", rs.getObject(2) == null ? null : rs.getLong(2));
                        return r;
                    }).optional();
            if (existing.isPresent()) {
                return existing.get();
            }
        }
        String source = req.path("source").asString("chat");
        // v3.1 §3.6: 풀스크린에서 보낸 말(intervention_id 있음)은 그 개입의 대화로 저장하고 intervention_talk turn.
        long interventionId = req.path("intervention_id").asLong(0);
        if (interventionId > 0) {
            if (!List.of("fullscreen", "fullscreen_voice").contains(source)) {
                source = "fullscreen";
            }
            long[] r = interventions.userTalk(interventionId, text, source, clientMsgId.isEmpty() ? null : clientMsgId);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("message_id", r[0]);
            result.put("turn_id", r[1]);
            return result;
        }
        long messageId = db.insert("""
                        INSERT INTO messages(role, content, kind, source, client_msg_id, created_at)
                        VALUES ('user', ?, 'reply', ?, ?, ?)""",
                text, source, clientMsgId.isEmpty() ? null : clientMsgId, clock.now());
        long questionId = req.path("answered_question_id").asLong(0);
        if (questionId > 0) {
            db.update("UPDATE questions SET status = 'answered', answered_at = ? WHERE id = ? AND status = 'open'",
                    clock.now(), questionId);
        }
        // v3.6 §2.3: 사용자가 말을 걸면 적극 개입 시간대 알람에 반응한 것으로 본다.
        TimeWindowRunner runner = windowRunner.getIfAvailable();
        if (runner != null) {
            runner.ack(false);
        }
        ObjectNode ref = Json.object();
        ref.put("message_id", messageId);
        // v3.1 §3.6-4: 풀스크린 대화 중이면 대화 시간을 연장한다.
        interventions.extendTalk();
        long turnId = enqueueTurn("user_message", ref, PRIORITY_USER);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("message_id", messageId);
        result.put("turn_id", turnId);
        return result;
    }

    /** 비서 메시지 저장 + chat.message 푸시(알림 있음). */
    public long sendAssistant(String text, String kind, String importance, Long questionId, Long confirmationId) {
        return sendAssistant(text, kind, importance, questionId, confirmationId, false);
    }

    /**
     * silent 여도 저장·푸시한다(앱이 채팅에만 쌓고 알림을 띄우지 않는다). channel 은 앱 알림 채널(chat·checkin, v3 §8.3).
     */
    public long sendAssistant(String text, String kind, String importance, Long questionId, Long confirmationId,
                              boolean silent) {
        return sendAssistant(text, kind, importance, questionId, confirmationId, silent, null);
    }

    /** interventionId: 풀스크린 대화의 답(v3.1 §3.6-3). 앱이 그 화면에 표시하고 TTS 로 읽는다. */
    public long sendAssistant(String text, String kind, String importance, Long questionId, Long confirmationId,
                              boolean silent, Long interventionId) {
        return sendAssistant(text, kind, importance, questionId, confirmationId, silent, interventionId, null);
    }

    /**
     * action: 답장에 붙는 버튼(v3.6 §1.2 open_settings).
     * v3.6 §2.2: 방해 금지 시간대에는 사용자에게 한 답까지 모두 소리 없이 보낸다. 적극 개입 시간대에 비서가 먼저 보내는 말은
     * speak=true 로 보내 앱이 §2.4 조건(무음 아님·통화 아님)을 다시 확인한 뒤 소리로 읽는다.
     */
    public long sendAssistant(String text, String kind, String importance, Long questionId, Long confirmationId,
                              boolean silent, Long interventionId, String action) {
        TimeWindows tw = windows.getIfAvailable();
        Instant now = clock.now();
        boolean dnd = tw != null && tw.dndAt(now).isPresent();
        if (dnd) {
            silent = true;
        }
        // 2026-11-07(사용자 요청): 적극 개입 시간대에는 먼저 거는 말만이 아니라 알림·확인·답까지 사람처럼 소리로 말한다
        // ('저장됨' 줄(system)·음성 탭·풀스크린 대화 답은 빼고). 무음·진동이면 앱이 말하지 않는다(§2.4 그대로).
        boolean speak = !silent && !dnd && interventionId == null && !"system".equals(kind) && tw != null
                && store.getIfAvailable() != null && tw.activeEffective(now, store.getObject());
        long id = db.insert("""
                        INSERT INTO messages(role, content, kind, importance, silent, question_id, confirmation_id,
                        intervention_id, action, created_at) VALUES ('assistant', ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                text, kind, importance, silent, questionId, confirmationId, interventionId, action, now);
        Map<String, String> data = new LinkedHashMap<>();
        data.put("type", "chat.message");
        data.put("message_id", String.valueOf(id));
        data.put("silent", String.valueOf(silent));
        data.put("channel", "checkin".equals(kind) ? "checkin" : "chat");
        if (interventionId != null) {
            data.put("intervention_id", String.valueOf(interventionId));
            data.put("text", text.length() > 1000 ? text.substring(0, 1000) : text);
        }
        data.put("preview", text.length() > 60 ? text.substring(0, 60) : text);
        if (speak) {
            data.put("speak", "true");
            data.put("text", text.length() > 1000 ? text.substring(0, 1000) : text);
        }
        push.pushAll(data);
        return id;
    }

    /**
     * v3.3 §1.2: 방해 금지 일정 중에는 저장만 하고 알리지 않는다(채팅에도 안 보인다). 일정이 끝나면 InterruptPolicy 가
     * 묶음 메시지로 보낸다.
     */
    public long holdAssistant(String text, String kind, String importance, Long questionId, Long confirmationId,
                              long heldBlockId) {
        return db.insert("""
                        INSERT INTO messages(role, content, kind, importance, silent, question_id, confirmation_id,
                        held_block_id, created_at) VALUES ('assistant', ?, ?, ?, TRUE, ?, ?, ?, ?)""",
                text, kind, importance, questionId, confirmationId, heldBlockId, clock.now());
    }

    /** v3.6: 방해 금지 시간대에 모아 둔다(채팅에도 안 보인다). 끝나면 TimeWindowRunner 가 묶음으로 보낸다. */
    public long holdAssistantWindow(String text, String kind, String importance, Long questionId, Long confirmationId,
                                    long heldWindowId) {
        return db.insert("""
                        INSERT INTO messages(role, content, kind, importance, silent, question_id, confirmation_id,
                        held_window_id, created_at) VALUES ('assistant', ?, ?, ?, TRUE, ?, ?, ?, ?)""",
                text, kind, importance, questionId, confirmationId, heldWindowId, clock.now());
    }

    public long askUser(String text, List<String> options) {
        return askUser(text, options, null);
    }

    /** heldBlockId 가 있으면 방해 금지 일정이 끝날 때까지 모아 둔다. */
    public long askUser(String text, List<String> options, Long heldBlockId) {
        return askUser(text, options, heldBlockId, null);
    }

    /** heldWindowId(v3.6): 방해 금지 시간대가 끝날 때까지 모아 둔다. */
    public long askUser(String text, List<String> options, Long heldBlockId, Long heldWindowId) {
        ArrayNode opts = Json.array();
        options.forEach(opts::add);
        long questionId = db.insert("INSERT INTO questions(text, options, asked_at) VALUES (?, ?, ?)",
                text, options.isEmpty() ? null : Json.write(opts), clock.now());
        if (heldWindowId != null) {
            holdAssistantWindow(text, "question", "normal", questionId, null, heldWindowId);
        } else if (heldBlockId != null) {
            holdAssistant(text, "question", "normal", questionId, null, heldBlockId);
        } else {
            sendAssistant(text, "question", "normal", questionId, null);
        }
        return questionId;
    }

    public Map<String, Object> list(long afterId) {
        String sql = afterId > 0
                ? "SELECT * FROM (SELECT * FROM messages WHERE id > ? AND held_block_id IS NULL AND held_window_id IS NULL ORDER BY id LIMIT 200) ORDER BY id"
                : "SELECT * FROM (SELECT * FROM messages WHERE held_block_id IS NULL AND held_window_id IS NULL ORDER BY id DESC LIMIT 100) ORDER BY id";
        var spec = db.jdbc().sql(sql);
        if (afterId > 0) {
            spec = spec.param(afterId);
        }
        List<Map<String, Object>> messages = new ArrayList<>(spec.query((rs, n) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getLong("id"));
            m.put("role", rs.getString("role"));
            m.put("content", rs.getString("content"));
            m.put("kind", rs.getString("kind"));
            m.put("importance", rs.getString("importance"));
            m.put("silent", rs.getBoolean("silent"));
            Long intervention = AssistantDb.nullableLong(rs, "intervention_id");
            if (intervention != null) {
                m.put("intervention_id", intervention);
            }
            Long goal = AssistantDb.nullableLong(rs, "goal_id");
            if (goal != null) {
                m.put("goal_id", goal);
            }
            Long document = AssistantDb.nullableLong(rs, "document_id");
            if (document != null) {
                m.put("document_id", document);
            }
            if (rs.getString("source") != null) {
                m.put("source", rs.getString("source"));
            }
            Long q = AssistantDb.nullableLong(rs, "question_id");
            Long c = AssistantDb.nullableLong(rs, "confirmation_id");
            if (q != null) {
                m.put("question_id", q);
            }
            if (c != null) {
                m.put("confirmation_id", c);
            }
            Long card = AssistantDb.nullableLong(rs, "card_id");
            if (card != null) {
                m.put("card_id", card);
            }
            if (rs.getString("action") != null) {
                m.put("action", rs.getString("action"));
            }
            if (!"chat".equals(rs.getString("channel"))) {
                m.put("channel", rs.getString("channel"));
            }
            m.put("read", rs.getObject("read_at") != null);
            m.put("created_at", time.iso(AssistantDb.instant(rs, "created_at")));
            return m;
        }).list());
        for (Map<String, Object> m : messages) {
            if (m.containsKey("question_id")) {
                db.jdbc().sql("SELECT options, status FROM questions WHERE id = ?").param(m.get("question_id"))
                        .query((rs, n) -> {
                            if (rs.getString(1) != null) {
                                m.put("options", Json.parse(rs.getString(1)));
                            }
                            m.put("question_status", rs.getString(2));
                            return null;
                        }).list();
            }
            if (m.containsKey("card_id")) {
                // v3.5 §10.1: 정리 카드·아침 카드
                db.jdbc().sql("SELECT kind, logical_day, evening, status, payload FROM cards WHERE id = ?").param(m.get("card_id"))
                        .query((rs, n) -> {
                            Map<String, Object> c = new LinkedHashMap<>();
                            c.put("id", m.get("card_id"));
                            c.put("kind", rs.getString(1));
                            c.put("logical_day", rs.getObject(2, java.time.LocalDate.class).toString());
                            c.put("evening", rs.getBoolean(3));
                            c.put("status", rs.getString(4));
                            c.put("payload", Json.parse(rs.getString(5)));
                            m.put("card", c);
                            return null;
                        }).list();
            }
            if (m.containsKey("confirmation_id")) {
                db.jdbc().sql("SELECT status FROM confirmations WHERE id = ?").param(m.get("confirmation_id"))
                        .query(String.class).optional().ifPresent(s -> m.put("confirmation_status", s));
            }
        }
        return Map.of("messages", messages);
    }

    public void markRead(long id) {
        db.update("UPDATE messages SET read_at = ? WHERE id = ? AND read_at IS NULL", clock.now(), id);
    }

    /** 오늘(logical day) 보낸 먼저 말 걸기 메시지 수와 그중 알림이 간 high 수. [count, high] */
    public int[] proactiveToday() {
        Instant dayStart = time.activeWindow(time.logicalDay(clock.now())).start();
        return db.jdbc().sql("""
                        SELECT COUNT(*), COALESCE(SUM(CASE WHEN importance = 'high' AND silent = FALSE THEN 1 ELSE 0 END), 0)
                        FROM messages WHERE role = 'assistant' AND kind IN ('proactive', 'briefing', 'checkin')
                        AND held_block_id IS NULL AND held_window_id IS NULL AND created_at >= ?""")
                .param(AssistantDb.ts(dayStart))
                .query((rs, n) -> new int[]{rs.getInt(1), rs.getInt(2)}).single();
    }

    /** 오늘(logical day) 시작된 turn 수. */
    public int turnsToday() {
        Instant dayStart = time.activeWindow(time.logicalDay(clock.now())).start();
        return db.jdbc().sql("""
                        SELECT COUNT(*) FROM turns WHERE status <> 'queued' AND trigger NOT IN ('document_process', 'memory_job', 'voice')
                        AND started_at >= ?""")
                .param(AssistantDb.ts(dayStart)).query(Integer.class).single();
    }
}
