package com.seobin.phonedrop.assistant.agent.llm;

import java.util.List;

import tools.jackson.databind.JsonNode;

/**
 * LLM 호출 인터페이스(빌드문서 v2 §8.4). 운영은 AnthropicLlm, 테스트는 ScriptedLlm.
 * 대화(Conversation)는 응답 내용(thinking 블록 포함)을 다음 요청에 그대로 되돌려 보내야 하므로 구현체가 들고 있는다.
 */
public interface LlmClient {

    Conversation start(String system, JsonNode toolDefinitions, String userContent);

    interface Conversation {
        /** 다음 응답. 네트워크·5xx·타임아웃은 LlmException. */
        LlmResponse next();

        /** 이번 응답의 tool_use 에 대한 결과를 한 user 메시지로 붙인다. */
        void addToolResults(List<ToolResult> results);
    }

    record ToolCall(String id, String name, JsonNode input) {
    }

    record ToolResult(String toolUseId, String content, boolean isError) {
    }

    record LlmResponse(List<ToolCall> toolCalls, String text, String stopReason, long inputTokens,
                       long outputTokens) {
    }

    class LlmException extends RuntimeException {
        public LlmException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
