package com.seobin.phonedrop.assistant;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Service;

import tools.jackson.databind.JsonNode;

/** 수치·생활 패턴은 모두 settings 테이블에서 읽는다(값은 JSON). */
@Service
public class SettingsService {

    /** 기본값. 시작할 때 없는 키만 넣는다. */
    public static final Map<String, String> DEFAULTS;

    static {
        Map<String, String> d = new LinkedHashMap<>();
        d.put("user.timezone", "\"Asia/Seoul\"");
        d.put("life.wake", "\"07:30\"");
        d.put("life.sleep", "\"23:30\"");
        d.put("dnd.windows", "[]");
        d.put("fullscreen.reshow_minutes", "3");
        d.put("intervention.expire_grace_minutes", "15");
        d.put("local_backup.grace_seconds", "90");
        d.put("jobs.late_threshold_seconds", "120");
        DEFAULTS = Map.copyOf(d);
    }

    private final AssistantDb db;

    public SettingsService(AssistantDb db) {
        this.db = db;
        seedDefaults();
    }

    public void seedDefaults() {
        DEFAULTS.forEach((key, value) -> {
            boolean exists = db.jdbc().sql("SELECT COUNT(*) FROM settings WHERE key = ?")
                    .param(key).query(Integer.class).single() > 0;
            if (!exists) {
                db.jdbc().sql("INSERT INTO settings(key, value) VALUES (?, ?)").param(key).param(value).update();
            }
        });
    }

    public JsonNode get(String key) {
        return db.jdbc().sql("SELECT value FROM settings WHERE key = ?").param(key)
                .query(String.class).optional()
                .map(Json::parse)
                .orElseGet(() -> Json.parse(DEFAULTS.get(key)));
    }

    public String getString(String key) {
        return get(key).asString();
    }

    public int getInt(String key) {
        return get(key).asInt();
    }

    public Map<String, JsonNode> all() {
        Map<String, JsonNode> result = new LinkedHashMap<>();
        db.jdbc().sql("SELECT key, value FROM settings ORDER BY key")
                .query((rs, n) -> Map.entry(rs.getString(1), Json.parse(rs.getString(2))))
                .list()
                .forEach(e -> result.put(e.getKey(), e.getValue()));
        return result;
    }

    public void put(String key, JsonNode value) {
        if (!DEFAULTS.containsKey(key)) {
            throw ApiException.unprocessable("알 수 없는 설정입니다: " + key);
        }
        validate(key, value);
        db.jdbc().sql("UPDATE settings SET value = ?, updated_at = CURRENT_TIMESTAMP WHERE key = ?")
                .param(Json.write(value)).param(key).update();
    }

    private static void validate(String key, JsonNode value) {
        try {
            switch (key) {
                case "user.timezone" -> ZoneId.of(value.asString());
                case "life.wake", "life.sleep" -> LocalTime.parse(value.asString());
                case "dnd.windows" -> {
                    if (!value.isArray()) {
                        throw new IllegalArgumentException();
                    }
                    for (JsonNode w : value) {
                        LocalTime.parse(w.path("start").asString());
                        LocalTime.parse(w.path("end").asString());
                        if (!w.path("days").isArray()) {
                            throw new IllegalArgumentException();
                        }
                        for (JsonNode day : w.path("days")) {
                            if (day.asInt() < 1 || day.asInt() > 7) {
                                throw new IllegalArgumentException();
                            }
                        }
                    }
                }
                default -> {
                    if (!value.isNumber() || value.asInt() < 0) {
                        throw new IllegalArgumentException();
                    }
                }
            }
        } catch (RuntimeException e) {
            throw ApiException.unprocessable("설정 값 형식이 맞지 않습니다: " + key);
        }
    }
}
