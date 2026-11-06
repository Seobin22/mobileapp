package com.seobin.phonedrop.assistant;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.seobin.phonedrop.assistant.agent.llm.ConfiguredLlm;

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
        // v2

        d.put("life.focus_windows", "[{\"days\":[1,2,3,4,5],\"start\":\"09:00\",\"end\":\"12:00\"}]");
        d.put("persona", "\"다정한 코치\"");
        d.put("agent.max_tool_calls", "15");
        d.put("agent.context_messages", "20");
        d.put("agent.daily_turn_hard", "250");
        d.put("confirmation.expire_minutes", "1440");
        d.put("load.ok_ratio", "1.5");
        d.put("load.tight_ratio", "1.0");
        d.put("privacy.sensitive_mode", "true");
        d.put("memory.max_chars", "4000");
        // v3
        d.put("pulse.interval_minutes", "120");
        d.put("messages.daily_budget", "12");
        d.put("messages.high_per_day", "3");
        d.put("wakeup.max_per_task", "3");
        d.put("agent.daily_turn_soft", "150");
        d.put("morning.fallback_minutes", "60");
        d.put("evening.before_sleep_minutes", "60");
        d.put("web.enabled", "true");
        // v3.1 (negotiation.timeout_minutes 는 talk.timeout_minutes 로 대체)
        d.put("goal.min_horizon_days", "2");
        d.put("goal.coverage_retry", "2");
        d.put("talk.timeout_minutes", "5");
        // 모델 선택(관리 화면). llm.model 이 비면 서버 기본 모델(AGENT_MODEL)
        d.put("llm.provider", "\"anthropic\"");
        d.put("llm.model", "\"\"");
        // v3.2
        d.put("series.horizon_days", "14");
        d.put("lookahead.days", "28");
        d.put("memo.urgent_hours", "24");
        d.put("memo.max_chars", "1000");
        // v3.3 지도 읽기(집 좌표는 주소를 저장할 때 코드가 채운다)·문서 받기
        d.put("travel.home_address", "\"\"");
        d.put("travel.home_lat", "null");
        d.put("travel.home_lng", "null");
        d.put("travel.mode", "\"transit\"");
        d.put("travel.daily_api_limit", "300");
        // v3.4: 집 주소가 없어 첫 이동 블록을 못 만들 때 한 번만 안내한 시각
        d.put("travel.home_hint_at", "null");
        // 문서 읽기·요약 모델. 비면 llm.model(이미지는 이미지 입력을 받는 모델이어야 한다)
        d.put("documents.model", "\"\"");
        // v3.5 하루 기록·패턴 분석·사용자 맞춤 메모리. memory.model 이 비면 Haiku 계열(MemoryJobs.DEFAULT_MODEL)
        d.put("memory.model", "\"\"");
        d.put("memory.enabled", "true");
        d.put("memory.extract_every_turns", "6");
        d.put("memory.min_samples", "8");
        d.put("memory.prior_k", "5");
        d.put("memory.highlight_max", "5");
        d.put("memory.profile_max_chars", "2000");
        d.put("card.min_blocks", "3");
        d.put("card.min_gap_hours", "4");
        d.put("card.daytime_max", "2");
        d.put("card.evening_wait_min", "30");
        d.put("card.expire_days", "2");
        // v3.6 시간대(active.* 는 사용자 전용, 비서 도구로는 못 바꾼다)
        d.put("active.alarm_repeat_min", "3");
        d.put("active.llm_min_interval_min", "10");
        d.put("active.llm_max", "12");
        d.put("active.pause_min", "10");
        d.put("active.auto_voice", "false");
        d.put("active.paused_until", "null");
        // v3.6 음성 탭. voice.model 이 비면 Haiku 계열
        d.put("voice.model", "\"\"");
        d.put("voice.stt_mode", "\"batch\"");
        d.put("voice.voice_id", "\"\"");
        d.put("voice.speed", "1.0");
        d.put("voice.end_silence_ms", "800");
        d.put("voice.daily_turn_cap", "200");
        // v4 단계 올리기·딴짓 감지(distract.watch_categories 는 사용자 전용)
        d.put("intervention.levels", "[1,3,4]");
        d.put("intervention.start_level", "1");
        d.put("intervention.wait_minutes", "[5,10]");
        d.put("active.wait_minutes", "[3,5]");
        d.put("distract.watch_categories", "[\"study\",\"work\"]");
        d.put("distract.trigger_min", "3");
        d.put("distract.judge_max_per_hour", "20");
        d.put("distract.llm_max_per_block", "3");

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
                case "dnd.windows", "life.focus_windows" -> validateWindows(value, true);

                case "persona" -> {
                    if (!value.isString() || value.asString().isBlank()) {
                        throw new IllegalArgumentException();
                    }
                }
                case "llm.provider" -> {
                    if (!ConfiguredLlm.PROVIDERS.contains(value.asString())) {
                        throw new IllegalArgumentException();
                    }
                }
                case "travel.home_address" -> {
                    if (!value.isString() || value.asString().length() > 300) {
                        throw new IllegalArgumentException();
                    }
                }
                case "intervention.levels" -> {
                    if (!value.isArray() || value.isEmpty()) {
                        throw new IllegalArgumentException();
                    }
                    for (JsonNode v : value) {
                        if (!List.of(1, 3, 4).contains(v.asInt())) {
                            throw new IllegalArgumentException();
                        }
                    }
                }
                case "intervention.wait_minutes", "active.wait_minutes" -> {
                    if (!value.isArray() || value.isEmpty()) {
                        throw new IllegalArgumentException();
                    }
                    for (JsonNode v : value) {
                        if (!v.isNumber() || v.asInt() < 1) {
                            throw new IllegalArgumentException();
                        }
                    }
                }
                case "distract.watch_categories" -> {
                    if (!value.isArray()) {
                        throw new IllegalArgumentException();
                    }
                    for (JsonNode v : value) {
                        if (!v.isString()) {
                            throw new IllegalArgumentException();
                        }
                    }
                }
                case "active.paused_until" -> {
                    if (!value.isNull() && !value.isString()) {
                        throw new IllegalArgumentException();
                    }
                }
                case "voice.stt_mode" -> {
                    if (!List.of("batch", "realtime").contains(value.asString())) {
                        throw new IllegalArgumentException();
                    }
                }
                case "voice.voice_id" -> {
                    if (!value.isString() || !value.asString().matches("[A-Za-z0-9_-]{0,64}")) {
                        throw new IllegalArgumentException();
                    }
                }
                case "voice.speed" -> {
                    if (!value.isNumber() || value.asDouble() < 0.7 || value.asDouble() > 1.2) {
                        throw new IllegalArgumentException();
                    }
                }
                case "travel.home_hint_at" -> {
                    if (!value.isNull() && !value.isString()) {
                        throw new IllegalArgumentException();
                    }
                }
                case "travel.home_lat", "travel.home_lng" -> {
                    if (!value.isNull() && !value.isNumber()) {
                        throw new IllegalArgumentException();
                    }
                }
                case "travel.mode" -> {
                    if (!List.of("transit", "car", "walk").contains(value.asString())) {
                        throw new IllegalArgumentException();
                    }
                }
                case "llm.model", "documents.model", "memory.model", "voice.model" -> {
                    if (!value.isString() || !value.asString().matches("[A-Za-z0-9._:/@-]{0,100}")) {
                        throw new IllegalArgumentException();
                    }
                }
                case "privacy.sensitive_mode", "web.enabled", "memory.enabled", "active.auto_voice" -> {
                    if (!value.isBoolean()) {
                        throw new IllegalArgumentException();
                    }
                }
                case "load.ok_ratio", "load.tight_ratio" -> {
                    if (!value.isNumber() || value.asDouble() <= 0) {
                        throw new IllegalArgumentException();
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

    /** [{"days":[1..7]?, "start":"HH:mm", "end":"HH:mm"}] */
    private static void validateWindows(JsonNode value, boolean daysRequired) {
        if (!value.isArray()) {
            throw new IllegalArgumentException();
        }
        for (JsonNode w : value) {
            LocalTime.parse(w.path("start").asString());
            LocalTime.parse(w.path("end").asString());
            if (daysRequired && !w.path("days").isArray()) {
                throw new IllegalArgumentException();
            }
            for (JsonNode day : w.path("days")) {
                if (day.asInt() < 1 || day.asInt() > 7) {
                    throw new IllegalArgumentException();
                }
            }
        }
    }

    public double getDouble(String key) {
        return get(key).asDouble();
    }

    public boolean getBoolean(String key) {
        return get(key).asBoolean();
    }
}
