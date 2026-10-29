package com.seobin.phonedrop.assistant.agent;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.seobin.phonedrop.assistant.ApiException;
import com.seobin.phonedrop.assistant.AssistantClock;
import com.seobin.phonedrop.assistant.AssistantDb;
import com.seobin.phonedrop.assistant.Jobs;
import com.seobin.phonedrop.assistant.Json;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** POST /v1/confirmations/{id} 와 confirmation_expire 작업(빌드문서 v2 §6). */
@Service
public class ConfirmationService {

    private final AssistantDb db;
    private final AssistantClock clock;
    private final Tools tools;
    private final ChatService chat;

    public ConfirmationService(AssistantDb db, AssistantClock clock, Tools tools, ChatService chat, Jobs jobs) {
        this.db = db;
        this.clock = clock;
        this.tools = tools;
        this.chat = chat;
        jobs.register(Tools.CONFIRMATION_EXPIRE, job -> db.update("""
                        UPDATE confirmations SET status = 'expired', resolved_at = ? WHERE id = ? AND status = 'pending'""",
                clock.now(), job.targetId()) > 0);
    }

    public Map<String, Object> resolve(long id, boolean accept) {
        record Row(String status, String tool, String args) {
        }
        Row row = db.jdbc().sql("SELECT status, tool, args FROM confirmations WHERE id = ?").param(id)
                .query((rs, n) -> new Row(rs.getString(1), rs.getString(2), rs.getString(3))).optional()
                .orElseThrow(() -> ApiException.notFound("확인 카드가 없습니다: " + id));
        if (!"pending".equals(row.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "이미 처리된 확인 카드입니다(" + row.status() + ")");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        if (accept) {
            // 코드가 저장된 도구·인자를 권한 검사 없이 실행한다. 변경 기록은 새 system turn 으로.
            long turnId = db.insert("""
                            INSERT INTO turns(trigger, trigger_ref, status, started_at, ended_at)
                            VALUES ('system', ?, 'ok', ?, ?)""",
                    Json.write(Map.of("confirmation_id", id)), clock.now(), clock.now());
            JsonNode args = Json.parse(row.args());
            ObjectNode toolResult = tools.dispatch(new Tools.Context(turnId, "system", true), row.tool(), args);
            result.put("result", toolResult);
        }
        String status = accept ? "accepted" : "rejected";
        db.update("UPDATE confirmations SET status = ?, resolved_at = ? WHERE id = ?", status, clock.now(), id);

        ObjectNode ref = Json.object();
        ref.put("confirmation_id", id);
        ref.put("accepted", accept);
        chat.enqueueTurn("confirmation_resolved", ref, ChatService.PRIORITY_DEFAULT);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", status);
        response.putAll(result);
        return response;
    }
}
