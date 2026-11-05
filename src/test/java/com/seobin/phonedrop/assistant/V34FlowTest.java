package com.seobin.phonedrop.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.seobin.phonedrop.assistant.agent.ContextBuilder;
import com.seobin.phonedrop.assistant.agent.MapApi;
import com.seobin.phonedrop.assistant.agent.Tools;
import com.seobin.phonedrop.assistant.agent.TravelBlocks;
import com.seobin.phonedrop.assistant.agent.TravelService;
import com.seobin.phonedrop.assistant.api.CalendarController;
import com.seobin.phonedrop.assistant.api.GoalController;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** 빌드문서 v3.4 §9 이동 블록 테스트. 시작 2026-11-02(월) 09:00, 집 (37.40, 127.00). */
class V34FlowTest extends AssistantTestBase {

    @Autowired
    Tools agentTools;
    @Autowired
    ContextBuilder contextBuilder;
    @Autowired
    CalendarController calendar;
    @Autowired
    GoalController goalApi;
    @Autowired
    TravelService travel;
    @Autowired
    TravelBlocks travelBlocks;

    static String iso(String local) {
        return at(local).atZone(KST).toOffsetDateTime().toString();
    }

    void home() {
        putSetting("travel.home_lat", "37.4000");
        putSetting("travel.home_lng", "127.0000");
    }

    void place(String name, double lat, double lng) {
        mapApi.places.put(name, new MapApi.Place(name, "", lat, lng));
    }

    /** 집 → 회의실 30분, 회의실 → 치과 40분, 치과 → 집 35분, 집 → 치과 45분 */
    void routes() {
        place("회의실", 37.4500, 127.0100);
        place("치과", 37.5000, 127.0300);
        place("치과2", 37.5000, 127.0300);
        mapApi.routeMinutes.put("37.4000>37.4500", 30);
        mapApi.routeMinutes.put("37.4500>37.5000", 40);
        mapApi.routeMinutes.put("37.5000>37.4000", 35);
        mapApi.routeMinutes.put("37.4000>37.5000", 45);
        mapApi.routeMinutes.put("37.4500>37.4000", 30);
    }

    long fixed(String title, String startLocal, int minutes, int lead, String location) {
        ObjectNode req = Json.object();
        req.put("title", title);
        req.put("start", iso(startLocal));
        req.put("duration_min", minutes);
        req.put("lead_minutes", lead);
        if (location != null) {
            req.put("location", location);
        }
        return ((Number) goalApi.createFixed(req).get("id")).longValue();
    }

    List<Store.Block> travels() {
        return db.jdbc().sql("SELECT * FROM blocks WHERE link_role = 'travel' AND status <> 'cancelled' ORDER BY start_at")
                .query(Store::block).list();
    }

    Store.Block travel(long fixedId, String direction) {
        return travels().stream().filter(t -> t.linkedFixedBlockId() == fixedId && direction.equals(t.travelDirection()))
                .findFirst().orElse(null);
    }

    ObjectNode tool(String trigger, String name, String inputJson) {
        long turnId = db.insert("INSERT INTO turns(trigger, status, started_at) VALUES (?, 'running', ?)", trigger, clock.now());
        return tx.run(() -> agentTools.dispatch(new Tools.Context(turnId, trigger, false), name, Json.parse(inputJson)));
    }

    int count(String sql, Object... params) {
        var spec = db.jdbc().sql(sql);
        for (Object p : params) {
            spec = spec.param(p);
        }
        return spec.query(Integer.class).single();
    }

    @Test
    void t1_t2_arriveAndHomeBlocksChainPlaces() {
        home();
        routes();
        long meeting = fixed("회의", "2026-11-02T13:00", 60, 0, "회의실");
        long dentist = fixed("치과", "2026-11-02T15:00", 60, 0, "치과");
        Store.Block a1 = travel(meeting, "arrive");
        assertThat(a1.start()).isEqualTo(at("2026-11-02T12:30"));
        assertThat(a1.end()).isEqualTo(at("2026-11-02T13:00"));
        assertThat(a1.title()).isEqualTo("이동: 집 → 회의 (대중교통 약 30분)");
        Store.Block a2 = travel(dentist, "arrive");
        assertThat(a2.title()).startsWith("이동: 회의 → 치과");
        assertThat(a2.start()).isEqualTo(at("2026-11-02T14:20"));
        assertThat(travel(meeting, "home")).isNull();
        Store.Block h = travel(dentist, "home");
        assertThat(h.start()).isEqualTo(at("2026-11-02T16:00"));
        assertThat(h.end()).isEqualTo(at("2026-11-02T16:35"));
        assertThat(a2.taskId()).isNull();
        assertThat(a2.kind()).isEqualTo("planned");
    }

    @Test
    void t3_samePlaceInARowHasNoTravel() {
        home();
        routes();
        fixed("치과", "2026-11-02T13:00", 60, 0, "치과");
        long second = fixed("치과 상담", "2026-11-02T14:30", 30, 0, "치과2");
        assertThat(travel(second, "arrive")).isNull();
    }

    @Test
    void t4_noHomeNoHomeTravelAndHintOnce() {
        routes();
        long meeting = fixed("회의", "2026-11-02T13:00", 60, 0, "회의실");
        fixed("회의2", "2026-11-03T13:00", 60, 0, "회의실");
        assertThat(travels()).isEmpty();
        assertThat(travel(meeting, "home")).isNull();
        assertThat(count("SELECT COUNT(*) FROM messages WHERE content LIKE '집 주소를 설정에 넣으면%'")).isEqualTo(1);
    }

    @Test
    void t5_t6_timeChangeRecomputesAndDeleteCancels() {
        home();
        routes();
        long dentist = fixed("치과", "2026-11-02T15:00", 60, 0, "치과");
        Store.Block before = travel(dentist, "arrive");
        ObjectNode req = Json.object();
        req.put("start", iso("2026-11-02T16:00"));
        req.put("duration_min", 60);
        goalApi.patchFixed(dentist, req);
        Store.Block after = travel(dentist, "arrive");
        assertThat(after.id()).isEqualTo(before.id());
        assertThat(after.start()).isEqualTo(at("2026-11-02T15:15"));
        assertThat(after.version()).isGreaterThan(before.version());
        assertThat(count("SELECT COUNT(*) FROM jobs WHERE type = 'start_intervention' AND target_id = ? AND status = 'scheduled'",
                after.id())).isEqualTo(1);

        goalApi.deleteFixed(dentist);
        assertThat(travels()).isEmpty();
    }

    @Test
    void t7_t8_extendPreviousMakesTightAndTightNotices() {
        home();
        routes();
        long meeting = fixed("회의", "2026-11-02T13:00", 60, 0, "회의실");
        long dentist = fixed("치과", "2026-11-02T15:00", 60, 10, "치과");
        assertThat(travel(dentist, "arrive").travelTightMin()).isNull();
        runUntil("2026-11-02T13:10");
        ObjectNode req = Json.object();
        req.put("end", iso("2026-11-02T14:40"));
        goalApi.patchFixed(meeting, req);
        Store.Block t = travel(dentist, "arrive");
        assertThat(t.start()).isEqualTo(at("2026-11-02T14:40"));
        assertThat(t.travelTightMin()).isEqualTo(30); // 40분 + 준비 10분 − 20분
        assertThat(t.leadMinutes()).isZero(); // 촉박이면 준비 알림 없음
        assertThat(count("SELECT COUNT(*) FROM jobs WHERE type = 'travel_notice' AND target_id = ? AND status = 'scheduled'"
                + " AND fire_at = ?", t.id(), AssistantDb.ts(at("2026-11-02T14:35")))).isEqualTo(1);
        assertThat(tx.run(() -> contextBuilder.build("user_message", Json.object())))
                .contains("이동 촉박: 회의(14:40 끝) → 치과(15:00) 이동 약 40분 · 30분 부족");
    }

    @Test
    void t9_tightAfterDndNoticesAtEnd() {
        home();
        routes();
        ObjectNode req = Json.object();
        req.put("title", "시험");
        req.put("start", iso("2026-11-02T13:00"));
        req.put("duration_min", 100);
        req.put("lead_minutes", 0);
        req.put("location", "회의실");
        req.put("interrupt_level", "dnd");
        goalApi.createFixed(req);
        long dentist = fixed("치과", "2026-11-02T15:00", 60, 0, "치과");
        Store.Block t = travel(dentist, "arrive");
        assertThat(t.travelTightMin()).isEqualTo(20);
        assertThat(count("SELECT COUNT(*) FROM jobs WHERE type = 'travel_notice' AND target_id = ? AND status = 'scheduled'"
                + " AND fire_at = ?", t.id(), AssistantDb.ts(at("2026-11-02T14:40")))).isEqualTo(1);
    }

    @Test
    void t10_t11_t12_assistantCannotTouchTravelBlocks() {
        home();
        routes();
        long dentist = fixed("치과", "2026-11-02T15:00", 60, 0, "치과");
        Store.Block t = travel(dentist, "arrive");
        long task = db.insert("INSERT INTO tasks(title, first_action, est_minutes, remaining_minutes) VALUES ('보고서', '열기', 60, 60)");
        JsonNode r = tool("user_message", "set_schedule", "{\"from\":\"" + iso("2026-11-02T09:30") + "\",\"to\":\""
                + iso("2026-11-02T20:00") + "\",\"blocks\":[{\"task_id\":" + task + ",\"start\":\"" + iso("2026-11-02T10:00")
                + "\",\"end\":\"" + iso("2026-11-02T11:00") + "\"}]}");
        assertThat(r.path("ok").asBoolean()).isTrue();
        assertThat(block(t.id()).status()).isEqualTo("planned");
        assertThat(tool("pulse", "move_block", "{\"block_id\":" + t.id() + ",\"new_start\":\"" + iso("2026-11-02T13:00") + "\"}")
                .path("error").asString()).contains("시스템이 관리");
        JsonNode overlap = tool("user_message", "set_schedule", "{\"from\":\"" + iso("2026-11-02T13:00") + "\",\"to\":\""
                + iso("2026-11-02T14:50") + "\",\"blocks\":[{\"task_id\":" + task + ",\"start\":\"" + iso("2026-11-02T14:00")
                + "\",\"end\":\"" + iso("2026-11-02T14:30") + "\"}]}");
        assertThat(overlap.path("ok").asBoolean()).isFalse();
    }

    @Test
    void t13_t15_noticeOrderAndAutoDone() {
        home();
        routes();
        long dentist = fixed("치과", "2026-11-02T15:00", 60, 10, "치과");
        Store.Block t = travel(dentist, "arrive");
        assertThat(t.start()).isEqualTo(at("2026-11-02T14:15"));
        runUntil("2026-11-02T14:06");
        assertThat(count("SELECT COUNT(*) FROM messages WHERE kind = 'notice' AND content LIKE '10분 뒤 치과%'")).isEqualTo(1);
        assertThat(interventionCount()).isZero();
        runUntil("2026-11-02T14:16");
        assertThat(count("SELECT COUNT(*) FROM interventions WHERE block_id = ?", t.id())).isEqualTo(1);
        assertThat(push.ofType("intervention.level").getLast().get("screen_text")).contains("지금 출발하세요");
        runUntil("2026-11-02T15:01");
        assertThat(block(t.id()).status()).isEqualTo("done"); // 놓침 없음
        assertThat(count("SELECT COUNT(*) FROM turns WHERE trigger = 'block_missed'")).isZero();
        assertThat(count("SELECT COUNT(*) FROM interventions WHERE block_id = ?", dentist)).isZero(); // 출발 화면 1번만
        assertThat(count("SELECT COUNT(*) FROM messages WHERE kind = 'notice' AND content LIKE '지금 치과 시간이에요%'")).isEqualTo(1);
    }

    @Test
    void t15b_departedThenArrivalStartsFixedSilently() {
        home();
        routes();
        long dentist = fixed("치과", "2026-11-02T15:00", 60, 0, "치과");
        Store.Block t = travel(dentist, "arrive");
        runUntil("2026-11-02T14:16");
        action("start", onlyIntervention().id(), t.id(), "go");
        assertThat(block(t.id()).status()).isEqualTo("started");
        assertThat(count("SELECT COUNT(*) FROM turns WHERE trigger = 'block_started'")).isZero();
        runUntil("2026-11-02T15:01");
        assertThat(block(dentist).status()).isEqualTo("started");
        assertThat(block(t.id()).status()).isEqualTo("done");
        assertThat(count("SELECT COUNT(*) FROM messages WHERE content LIKE '지금 치과 시간이에요%'")).isZero();
    }

    @Test
    void t14_t16_excludedFromStateAndNoTravelKeepsOldIntervention() {
        long plain = fixed("독서", "2026-11-02T15:00", 60, 10, null);
        runUntil("2026-11-02T14:51");
        assertThat(count("SELECT COUNT(*) FROM interventions WHERE block_id = ?", plain)).isEqualTo(1);
        home();
        routes();
        fixed("치과", "2026-11-03T15:00", 60, 0, "치과");
        String ctx = tx.run(() -> contextBuilder.build("pulse", Json.object()));
        assertThat(ctx).contains("[이동] 집 → 치과").contains("[귀가] 치과 → 집").contains("시스템 관리");
        assertThat(ctx).doesNotContain("블록이 없는 목표(2일 안)\n- ");
    }

    @Test
    void t17_userMinutesWinAndRevert() {
        home();
        routes();
        long dentist = fixed("치과", "2026-11-02T15:00", 60, 0, "치과");
        Store.Block t = travel(dentist, "arrive");
        calendar.patchTravelBlock(t.id(), Json.parse("{\"minutes\":20}"));
        assertThat(block(t.id()).start()).isEqualTo(at("2026-11-02T14:40"));
        tx.exec(() -> travel.computeDay(LocalDate.parse("2026-11-02")));
        assertThat(block(t.id()).start()).isEqualTo(at("2026-11-02T14:40"));
        Map<String, Object> r = calendar.patchTravelBlock(t.id(), Json.parse("{\"minutes\":null}"));
        assertThat(block(t.id()).start()).isEqualTo(at("2026-11-02T14:15"));
        assertThat(r.get("travel_info").toString()).contains("minutes=45");
        assertThatThrownBy(() -> calendar.patchResult(t.id(), Json.parse("{\"status\":\"done\"}"))).isInstanceOf(ApiException.class);
    }

    @Test
    void t18_eachSeriesOccurrenceGetsTravel() {
        home();
        routes();
        calendar.createSeries(Json.parse("""
                {"title":"수업","freq":"weekly","by_days":[1],"start_time":"15:00","duration_min":60,
                "start_date":"2026-11-02","lead_minutes":0,"location":"치과"}"""));
        assertThat(travels().stream().filter(t -> "arrive".equals(t.travelDirection())).count()).isEqualTo(3);
    }

    @Test
    void t19_leadMigrationOnlyAssistantLeadsAndIdempotent() {
        home();
        routes();
        long user = fixed("치과", "2026-11-02T15:00", 60, 50, "치과"); // 앱에서 정함 = user
        long asst = fixed("회의", "2026-11-03T13:00", 60, 0, "회의실");
        db.update("UPDATE blocks SET lead_minutes = 40, prep_set_by = 'assistant' WHERE id = ?", asst);
        assertThat(tx.run(() -> travelBlocks.migrateLeads())).contains("target=2 changed=1 skipped=1");
        assertThat(block(user).leadMinutes()).isEqualTo(50);
        assertThat(block(asst).leadMinutes()).isZero();
        assertThat(block(asst).prepSetBy()).isNull();
        assertThat(tx.run(() -> travelBlocks.migrateLeads())).isEmpty();
        // 사용자 lead 50분 = 이동 45분 + 준비 5분
        tx.exec(() -> travel.computeDay(LocalDate.parse("2026-11-02")));
        assertThat(travel(user, "arrive").leadMinutes()).isEqualTo(5);
    }
}
