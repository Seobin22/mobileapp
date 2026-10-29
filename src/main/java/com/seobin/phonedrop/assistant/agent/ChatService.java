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
import com.seobin.phonedrop.assistant.Jobs;
import com.seobin.phonedrop.assistant.Json;
import com.seobin.phonedrop.assistant.TimeUtil;
import com.seobin.phonedrop.assistant.push.PushService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** 채팅 메시지·질문·turn 예약(빌드문서 v2 §5.4, §8.1, §10). */
@Service
public class ChatService {

    public static final String TURN_JOB = "turn";
    public static final int PRIORITY_USER = 10;
    public static final int PRIORITY_DEFAULT = 5;

    private final AssistantDb db;
    private final AssistantClock clock;
    private final Jobs jobs;
    private final PushService push;
    private final TimeUtil time;

    public ChatService(AssistantDb db, AssistantClock clock, Jobs jobs, PushService push, TimeUtil time) {
        this.db = db;
        this.clock = clock;
        this.jobs = jobs;
        this.push = push;
        this.time = time;
    }

    /** turns 행(queued)을 만들고 turn 작업을 예약한다. */
    public long enqueueTurn(String trigger, JsonNode ref, int priority) {
        long turnId = db.insert("INSERT INTO turns(trigger, trigger_ref, status, started_at) VALUES (?, ?, 'queued', ?)",
                trigger, Json.write(ref), clock.now());
        jobs.schedule(TURN_JOB, turnId, clock.now(), null, Json.object(), priority);
        return turnId;
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
        long messageId = db.insert("""
                        INSERT INTO messages(role, content, kind, source, client_msg_id, created_at)
                        VALUES ('user', ?, 'reply', ?, ?, ?)""",
                text, source, clientMsgId.isEmpty() ? null : clientMsgId, clock.now());
        long questionId = req.path("answered_question_id").asLong(0);
        if (questionId > 0) {
            db.update("UPDATE questions SET status = 'answered', answered_at = ? WHERE id = ? AND status = 'open'",
                    clock.now(), questionId);
        }
        ObjectNode ref = Json.object();
        ref.put("message_id", messageId);
        long turnId = enqueueTurn("user_message", ref, PRIORITY_USER);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("message_id", messageId);
        result.put("turn_id", turnId);
        return result;
    }

    /** 비서 메시지 저장 + chat.message 푸시. v2 는 silent 가 항상 false. */
    public long sendAssistant(String text, String kind, String importance, Long questionId, Long confirmationId) {
        long id = db.insert("""
                        INSERT INTO messages(role, content, kind, importance, silent, question_id, confirmation_id, created_at)
                        VALUES ('assistant', ?, ?, ?, FALSE, ?, ?, ?)""",
                text, kind, importance, questionId, confirmationId, clock.now());
        Map<String, String> data = new LinkedHashMap<>();
        data.put("type", "chat.message");
        data.put("message_id", String.valueOf(id));
        data.put("silent", "false");
        data.put("preview", text.length() > 60 ? text.substring(0, 60) : text);
        push.pushAll(data);
        return id;
    }

    public long askUser(String text, List<String> options) {
        ArrayNode opts = Json.array();
        options.forEach(opts::add);
        long questionId = db.insert("INSERT INTO questions(text, options, asked_at) VALUES (?, ?, ?)",
                text, options.isEmpty() ? null : Json.write(opts), clock.now());
        sendAssistant(text, "question", "normal", questionId, null);
        return questionId;
    }

    public Map<String, Object> list(long afterId) {
        String sql = afterId > 0
                ? "SELECT * FROM (SELECT * FROM messages WHERE id > ? ORDER BY id LIMIT 200) ORDER BY id"
                : "SELECT * FROM (SELECT * FROM messages ORDER BY id DESC LIMIT 100) ORDER BY id";
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
            Long q = AssistantDb.nullableLong(rs, "question_id");
            Long c = AssistantDb.nullableLong(rs, "confirmation_id");
            if (q != null) {
                m.put("question_id", q);
            }
            if (c != null) {
                m.put("confirmation_id", c);
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

    /** 오늘(logical day) 시작된 turn 수. */
    public int turnsToday() {
        Instant dayStart = time.activeWindow(time.logicalDay(clock.now())).start();
        return db.jdbc().sql("SELECT COUNT(*) FROM turns WHERE status <> 'queued' AND started_at >= ?")
                .param(AssistantDb.ts(dayStart)).query(Integer.class).single();
    }
}
