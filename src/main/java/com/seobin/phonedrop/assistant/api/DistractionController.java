package com.seobin.phonedrop.assistant.api;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.seobin.phonedrop.assistant.ApiException;
import com.seobin.phonedrop.assistant.Tx;
import com.seobin.phonedrop.assistant.distract.DistractionService;

import tools.jackson.databind.JsonNode;

/**
 * 딴짓 앱 목록·블록 감시(사용자 전용, 빌드문서 v4 §4)와 앱이 보내는 딴짓 기록·제목 판단(§5). 비서 도구로는 바꿀 수 없다.
 */
@RestController
@RequestMapping("/api/assistant")
public class DistractionController {

    private final Tx tx;
    private final DistractionService distractions;

    public DistractionController(Tx tx, DistractionService distractions) {
        this.tx = tx;
        this.distractions = distractions;
    }

    @GetMapping("/v1/settings/app-rules")
    public Map<String, Object> rules() {
        return tx.run(distractions::rules);
    }

    @PutMapping("/v1/settings/app-rules")
    public Map<String, Object> putRules(@RequestBody JsonNode body) {
        return tx.run(() -> distractions.replaceRules(body));
    }

    @GetMapping("/v1/blocks/{id}/watch")
    public Map<String, Object> getBlockWatch(@PathVariable long id) {
        return tx.run(() -> distractions.blockWatch(id));
    }

    /** {watch: on|off|null, allowed_apps: [...]} */
    @PatchMapping("/v1/blocks/{id}/watch")
    public Map<String, Object> blockWatch(@PathVariable long id, @RequestBody JsonNode body) {
        return tx.run(() -> distractions.setBlockWatch(id, body));
    }

    @PatchMapping("/v1/fixed_series/{id}/watch")
    public Map<String, Object> seriesWatch(@PathVariable long id, @RequestBody JsonNode body) {
        return tx.run(() -> distractions.setSeriesWatch(id, body));
    }

    @PostMapping("/v1/distraction/events")
    public Map<String, Object> event(@RequestBody JsonNode body) {
        return tx.run(() -> distractions.event(body));
    }

    /** 판단은 LLM 호출이 있을 수 있어 트랜잭션 밖에서 기다리지 않게 짧게 묶는다. */
    @PostMapping("/v1/distraction/judge")
    public Map<String, Object> judge(@RequestBody JsonNode body) {
        return tx.run(() -> distractions.judge(body));
    }

    /** [이건 작업이었어] */
    @PatchMapping("/v1/distraction/logs/{id}")
    public Map<String, Object> markRelated(@PathVariable long id, @RequestBody(required = false) JsonNode body) {
        return tx.run(() -> distractions.markRelated(id));
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, String>> apiError(ApiException e) {
        return ResponseEntity.status(e.status()).body(Map.of("error", e.getMessage()));
    }
}
