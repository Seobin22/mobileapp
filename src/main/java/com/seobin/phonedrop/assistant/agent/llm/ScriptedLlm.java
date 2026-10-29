package com.seobin.phonedrop.assistant.agent.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import com.seobin.phonedrop.assistant.Json;

import tools.jackson.databind.JsonNode;

/**
 * 테스트용 LLM: 미리 정한 응답을 순서대로 돌려준다(API 키 없이 결정적).
 * 스크립트가 끝나면 도구 호출 없는 빈 응답을 돌려준다.
 */
public class ScriptedLlm implements LlmClient {

    private final List<Supplier<LlmResponse>> script = new ArrayList<>();
    private int cursor;
    /** 받은 맥락·도구 결과(검증용). */
    public final List<String> userContents = new ArrayList<>();
    public final List<ToolResult> toolResults = new ArrayList<>();

    public static LlmResponse tools(ToolCall... calls) {
        return new LlmResponse(List.of(calls), "", "tool_use", 100, 50);
    }

    public static LlmResponse text(String text) {
        return new LlmResponse(List.of(), text, "end_turn", 100, 20);
    }

    public static ToolCall call(String name, String inputJson) {
        return new ToolCall("toolu_" + name + "_" + System.nanoTime(), name, Json.parse(inputJson));
    }

    public ScriptedLlm then(LlmResponse response) {
        script.add(() -> response);
        return this;
    }

    /** 응답 대신 예외(LLM 장애) 또는 지연을 흉내 낼 때. */
    public ScriptedLlm thenDo(Supplier<LlmResponse> step) {
        script.add(step);
        return this;
    }

    public synchronized void reset() {
        script.clear();
        cursor = 0;
        userContents.clear();
        toolResults.clear();
    }

    @Override
    public Conversation start(String system, JsonNode toolDefinitions, String userContent) {
        synchronized (this) {
            userContents.add(userContent);
        }
        return new Conversation() {
            @Override
            public LlmResponse next() {
                Supplier<LlmResponse> step;
                synchronized (ScriptedLlm.this) {
                    step = cursor < script.size() ? script.get(cursor++) : null;
                }
                return step == null ? text("") : step.get();
            }

            @Override
            public void addToolResults(List<ToolResult> results) {
                synchronized (ScriptedLlm.this) {
                    toolResults.addAll(results);
                }
            }
        };
    }
}
