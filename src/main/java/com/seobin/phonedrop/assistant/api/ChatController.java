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
import com.seobin.phonedrop.assistant.Json;
import com.seobin.phonedrop.assistant.SettingsService;
import com.seobin.phonedrop.assistant.TimeUtil;
import com.seobin.phonedrop.assistant.Tx;
import com.seobin.phonedrop.assistant.agent.ChatService;
import com.seobin.phonedrop.assistant.agent.ConfirmationService;
import com.seobin.phonedrop.assistant.agent.Timeline;
import com.seobin.phonedrop.assistant.agent.llm.ConfiguredLlm;
import com.seobin.phonedrop.assistant.agent.llm.LlmClient;

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
    private final SettingsService settings;
    private final TimeUtil time;

    public ChatController(Tx tx, AssistantDb db, AssistantClock clock, ChatService chat,
                          ConfirmationService confirmations, Timeline timeline, ConfiguredLlm llm,
                          SettingsService settings, TimeUtil time) {
        this.tx = tx;
        this.db = db;
        this.clock = clock;
        this.chat = chat;
        this.confirmations = confirmations;
        this.timeline = timeline;
        this.llm = llm;
        this.settings = settings;
        this.time = time;
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

    /** 작업물(빌드문서 v3 §7). */
    @GetMapping("/work_products/{id}")
    public Map<String, Object> workProduct(@PathVariable long id) {
        return tx.run(() -> db.jdbc().sql("SELECT * FROM work_products WHERE id = ?").param(id)
                .query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("task_id", rs.getLong("task_id"));
                    m.put("title", rs.getString("title"));
                    m.put("kind", rs.getString("kind"));
                    m.put("content_md", rs.getString("content_md"));
                    m.put("sources", Json.parse(rs.getString("sources")));
                    m.put("created_at", time.iso(AssistantDb.instant(rs, "created_at")));
                    return m;
                }).optional())
                .orElseThrow(() -> ApiException.notFound("작업물이 없습니다: " + id));
    }

    /** 관리 화면용: 먼저 말 걸기 상태(메시지 예산, 예약된 wakeup, 아침·저녁 완료, 최근 작업물). */
    @GetMapping("/admin/proactive")
    public Map<String, Object> proactiveStatus() {
        return tx.run(() -> {
            Map<String, Object> result = new LinkedHashMap<>();
            int[] sent = chat.proactiveToday();
            result.put("messages_today", sent[0]);
            result.put("daily_budget", settings.getInt("messages.daily_budget"));
            result.put("high_today", sent[1]);
            result.put("high_per_day", settings.getInt("messages.high_per_day"));
            result.put("turns_today", chat.turnsToday());
            result.put("turn_soft_limit", settings.getInt("agent.daily_turn_soft"));
            result.put("wakeups", db.jdbc().sql("""
                            SELECT w.id, w.fire_at, w.reason, w.task_id, w.status, t.title FROM wakeups w
                            LEFT JOIN tasks t ON t.id = w.task_id
                            WHERE w.status = 'scheduled' OR w.updated_at >= ? ORDER BY w.fire_at DESC LIMIT 30""")
                    .param(AssistantDb.ts(clock.now().minus(Duration.ofDays(1))))
                    .query((rs, n) -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("id", rs.getLong("id"));
                        m.put("fire_at", time.iso(AssistantDb.instant(rs, "fire_at")));
                        m.put("reason", rs.getString("reason"));
                        m.put("task_id", rs.getObject("task_id"));
                        m.put("task_title", rs.getString("title"));
                        m.put("status", rs.getString("status"));
                        return m;
                    }).list());
            result.put("daily_flags", db.jdbc().sql(
                            "SELECT * FROM daily_flags ORDER BY logical_day DESC LIMIT 3")
                    .query((rs, n) -> Map.of("logical_day", rs.getString("logical_day"),
                            "morning_done", rs.getBoolean("morning_done"),
                            "evening_done", rs.getBoolean("evening_done"))).list());
            result.put("work_products", db.jdbc().sql("""
                            SELECT w.id, w.task_id, w.title, w.kind, w.created_at,
                            (SELECT MIN(b.id) FROM blocks b WHERE b.work_product_id = w.id) block_id
                            FROM work_products w ORDER BY w.id DESC LIMIT 20""")
                    .query((rs, n) -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("id", rs.getLong("id"));
                        m.put("task_id", rs.getLong("task_id"));
                        m.put("title", rs.getString("title"));
                        m.put("kind", rs.getString("kind"));
                        m.put("block_id", rs.getObject("block_id"));
                        m.put("created_at", time.iso(AssistantDb.instant(rs, "created_at")));
                        return m;
                    }).list());
            return result;
        });
    }

    /** 관리 화면에서 Anthropic API 키 저장. 값은 다시 돌려주지 않는다. */
    @PostMapping("/admin/anthropic_key")
    public Map<String, Object> saveKey(@RequestBody JsonNode body) throws IOException {
        String key = body.path("key").asString("").trim();
        if (key.isEmpty() || key.length() > 500 || key.contains("\n") || key.contains(" ")) {
            throw ApiException.badRequest("API 키 형식이 맞지 않습니다");
        }
        llm.saveKey(key);
        return Map.of("ok", true, "configured", llm.hasKey(ConfiguredLlm.ANTHROPIC));
    }

    /** 관리 화면에서 제공자별 API 키 저장(anthropic | factchat). 값은 다시 돌려주지 않는다. */
    @PostMapping("/admin/llm_key")
    public Map<String, Object> saveProviderKey(@RequestBody JsonNode body) throws IOException {
        String provider = body.path("provider").asString("");
        String key = body.path("key").asString("").trim();
        if (!ConfiguredLlm.PROVIDERS.contains(provider)) {
            throw ApiException.badRequest("알 수 없는 제공자입니다");
        }
        if (key.isEmpty() || key.length() > 500 || key.contains("\n") || key.contains(" ")) {
            throw ApiException.badRequest("API 키 형식이 맞지 않습니다");
        }
        llm.saveKey(provider, key);
        return Map.of("ok", true, "configured", llm.hasKey(provider));
    }

    /** 관리 화면에서 제공자·모델 변경. model 이 비면 서버 기본 모델. 다음 turn 부터 적용된다. */
    @PostMapping("/admin/llm")
    public Map<String, Object> saveLlm(@RequestBody JsonNode body) {
        String provider = body.path("provider").asString("");
        String model = body.path("model").asString("").trim();
        tx.exec(() -> {
            settings.put("llm.provider", Json.MAPPER.valueToTree(provider));
            settings.put("llm.model", Json.MAPPER.valueToTree(model));
        });
        return Map.of("ok", true, "provider", llm.provider(), "model", llm.model());
    }

    /** 게이트웨이(FactChat)의 모델 목록. */
    @GetMapping("/admin/llm/models")
    public Map<String, Object> llmModels(@RequestParam(defaultValue = ConfiguredLlm.FACTCHAT) String provider) {
        try {
            return Map.of("models", llm.listModels(provider));
        } catch (ConfiguredLlm.NotConfiguredException | LlmClient.LlmException e) {
            throw ApiException.unprocessable(e.getMessage());
        }
    }

    /** 지금 설정으로 짧은 요청을 한 번 보내 본다(도구 없음, 비용 아주 적음). */
    @PostMapping("/admin/llm/test")
    public Map<String, Object> testLlm() {
        long started = System.nanoTime();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("provider", llm.provider());
        result.put("model", llm.model());
        try {
            LlmClient.LlmResponse resp = llm.start("짧게 답한다.", Json.array(), "연결 확인입니다. '연결됨' 한 단어로만 답하세요.")
                    .next();
            result.put("ok", true);
            result.put("text", resp.text().length() > 200 ? resp.text().substring(0, 200) : resp.text());
            result.put("input_tokens", resp.inputTokens());
            result.put("output_tokens", resp.outputTokens());
        } catch (ConfiguredLlm.NotConfiguredException | LlmClient.LlmException e) {
            result.put("ok", false);
            result.put("error", e.getMessage());
        }
        result.put("ms", (System.nanoTime() - started) / 1_000_000);
        return result;
    }

    /** 관리 화면용: 비서 설정 상태와 최근 turn. */
    @GetMapping("/admin/agent")
    public Map<String, Object> agentStatus() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("provider", llm.provider());
        result.put("model", llm.model());
        result.put("default_model", llm.defaultModel());
        result.put("protocol", ConfiguredLlm.protocol(llm.provider(), llm.model()));
        result.put("keys", llm.keyStatus());
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
