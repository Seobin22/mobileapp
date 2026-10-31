package com.seobin.phonedrop.assistant.agent.llm;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.seobin.phonedrop.assistant.Json;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * OpenAI 호환 Chat Completions(FactChat 게이트웨이의 GPT·Gemini 등) 수동 도구 루프.
 * - tools.json 의 input_schema 를 function.parameters 로 옮긴다.
 * - 받은 assistant 메시지(content·tool_calls)를 그대로 다음 요청에 붙인다(Gemini 의 thought signature 등 보존).
 * - 서버 측 웹 검색·프롬프트 캐시 지정은 없다(게이트웨이가 자동 캐시하면 cached_tokens 로 센다).
 * - 출력 토큰 상한은 보내지 않는다(모델마다 max_tokens / max_completion_tokens 가 달라 400 이 난다).
 */
public class OpenAiCompatLlm implements LlmClient {

    private static final int MAX_ATTEMPTS = 2;

    private final HttpClient http;
    private final String baseUrl;
    private final String apiKey;
    private final String model;

    public OpenAiCompatLlm(String baseUrl, String apiKey, String model) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    }

    /** tools.json 정의 → OpenAI function 도구. */
    static ArrayNode toFunctions(JsonNode toolDefinitions) {
        ArrayNode tools = Json.array();
        for (JsonNode def : toolDefinitions) {
            ObjectNode fn = Json.object();
            fn.put("name", def.path("name").asString());
            fn.put("description", def.path("description").asString());
            fn.set("parameters", def.path("input_schema"));
            ObjectNode tool = Json.object();
            tool.put("type", "function");
            tool.set("function", fn);
            tools.add(tool);
        }
        return tools;
    }

    @Override
    public Conversation start(String system, JsonNode toolDefinitions, String userContent, boolean webSearch) {
        ArrayNode messages = Json.array();
        messages.add(message("system", system));
        messages.add(message("user", userContent));
        ArrayNode tools = toFunctions(toolDefinitions);

        return new Conversation() {
            @Override
            public LlmResponse next() {
                ObjectNode body = Json.object();
                body.put("model", model);
                body.set("messages", messages);
                if (!tools.isEmpty()) {
                    body.set("tools", tools);
                }
                JsonNode resp = post("/chat/completions/", body);
                JsonNode choice = resp.path("choices").path(0);
                JsonNode msg = choice.path("message");
                if (msg.isMissingNode()) {
                    throw new LlmException("응답에 choices[0].message 가 없습니다", null);
                }

                ObjectNode echo = Json.object();
                echo.put("role", "assistant");
                echo.set("content", msg.path("content").isString() ? msg.get("content") : null);
                List<ToolCall> calls = new ArrayList<>();
                if (msg.path("tool_calls").isArray() && !msg.get("tool_calls").isEmpty()) {
                    echo.set("tool_calls", msg.get("tool_calls"));
                    for (JsonNode tc : msg.get("tool_calls")) {
                        calls.add(new ToolCall(tc.path("id").asString(), tc.path("function").path("name").asString(),
                                arguments(tc.path("function").path("arguments"))));
                    }
                }
                messages.add(echo);

                JsonNode usage = resp.path("usage");
                long prompt = usage.path("prompt_tokens").asLong(0);
                long cached = usage.path("prompt_tokens_details").path("cached_tokens").asLong(0);
                long output = usage.path("completion_tokens").asLong(0);
                String text = msg.path("content").isString() ? msg.get("content").asString() : "";
                return new LlmResponse(calls, text, choice.path("finish_reason").asString(""),
                        Math.max(0, prompt - cached), output, cached, 0);
            }

            @Override
            public void addToolResults(List<ToolResult> results) {
                for (ToolResult r : results) {
                    ObjectNode m = message("tool", r.content());
                    m.put("tool_call_id", r.toolUseId());
                    messages.add(m);
                }
            }

            @Override
            public void addUserText(String text) {
                messages.add(message("user", text));
            }
        };
    }

    /** 게이트웨이의 모델 id 목록(GET /models/). {data:[{id}]} 또는 [{id}] 둘 다 받는다. */
    public List<String> listModels() {
        JsonNode resp = send(HttpRequest.newBuilder(URI.create(baseUrl + "/models/")).GET());
        JsonNode items = resp.isArray() ? resp : resp.path("data");
        List<String> ids = new ArrayList<>();
        for (JsonNode item : items) {
            String id = item.isString() ? item.asString() : item.path("id").asString("");
            if (!id.isBlank()) {
                ids.add(id);
            }
        }
        return ids;
    }

    private static ObjectNode message(String role, String content) {
        ObjectNode m = Json.object();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    /** function.arguments 는 JSON 문자열. 깨졌으면 빈 객체(도구 검증이 오류를 돌려준다). */
    private static JsonNode arguments(JsonNode raw) {
        if (raw.isObject()) {
            return raw;
        }
        try {
            JsonNode parsed = Json.parse(raw.asString("{}"));
            return parsed.isObject() ? parsed : Json.object();
        } catch (RuntimeException e) {
            return Json.object();
        }
    }

    private JsonNode post(String path, JsonNode body) {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body))));
    }

    /** 429·5xx·네트워크 오류는 한 번 더 시도한다. */
    private JsonNode send(HttpRequest.Builder request) {
        HttpRequest req = request.timeout(Duration.ofSeconds(120))
                .header("Authorization", "Bearer " + apiKey)
                .build();
        LlmException last = null;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            try {
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                int status = resp.statusCode();
                if (status / 100 == 2) {
                    return Json.parse(resp.body());
                }
                String body = resp.body() == null ? "" : resp.body();
                last = new LlmException("HTTP " + status + ": " + body.substring(0, Math.min(body.length(), 300)), null);
                if (status != 429 && status < 500) {
                    break;
                }
            } catch (IOException e) {
                last = new LlmException("요청 실패: " + e.getMessage(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LlmException("요청이 중단되었습니다", e);
            } catch (RuntimeException e) {
                throw new LlmException("응답을 읽을 수 없습니다: " + e.getMessage(), e);
            }
        }
        throw last;
    }
}
