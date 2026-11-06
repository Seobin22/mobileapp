package com.seobin.phonedrop.assistant;

import static com.seobin.phonedrop.assistant.agent.llm.ScriptedLlm.text;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.seobin.phonedrop.assistant.agent.ContextBuilder;
import com.seobin.phonedrop.assistant.agent.Tools;
import com.seobin.phonedrop.assistant.api.TimeWindowController;
import com.seobin.phonedrop.assistant.distract.DistractionService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** 빌드문서 v4 §9.1 D1~D10 (딴짓 감지). 시작 2026-11-02(월) 09:00. */
class V4DistractionTest extends AssistantTestBase {

    @Autowired
    DistractionService distractions;
    @Autowired
    Tools agentTools;
    @Autowired
    ContextBuilder contextBuilder;
    @Autowired
    TimeWindowController windowApi;

    int keys;

    @BeforeEach
    void v4() {
        settings.put("intervention.levels", Json.parse("[1,3,4]"));
        db.update("UPDATE devices SET capabilities = ?", "{\"notifications\":true,\"fullscreen_intent\":true,\"accessibility\":true}");
    }

    static String iso(String local) {
        return at(local).atZone(KST).toOffsetDateTime().toString();
    }

    ObjectNode tool(String trigger, String name, String json) {
        long turnId = db.insert("INSERT INTO turns(trigger, status, started_at) VALUES (?, 'running', ?)", trigger, clock.now());
        return tx.run(() -> agentTools.dispatch(new Tools.Context(turnId, trigger, false), name, Json.parse(json)));
    }

    /** 계획 블록 하나(분류 지정). */
    long planned(String title, String startLocal, int minutes, String category) {
        long task = tool("user_message", "upsert_task", "{\"title\":\"" + title + "\",\"first_action\":\"열기\",\"est_minutes\":"
                + minutes + "}").get("id").asLong();
        String end = iso(LocalDateTime.parse(startLocal).plusMinutes(minutes).toString());
        ObjectNode r = tool("user_message", "set_schedule", "{\"from\":\"" + iso(startLocal) + "\",\"to\":\"" + end
                + "\",\"blocks\":[{\"task_id\":" + task + ",\"start\":\"" + iso(startLocal) + "\",\"end\":\"" + end + "\"}]}");
        long id = r.get("created").get(0).asLong();
        db.update("UPDATE blocks SET category = ? WHERE id = ?", category, id);
        return id;
    }

    Map<String, Object> report(long blockId, String app, int minutes, boolean ended) {
        return report(blockId, app, minutes, ended, "k" + (++keys));
    }

    Map<String, Object> report(long blockId, String app, int minutes, boolean ended, String key) {
        ObjectNode e = Json.object();
        e.put("client_key", key);
        e.put("block_id", blockId);
        e.put("package", "com." + app);
        e.put("app_label", app);
        e.put("kind", "app");
        e.put("started_at", iso(LocalDateTime.ofInstant(clock.now().minusSeconds(minutes * 60L), KST).toString()));
        e.put("minutes", minutes);
        if (ended) {
            e.put("ended_at", clock.now().atZone(KST).toOffsetDateTime().toString());
        }
        return tx.run(() -> distractions.event(e));
    }

    void startBlock(long blockId) {
        action("start", store.runningForBlock(blockId).map(Store.Intervention::id).orElse(null), blockId, "s" + blockId);
    }

    @Test
    void d1_watchConditions() {
        long study = planned("영어", "2026-11-02T09:05", 60, "study");
        long rest = planned("쉬기", "2026-11-02T10:10", 30, "rest");
        long off = planned("수학", "2026-11-02T11:00", 30, "study");
        db.update("UPDATE blocks SET watch = 'off' WHERE id = ?", off);
        runUntil("2026-11-02T09:06");
        startBlock(study);
        assertThat(report(study, "인스타", 3, false)).doesNotContainKey("ignored");
        runUntil("2026-11-02T10:11");
        assertThat(report(rest, "인스타", 3, false)).containsEntry("ignored", true);
        runUntil("2026-11-02T11:01");
        assertThat(report(off, "인스타", 3, false)).containsEntry("ignored", true);
        // 방해 금지 시간대
        long later = planned("국어", "2026-11-02T13:00", 60, "study");
        windowApi.put(Json.parse("{\"windows\":[{\"kind\":\"dnd\",\"name\":\"수업\",\"days\":[1],\"start_time\":\"13:30\",\"end_type\":\"time\",\"end_time\":\"15:00\"}]}"));
        runUntil("2026-11-02T13:31");
        assertThat(report(later, "인스타", 3, false)).containsEntry("ignored", true);
        // 다른 고정 일정 중
        long late = planned("과학", "2026-11-02T16:00", 60, "study");
        tool("user_message", "upsert_fixed_block", "{\"title\":\"면담\",\"start\":\"" + iso("2026-11-02T16:20") + "\",\"end\":\""
                + iso("2026-11-02T16:40") + "\"}");
        runUntil("2026-11-02T16:25");
        assertThat(report(late, "인스타", 3, false)).containsEntry("ignored", true);
        // monitor 창은 공부 블록만
        String monitor = plan.syncData().get("monitor");
        assertThat(monitor).doesNotContain("\"block_id\":" + rest + ",");
    }

    @Test
    void d2_snoozedWindowIsRecordOnlyAndShownInContext() {
        long b = planned("영어", "2026-11-02T10:00", 60, "study");
        long iid = db.insert("INSERT INTO interventions(block_id, status, level, block_version, started_at, ended_at, end_reason) VALUES (?, 'snoozed', 3, 1, ?, ?, 'moved')",
                b, clock.now().minusSeconds(60), clock.now());
        Map<String, Object> r = report(b, "인스타", 18, false);
        assertThat(r).containsEntry("record_only", true).doesNotContainKey("action");
        assertThat(store.runningForBlock(b)).isEmpty();
        String ctx = tx.run(() -> contextBuilder.build("block_missed", Json.parse("{\"block_id\":" + b + ",\"reason\":\"no_response\"}")));
        assertThat(ctx).contains("미룬 동안 인스타 18분");
        assertThat(plan.syncData().get("monitor")).contains("\"record_only\":true");
        assertThat(iid).isPositive();
    }

    @Test
    void d3_notStartedL1JumpsToL3() {
        long b = planned("영어", "2026-11-02T09:05", 60, "study");
        runUntil("2026-11-02T09:06");
        assertThat(store.runningForBlock(b).orElseThrow().level()).isEqualTo(1);
        assertThat(report(b, "인스타", 3, false)).containsEntry("action", "raised_to_L3");
        assertThat(store.runningForBlock(b).orElseThrow().level()).isEqualTo(3);
    }

    @Test
    void d4_d5_startedBlockFirstL1ThenL3AndReturnEndsIt() {
        long b = planned("영어", "2026-11-02T09:05", 60, "study");
        runUntil("2026-11-02T09:06");
        startBlock(b);
        assertThat(block(b).status()).isEqualTo("started");
        assertThat(report(b, "인스타", 3, false, "x1")).containsEntry("action", "L1");
        Store.Intervention i = store.runningForBlock(b).orElseThrow();
        assertThat(i.level()).isEqualTo(1);
        assertThat(block(b).status()).isEqualTo("started"); // 진행 중 블록 상태는 그대로
        assertThat(db.jdbc().sql("SELECT COUNT(*) FROM turns WHERE trigger = 'distraction'").query(Integer.class).single()).isEqualTo(1);
        // D5: 다른 앱으로 1분 넘게 → 끝 보고 → 개입 자동 종료(returned)
        push.clear();
        assertThat(report(b, "인스타", 5, true, "x1")).containsEntry("returned", true);
        assertThat(store.intervention(i.id()).orElseThrow().endReason()).isEqualTo("returned");
        assertThat(push.ofType("intervention.end")).isNotEmpty();
        // 두 번째 딴짓은 L3 부터
        assertThat(report(b, "유튜브", 3, false, "x2")).containsEntry("action", "L3");
        assertThat(store.runningForBlock(b).orElseThrow().level()).isEqualTo(3);
        assertThat(db.jdbc().sql("SELECT distraction_minutes FROM block_facts WHERE block_id = ?").param(b)
                .query(Integer.class).optional().orElse(0)).isGreaterThanOrEqualTo(0);
    }

    JsonNode judgeReq(long b, String title) {
        return Json.parse("{\"block_id\":" + b + ",\"kind\":\"video\",\"title\":\"" + title + "\"}");
    }

    @Test
    void d6_d7_judgeCacheAndHourlyLimit() {
        long b = planned("컴활 기출", "2026-11-02T09:05", 60, "study");
        llm.then(text("{\"verdict\":\"related\"}"));
        assertThat(tx.run(() -> distractions.judge(judgeReq(b, "컴활 1급 엑셀 함수 정리")))).containsEntry("verdict", "related");
        int afterFirst = llm.userContents.size();
        assertThat(tx.run(() -> distractions.judge(judgeReq(b, "컴활 1급 엑셀 함수 정리")))).containsEntry("cached", true);
        assertThat(llm.userContents.size()).isEqualTo(afterFirst); // 두 번째는 캐시(LLM 호출 없음)
        // D7: 시간당 한도
        putSetting("distract.judge_max_per_hour", "1");
        assertThat(tx.run(() -> distractions.judge(judgeReq(b, "예능 하이라이트")))).containsEntry("verdict", "unsure")
                .containsEntry("limited", true);
    }

    @Test
    void d8_fourthDistractionUsesTemplate() {
        putSetting("distract.llm_max_per_block", "3");
        long b = planned("영어", "2026-11-02T09:05", 120, "study");
        runUntil("2026-11-02T09:06");
        startBlock(b);
        for (int n = 1; n <= 4; n++) {
            report(b, "인스타", 3, false, "t" + n);
            report(b, "인스타", 4, true, "t" + n);
        }
        assertThat(db.jdbc().sql("SELECT COUNT(*) FROM turns WHERE trigger = 'distraction'").query(Integer.class).single()).isEqualTo(3);
        assertThat(db.jdbc().sql("SELECT COUNT(*) FROM messages WHERE content LIKE '인스타 3분째예요.%'").query(Integer.class).single())
                .isEqualTo(1);
    }

    @Test
    void d9_assistantCannotChangeRulesOrWatch() throws Exception {
        String toolsJson = new String(getClass().getResourceAsStream("/assistant/agent/tools.json").readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
        for (JsonNode t : Json.parse(toolsJson)) {
            assertThat(t.path("name").asString()).doesNotContain("app_rule").doesNotContain("watch").doesNotContain("allowed");
        }
        for (String name : List.of("set_app_rules", "set_block_watch", "update_settings")) {
            assertThat(tool("user_message", name, "{\"package\":\"com.instagram.android\"}").path("ok").asBoolean(true)).isFalse();
        }
        long b = planned("영어", "2026-11-02T10:00", 60, "study");
        ObjectNode r = tool("user_message", "set_schedule", "{\"from\":\"" + iso("2026-11-02T10:00") + "\",\"to\":\"" + iso("2026-11-02T11:00")
                + "\",\"blocks\":[],\"watch\":\"off\",\"allowed_apps\":[\"x\"]}");
        assertThat(db.jdbc().sql("SELECT watch FROM blocks WHERE id = ?").param(b).query(String.class).optional().orElse(null)).isNull();
        assertThat(r).isNotNull();
    }

    @Test
    void d10_cleanupRemovesTitlesAfterSevenDays() {
        long b = planned("영어", "2026-11-02T09:05", 60, "study");
        db.insert("""
                        INSERT INTO distraction_logs(block_id, logical_day, package, app_label, kind, domain, title, started_at, minutes)
                        VALUES (?, ?, 'com.google.android.youtube', '유튜브', 'video', 'youtube.com', '어떤 영상', ?, 12)""",
                b, java.time.LocalDate.of(2026, 10, 25), clock.now().minus(java.time.Duration.ofDays(8)));
        assertThat(tx.run(distractions::cleanup)).isEqualTo(1);
        record R(String title, String domain, String app, int minutes) {
        }
        R r = db.jdbc().sql("SELECT title, domain, app_label, minutes FROM distraction_logs").query((rs, n) ->
                new R(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4))).single();
        assertThat(r.title()).isNull();
        assertThat(r.domain()).isNull();
        assertThat(r.app()).isEqualTo("유튜브");
        assertThat(r.minutes()).isEqualTo(12);
    }

    @Test
    void rulesAreUserOnlyAndSynced() {
        Map<String, Object> r = tx.run(() -> distractions.replaceRules(Json.parse(
                "{\"rules\":[{\"package\":\"com.instagram.android\",\"label\":\"인스타그램\",\"kind\":\"distract\",\"set_by\":\"default\"},"
                        + "{\"package\":\"com.google.android.youtube\",\"label\":\"YouTube\",\"kind\":\"ambiguous\"}],"
                        + "\"watch_categories\":[\"study\"]}")));
        assertThat((List<?>) r.get("rules")).hasSize(2);
        assertThat(plan.syncData().get("app_rules")).contains("com.instagram.android");
        assertThat(settings.get("distract.watch_categories").toString()).isEqualTo("[\"study\"]");
    }
}
