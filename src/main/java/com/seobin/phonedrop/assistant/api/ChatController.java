package com.seobin.phonedrop.assistant.api;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.seobin.phonedrop.assistant.ApiException;
import com.seobin.phonedrop.assistant.AssistantClock;
import com.seobin.phonedrop.assistant.AssistantDb;
import com.seobin.phonedrop.assistant.TimeUtil;
import com.seobin.phonedrop.assistant.Tx;
import com.seobin.phonedrop.assistant.agent.ChatService;
import com.seobin.phonedrop.assistant.agent.ConfirmationService;
import com.seobin.phonedrop.assistant.agent.Timeline;
import com.seobin.phonedrop.assistant.agent.llm.ConfiguredLlm;

import tools.jackson.databind.JsonNode;

/** 채팅 비서 API(빌드문서 v2 §10). 기본 주소 https://test.seobin.page/api/assistant */
@RestController
@RequestMapping("/api/assistant/v1")
public class ChatController {

    private final Tx tx;
    private final AssistantDb db;
    private final AssistantClock clock;
    private final ChatService chat;
    private final ConfirmationService confirmations;
    private final Timeline timeline;
    private final ConfiguredLlm llm;

    public ChatController(Tx tx, AssistantDb db, AssistantClock clock, ChatService chat,
                          ConfirmationService confirmations, Timeline timeline, ConfiguredLlm llm) {
        this.tx = tx;
        this.db = db;
        this.clock = clock;
        this.chat = chat;
        this.confirmations = confirmations;
        this.timeline = timeline;
        this.llm = llm;
    }

    @PostMapping("/messages")
    public ResponseEntity<Map<String, Object>> postMessage(@RequestBody JsonNode body) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(tx.run(() -> chat.receive(body)));
    }

    @GetMapping("/messages")
    public Map<String, Object> messages(@RequestParam(name = "after_id", defaultValue = "0") long afterId) {
        return tx.run(() -> chat.list(afterId));
    }

    @PostMapping("/messages/{id}/read")
    public Map<String, Object> read(@PathVariable long id) {
        tx.exec(() -> chat.markRead(id));
        return Map.of("ok", true);
    }

    @PostMapping("/confirmations/{id}")
    public Map<String, Object> confirm(@PathVariable long id, @RequestBody JsonNode body) {
        if (!body.has("accept") || !body.get("accept").isBoolean()) {
            throw ApiException.badRequest("accept(true/false)가 필요합니다");
        }
        return tx.run(() -> confirmations.resolve(id, body.get("accept").asBoolean()));
    }

    /** 기본: 지금 ~ 7일 뒤. */
    @GetMapping("/timeline")
    public Map<String, Object> timeline(@RequestParam(required = false) String from,
                                        @RequestParam(required = false) String to) {
        Instant f = from == null ? clock.now() : TimeUtil.parse(from);
        Instant t = to == null ? f.plus(Duration.ofDays(7)) : TimeUtil.parse(to);
        if (!t.isAfter(f)) {
            throw ApiException.badRequest("to 는 from 보다 뒤여야 합니다");
        }
        return tx.run(() -> timeline.timeline(f, t, false));
    }

    /** 관리 화면에서 Anthropic API 키 저장. 값은 다시 돌려주지 않는다. */
    @PostMapping("/admin/anthropic_key")
    public Map<String, Object> saveKey(@RequestBody JsonNode body) throws IOException {
        String key = body.path("key").asString("").trim();
        if (key.isEmpty() || key.length() > 500 || key.contains("\n") || key.contains(" ")) {
            throw ApiException.badRequest("API 키 형식이 맞지 않습니다");
        }
        llm.saveKey(key);
        return Map.of("ok", true, "configured", llm.hasKey());
    }

    /** 관리 화면용: 비서 설정 상태와 최근 turn. */
    @GetMapping("/admin/agent")
    public Map<String, Object> agentStatus() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("model", llm.model());
        result.put("api_key_configured", llm.hasKey());
        List<Map<String, Object>> turns = tx.run(() -> db.jdbc().sql("""
                        SELECT id, trigger, status, tool_calls, input_tokens, output_tokens, started_at, error
                        FROM turns ORDER BY id DESC LIMIT 10""")
                .query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("trigger", rs.getString("trigger"));
                    m.put("status", rs.getString("status"));
                    m.put("tool_calls", rs.getInt("tool_calls"));
                    m.put("input_tokens", rs.getObject("input_tokens"));
                    m.put("output_tokens", rs.getObject("output_tokens"));
                    m.put("started_at", String.valueOf(AssistantDb.instant(rs, "started_at")));
                    m.put("error", rs.getString("error"));
                    return m;
                }).list());
        result.put("recent_turns", turns);
        result.put("turns_today", tx.run(chat::turnsToday));
        return result;
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, String>> apiError(ApiException e) {
        return ResponseEntity.status(e.status()).body(Map.of("error", e.getMessage()));
    }
}
