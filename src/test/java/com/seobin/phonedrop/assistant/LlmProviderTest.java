package com.seobin.phonedrop.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.seobin.phonedrop.assistant.agent.llm.AnthropicLlm;
import com.seobin.phonedrop.assistant.agent.llm.ConfiguredLlm;
import com.seobin.phonedrop.assistant.agent.llm.LlmClient;
import com.seobin.phonedrop.assistant.agent.llm.OpenAiCompatLlm;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;

/** 모델 선택: 제공자별 클라이언트, OpenAI 호환 도구 루프, 게이트웨이 요청 모양. 실제 API 는 부르지 않는다. */
class LlmProviderTest {

    @TempDir
    Path dir;

    private HttpServer server;
    private final List<JsonNode> requests = new ArrayList<>();
    private final List<String> authHeaders = new ArrayList<>();
    private final List<String> paths = new ArrayList<>();

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** 요청을 기록하고 replies 를 차례로 돌려주는 가짜 서버. 주소를 돌려준다. */
    private String stub(String... replies) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int[] next = {0};
        server.createContext("/", (HttpExchange ex) -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            synchronized (requests) {
                requests.add(body.isEmpty() ? Json.object() : Json.parse(body));
                authHeaders.add(ex.getRequestHeaders().getFirst("Authorization") + "|" + ex.getRequestHeaders().getFirst("x-api-key"));
                paths.add(ex.getRequestURI().getPath());
            }
            byte[] out = replies[Math.min(next[0]++, replies.length - 1)].getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static JsonNode toolDefs() {
        return Json.parse("""
                [{"name":"send_message","description":"메시지 보내기",
                  "input_schema":{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}}]""");
    }

    @Test
    void openAiCompatToolLoop() throws IOException {
        String base = stub("""
                {"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":null,
                  "tool_calls":[{"id":"call_1","type":"function","function":{"name":"send_message","arguments":"{\\"text\\":\\"안녕\\"}"},
                                 "extra_content":{"google":{"thought_signature":"sig"}}}]}}],
                 "usage":{"prompt_tokens":1000,"completion_tokens":50,"prompt_tokens_details":{"cached_tokens":600}}}""",
                """
                {"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"끝"}}],
                 "usage":{"prompt_tokens":1200,"completion_tokens":5}}""");
        LlmClient llm = new OpenAiCompatLlm(base + "/v1/gateway", "k-123", "gpt-5");

        LlmClient.Conversation conv = llm.start("시스템", toolDefs(), "사용자", true);
        LlmClient.LlmResponse first = conv.next();
        assertEquals(1, first.toolCalls().size());
        assertEquals("send_message", first.toolCalls().get(0).name());
        assertEquals("안녕", first.toolCalls().get(0).input().path("text").asString());
        assertEquals(400, first.inputTokens());
        assertEquals(600, first.cacheReadTokens());
        assertEquals(50, first.outputTokens());

        conv.addToolResults(List.of(new LlmClient.ToolResult("call_1", "{\"ok\":true}", false)));
        LlmClient.LlmResponse second = conv.next();
        assertEquals("끝", second.text());
        assertTrue(second.toolCalls().isEmpty());

        assertEquals("/v1/gateway/chat/completions/", paths.get(0));
        assertEquals("Bearer k-123|null", authHeaders.get(0));
        JsonNode req1 = requests.get(0);
        assertEquals("gpt-5", req1.path("model").asString());
        assertEquals("system", req1.path("messages").path(0).path("role").asString());
        JsonNode fn = req1.path("tools").path(0);
        assertEquals("function", fn.path("type").asString());
        assertEquals("send_message", fn.path("function").path("name").asString());
        assertEquals("object", fn.path("function").path("parameters").path("type").asString());
        assertFalse(req1.has("max_tokens"));

        // 두 번째 요청: assistant 메시지를 그대로(서명 포함) 붙이고 도구 결과를 tool 메시지로.
        JsonNode msgs = requests.get(1).path("messages");
        assertEquals(4, msgs.size());
        assertEquals("sig", msgs.path(2).path("tool_calls").path(0).path("extra_content").path("google")
                .path("thought_signature").asString());
        assertEquals("tool", msgs.path(3).path("role").asString());
        assertEquals("call_1", msgs.path(3).path("tool_call_id").asString());
    }

    @Test
    void openAiCompatErrorBecomesLlmException() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            byte[] out = "{\"detail\":\"Model not found\"}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(404, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        LlmClient llm = new OpenAiCompatLlm("http://127.0.0.1:" + server.getAddress().getPort(), "k", "nope");
        LlmClient.LlmException e = assertThrows(LlmClient.LlmException.class,
                () -> llm.start("s", Json.array(), "u").next());
        assertTrue(e.getMessage().contains("404"));
    }

    @Test
    void listModelsReadsDataIds() throws IOException {
        String base = stub("{\"object\":\"list\",\"data\":[{\"id\":\"claude-sonnet-5\"},{\"id\":\"gpt-5\"}]}");
        assertEquals(List.of("claude-sonnet-5", "gpt-5"), new OpenAiCompatLlm(base, "k", "").listModels());
        assertEquals("/models/", paths.get(0));
    }

    @Test
    void gatewayClaudeOmitsAnthropicOnlyFeatures() throws IOException {
        String base = stub("""
                {"id":"msg_1","type":"message","role":"assistant","model":"claude-sonnet-5-5",
                 "content":[{"type":"text","text":"연결됨"}],"stop_reason":"end_turn","stop_sequence":null,
                 "usage":{"input_tokens":10,"output_tokens":3}}""");
        LlmClient llm = new AnthropicLlm("k-9", "claude-sonnet-5-5", base + "/claude");
        LlmClient.LlmResponse resp = llm.start("s", toolDefs(), "u", true).next();
        assertEquals("연결됨", resp.text());

        assertEquals("/claude/v1/messages", paths.get(0));
        assertEquals("Bearer k-9|k-9", authHeaders.get(0));
        JsonNode req = requests.get(0);
        assertFalse(req.has("fallbacks"));
        assertEquals("medium", req.path("output_config").path("effort").asString());
        assertEquals(1, req.path("tools").size()); // web_search 없음
    }

    @Test
    void olderClaudeModelGetsNoEffort() throws IOException {
        String base = stub("""
                {"id":"msg_1","type":"message","role":"assistant","model":"claude-haiku-4-5",
                 "content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn","stop_sequence":null,
                 "usage":{"input_tokens":10,"output_tokens":3}}""");
        new AnthropicLlm("k", "claude-haiku-4-5", base).start("s", toolDefs(), "u", true).next();
        assertFalse(requests.get(0).has("output_config"));
        assertEquals(1, requests.get(0).path("tools").size());
    }

    @Test
    void configuredLlmFollowsSettingsAndKeys() throws IOException {
        AtomicReference<String> provider = new AtomicReference<>("");
        AtomicReference<String> model = new AtomicReference<>("");
        ConfiguredLlm llm = new ConfiguredLlm(dir, "", "claude-sonnet-5-5", provider::get, model::get);

        assertEquals("anthropic", llm.provider());
        assertEquals("claude-sonnet-5-5", llm.model());
        assertFalse(llm.hasKey());
        assertThrows(ConfiguredLlm.NotConfiguredException.class, () -> llm.start("s", Json.array(), "u"));

        provider.set("factchat");
        model.set("gpt-5");
        llm.saveKey("factchat", "fc-key");
        assertTrue(llm.hasKey());
        assertFalse(llm.hasKey("anthropic"));
        assertEquals("fc-key", Files.readString(dir.resolve("factchat_api_key")).trim());
        assertEquals("openai-chat(factchat)", ConfiguredLlm.protocol("factchat", "gpt-5"));
        assertEquals("anthropic-messages(factchat)", ConfiguredLlm.protocol("factchat", "claude-opus-5"));
        assertEquals("anthropic-messages", ConfiguredLlm.protocol("anthropic", "claude-opus-5"));
    }

    @Test
    void createPicksClientByProviderAndModel() throws Exception {
        var create = ConfiguredLlm.class.getDeclaredMethod("create", String.class, String.class, String.class);
        create.setAccessible(true);
        assertInstanceOf(OpenAiCompatLlm.class, create.invoke(null, "factchat", "gemini-3-pro", "k"));
        assertInstanceOf(AnthropicLlm.class, create.invoke(null, "factchat", "claude-sonnet-5", "k"));
        assertInstanceOf(AnthropicLlm.class, create.invoke(null, "anthropic", "claude-sonnet-5-5", "k"));
    }
}
