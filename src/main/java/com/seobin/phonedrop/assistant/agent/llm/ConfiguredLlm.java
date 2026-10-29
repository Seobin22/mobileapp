package com.seobin.phonedrop.assistant.agent.llm;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import tools.jackson.databind.JsonNode;

/**
 * 운영용 LLM. API 키는 환경변수 ANTHROPIC_API_KEY 또는 관리 화면이 저장한 data/assistant/anthropic_api_key.
 * 키가 바뀌면 클라이언트를 다시 만든다. 키 값은 로그·응답에 남기지 않는다.
 */
public class ConfiguredLlm implements LlmClient {

    /** 키나 모델이 없을 때. 재시도해도 소용없으므로 LlmException 과 구분한다. */
    public static class NotConfiguredException extends RuntimeException {
        public NotConfiguredException(String message) {
            super(message);
        }
    }

    private final Path keyFile;
    private final String envKey;
    private final String model;
    private String cachedKey;
    private AnthropicLlm cached;

    public ConfiguredLlm(Path keyFile, String envKey, String model) {
        this.keyFile = keyFile;
        this.envKey = envKey == null ? "" : envKey.trim();
        this.model = model == null ? "" : model.trim();
    }

    public String model() {
        return model;
    }

    public boolean hasKey() {
        return !currentKey().isEmpty();
    }

    public void saveKey(String key) throws IOException {
        Files.createDirectories(keyFile.getParent());
        Files.writeString(keyFile, key.trim() + "\n");
        try {
            Files.setPosixFilePermissions(keyFile, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException e) {
            // Windows(로컬 개발)
        }
    }

    private String currentKey() {
        if (!envKey.isEmpty()) {
            return envKey;
        }
        try {
            return Files.exists(keyFile) ? Files.readString(keyFile).trim() : "";
        } catch (IOException e) {
            return "";
        }
    }

    private synchronized AnthropicLlm client() {
        if (model.isEmpty()) {
            throw new NotConfiguredException("AGENT_MODEL 이 설정되지 않았습니다");
        }
        String key = currentKey();
        if (key.isEmpty()) {
            throw new NotConfiguredException("Anthropic API 키가 설정되지 않았습니다");
        }
        if (cached == null || !key.equals(cachedKey)) {
            cached = new AnthropicLlm(key, model);
            cachedKey = key;
        }
        return cached;
    }

    @Override
    public Conversation start(String system, JsonNode toolDefinitions, String userContent, boolean webSearch) {
        return client().start(system, toolDefinitions, userContent, webSearch);
    }
}
