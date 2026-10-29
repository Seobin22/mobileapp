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
import com.seobin.phonedrop.assistant.Json;

import tools.jackson.databind.JsonNode;

/**
 * Claude Messages API(공식 Java SDK) 수동 도구 루프.
 * - 응답(Message)을 그대로 다음 요청에 붙인다(thinking 블록 보존).
 * - system·도구 정의에 프롬프트 캐시를 건다(매 turn 같은 앞부분).
 * - 거절(refusal) 시 서버 측 대체 모델(fallbacks: "default")을 쓴다.
 * - 강제 tool_choice 는 쓰지 않는다(Sonnet 5.5 는 400).
 */
public class AnthropicLlm implements LlmClient {

    private static final long MAX_TOKENS = 8000;

    private final AnthropicClient client;
    private final String model;

    public AnthropicLlm(String apiKey, String model) {
        this.model = model;
        this.client = AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .timeout(Duration.ofSeconds(60))
                .maxRetries(1)
                .build();
    }

    @Override
    public Conversation start(String system, JsonNode toolDefinitions, String userContent) {
        MessageCreateParams.Builder builder = MessageCreateParams.builder()
                .model(model)
                .maxTokens(MAX_TOKENS)
                .systemOfTextBlockParams(List.of(TextBlockParam.builder()
                        .text(system)
                        .cacheControl(CacheControlEphemeral.builder().build())
                        .build()))
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.MEDIUM).build())
                .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));

        List<JsonNode> defs = new ArrayList<>();
        toolDefinitions.forEach(defs::add);
        for (int i = 0; i < defs.size(); i++) {
            builder.addTool(tool(defs.get(i), i == defs.size() - 1));
        }
        builder.addUserMessage(userContent);

        return new Conversation() {
            @Override
            public LlmResponse next() {
                Message message;
                try {
                    message = client.messages().create(builder.build());
                } catch (AnthropicException e) {
                    throw new LlmException("Claude 호출 실패: " + e.getMessage(), e);
                } catch (RuntimeException e) {
                    throw new LlmException("Claude 호출 실패: " + e.getMessage(), e);
                }
                builder.addMessage(message);

                List<ToolCall> calls = new ArrayList<>();
                StringBuilder text = new StringBuilder();
                for (ContentBlock block : message.content()) {
                    block.toolUse().ifPresent(t -> calls.add(new ToolCall(t.id(), t.name(),
                            Json.MAPPER.valueToTree(t._input().convert(Object.class)))));
                    block.text().ifPresent(t -> text.append(t.text()));
                }
                String stop = message.stopReason().map(StopReason::asString).orElse("");
                return new LlmResponse(calls, text.toString(), stop,
                        message.usage().inputTokens(), message.usage().outputTokens());
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
