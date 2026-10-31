package com.seobin.phonedrop.assistant.agent.llm;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicException;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.WebSearchTool20260209;
import com.seobin.phonedrop.assistant.Json;

import tools.jackson.databind.JsonNode;

/**
 * Claude Messages API(공식 Java SDK) 수동 도구 루프.
 * - 응답(Message)을 그대로 다음 요청에 붙인다(thinking 블록 보존).
 * - system·도구 정의에 프롬프트 캐시를 건다(매 turn 같은 앞부분).
 * - 거절(refusal) 시 서버 측 대체 모델(fallbacks: "default")을 쓴다.
 * - 강제 tool_choice 는 쓰지 않는다(Sonnet 5.5 는 400).
 * - 웹 검색은 서버 측 도구(web_search_20260209, max_uses 5). 서버 루프가 길어 pause_turn 으로 멈추면
 *   응답을 그대로 붙여 다시 요청한다("계속" 같은 사용자 메시지를 넣지 않는다).
 * - 게이트웨이(FactChat 등 baseUrl 지정): Anthropic 전용 기능(대체 모델 fallbacks, 서버 측 웹 검색)은 쓰지 않는다.
 *   인증은 x-api-key 와 Authorization: Bearer 를 함께 보낸다(게이트웨이마다 받는 헤더가 다르다).
 * - effort·웹 검색·fallbacks 는 지원하는 모델에만 붙인다(지원하지 않는 모델은 400).
 */
public class AnthropicLlm implements LlmClient {

    private static final long MAX_TOKENS = 8000;
    private static final long WEB_SEARCH_MAX_USES = 5;
    private static final int MAX_PAUSE_CONTINUATIONS = 5;

    private final AnthropicClient client;
    private final String model;
    private final boolean gateway;

    public AnthropicLlm(String apiKey, String model) {
        this(apiKey, model, null);
    }

    /** baseUrl 이 null 이면 Anthropic 공식 API, 아니면 Anthropic Messages 호환 게이트웨이. */
    public AnthropicLlm(String apiKey, String model, String baseUrl) {
        this.model = model;
        this.gateway = baseUrl != null;
        AnthropicOkHttpClient.Builder builder = AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .timeout(Duration.ofSeconds(gateway ? 120 : 60))
                .maxRetries(1);
        if (gateway) {
            builder.baseUrl(baseUrl).authToken(apiKey);
        }
        this.client = builder.build();
    }

    /** output_config.effort 를 받는 모델(Opus 4.5+, Sonnet 4.6+, 5세대 이후). */
    static boolean supportsEffort(String model) {
        return model.matches("claude-(opus|sonnet|haiku|fable|mythos)-([5-9]|[1-9][0-9]).*")
                || model.matches("claude-opus-4-[5-9].*") || model.matches("claude-sonnet-4-[6-9].*");
    }

    /** web_search_20260209(동적 필터링)를 받는 모델. */
    static boolean supportsWebSearch(String model) {
        return model.matches("claude-(opus|sonnet|fable)-([5-9]|[1-9][0-9]).*")
                || model.matches("claude-(opus|sonnet)-4-[6-9].*");
    }

    /** 서버 측 대체 모델(fallbacks: "default")을 받는 모델(Claude API 에서만). */
    static boolean supportsFallbacks(String model) {
        return model.matches("claude-(fable-5-1|opus-5-5|opus-5|sonnet-5-5)");
    }

    @Override
    public Conversation start(String system, JsonNode toolDefinitions, String userContent, boolean webSearch) {
        MessageCreateParams.Builder builder = MessageCreateParams.builder()
                .model(model)
                .maxTokens(MAX_TOKENS)
                .systemOfTextBlockParams(List.of(TextBlockParam.builder()
                        .text(system)
                        .cacheControl(CacheControlEphemeral.builder().build())
                        .build()));
        if (supportsEffort(model)) {
            builder.outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.MEDIUM).build());
        }
        if (!gateway && supportsFallbacks(model)) {
            builder.putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                    .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
        }

        List<JsonNode> defs = new ArrayList<>();
        toolDefinitions.forEach(defs::add);
        for (int i = 0; i < defs.size(); i++) {
            builder.addTool(tool(defs.get(i), i == defs.size() - 1));
        }
        if (webSearch && !gateway && supportsWebSearch(model)) {
            // 캐시 지점(마지막 사용자 도구) 뒤에 둔다. 켜고 끄는 것이 앞부분 캐시를 깨지 않도록.
            builder.addTool(WebSearchTool20260209.builder().maxUses(WEB_SEARCH_MAX_USES).build());
        }
        builder.addUserMessage(userContent);

        return new Conversation() {
            @Override
            public LlmResponse next() {
                List<ToolCall> calls = new ArrayList<>();
                StringBuilder text = new StringBuilder();
                long[] usage = new long[4];
                String stop = "";
                for (int round = 0; round <= MAX_PAUSE_CONTINUATIONS; round++) {
                    Message message;
                    try {
                        message = client.messages().create(builder.build());
                    } catch (AnthropicException e) {
                        throw new LlmException("Claude 호출 실패: " + e.getMessage(), e);
                    } catch (RuntimeException e) {
                        throw new LlmException("Claude 호출 실패: " + e.getMessage(), e);
                    }
                    builder.addMessage(message);
                    // server_tool_use·web_search_tool_result 블록은 그대로 대화에 남고 여기서는 건너뛴다.
                    for (ContentBlock block : message.content()) {
                        block.toolUse().ifPresent(t -> calls.add(new ToolCall(t.id(), t.name(),
                                Json.MAPPER.valueToTree(t._input().convert(Object.class)))));
                        block.text().ifPresent(t -> text.append(t.text()));
                    }
                    usage[0] += message.usage().inputTokens();
                    usage[1] += message.usage().outputTokens();
                    usage[2] += message.usage().cacheReadInputTokens().orElse(0L);
                    usage[3] += message.usage().cacheCreationInputTokens().orElse(0L);
                    stop = message.stopReason().map(StopReason::asString).orElse("");
                    if (!"pause_turn".equals(stop)) {
                        break;
                    }
                }
                return new LlmResponse(calls, text.toString(), stop, usage[0], usage[1], usage[2], usage[3]);
            }

            @Override
            public void addToolResults(List<ToolResult> results) {
                List<ContentBlockParam> blocks = new ArrayList<>();
                for (ToolResult r : results) {
                    blocks.add(ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                            .toolUseId(r.toolUseId())
                            .content(r.content())
                            .isError(r.isError())
                            .build()));
                }
                builder.addUserMessageOfBlockParams(blocks);
            }

            @Override
            public void addUserText(String text) {
                builder.addUserMessage(text);
            }
        };
    }

    /** tools.json 의 정의 하나를 SDK Tool 로. 마지막 도구에 캐시 표시를 달아 도구 목록 전체를 캐시한다. */
    private static Tool tool(JsonNode def, boolean cacheBreakpoint) {
        JsonNode schema = def.path("input_schema");
        Tool.InputSchema.Properties.Builder props = Tool.InputSchema.Properties.builder();
        schema.path("properties").properties().forEach(e ->
                props.putAdditionalProperty(e.getKey(), JsonValue.from(Json.MAPPER.convertValue(e.getValue(), Map.class))));
        List<String> required = new ArrayList<>();
        schema.path("required").forEach(r -> required.add(r.asString()));
        Tool.Builder tool = Tool.builder()
                .name(def.path("name").asString())
                .description(def.path("description").asString())
                .inputSchema(Tool.InputSchema.builder().properties(props.build()).required(required).build());
        if (cacheBreakpoint) {
            tool.cacheControl(CacheControlEphemeral.builder().build());
        }
        return tool.build();
    }
}
