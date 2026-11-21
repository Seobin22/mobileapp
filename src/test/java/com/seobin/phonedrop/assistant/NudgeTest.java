package com.seobin.phonedrop.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.seobin.phonedrop.assistant.agent.Tools;

import tools.jackson.databind.node.ObjectNode;

/**
 * 2026-11-20 블록별 알림 방식(비서가 정하고 코드가 실행) + 운영 버그 재현.
 * - 수업(고정 일정)이 3시간 동안 3분마다 잠금까지 독촉했다 → 기본 once
 * - 수업 뒤 귀가 이동이 시작되자 수업이 '놓침'이 됐다 → 딸린 이동은 다른 일정이 아니다
 * - "그만"이라고 해도 비서가 개입을 끝낼 수단이 없었다 → set_nudge·defer_blocks
 * 시작 2026-11-02(월) 09:00, 기상 07:30·취침 23:30.
 */
class NudgeTest extends AssistantTestBase {

    @Autowired
    Tools agentTools;
    @Autowired
    TimeUtil time;

    @BeforeEach
    void v4() {
        settings.put("intervention.levels", Json.parse("[1,3,4]"));
        settings.put("nudge.fixed_default", Json.parse("\"once\""));
        db.update("UPDATE devices SET capabilities = ?", "{\"notifications\":true,\"fullscreen_intent\":true,\"accessibility\":true}");
    }

    static String iso(String local) {
        return at(local).atZone(KST).toOffsetDateTime().toString();
    }

    ObjectNode tool(String trigger, String name, String json) {
        long turnId = db.insert("INSERT INTO turns(trigger, status, started_at) VALUES (?, 'running', ?)", trigger, clock.now());
        return tx.run(() -> agentTools.dispatch(new Tools.Context(turnId, trigger, false), name, Json.parse(json)));
    }

    long planned(String title, String startLocal, int minutes) {
        long task = tool("user_message", "upsert_task", "{\"title\":\"" + title + "\",\"first_action\":\"열기\",\"est_minutes\":"
                + minutes + "}").get("id").asLong();
        String end = iso(LocalDateTime.parse(startLocal).plusMinutes(minutes).toString());
        return tool("user_message", "set_schedule", "{\"from\":\"" + iso(startLocal) + "\",\"to\":\"" + end
                + "\",\"blocks\":[{\"task_id\":" + task + ",\"start\":\"" + iso(startLocal) + "\",\"end\":\"" + end + "\"}]}")
                .get("created").get(0).asLong();
    }

    int levelPushes(long blockId) {
        return (int) push.ofType("intervention.level").stream().filter(d -> String.valueOf(blockId).equals(d.get("block_id"))).count();
    }

    @Test
    void fixedClassNotifiesOnceAndCountsAsAttended() {
        long cls = createBlock("수업", "2026-11-02T10:00", 180)[1];
        runUntil("2026-11-02T10:00");
        assertThat(levelPushes(cls)).isEqualTo(1);
        runUntil("2026-11-02T12:59");
        assertThat(levelPushes(cls)).isEqualTo(1); // 단계 올리기·잠금·반복 없음
        assertThat(db.jdbc().sql("SELECT end_reason FROM interventions WHERE block_id = ?").param(cls)
                .query(String.class).single()).isEqualTo("notified");
        assertThat(block(cls).status()).isEqualTo("planned");
        runUntil("2026-11-02T13:01");
        assertThat(block(cls).status()).isEqualTo("done"); // [시작]을 안 눌렀다고 놓침이 아니다
        assertThat(db.jdbc().sql("SELECT COUNT(*) FROM turns WHERE trigger = 'block_missed'").query(Integer.class).single()).isZero();
    }

    @Test
    void ownReturnTravelDoesNotMakeClassMissed() {
        long cls = createBlock("수업", "2026-11-02T10:00", 60)[1];
        tool("user_message", "set_nudge", "{\"block_id\":" + cls + ",\"mode\":\"normal\"}");
        db.insert("""
                        INSERT INTO blocks(kind, title, start_at, end_at, status, screen_text, link_role, travel_direction,
                        linked_fixed_block_id) VALUES ('planned', '귀가', ?, ?, 'planned', '', 'travel', 'home', ?)""",
                at("2026-11-02T11:00"), at("2026-11-02T11:20"), cls);
        runUntil("2026-11-02T11:01");
        assertThat(db.jdbc().sql("SELECT end_reason FROM interventions WHERE block_id = ?").param(cls)
                .query(String.class).optional().orElse(null)).isNotEqualTo("fixed_block");
        assertThat(block(cls).status()).isNotEqualTo("missed");
    }

    @Test
    void assistantTurnsOffRunningInterventionWhenUserSaysSo() {
        long b = planned("장부 정리", "2026-11-02T09:30", 30);
        runUntil("2026-11-02T09:31");
        assertThat(store.runningForBlock(b)).isPresent();
        // 비서 혼자서는 off 못 함
        assertThat(tool("pulse", "set_nudge", "{\"block_id\":" + b + ",\"mode\":\"off\"}").path("ok").asBoolean(true)).isFalse();
        push.clear();
        ObjectNode r = tool("user_message", "set_nudge", "{\"block_id\":" + b + ",\"mode\":\"off\"}");
        assertThat(r.path("ok").asBoolean()).as(r.toString()).isTrue();
        assertThat(store.runningForBlock(b)).isEmpty();
        assertThat(push.ofType("intervention.end")).isNotEmpty();
        runUntil("2026-11-02T09:50");
        assertThat(levelPushes(b)).isZero();
        // 사용자가 정한 방식은 비서가 스스로 바꾸지 않는다
        assertThat(tool("pulse", "set_nudge", "{\"block_id\":" + b + ",\"mode\":\"strong\"}").path("skipped").toString())
                .contains("사용자가 정한");
    }

    @Test
    void deferBlocksMovesTonightToTomorrowFreeSlot() {
        long b = planned("장부 정리", "2026-11-02T09:30", 30);
        runUntil("2026-11-02T09:31");
        assertThat(tool("pulse", "defer_blocks", "{\"block_ids\":[" + b + "]}").path("ok").asBoolean(true)).isFalse();
        ObjectNode r = tool("user_message", "defer_blocks", "{\"block_ids\":[" + b + "],\"reason\":\"오늘 그만\"}");
        assertThat(r.path("ok").asBoolean()).as(r.toString()).isTrue();
        assertThat(store.runningForBlock(b)).isEmpty();
        assertThat(block(b).start()).isEqualTo(at("2026-11-03T07:30"));
        assertThat(block(b).status()).isEqualTo("planned");
    }

    @Test
    void pausedActiveWindowRestoresSleepQuiet() {
        db.insert("""
                        INSERT INTO time_windows(kind, name, days, start_time, end_type, end_time, enabled)
                        VALUES ('active', '밤', '[1,2,3,4,5,6,7]', '23:00', 'time', '03:00', TRUE)""");
        assertThat(time.isQuiet(at("2026-11-03T01:00"))).isFalse(); // 적극 개입이 수면 시간을 덮는다
        settings.put("active.paused_until", Json.MAPPER.getNodeFactory().stringNode(iso("2026-11-03T02:00")));
        assertThat(time.isQuiet(at("2026-11-03T01:00"))).isTrue(); // 멈춘 동안은 다시 조용한 시간
        assertThat(time.isQuiet(at("2026-11-03T02:30"))).isFalse();
    }

    @Test
    void chatDoneEndsRingingBlockAndItsAlarm() {
        // 2026-11-21 운영: 알림이 울리는 중 채팅으로 "완료했어" → end_block 이 started 만 받아 실패, 알림·잠금이 계속됐다
        long b = planned("장부 정리", "2026-11-02T09:30", 30);
        runUntil("2026-11-02T09:45");
        assertThat(store.runningForBlock(b)).isPresent();
        assertThat(block(b).status()).isEqualTo("nudging");
        // 비서 혼자서는 시작 안 한 블록을 완료로 못 바꾼다
        assertThat(tool("pulse", "end_block", "{\"block_id\":" + b + ",\"outcome\":\"done\"}").path("ok").asBoolean(true)).isFalse();
        push.clear();
        ObjectNode r = tool("user_message", "end_block", "{\"block_id\":" + b + ",\"outcome\":\"done\"}");
        assertThat(r.path("ok").asBoolean()).as(r.toString()).isTrue();
        assertThat(block(b).status()).isEqualTo("done");
        assertThat(store.runningForBlock(b)).isEmpty();
        assertThat(push.ofType("intervention.end")).isNotEmpty();
        runUntil("2026-11-02T10:10");
        assertThat(levelPushes(b)).isZero();
    }
}
