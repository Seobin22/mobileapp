package com.seobin.phonedrop.assistant;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.HexFormat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.seobin.phonedrop.assistant.agent.llm.ConfiguredLlm;
import com.seobin.phonedrop.assistant.push.FakePush;
import com.seobin.phonedrop.assistant.push.FirebasePush;
import com.seobin.phonedrop.assistant.push.Push;

@Configuration
@EnableScheduling
public class AssistantConfig {

    private static final Logger log = LoggerFactory.getLogger(AssistantConfig.class);

    /** API 토큰. 설정값(ASSISTANT_API_TOKEN)이 없으면 data/assistant/api_token 파일을 만들어 쓴다. */
    public record ApiToken(String value) {
    }

    @Bean
    AssistantClock assistantClock() {
        return new RealClock();
    }

    @Bean
    Push push(@Value("${assistant.push-mode:fake}") String mode,
              @Value("${assistant.firebase-credentials:}") String credentials) {
        if ("firebase".equals(mode)) {
            log.info("assistant push: firebase ({})", credentials);
            return new FirebasePush(Path.of(credentials));
        }
        log.info("assistant push: fake (FCM 미설정, 발송 내용은 관리 화면에만 보임)");
        return new FakePush();
    }

    /**
     * 운영 LLM. 제공자·모델은 설정(llm.provider, llm.model — 관리 화면), 비어 있으면 AGENT_MODEL.
     * 키는 ANTHROPIC_API_KEY 또는 관리 화면이 저장한 data/assistant/{제공자}_api_key.
     */
    @Bean
    ConfiguredLlm llmClient(@Value("${assistant.data-dir:data/assistant}") String dataDir,
                            @Value("${ANTHROPIC_API_KEY:}") String envKey,
                            @Value("${assistant.agent-model:}") String model,
                            SettingsService settings) {
        if (model.isBlank()) {
            log.error("AGENT_MODEL 이 비어 있습니다. 관리 화면에서 모델을 정하기 전에는 채팅 비서가 동작하지 않습니다(개입 기능은 정상).");
        }
        return new ConfiguredLlm(Path.of(dataDir), envKey, model,
                () -> settings.getString("llm.provider"), () -> settings.getString("llm.model"));
    }

    @Bean
    ApiToken apiToken(@Value("${assistant.api-token:}") String configured,
                      @Value("${assistant.data-dir:data/assistant}") String dataDir) throws IOException {
        if (!configured.isBlank()) {
            return new ApiToken(configured.trim());
        }
        Path file = Path.of(dataDir, "api_token");
        if (Files.exists(file)) {
            return new ApiToken(Files.readString(file).trim());
        }
        Files.createDirectories(file.getParent());
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        String token = HexFormat.of().formatHex(bytes);
        Files.writeString(file, token + "\n");
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException e) {
            // Windows(로컬 개발)에서는 권한 설정을 건너뛴다.
        }
        log.info("assistant API 토큰을 새로 만들었습니다: {}", file.toAbsolutePath());
        return new ApiToken(token);
    }
}
