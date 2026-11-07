package com.seobin.phonedrop.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.seobin.phonedrop.assistant.api.TimeWindowController;

import tools.jackson.databind.node.ObjectNode;

/** 빌드문서 v4 §9.1 E1~E8 (단계 올리기 L1·L3·L4). 시작 2026-11-02(월) 09:00. */
class V4FlowTest extends AssistantTestBase {

    @Autowired
    TimeWindowController windowApi;

    @BeforeEach
    void v4Levels() {
        settings.put("intervention.levels", Json.parse("[1,3,4]"));
        caps(true, true, true);
    }

    void caps(boolean notifications, boolean fullscreen, boolean accessibility) {
        db.update("UPDATE devices SET capabilities = ?", "{\"notifications\":" + notifications
                + ",\"fullscreen_intent\":" + fullscreen + ",\"accessibility\":" + accessibility + "}");
    }

    List<String> levelsPushed() {
        return push.ofType("intervention.level").stream().map(d -> d.get("level")).toList();
    }

    String lastLevel() {
        List<String> l = levelsPushed();
        return l.isEmpty() ? null : l.getLast();
    }

    @Test
    void e1_noResponseEscalatesL1L3L4ThenStops() {
        long blockId = createBlock("보고서", "2026-11-02T10:00", 60)[1];
        runUntil("2026-11-02T10:00");
        assertThat(lastLevel()).isEqualTo("1");
        assertThat(onlyIntervention().level()).isEqualTo(1);
        assertThat(db.jdbc().sql("SELECT COUNT(*) FROM messages WHERE kind = 'intervention'").query(Integer.class).single())
                .isEqualTo(1);
        runUntil("2026-11-02T10:04");
        assertThat(lastLevel()).isEqualTo("1");
        runUntil("2026-11-02T10:05");
        assertThat(lastLevel()).isEqualTo("3");
        runUntil("2026-11-02T10:14");
        assertThat(onlyIntervention().level()).isEqualTo(3);
        runUntil("2026-11-02T10:15");
        assertThat(onlyIntervention().level()).isEqualTo(4);
        Map<String, String> l4 = push.ofType("intervention.level").getLast();
        assertThat(l4.get("level")).isEqualTo("4");
        assertThat(l4.get("lock_apps")).isEqualTo("true");
        runUntil("2026-11-02T10:50");
        assertThat(onlyIntervention().level()).isEqualTo(4);
        assertThat(levelsPushed()).doesNotContain("2").containsOnly("1", "3", "4");
        assertThat(block(blockId).status()).isEqualTo("nudging");
    }

    @Test
    void e2_startAtL3StopsEscalation() {
        long blockId = createBlock("보고서", "2026-11-02T10:00", 60)[1];
        runUntil("2026-11-02T10:06");
        assertThat(onlyIntervention().level()).isEqualTo(3);
        action("start", onlyIntervention().id(), blockId, "s1");
        assertThat(onlyIntervention().status()).isEqualTo("acked");
        runUntil("2026-11-02T10:40");
        assertThat(levelsPushed()).doesNotContain("4");
        assertThat(db.jdbc().sql("SELECT status FROM jobs WHERE type = 'escalate' ORDER BY id DESC LIMIT 1")
                .query(String.class).single()).isIn("cancelled", "skipped");
    }

    @Test
    void e3_negotiationTimeoutResendsSameLevelThenEscalatesLater() {
        createBlock("보고서", "2026-11-02T10:00", 60);
        runUntil("2026-11-02T10:06");
        long id = onlyIntervention().id();
        tx.run(() -> interventions.userTalk(id, "잠깐만", "fullscreen", null));
        int before = levelsPushed().size();
        runUntil("2026-11-02T10:10");
        assertThat(levelsPushed()).hasSize(before); // 협상 중에는 escalate skipped
        runUntil("2026-11-02T10:11"); // 대화 5분 무응답
        assertThat(onlyIntervention().status()).isEqualTo("running");
        assertThat(lastLevel()).isEqualTo("3");
        runUntil("2026-11-02T10:20");
        assertThat(onlyIntervention().level()).isEqualTo(3);
        runUntil("2026-11-02T10:21");
        assertThat(onlyIntervention().level()).isEqualTo(4);
    }

    @Test
    void e4_noFullscreenGoesL1ToL4() {
        caps(true, false, true);
        createBlock("보고서", "2026-11-02T10:00", 60);
        runUntil("2026-11-02T10:05");
        assertThat(levelsPushed()).containsExactly("1", "4");
    }

    @Test
    void e5_noAccessibilityStopsAtL3() {
        caps(true, true, false);
        createBlock("보고서", "2026-11-02T10:00", 60);
        runUntil("2026-11-02T10:40");
        assertThat(onlyIntervention().level()).isEqualTo(3);
        assertThat(levelsPushed()).doesNotContain("4");
    }

    @Test
    void e6_dndWindowStartSuspendsRunningL4() {
        long blockId = createBlock("보고서", "2026-11-02T10:00", 60)[1];
        runUntil("2026-11-02T10:16");
        assertThat(onlyIntervention().level()).isEqualTo(4);
        windowApi.put(Json.parse("{\"windows\":[{\"kind\":\"dnd\",\"name\":\"수업\",\"days\":[1],\"start_time\":\"10:20\","
                + "\"end_type\":\"time\",\"end_time\":\"11:30\"}]}"));
        push.clear();
        runUntil("2026-11-02T10:21");
        assertThat(onlyIntervention().status()).isEqualTo("suspended");
        assertThat(push.ofType("intervention.end")).isNotEmpty();
        assertThat(block(blockId).status()).isEqualTo("missed");
    }

    @Test
    void e7_emergencyUnlock() {
        long blockId = createBlock("보고서", "2026-11-02T10:00", 60)[1];
        runUntil("2026-11-02T10:16");
        push.clear();
        action("emergency_unlock", onlyIntervention().id(), blockId, "e1");
        Store.Intervention i = onlyIntervention();
        assertThat(i.status()).isEqualTo("suspended");
        assertThat(i.endReason()).isEqualTo("emergency");
        assertThat(block(blockId).status()).isEqualTo("missed");
        assertThat(push.ofType("intervention.end")).singleElement()
                .satisfies(d -> assertThat(d.get("reason")).isEqualTo("emergency"));
        assertThat(db.jdbc().sql("SELECT trigger_ref FROM turns WHERE trigger = 'block_missed'").query(String.class).single())
                .contains("\"reason\":\"emergency\"");
        assertThat(db.jdbc().sql("SELECT COUNT(*) FROM intervention_logs WHERE reaction = 'emergency_unlock'")
                .query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void e8_activeWindowWaitsAreShorter() {
        windowApi.put(Json.parse("{\"windows\":[{\"kind\":\"active\",\"name\":\"아침\",\"days\":[1],\"start_time\":\"09:30\","
                + "\"end_type\":\"time\",\"end_time\":\"12:00\"}]}"));
        createBlock("보고서", "2026-11-02T10:00", 60);
        runUntil("2026-11-02T10:02");
        assertThat(onlyIntervention().level()).isEqualTo(1);
        runUntil("2026-11-02T10:03");
        assertThat(onlyIntervention().level()).isEqualTo(3);
        runUntil("2026-11-02T10:07");
        assertThat(onlyIntervention().level()).isEqualTo(3);
        runUntil("2026-11-02T10:08");
        assertThat(onlyIntervention().level()).isEqualTo(4);
    }

    @Autowired
    com.seobin.phonedrop.assistant.agent.Tools agentTools;

    ObjectNode tool(String trigger, String name, String json) {
        long turnId = db.insert("INSERT INTO turns(trigger, status, started_at) VALUES (?, 'running', ?)", trigger, clock.now());
        return tx.run(() -> agentTools.dispatch(new com.seobin.phonedrop.assistant.agent.Tools.Context(turnId, trigger, false),
                name, Json.parse(json)));
    }

    static String iso(String local) {
        return at(local).atZone(KST).toOffsetDateTime().toString();
    }

    @Test
    void otherFixedPrepStartSuspendsRunningIntervention() {
        // 2026-11-07 운영 버그: 알바 준비 시간(고정 일정 lead)이 시작돼도 다른 블록의 L4 잠금이 계속됐다
        ObjectNode fixed = tool("user_message", "upsert_fixed_block", "{\"title\":\"알바\",\"start\":\"" + iso("2026-11-02T11:00")
                + "\",\"end\":\"" + iso("2026-11-02T15:00") + "\",\"lead_minutes\":20,\"prep_from_user\":true}");
        assertThat(fixed.path("ok").asBoolean()).as(fixed.toString()).isTrue();
        long task = tool("user_message", "upsert_task", "{\"title\":\"러닝\",\"first_action\":\"신발\",\"est_minutes\":40}").get("id").asLong();
        ObjectNode r = tool("user_message", "set_schedule", "{\"from\":\"" + iso("2026-11-02T10:00") + "\",\"to\":\"" + iso("2026-11-02T10:40")
                + "\",\"blocks\":[{\"task_id\":" + task + ",\"start\":\"" + iso("2026-11-02T10:00") + "\",\"end\":\"" + iso("2026-11-02T10:40") + "\"}]}");
        long run = r.get("created").get(0).asLong();
        runUntil("2026-11-02T10:39");
        assertThat(store.runningForBlock(run).orElseThrow().level()).isEqualTo(4);
        push.clear();
        runUntil("2026-11-02T10:41"); // 알바 준비 시작 10:40
        Store.Intervention i = db.jdbc().sql("SELECT * FROM interventions WHERE block_id = ?").param(run).query(Store::intervention).single();
        assertThat(i.status()).isEqualTo("suspended");
        assertThat(i.endReason()).isEqualTo("fixed_block");
        assertThat(push.ofType("intervention.end")).isNotEmpty();
        assertThat(block(run).status()).isEqualTo("missed");
    }

    @Test
    void missedRoutineIsRecordedNotMoved() {
        // 2026-11-07: 아침 루틴을 못 했는데 비서가 옮길 수밖에 없어 '놓침'이 남지 않았다
        long task = tool("user_message", "upsert_task", "{\"title\":\"기상 루틴\",\"first_action\":\"물 마시기\",\"est_minutes\":20}").get("id").asLong();
        long b = tool("user_message", "set_schedule", "{\"from\":\"" + iso("2026-11-02T09:30") + "\",\"to\":\"" + iso("2026-11-02T09:50")
                + "\",\"blocks\":[{\"task_id\":" + task + ",\"start\":\"" + iso("2026-11-02T09:30") + "\",\"end\":\"" + iso("2026-11-02T09:50") + "\"}]}")
                .get("created").get(0).asLong();
        // 아직 시작 전이면 놓침으로 못 바꾼다
        assertThat(tool("user_message", "end_block", "{\"block_id\":" + b + ",\"outcome\":\"missed\"}").path("ok").asBoolean(true)).isFalse();
        runUntil("2026-11-02T09:36");
        assertThat(store.runningForBlock(b)).isPresent();
        ObjectNode r = tool("intervention_talk", "end_block", "{\"block_id\":" + b + ",\"outcome\":\"missed\"}");
        assertThat(r.path("ok").asBoolean()).as(r.toString()).isTrue();
        assertThat(block(b).status()).isEqualTo("missed");
        assertThat(store.runningForBlock(b)).isEmpty();
        assertThat(db.jdbc().sql("SELECT outcome FROM block_facts WHERE block_id = ?").param(b).query(String.class).single())
                .isEqualTo("missed");
        push.clear();
        runUntil("2026-11-02T10:20"); // 놓침으로 끝낸 뒤에는 그 블록 알람이 다시 오지 않는다
        assertThat(push.ofType("intervention.level").stream().filter(d -> String.valueOf(b).equals(d.get("block_id"))).count())
                .isZero();
    }

    @Test
    void unreachableDeviceIsNotIgnoring() {
        deviceAlive = false;
        long blockId = createBlock("보고서", "2026-11-02T10:00", 30)[1];
        runUntil("2026-11-02T10:50");
        assertThat(block(blockId).status()).isEqualTo("missed");
        assertThat(onlyIntervention().endReason()).isEqualTo("unreachable");
        assertThat(db.jdbc().sql("SELECT trigger_ref FROM turns WHERE trigger = 'block_missed'").query(String.class).single())
                .contains("\"reason\":\"unreachable\"");
    }
}
