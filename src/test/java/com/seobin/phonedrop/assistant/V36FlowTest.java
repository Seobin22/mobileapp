package com.seobin.phonedrop.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.seobin.phonedrop.assistant.agent.ChatService;
import com.seobin.phonedrop.assistant.agent.ContextBuilder;
import com.seobin.phonedrop.assistant.agent.TimeWindowRunner;
import com.seobin.phonedrop.assistant.agent.TimeWindows;
import com.seobin.phonedrop.assistant.agent.Tools;
import com.seobin.phonedrop.assistant.api.TimeWindowController;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** 빌드문서 v3.6 §7 T1~T13 (시간대). 시작 2026-11-02(월) 09:00, 기상 07:30·취침 23:30. */
class V36FlowTest extends AssistantTestBase {

    @Autowired
    TimeWindowController api;
    @Autowired
    TimeWindows windows;
    @Autowired
    TimeWindowRunner runner;
    @Autowired
    Tools agentTools;
    @Autowired
    ChatService chat;
    @Autowired
    ContextBuilder contextBuilder;
    @Autowired
    TimeUtil time;

    static String iso(String local) {
        return at(local).atZone(KST).toOffsetDateTime().toString();
    }

    static String dnd(String name, String start, String end) {
        return "{\"kind\":\"dnd\",\"name\":\"" + name + "\",\"days\":[1,2,3,4,5,6,7],\"start_time\":\"" + start
                + "\",\"end_type\":\"time\",\"end_time\":\"" + end + "\"}";
    }

    static String active(String name, String start, String end) {
        return "{\"kind\":\"active\",\"name\":\"" + name + "\",\"days\":[1,2,3,4,5,6,7],\"start_time\":\"" + start
                + "\",\"end_type\":\"time\",\"end_time\":\"" + end + "\"}";
    }

    static String activeFirstBlock(String name, String start) {
        return "{\"kind\":\"active\",\"name\":\"" + name + "\",\"days\":[1,2,3,4,5,6,7],\"start_time\":\"" + start
                + "\",\"end_type\":\"first_block\",\"end_fallback_time\":\"12:00\"}";
    }

    Map<String, Object> save(String... items) {
        return api.put(Json.parse("{\"windows\":[" + String.join(",", items) + "]}"));
    }

    ObjectNode tool(String trigger, String name, String inputJson) {
        long turnId = db.insert("INSERT INTO turns(trigger, status, started_at) VALUES (?, 'running', ?)",
                trigger, clock.now());
        Tools.Context ctx = new Tools.Context(turnId, trigger, false);
        return tx.run(() -> agentTools.dispatch(ctx, name, Json.parse(inputJson)));
    }

    int count(String sql) {
        return db.jdbc().sql(sql).query(Integer.class).single();
    }

    List<Map<String, String>> chatPushes() {
        return push.ofType("chat.message");
    }

    // ------------------------------------------------------------------ T1 저장·겹침

    @Test
    void t1_saveAndRejectOverlap() {
        Map<String, Object> r = save(dnd("밤", "23:00", "07:00"), activeFirstBlock("아침", "07:00"));
        assertThat((List<?>) r.get("windows")).hasSize(2);
        assertThatThrownBy(() -> save(dnd("밤", "23:00", "07:00"), active("아침", "06:30", "08:00")))
                .isInstanceOf(ApiException.class).hasMessageContaining("겹쳐요");
        // 꺼진 시간대는 겹쳐도 된다
        save(dnd("밤", "23:00", "07:00"),
                "{\"kind\":\"active\",\"name\":\"아침\",\"days\":[1],\"start_time\":\"06:30\",\"end_type\":\"time\",\"end_time\":\"08:00\",\"enabled\":false}");
        assertThat(windows.list()).hasSize(2);
    }

    // ------------------------------------------------------------------ T2 비서는 못 바꾼다

    @Test
    void t2_assistantCannotWriteTimeWindowsOrActiveSettings() throws Exception {
        String toolsJson = new String(getClass().getResourceAsStream("/assistant/agent/tools.json").readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
        for (JsonNode t : Json.parse(toolsJson)) {
            String name = t.path("name").asString();
            assertThat(name).doesNotContain("setting").doesNotContain("time_window").doesNotContain("window");
        }
        for (String name : List.of("update_settings", "set_time_window", "upsert_time_window", "set_setting")) {
            ObjectNode r = tool("user_message", name, "{\"key\":\"active.llm_max\",\"value\":99}");
            assertThat(r.path("ok").asBoolean(true)).as(name).isFalse();
        }
        assertThat(settings.getInt("active.llm_max")).isEqualTo(12);
    }

    // ------------------------------------------------------------------ T3·T5·T6 방해 금지

    @Test
    void t3_dndHoldsProactiveBlocksInterventionAndReleasesOnce() {
        long blockId = createBlock("보고서", "2026-11-02T10:30", 30)[1];
        save(dnd("회의 전 집중", "10:00", "11:00"));
        runUntil("2026-11-02T10:10");
        push.clear();

        ObjectNode r1 = tool("pulse", "send_message", "{\"text\":\"점심 전에 영어 단어 볼까요?\",\"kind\":\"proactive\"}");
        ObjectNode r2 = tool("wakeup", "send_message", "{\"text\":\"내일 마감 잊지 마세요\",\"kind\":\"proactive\"}");
        assertThat(r1.path("ok").asBoolean()).isTrue();
        assertThat(r2.path("ok").asBoolean()).isTrue();
        assertThat(count("SELECT COUNT(*) FROM messages WHERE held_window_id IS NOT NULL")).isEqualTo(2);
        assertThat(chatPushes()).isEmpty();

        runUntil("2026-11-02T10:59");
        assertThat(interventionCount()).isZero(); // 10:30 블록 개입 없음
        assertThat(push.ofType("intervention.level")).isEmpty();
        assertThat(chatPushes()).isEmpty();

        runUntil("2026-11-02T11:06");
        assertThat(count("SELECT COUNT(*) FROM messages WHERE held_window_id IS NOT NULL")).isZero();
        List<Map<String, String>> sent = chatPushes();
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).get("preview")).contains("회의 전 집중");
        assertThat(block(blockId).status()).isNotEqualTo("nudging");
    }

    @Test
    void t5_dndReplyToUserIsSilent() {
        save(dnd("수업", "08:00", "12:00"));
        push.clear();
        ObjectNode r = tool("user_message", "send_message", "{\"text\":\"네, 넣어 둘게요\",\"kind\":\"reply\"}");
        assertThat(r.path("ok").asBoolean()).isTrue();
        Map<String, String> p = chatPushes().getLast();
        assertThat(p.get("silent")).isEqualTo("true");
        assertThat(p).doesNotContainKey("speak");
    }

    @Test
    void t6_plannedBlockInDndIsRejected() {
        save(dnd("수업", "13:00", "15:00"));
        long taskId = tool("user_message", "upsert_task",
                "{\"title\":\"영어\",\"first_action\":\"열기\",\"est_minutes\":30}").get("id").asLong();
        ObjectNode r = tool("user_message", "set_schedule", "{\"from\":\"" + iso("2026-11-02T13:00") + "\",\"to\":\""
                + iso("2026-11-02T16:00") + "\",\"blocks\":[{\"task_id\":" + taskId + ",\"start\":\""
                + iso("2026-11-02T13:30") + "\",\"end\":\"" + iso("2026-11-02T14:00") + "\"}]}");
        assertThat(r.path("ok").asBoolean(true)).as(r.toString()).isFalse();
        assertThat(time.isQuiet(at("2026-11-02T14:00"))).isTrue();
        assertThat(time.isQuiet(at("2026-11-02T15:30"))).isFalse();
    }

    // ------------------------------------------------------------------ T7·T8 first_block

    @Test
    void t7_firstBlockEndFollowsBlockMove() {
        long blockId = createBlock("보고서", "2026-11-02T11:00", 30)[1];
        save(activeFirstBlock("아침", "09:30"));
        TimeWindows.Occ o = windows.occurrences(at("2026-11-02T09:00"), at("2026-11-02T23:00")).getFirst();
        assertThat(o.end()).isEqualTo(at("2026-11-02T11:00"));
        db.update("UPDATE blocks SET start_at = ?, end_at = ? WHERE id = ?",
                AssistantDb.ts(at("2026-11-02T12:30")), AssistantDb.ts(at("2026-11-02T13:00")), blockId);
        o = windows.occurrences(at("2026-11-02T09:00"), at("2026-11-02T23:00")).getFirst();
        assertThat(o.end()).isEqualTo(at("2026-11-02T12:30"));
        // 첫 블록이 시간대 시작 전이면 그날은 열리지 않는다
        createBlock("운동", "2026-11-02T09:10", 10);
        assertThat(windows.occurrences(at("2026-11-02T09:00"), at("2026-11-02T23:00"))).isEmpty();
    }

    @Test
    void t8_noBlockEndsAtFallback() {
        save(activeFirstBlock("아침", "09:30"));
        TimeWindows.Occ o = windows.occurrences(at("2026-11-02T09:00"), at("2026-11-02T23:00")).getFirst();
        assertThat(o.end()).isEqualTo(at("2026-11-02T12:00"));
        assertThat(o.fallback()).isTrue();
        Map<String, Object> state = api.get();
        assertThat((List<?>) state.get("today")).hasSize(1);
    }

    // ------------------------------------------------------------------ T9·T10·T11 적극 개입

    @Test
    void t9_t10_alarmRepeatsUntilAckThenLimitedNudges() {
        putSetting("active.llm_max", "3");
        save(active("아침 몰입", "09:00", "12:00"));
        advanceAndRun(7); // 09:00, 09:03, 09:06
        assertThat(push.ofType("active.alarm")).hasSize(3);

        tx.run(() -> runner.ack(true));
        assertThat(push.ofType("active.alarm_end")).isNotEmpty();
        assertThat(count("SELECT COUNT(*) FROM turns WHERE trigger = 'active_window'")).isEqualTo(1);

        int alarms = push.ofType("active.alarm").size();
        advanceAndRun(60);
        assertThat(push.ofType("active.alarm")).hasSize(alarms); // 반응한 뒤에는 알람 없음
        // 최소 10분 간격, 회차당 최대 3번(설정)
        List<Instant> at = db.jdbc().sql("SELECT started_at FROM turns WHERE trigger = 'active_window' ORDER BY id")
                .query((rs, n) -> AssistantDb.instant(rs, "started_at")).list();
        assertThat(at).hasSize(3);
        for (int i = 1; i < at.size(); i++) {
            assertThat(java.time.Duration.between(at.get(i - 1), at.get(i)).toMinutes()).isGreaterThanOrEqualTo(10);
        }
    }

    @Test
    void t9b_activeIgnoresMessageBudgetAndSpeaks() {
        putSetting("messages.daily_budget", "0");
        save(active("아침 몰입", "09:00", "12:00"));
        advanceAndRun(1);
        push.clear();
        ObjectNode r = tool("active_window", "send_message", "{\"text\":\"영어부터 시작하자\",\"kind\":\"proactive\"}");
        assertThat(r.path("silent").asBoolean(true)).isFalse();
        Map<String, String> p = chatPushes().getLast();
        assertThat(p.get("speak")).isEqualTo("true");
        assertThat(p.get("text")).isEqualTo("영어부터 시작하자");
        String ctx = tx.run(() -> contextBuilder.build("active_window", Json.parse("{\"reason\":\"nudge\",\"name\":\"아침 몰입\",\"until\":\"" + iso("2026-11-02T12:00") + "\",\"count\":1}")));
        assertThat(ctx).contains("[적극 개입 시간대] 아침 몰입").contains("지금: 적극 개입 시간대");
    }

    @Test
    void t11_pauseReturnsToDefaultForTenMinutes() {
        save(active("아침 몰입", "09:00", "12:00"));
        advanceAndRun(1);
        assertThat(push.ofType("active.alarm")).hasSize(1);
        tx.run(() -> runner.pause());
        advanceAndRun(9);
        assertThat(push.ofType("active.alarm")).hasSize(1);
        advanceAndRun(3);
        assertThat(push.ofType("active.alarm")).hasSize(2);
        assertThat(plan.syncData().get("time_windows")).contains("아침 몰입");
    }

    @Test
    void t13_fixedBlockInProgressWinsOverActive() {
        ObjectNode fixed = tool("user_message", "upsert_fixed_block", "{\"title\":\"수업\",\"start\":\""
                + iso("2026-11-02T09:00") + "\",\"end\":\"" + iso("2026-11-02T10:00") + "\",\"interrupt_level\":\"quiet\"}");
        assertThat(fixed.path("ok").asBoolean()).as(fixed.toString()).isTrue();
        save(active("아침 몰입", "09:00", "12:00"));
        advanceAndRun(30);
        assertThat(push.ofType("active.alarm")).isEmpty();
        // 수업 개입 화면(끝 + 15분 유예)이 닫힌 뒤에는 적극 개입 알람
        runUntil("2026-11-02T10:22");
        assertThat(push.ofType("active.alarm")).isNotEmpty();
    }

    @Autowired
    com.seobin.phonedrop.assistant.api.GoalController goalApi;

    @Test
    void fixedBlockInDndIsSavedWithWarning() {
        save(dnd("수업", "13:00", "15:00"));
        Map<String, Object> r = goalApi.createFixed(Json.parse("{\"title\":\"면담\",\"start\":\"" + iso("2026-11-02T14:00")
                + "\",\"duration_min\":30}"));
        assertThat(r.get("ok")).isEqualTo(true);
        assertThat(r.get("dnd_warning")).isNotNull();
        Map<String, Object> r2 = goalApi.createFixed(Json.parse("{\"title\":\"운동\",\"start\":\"" + iso("2026-11-02T16:00")
                + "\",\"duration_min\":30}"));
        assertThat(r2).doesNotContainKey("dnd_warning");
    }

    @Test
    void activeOverridesSleepQuietButDndWins() {
        save(active("새벽 운동", "06:00", "07:00"), dnd("밤", "23:00", "05:30"));
        assertThat(time.isQuiet(at("2026-11-03T06:30"))).isFalse(); // 수면 시간이지만 적극 개입
        assertThat(time.isQuiet(at("2026-11-03T07:10"))).isTrue(); // 다시 수면(기상 07:30)
        assertThat(time.isQuiet(at("2026-11-03T05:00"))).isTrue();
        assertThat(windows.dndAt(at("2026-11-03T05:00"))).isPresent();
    }
}
