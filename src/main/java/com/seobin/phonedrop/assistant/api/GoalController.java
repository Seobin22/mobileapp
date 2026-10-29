package com.seobin.phonedrop.assistant.api;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.seobin.phonedrop.assistant.ApiException;
import com.seobin.phonedrop.assistant.Json;
import com.seobin.phonedrop.assistant.Tx;
import com.seobin.phonedrop.assistant.agent.FixedBlockService;
import com.seobin.phonedrop.assistant.agent.GoalService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 목표 탭(빌드문서 v3.1 §3.7)과 고정 블록 직접 추가·수정·삭제(§4.4).
 * 사용자가 앱에서 누른 것이므로 권한 검사 없이 바로 적용하고, 그 뒤 비서에게 turn 으로 알린다.
 */
@RestController
@RequestMapping("/api/assistant/v1")
public class GoalController {

    private final Tx tx;
    private final GoalService goals;
    private final FixedBlockService fixedBlocks;

    public GoalController(Tx tx, GoalService goals, FixedBlockService fixedBlocks) {
        this.tx = tx;
        this.goals = goals;
        this.fixedBlocks = fixedBlocks;
    }

    @GetMapping("/goals")
    public Map<String, Object> list() {
        return Map.of("goals", tx.run(goals::list));
    }

    @GetMapping("/goals/{id}/blocks")
    public Map<String, Object> blocks(@PathVariable long id) {
        return tx.run(() -> goals.blocks(id));
    }

    @PatchMapping("/goals/{id}")
    public Map<String, Object> patch(@PathVariable long id, @RequestBody JsonNode body) {
        return tx.run(() -> goals.patch(id, body));
    }

    @PostMapping("/goals/{id}/complete")
    public Map<String, Object> complete(@PathVariable long id) {
        return tx.run(() -> goals.completeByUser(id));
    }

    @PostMapping("/goals/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable long id) {
        return tx.run(() -> goals.cancelByUser(id));
    }

    /** {title, start, duration_min, lead_minutes?, location?, goal_id?, first_action?} */
    @PostMapping("/fixed_blocks")
    public Map<String, Object> createFixed(@RequestBody JsonNode body) {
        if (body.hasNonNull("id")) {
            throw ApiException.badRequest("새 고정 블록에는 id 를 넣지 않습니다");
        }
        return tx.run(() -> {
            FixedBlockService.Result r = fixedBlocks.upsert(body, null);
            fixedBlocks.notifyDisplaced(r);
            fixedBlocks.notifyPrepNeeded(r);
            return fixedBlocks.toResponse(r);
        });
    }

    @PatchMapping("/fixed_blocks/{id}")
    public Map<String, Object> patchFixed(@PathVariable long id, @RequestBody JsonNode body) {
        ObjectNode in = body.isObject() ? ((ObjectNode) body).deepCopy() : Json.object();
        in.put("id", id);
        return tx.run(() -> {
            FixedBlockService.Result r = fixedBlocks.upsert(in, null);
            fixedBlocks.notifyDisplaced(r);
            return fixedBlocks.toResponse(r);
        });
    }

    @DeleteMapping("/fixed_blocks/{id}")
    public Map<String, Object> deleteFixed(@PathVariable long id) {
        return tx.run(() -> fixedBlocks.toResponse(fixedBlocks.cancel(id, null)));
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, String>> apiError(ApiException e) {
        return ResponseEntity.status(e.status()).body(Map.of("error", e.getMessage()));
    }
}
