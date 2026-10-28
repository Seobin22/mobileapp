package com.seobin.phonedrop.assistant;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;

import tools.jackson.databind.JsonNode;

@Service
public class DeviceService {

    private final AssistantDb db;
    private final AssistantClock clock;
    private final TimeUtil time;

    public DeviceService(AssistantDb db, AssistantClock clock, TimeUtil time) {
        this.db = db;
        this.clock = clock;
        this.time = time;
    }

    /** 같은 토큰이면 갱신한다. */
    public long register(JsonNode req) {
        String token = req.path("fcm_token").asString("").trim();
        if (token.isEmpty()) {
            throw ApiException.badRequest("fcm_token 이 필요합니다");
        }
        String appVersion = req.path("app_version").asString("");
        String capabilities = Json.write(req.path("capabilities").isObject() ? req.get("capabilities") : Json.object());
        Optional<Long> existing = db.jdbc().sql("SELECT id FROM devices WHERE fcm_token = ?").param(token)
                .query(Long.class).optional();
        if (existing.isPresent()) {
            db.update("""
                            UPDATE devices SET app_version = ?, capabilities = ?, last_heartbeat = ?,
                            updated_at = CURRENT_TIMESTAMP WHERE id = ?""",
                    appVersion, capabilities, clock.now(), existing.get());
            return existing.get();
        }
        return db.insert("""
                        INSERT INTO devices(fcm_token, app_version, capabilities, last_heartbeat)
                        VALUES (?, ?, ?, ?)""",
                token, appVersion, capabilities, clock.now());
    }

    /** events 는 v1 에서 저장만 한다. 모르는 기기면 404(앱이 다시 register 한다). */
    public void heartbeat(JsonNode req) {
        long deviceId = req.path("device_id").asLong(-1);
        int updated;
        if (req.path("capabilities").isObject()) {
            updated = db.update("""
                            UPDATE devices SET capabilities = ?, last_heartbeat = ?, updated_at = CURRENT_TIMESTAMP
                            WHERE id = ?""",
                    Json.write(req.get("capabilities")), clock.now(), deviceId);
        } else {
            updated = db.update("""
                            UPDATE devices SET last_heartbeat = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?""",
                    clock.now(), deviceId);
        }
        if (updated == 0) {
            throw ApiException.notFound("등록되지 않은 기기입니다: " + deviceId);
        }
        for (JsonNode event : req.path("events")) {
            String at = event.path("at").asString("");
            db.insert("INSERT INTO device_events(device_id, type, at) VALUES (?, ?, ?)",
                    deviceId, event.path("type").asString("unknown"), at.isEmpty() ? null : TimeUtil.parse(at));
        }
    }

    public List<Map<String, Object>> list() {
        List<Map<String, Object>> result = new ArrayList<>();
        db.jdbc().sql("SELECT id, app_version, capabilities, last_heartbeat FROM devices ORDER BY id")
                .query((rs, n) -> {
                    Map<String, Object> d = new LinkedHashMap<>();
                    d.put("id", rs.getLong("id"));
                    d.put("app_version", rs.getString("app_version"));
                    d.put("capabilities", Json.parse(rs.getString("capabilities")));
                    d.put("last_heartbeat", time.iso(AssistantDb.instant(rs, "last_heartbeat")));
                    return d;
                }).list().forEach(result::add);
        return result;
    }
}
