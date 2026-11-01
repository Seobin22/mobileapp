package com.seobin.phonedrop.check;

import java.net.URI;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** check.seobin.page 에서 쓰는 GitHub Actions 결과물 목록 API (읽기 전용). */
@RestController
@RequestMapping("/api/check")
public class ArtifactController {

    private final ArtifactService service;

    public ArtifactController(ArtifactService service) {
        this.service = service;
    }

    @GetMapping("/artifacts")
    public ResponseEntity<Map<String, Object>> artifacts() {
        try {
            return ResponseEntity.ok(service.list());
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", "GitHub 목록을 가져오지 못했습니다: " + e.getMessage()));
        }
    }

    /** 토큰이 있으면 zip 으로, 없으면 GitHub 결과물 화면으로 보낸다. */
    @GetMapping("/artifacts/{id}/download")
    public ResponseEntity<Void> download(@PathVariable long id) {
        String target = "https://github.com/" + ArtifactService.REPO + "/actions";
        try {
            Optional<String> zip = service.downloadUrl(id);
            if (zip.isPresent()) {
                target = zip.get();
            }
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(target)).build();
    }
}
