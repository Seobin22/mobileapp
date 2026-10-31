package com.seobin.phonedrop.assistant.agent.llm;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import tools.jackson.databind.JsonNode;

/**
 * 운영용 LLM. 제공자(llm.provider)·모델(llm.model)은 관리 화면에서 바꾸고, 다음 turn 부터 적용된다.
 * - anthropic: Anthropic 공식 API. 키는 환경변수 ANTHROPIC_API_KEY 또는 data/assistant/anthropic_api_key.
 * - factchat: 영남대 FactChat 게이트웨이. 키는 data/assistant/factchat_api_key.
 *   claude-* 모델은 Anthropic Messages 호환 주소, 그 밖의 모델(GPT·Gemini 등)은 OpenAI 호환 Chat Completions.
 * 설정이나 키가 바뀌면 클라이언트를 다시 만든다. 키 값은 로그·응답에 남기지 않는다.
 */
public class ConfiguredLlm implements LlmClient {

    public static final String ANTHROPIC = "anthropic";
    public static final String FACTCHAT = "factchat";
    public static final List<String> PROVIDERS = List.of(ANTHROPIC, FACTCHAT);
    static final String FACTCHAT_OPENAI_BASE = "https://factchat-cloud.mindlogic.ai/v1/gateway";
    static final String FACTCHAT_CLAUDE_BASE = "https://factchat-cloud.mindlogic.ai/v1/gateway/claude";

    /** 키나 모델이 없을 때. 재시도해도 소용없으므로 LlmException 과 구분한다. */
    public static class NotConfiguredException extends RuntimeException {
        public NotConfiguredException(String message) {
            super(message);
        }
    }

    private final Path dataDir;
    private final String envAnthropicKey;
    private final String defaultModel;
    private final Supplier<String> providerSetting;
    private final Supplier<String> modelSetting;
    private String cachedSignature;
    private LlmClient cached;

    /**
     * @param providerSetting 설정 llm.provider (빈 값이면 anthropic)
     * @param modelSetting    설정 llm.model (빈 값이면 서버 기본 모델 defaultModel)
     */
    public ConfiguredLlm(Path dataDir, String envAnthropicKey, String defaultModel,
                         Supplier<String> providerSetting, Supplier<String> modelSetting) {
        this.dataDir = dataDir;
        this.envAnthropicKey = envAnthropicKey == null ? "" : envAnthropicKey.trim();
        this.defaultModel = defaultModel == null ? "" : defaultModel.trim();
        this.providerSetting = providerSetting;
        this.modelSetting = modelSetting;
    }

    public String provider() {
        String p = providerSetting.get();
        return p == null || p.isBlank() ? ANTHROPIC : p.trim();
    }

    /** 지금 쓰는 모델(설정이 비어 있으면 서버 기본 모델). */
    public String model() {
        String m = modelSetting.get();
        return m == null || m.isBlank() ? defaultModel : m.trim();
    }

    public String defaultModel() {
        return defaultModel;
    }

    /** 지금 제공자에 키가 있는지. */
    public boolean hasKey() {
        return hasKey(provider());
    }

    public boolean hasKey(String provider) {
        return !currentKey(provider).isEmpty();
    }

    public Map<String, Boolean> keyStatus() {
        return Map.of(ANTHROPIC, hasKey(ANTHROPIC), FACTCHAT, hasKey(FACTCHAT));
    }

    /** 예전 관리 화면 호환: Anthropic 키. */
    public void saveKey(String key) throws IOException {
        saveKey(ANTHROPIC, key);
    }

    public void saveKey(String provider, String key) throws IOException {
        Path file = keyFile(provider);
        Files.createDirectories(file.getParent());
        Files.writeString(file, key.trim() + "\n");
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException e) {
            // Windows(로컬 개발)
        }
    }

    /** 게이트웨이의 모델 목록. FactChat 만 지원한다. */
    public List<String> listModels(String provider) {
        if (!FACTCHAT.equals(provider)) {
            throw new NotConfiguredException("모델 목록은 FactChat 에서만 불러올 수 있습니다");
        }
        String key = currentKey(FACTCHAT);
        if (key.isEmpty()) {
            throw new NotConfiguredException("FactChat API 키가 설정되지 않았습니다");
        }
        return new OpenAiCompatLlm(FACTCHAT_OPENAI_BASE, key, "").listModels();
    }

    /** 이 제공자·모델이 어떤 방식으로 호출되는지(관리 화면 표시용). */
    public static String protocol(String provider, String model) {
        if (FACTCHAT.equals(provider)) {
            return model.startsWith("claude") ? "anthropic-messages(factchat)" : "openai-chat(factchat)";
        }
        return "anthropic-messages";
    }

    private Path keyFile(String provider) {
        if (!PROVIDERS.contains(provider)) {
            throw new IllegalArgumentException("알 수 없는 제공자: " + provider);
        }
        return dataDir.resolve(provider + "_api_key");
    }

    private String currentKey(String provider) {
        if (ANTHROPIC.equals(provider) && !envAnthropicKey.isEmpty()) {
            return envAnthropicKey;
        }
        try {
            Path file = keyFile(provider);
            return Files.exists(file) ? Files.readString(file).trim() : "";
        } catch (IOException | IllegalArgumentException e) {
            return "";
        }
    }

    private synchronized LlmClient client() {
        String provider = provider();
        String model = model();
        if (model.isEmpty()) {
            throw new NotConfiguredException("모델이 설정되지 않았습니다");
        }
        if (!PROVIDERS.contains(provider)) {
            throw new NotConfiguredException("알 수 없는 제공자입니다: " + provider);
        }
        String key = currentKey(provider);
        if (key.isEmpty()) {
            throw new NotConfiguredException((FACTCHAT.equals(provider) ? "FactChat" : "Anthropic")
                    + " API 키가 설정되지 않았습니다");
        }
        String signature = provider + "|" + model + "|" + key.hashCode();
        if (cached == null || !signature.equals(cachedSignature)) {
            cached = create(provider, model, key);
            cachedSignature = signature;
        }
        return cached;
    }

    static LlmClient create(String provider, String model, String key) {
        if (FACTCHAT.equals(provider)) {
            return model.startsWith("claude")
                    ? new AnthropicLlm(key, model, FACTCHAT_CLAUDE_BASE)
                    : new OpenAiCompatLlm(FACTCHAT_OPENAI_BASE, key, model);
        }
        return new AnthropicLlm(key, model);
    }

    @Override
    public Conversation start(String system, JsonNode toolDefinitions, String userContent, boolean webSearch) {
        return client().start(system, toolDefinitions, userContent, webSearch);
    }
}
