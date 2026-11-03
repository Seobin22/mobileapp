package com.seobin.phonedrop.assistant.api;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.seobin.phonedrop.assistant.Json;

import tools.jackson.databind.JsonNode;

/**
 * 앱 안 업데이트(2026-11-03). CI(android.yml)가 고정 키로 서명한 APK 와 version.json 을
 * data/assistant/app/ 에 올리면, 앱이 버전을 비교해 새 APK 를 받아 설치 화면을 띄운다. API 토큰이 있어야 받는다.
 */
@RestController
@RequestMapping("/api/assistant/v1/app")
public class AppUpdateController {

    private final Path dir;

    public AppUpdateController(@Value("${assistant.data-dir:data/assistant}") String dataDir) {
        this.dir = Path.of(dataDir, "app");
    }

    /** {available, version_code, version_name, size} */
    @GetMapping("/latest")
    public Map<String, Object> latest() throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        Path version = dir.resolve("version.json");
        Path apk = dir.resolve("app.apk");
        if (!Files.exists(version) || !Files.exists(apk)) {
            m.put("available", false);
            return m;
        }
        JsonNode v = Json.parse(Files.readString(version));
        m.put("available", true);
        m.put("version_code", v.path("version_code").asLong(0));
        m.put("version_name", v.path("version_name").asString(""));
        m.put("size", Files.size(apk));
        return m;
    }

    @GetMapping("/apk")
    public ResponseEntity<FileSystemResource> apk() {
        Path apk = dir.resolve("app.apk");
        if (!Files.exists(apk)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.android.package-archive"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"assistant.apk\"")
                .body(new FileSystemResource(apk));
    }
}
