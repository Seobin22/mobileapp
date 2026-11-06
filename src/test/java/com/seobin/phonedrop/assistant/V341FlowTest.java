package com.seobin.phonedrop.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.seobin.phonedrop.assistant.agent.MapApi;
import com.seobin.phonedrop.assistant.agent.Tools;
import com.seobin.phonedrop.assistant.agent.TravelService;
import com.seobin.phonedrop.assistant.api.GoalController;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** 2026-11-06 이동 블록 보강: 백업 알람·출발 미루기·이동 시간 수정·재계산 일원화. 시작 11/02(월) 09:00. */
class V341FlowTest extends AssistantTestBase {

    @Autowired
    Tools agentTools;
    @Autowired
    GoalController goalApi;
    @Autowired
    TravelService travel;

    static String iso(String local) {
        return at(local).atZone(KST).toOffsetDateTime().toString();
    }

    void setup() {
        putSetting("travel.home_lat", "37.4000");
        putSetting("travel.home_lng", "127.0000");
        mapApi.places.put("치과", new MapApi.Place("치과", "", 37.5000, 127.0300));
        mapApi.routeMinutes.put("37.4000>37.5000", 45);
        mapApi.routeMinutes.put("37.5000>37.4000", 35);
    }

    long fixed(String title, String startLocal, int minutes, String location) {
        ObjectNode req = Json.object();
        req.put("title", title);
        req.put("start", iso(startLocal));
        req.put("duration_min", minutes);
        req.put("lead_minutes", 0);
        if (location != null) {
            req.put("location", location);
        }
        return ((Number) goalApi.createFixed(req).get("id")).longValue();
    }

    Store.Block travel(long fixedId, String dir) {
        return db.jdbc().sql("""
                        SELECT * FROM blocks WHERE link_role = 'travel' AND linked_fixed_block_id = ? AND travel_direction = ?
                        AND status <> 'cancelled'""").param(fixedId).param(dir).query(Store::block).optional().orElse(null);
    }

    ObjectNode tool(String trigger, String name, String json) {
        long turnId = db.insert("INSERT INTO turns(trigger, status, started_at) VALUES (?, 'running', ?)", trigger, clock.now());
        return tx.run(() -> agentTools.dispatch(new Tools.Context(turnId, trigger, false), name, Json.parse(json)));
    }

    List<JsonNode> upcoming() {
        JsonNode list = Json.parse(tx.run(plan::syncData).get("upcoming"));
        List<JsonNode> out = new java.util.ArrayList<>();
        list.forEach(out::add);
        return out;
    }

    Map<String, Object> localFired(long blockId, Integer version, String key) {
        ObjectNode req = Json.object();
        req.put("idempotency_key", key);
        req.put("type", "local_fired");
        req.put("block_id", blockId);
        if (version != null) {
            req.put("block_version", version);
        }
        return tx.run(() -> interventions.action(req));
    }

    @Test
    void backupListSkipsFixedWithTravelAndHomeAndUsesDepartTime() {
        setup();
        long dentist = fixed("치과", "2026-11-02T15:00", 60, "치과");
        long plain = fixed("독서", "2026-11-02T12:00", 30, null);
        Store.Block arrive = travel(dentist, "arrive");
        Store.Block home = travel(dentist, "home");
        List<JsonNode> list = upcoming();
        assertThat(list).extracting(n -> n.path("block_id").asLong()).contains(arrive.id(), plain).doesNotContain(dentist, home.id());
        JsonNode a = list.stream().filter(n -> n.path("block_id").asLong() == arrive.id()).findFirst().orElseThrow();
        assertThat(TimeUtil.parse(a.path("alarm_at").asString())).isEqualTo(at("2026-11-02T14:15"));
        assertThat(a.path("version").asInt()).isEqualTo(arrive.version());
    }

    @Test
    void staleLocalAlarmsAreIgnored() {
        setup();
        long dentist = fixed("치과", "2026-11-02T15:00", 60, "치과");
        long plain = fixed("독서", "2026-11-02T12:00", 30, null);
        Store.Block home = travel(dentist, "home");
        // 시각이 아님(1시간 이른 옛 알람)
        assertThat(localFired(plain, null, "a").get("status")).isEqualTo("ignored");
        runUntil("2026-11-02T11:59");
        // version 이 다르면 무시
        assertThat(localFired(plain, block(plain).version() + 1, "b").get("status")).isEqualTo("ignored");
        assertThat(localFired(plain, block(plain).version(), "c").get("status")).isEqualTo("ok");
        assertThat(store.runningForBlock(plain)).isPresent();
        // 이동 블록이 출발 화면을 맡은 고정 일정, 귀가 블록
        runUntil("2026-11-02T14:59");
        assertThat(localFired(dentist, null, "d").get("status")).isEqualTo("ignored");
        runUntil("2026-11-02T16:01");
        assertThat(localFired(home.id(), null, "e").get("status")).isEqualTo("ignored");
        assertThat(store.runningForBlock(home.id())).isEmpty();
    }

    @Test
    void assistantDelaysDepartureWithoutMovingAppointment() {
        setup();
        long dentist = fixed("치과", "2026-11-02T15:00", 60, "치과");
        Store.Block t = travel(dentist, "arrive");
        runUntil("2026-11-02T14:16");
        assertThat(block(t.id()).status()).isEqualTo("nudging");
        int v = block(t.id()).version();
        JsonNode r = tool("intervention_talk", "adjust_travel", "{\"block_id\":" + t.id() + ",\"delay_minutes\":10}");
        assertThat(r.path("ok").asBoolean()).isTrue();
        Store.Block after = block(t.id());
        assertThat(after.start()).isEqualTo(at("2026-11-02T14:26")); // 출발 시각이 지났으면 지금부터 10분
        assertThat(after.travelLateMin()).isEqualTo(11);
        assertThat(after.version()).isGreaterThan(v);
        assertThat(after.status()).isEqualTo("planned");
        assertThat(store.runningForBlock(t.id())).isEmpty(); // 지금 화면은 닫힘
        assertThat(block(dentist).start()).isEqualTo(at("2026-11-02T15:00")); // 약속은 그대로
        tx.exec(() -> travel.computeDay(LocalDate.parse("2026-11-02")));
        assertThat(block(t.id()).start()).isEqualTo(at("2026-11-02T14:26")); // 재계산해도 유지
        runUntil("2026-11-02T14:27");
        assertThat(store.runningForBlock(t.id())).isPresent(); // 새 출발 시각에 다시
        // 약속 시작을 넘겨 미룰 수는 없다
        assertThat(tool("user_message", "adjust_travel", "{\"block_id\":" + dentist + ",\"delay_minutes\":60}")
                .path("ok").asBoolean()).isFalse();
        // 사용자 말이 아니면 못 고친다
        assertThat(tool("pulse", "adjust_travel", "{\"block_id\":" + dentist + ",\"minutes\":20}").path("ok").asBoolean()).isFalse();
    }

    @Test
    void assistantFixesTravelMinutesAndResets() {
        setup();
        long dentist = fixed("치과", "2026-11-02T15:00", 60, "치과");
        Store.Block t = travel(dentist, "arrive");
        assertThat(tool("user_message", "adjust_travel", "{\"block_id\":" + dentist + ",\"minutes\":20}").path("ok").asBoolean()).isTrue();
        assertThat(block(t.id()).start()).isEqualTo(at("2026-11-02T14:40"));
        assertThat(tool("user_message", "adjust_travel", "{\"block_id\":" + t.id() + ",\"reset\":true}").path("ok").asBoolean()).isTrue();
        assertThat(block(t.id()).start()).isEqualTo(at("2026-11-02T14:15"));
    }

    @Test
    void talkCannotMoveAppointmentWithoutConfirmButStudyBlocksStillSnooze() {
        setup();
        long dentist = fixed("치과", "2026-11-02T15:00", 60, "치과");
        JsonNode r = tool("intervention_talk", "move_block", "{\"block_id\":" + dentist + ",\"new_start\":\"" + iso("2026-11-02T16:00") + "\"}");
        assertThat(r.path("pending_confirmation_id").asLong()).isPositive();
        assertThat(block(dentist).start()).isEqualTo(at("2026-11-02T15:00"));
        long study = fixed("공부", "2026-11-02T11:00", 60, null);
        assertThat(tool("intervention_talk", "move_block", "{\"block_id\":" + study + ",\"new_start\":\"" + iso("2026-11-02T11:10") + "\"}")
                .path("ok").asBoolean()).isTrue();
    }

    @Test
    void movingToAnotherDayCleansOldDayAndNudgingTravelIsReshown() {
        setup();
        long dentist = fixed("치과", "2026-11-02T15:00", 60, "치과");
        Store.Block home = travel(dentist, "home");
        ObjectNode req = Json.object();
        req.put("start", iso("2026-11-03T15:00"));
        req.put("duration_min", 60);
        goalApi.patchFixed(dentist, req);
        assertThat(block(home.id()).status()).isEqualTo("cancelled".equals(block(home.id()).status()) ? "cancelled" : "planned");
        assertThat(travel(dentist, "home").start()).isEqualTo(at("2026-11-03T16:00"));
        assertThat(db.jdbc().sql("""
                        SELECT COUNT(*) FROM blocks WHERE link_role = 'travel' AND status = 'planned' AND start_at < ?""")
                .param(AssistantDb.ts(at("2026-11-03T00:00"))).query(Integer.class).single()).isZero();

        // 출발 화면이 떠 있는 동안 약속이 바뀌면 그 화면은 닫고 새 시각에 다시
        long cafe = fixed("카페", "2026-11-02T13:00", 60, "치과");
        Store.Block t = travel(cafe, "arrive");
        runUntil("2026-11-02T12:16");
        assertThat(store.runningForBlock(t.id())).isPresent();
        ObjectNode later = Json.object();
        later.put("start", iso("2026-11-02T14:00"));
        later.put("duration_min", 60);
        goalApi.patchFixed(cafe, later);
        Store.Block moved = travel(cafe, "arrive");
        assertThat(moved.start()).isEqualTo(at("2026-11-02T13:15"));
        assertThat(store.runningForBlock(moved.id())).isEmpty();
        assertThat(moved.status()).isEqualTo("planned");
    }

    @Test
    void removingPlaceRestoresFixedAlarmAndNoChangeWritesNothing() {
        setup();
        long dentist = fixed("치과", "2026-11-02T15:00", 60, "치과");
        assertThat(db.jdbc().sql("SELECT COUNT(*) FROM jobs WHERE type = 'start_intervention' AND target_id = ? AND status = 'scheduled'")
                .param(dentist).query(Integer.class).single()).isZero();
        int logsBefore = db.jdbc().sql("SELECT COUNT(*) FROM block_logs").query(Integer.class).single();
        assertThat(tx.run(() -> travel.safetyCheck())).isFalse(); // 바뀐 게 없으면 기록·전송 없음
        assertThat(db.jdbc().sql("SELECT COUNT(*) FROM block_logs").query(Integer.class).single()).isEqualTo(logsBefore);

        ObjectNode req = Json.object();
        req.put("location", "");
        goalApi.patchFixed(dentist, req);
        assertThat(travel(dentist, "arrive")).isNull();
        assertThat(db.jdbc().sql("SELECT COUNT(*) FROM jobs WHERE type = 'start_intervention' AND target_id = ? AND status = 'scheduled'")
                .param(dentist).query(Integer.class).single()).isEqualTo(1);
    }
}
