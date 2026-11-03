package com.seobin.phonedrop.assistant;

import static com.seobin.phonedrop.assistant.agent.llm.ScriptedLlm.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.seobin.phonedrop.assistant.agent.ChatService;
import com.seobin.phonedrop.assistant.agent.ContextBuilder;
import com.seobin.phonedrop.assistant.agent.DocumentService;
import com.seobin.phonedrop.assistant.agent.KakaoMapApi;
import com.seobin.phonedrop.assistant.agent.MapApi;
import com.seobin.phonedrop.assistant.agent.Tools;
import com.seobin.phonedrop.assistant.agent.TravelService;
import com.seobin.phonedrop.assistant.api.CalendarController;
import com.seobin.phonedrop.assistant.api.GoalController;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** 빌드문서 v3.3 §5 테스트 (ScriptedLLM + FakeClock + FakePush + 가짜 카카오). 시작 시각 2026-11-02(월) 09:00. */
class V33FlowTest extends AssistantTestBase {

    @Autowired
    ChatService chat;
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
    DocumentService documents;

    static String iso(String local) {
        return at(local).atZone(KST).toOffsetDateTime().toString();
    }

    void runAgent() {
        while (agentWorker.runOnce()) {
            // 대기열이 빌 때까지
        }
    }

    int count(String sql, Object... params) {
        var spec = db.jdbc().sql(sql);
        for (Object p : params) {
            spec = spec.param(p);
        }
        return spec.query(Integer.class).single();
    }

    ObjectNode tool(String trigger, String name, String inputJson) {
        long turnId = db.insert("INSERT INTO turns(trigger, status, started_at) VALUES (?, 'running', ?)",
                trigger, clock.now());
        Tools.Context ctx = new Tools.Context(turnId, trigger, false);
        return tx.run(() -> agentTools.dispatch(ctx, name, Json.parse(inputJson)));
    }

    /** 앱에서 만든 고정 블록. level 이 null 이면 '비서가 정함'. */
    long fixed(String title, String startLocal, int minutes, String level, String location) {
        ObjectNode req = Json.object();
        req.put("title", title);
        req.put("start", iso(startLocal));
        req.put("duration_min", minutes);
        req.put("lead_minutes", 0);
        if (level != null) {
            req.put("interrupt_level", level);
        }
        if (location != null) {
            req.put("location", location);
        }
        return ((Number) goalApi.createFixed(req).get("id")).longValue();
    }

    long plannedBlock(String title, String startLocal, int minutes) {
        long task = db.insert("INSERT INTO tasks(title, first_action, est_minutes, remaining_minutes) VALUES (?, '열기', ?, ?)",
                title, minutes, minutes);
        long id = db.insert("""
                        INSERT INTO blocks(kind, task_id, title, start_at, end_at, status, screen_text)
                        VALUES ('planned', ?, ?, ?, ?, 'planned', '시작')""",
                task, title, at(startLocal), at(startLocal).plusSeconds(minutes * 60L));
        tx.exec(() -> interventions.scheduleBlockJobs(block(id)));
        return id;
    }

    List<Map<String, String>> chatPushes() {
        return push.ofType("chat.message");
    }

    String context(String trigger) {
        return tx.run(() -> contextBuilder.build(trigger, Json.object()));
    }

    // ------------------------------------------------------------------ §1 방해 허용 정도

    @Test
    void i1_dndHoldsProactiveMessagesAndSendsOneBundleAtEnd() {
        long exam = fixed("정보처리기사 시험", "2026-11-02T10:00", 120, "dnd", null);
        runUntil("2026-11-02T10:30");
        push.clear();
        JsonNode r = tool("morning", "send_message", "{\"text\":\"오늘 일정 알려드릴게요\",\"kind\":\"briefing\"}");
        tool("pulse", "ask_user", "{\"text\":\"내일 계획 짤까요?\",\"options\":[\"응\",\"아니\"]}");
        assertThat(r.path("held_until_fixed_block_ends").asLong()).isEqualTo(exam);
        assertThat(chatPushes()).isEmpty();
        assertThat(tx.run(() -> chat.list(0)).get("messages").toString()).doesNotContain("오늘 일정 알려드릴게요");

        runUntil("2026-11-02T12:01");
        List<String> proactive = db.jdbc().sql("SELECT content FROM messages WHERE kind = 'proactive' ORDER BY id")
                .query(String.class).list();
        assertThat(proactive).hasSize(1);
        assertThat(proactive.getFirst()).contains("정보처리기사 시험").contains("오늘 일정 알려드릴게요").contains("질문 1개");
        assertThat(count("SELECT COUNT(*) FROM messages WHERE kind = 'question' AND held_block_id IS NULL")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM messages WHERE held_block_id IS NOT NULL")).isZero();
        assertThat(tx.run(() -> chat.proactiveToday())[0]).isEqualTo(1);
        assertThat(chatPushes().stream().filter(p -> "false".equals(p.get("silent"))).count()).isEqualTo(1);
    }

    @Test
    void i2_replyToUserIsSentDuringDnd() {
        fixed("시험", "2026-11-02T10:00", 120, "dnd", null);
        runUntil("2026-11-02T10:30");
        push.clear();
        tool("user_message", "send_message", "{\"text\":\"네, 끝나고 볼게요\",\"kind\":\"reply\"}");
        assertThat(chatPushes()).hasSize(1);
        assertThat(chatPushes().getFirst().get("silent")).isEqualTo("false");
    }

    @Test
    void i3_noOtherInterventionDuringDnd() {
        fixed("시험", "2026-11-02T10:00", 120, "dnd", null);
        long other = plannedBlock("보고서", "2026-11-02T11:00", 30);
        runUntil("2026-11-02T11:05");
        assertThat(count("SELECT COUNT(*) FROM interventions WHERE block_id = ?", other)).isZero();
        assertThat(block(other).status()).isEqualTo("planned");
    }

    @Test
    void i4_i6_allowNotifiesQuietAndUnsetAreSilent() {
        fixed("워크샵", "2026-11-02T10:00", 120, "allow", null);
        runUntil("2026-11-02T10:30");
        assertThat(tool("pulse", "send_message", "{\"text\":\"점심 챙기세요\",\"kind\":\"proactive\"}")
                .path("silent").asBoolean()).isFalse();
        fixed("회의", "2026-11-02T13:00", 60, "quiet", null);
        fixed("미정", "2026-11-02T15:00", 60, null, null);
        runUntil("2026-11-02T13:10");
        assertThat(tool("pulse", "send_message", "{\"text\":\"a\",\"kind\":\"proactive\"}").path("silent").asBoolean()).isTrue();
        runUntil("2026-11-02T15:10");
        assertThat(tool("pulse", "send_message", "{\"text\":\"b\",\"kind\":\"proactive\"}").path("silent").asBoolean()).isTrue();
        assertThat(block(fixed("또 미정", "2026-11-03T15:00", 60, null, null)).level()).isEqualTo("quiet");
    }

    @Test
    void i5_assistantCannotChangeUserLevel() {
        long id = fixed("면접", "2026-11-03T10:00", 60, "dnd", null);
        assertThat(block(id).interruptSetBy()).isEqualTo("user");
        JsonNode r = tool("pulse", "set_interrupt_level", "{\"block_id\":" + id + ",\"level\":\"allow\"}");
        assertThat(r.path("ok").asBoolean()).isFalse();
        assertThat(r.path("error").asString()).contains("사용자가 정한");
        assertThat(tool("user_message", "upsert_fixed_block", "{\"id\":" + id + ",\"interrupt_level\":\"quiet\"}")
                .path("ok").asBoolean()).isFalse();
        assertThat(block(id).level()).isEqualTo("dnd");
        // 비서가 정한 값은 확인 카드 없이 다시 정할 수 있다
        long other = fixed("모임", "2026-11-04T10:00", 60, null, null);
        assertThat(tool("pulse", "set_interrupt_level", "{\"block_id\":" + other + ",\"level\":\"allow\"}")
                .path("ok").asBoolean()).isTrue();
        assertThat(tool("pulse", "set_interrupt_level", "{\"block_id\":" + other + ",\"level\":\"dnd\"}")
                .path("ok").asBoolean()).isTrue();
        assertThat(block(other).interruptSetBy()).isEqualTo("assistant");
        assertThat(count("SELECT COUNT(*) FROM confirmations")).isZero();
        assertThat(context("pulse")).contains("[고정·방해금지(사용자)] 면접").contains("[고정·방해금지] 모임");
    }

    // ------------------------------------------------------------------ §2 진행 중 고정 일정 수정

    @Test
    void e1_extendInProgressDisplacesPlannedAndReschedulesEnd() {
        long ws = fixed("워크샵", "2026-11-02T10:00", 120, "allow", null);
        long after = plannedBlock("보고서", "2026-11-02T12:30", 60);
        runUntil("2026-11-02T10:30");
        ObjectNode req = Json.object();
        req.put("end", iso("2026-11-02T13:30"));
        Map<String, Object> r = goalApi.patchFixed(ws, req);
        assertThat(r.get("displaced").toString()).isEqualTo("[" + after + "]");
        assertThat(block(ws).end()).isEqualTo(at("2026-11-02T13:30"));
        assertThat(block(after).status()).isEqualTo("cancelled");
        assertThat(count("SELECT COUNT(*) FROM jobs WHERE type = 'block_end' AND target_id = ? AND status = 'scheduled'"
                + " AND fire_at = ?", ws, AssistantDb.ts(at("2026-11-02T13:30")))).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM turns WHERE trigger = 'blocks_displaced'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM block_logs WHERE block_id = ? AND actor = 'user' AND type = 'moved'", ws))
                .isEqualTo(1);
    }

    @Test
    void e2_extendIntoFixedIsRejected() {
        long ws = fixed("워크샵", "2026-11-02T10:00", 120, "allow", null);
        fixed("치과", "2026-11-02T13:00", 60, null, null);
        runUntil("2026-11-02T10:30");
        ObjectNode req = Json.object();
        req.put("end", iso("2026-11-02T13:30"));
        assertThatThrownBy(() -> goalApi.patchFixed(ws, req)).isInstanceOf(ApiException.class)
                .hasMessageContaining("치과").hasMessageContaining("먼저 옮겨");
        assertThat(block(ws).end()).isEqualTo(at("2026-11-02T12:00"));
    }

    @Test
    void e3_e4_endNowAndStartCannotChange() {
        long ws = fixed("워크샵", "2026-11-02T10:00", 120, "allow", null);
        runUntil("2026-11-02T10:01");
        action("start", onlyIntervention().id(), ws, "k1");
        runUntil("2026-11-02T10:40");
        ObjectNode req = Json.object();
        req.put("start", iso("2026-11-02T10:30"));
        assertThatThrownBy(() -> goalApi.patchFixed(ws, req)).isInstanceOf(ApiException.class)
                .hasMessageContaining("시작 시각");
        goalApi.endFixedNow(ws);
        assertThat(block(ws).status()).isEqualTo("done");
        assertThat(block(ws).actualEnd()).isEqualTo(at("2026-11-02T10:40"));
    }

    @Test
    void e5_e6_assistantNeedsConfirmationUserSaysItDirectly() {
        long ws = fixed("워크샵", "2026-11-02T10:00", 120, "allow", null);
        runUntil("2026-11-02T10:30");
        JsonNode r = tool("pulse", "extend_block", "{\"block_id\":" + ws + ",\"new_end\":\"" + iso("2026-11-02T13:00") + "\"}");
        assertThat(r.path("pending_confirmation_id").asLong()).isPositive();
        assertThat(block(ws).end()).isEqualTo(at("2026-11-02T12:00"));
        assertThat(tool("pulse", "upsert_fixed_block", "{\"id\":" + ws + ",\"end\":\"" + iso("2026-11-02T13:00") + "\"}")
                .path("pending_confirmation_id").asLong()).isPositive();

        // "워크샵 1시간 늘어났어"
        assertThat(tool("user_message", "upsert_fixed_block", "{\"id\":" + ws + ",\"end\":\"" + iso("2026-11-02T13:00") + "\"}")
                .path("ok").asBoolean()).isTrue();
        assertThat(block(ws).end()).isEqualTo(at("2026-11-02T13:00"));
        // "워크샵 끝났어"
        assertThat(tool("user_message", "end_block", "{\"block_id\":" + ws + ",\"outcome\":\"done\"}").path("ok").asBoolean())
                .isTrue();
        assertThat(block(ws).status()).isEqualTo("done");
    }

    @Test
    void e7_extendedDndStillHoldsMessages() {
        long exam = fixed("시험", "2026-11-02T10:00", 120, "dnd", null);
        runUntil("2026-11-02T10:30");
        ObjectNode req = Json.object();
        req.put("end", iso("2026-11-02T13:00"));
        goalApi.patchFixed(exam, req);
        runUntil("2026-11-02T12:30");
        assertThat(tool("pulse", "send_message", "{\"text\":\"늘어난 시간 중\",\"kind\":\"proactive\"}")
                .path("held_until_fixed_block_ends").asLong()).isEqualTo(exam);
        long other = plannedBlock("정리", "2026-11-02T12:40", 10);
        runUntil("2026-11-02T12:45");
        assertThat(count("SELECT COUNT(*) FROM interventions WHERE block_id = ?", other)).isZero();
        assertThat(count("SELECT COUNT(*) FROM messages WHERE kind = 'proactive' AND held_block_id IS NULL")).isZero();
        runUntil("2026-11-02T13:01");
        assertThat(count("SELECT COUNT(*) FROM messages WHERE kind = 'proactive' AND content LIKE '%늘어난 시간 중%'")).isEqualTo(1);
    }

    @Test
    void e8_seriesOccurrenceExtendChangesOnlyThatDay() {
        long seriesId = ((Number) calendar.createSeries(Json.parse("""
                {"title":"수업","freq":"weekly","by_days":[1],"start_time":"10:00","duration_min":60,
                "start_date":"2026-11-02","lead_minutes":0}""")).get("id")).longValue();
        long today = db.jdbc().sql("SELECT id FROM blocks WHERE series_id = ? AND occurrence_date = ?").param(seriesId)
                .param(LocalDate.parse("2026-11-02")).query(Long.class).single();
        long next = db.jdbc().sql("SELECT id FROM blocks WHERE series_id = ? AND occurrence_date = ?").param(seriesId)
                .param(LocalDate.parse("2026-11-09")).query(Long.class).single();
        runUntil("2026-11-02T10:20");
        ObjectNode req = Json.object();
        req.put("end", iso("2026-11-02T11:30"));
        goalApi.patchFixed(today, req);
        assertThat(block(today).end()).isEqualTo(at("2026-11-02T11:30"));
        assertThat(block(next).end()).isEqualTo(at("2026-11-09T11:00"));
        assertThat(count("SELECT COUNT(*) FROM series_exceptions WHERE series_id = ? AND action = 'moved'", seriesId))
                .isEqualTo(1);
    }

    // ------------------------------------------------------------------ §3 지도 읽기

    void home() {
        putSetting("travel.home_lat", "37.4000");
        putSetting("travel.home_lng", "127.0000");
    }

    @Test
    void m1_placeIsSavedAndShownInSavedLine() {
        home();
        mapApi.places.put("치과", new MapApi.Place("OO치과 강남점", "서울 강남구", 37.5000, 127.0300));
        long turnId = db.insert("INSERT INTO turns(trigger, status, started_at) VALUES ('user_message', 'running', ?)",
                clock.now());
        Tools.Context ctx = new Tools.Context(turnId, "user_message", false);
        JsonNode r = tx.run(() -> agentTools.dispatch(ctx, "upsert_fixed_block", Json.parse("{\"title\":\"치과\",\"start\":\""
                + iso("2026-11-05T15:00") + "\",\"end\":\"" + iso("2026-11-05T16:00") + "\",\"location\":\"치과\","
                + "\"interrupt_level\":\"dnd\"}")));
        Store.Block b = block(r.path("id").asLong());
        assertThat(b.placeName()).isEqualTo("OO치과 강남점");
        assertThat(b.lat()).isEqualTo(37.5);
        assertThat(b.placeSource()).isEqualTo("kakao");
        assertThat(ctx.savedLines.getFirst()).contains("장소: OO치과 강남점");
        assertThat(travel.legs(b.id())).extracting(TravelService.Leg::direction).containsExactlyInAnyOrder("arrive", "depart");
    }

    @Test
    void m2_m6_twoFixedSameDayChainAndCacheIsUsed() {
        home();
        mapApi.places.put("회의실", new MapApi.Place("회의실", "", 37.4500, 127.0100));
        mapApi.places.put("치과", new MapApi.Place("치과", "", 37.5000, 127.0300));
        mapApi.routeMinutes.put("37.4500>37.5000", 25);
        long meeting = fixed("회의", "2026-11-05T13:00", 60, null, "회의실");
        long dentist = fixed("치과", "2026-11-05T15:00", 60, null, "치과");
        TravelService.Leg arrive = travel.leg(dentist, "arrive").orElseThrow();
        assertThat(arrive.from()).isEqualTo("회의");
        assertThat(arrive.minutes()).isEqualTo(25);
        assertThat(arrive.source()).isEqualTo("kakao");
        assertThat(travel.leg(meeting, "depart").orElseThrow().to()).isEqualTo("치과");
        int calls = mapApi.routeCalls;
        tx.exec(() -> travel.computeDay(LocalDate.parse("2026-11-05")));
        assertThat(mapApi.routeCalls).isEqualTo(calls);
    }

    @Test
    void m3_m4_failureAndLimitUseEstimate() {
        home();
        mapApi.places.put("치과", new MapApi.Place("치과", "", 37.5000, 127.0300));
        long dentist = fixed("치과", "2026-11-05T15:00", 60, null, "치과");
        assertThat(travel.leg(dentist, "arrive").orElseThrow().source()).isEqualTo("estimate"); // 경로 없음 → 어림값
        assertThat(context("pulse")).contains("어림값");

        mapApi.failCode = "error";
        mapApi.places.put("병원", new MapApi.Place("병원", "", 37.4200, 127.0500));
        long clinic = fixed("병원", "2026-11-06T15:00", 60, null, "병원");
        assertThat(block(clinic).placeSource()).isEqualTo("estimate");

        mapApi.failCode = null;
        putSetting("travel.daily_api_limit", "0");
        int calls = mapApi.routeCalls + mapApi.searchCalls;
        long gym = fixed("헬스", "2026-11-07T15:00", 60, null, "병원");
        assertThat(mapApi.routeCalls + mapApi.searchCalls).isEqualTo(calls);
        assertThat(block(gym).placeSource()).isEqualTo("estimate");
    }

    @Test
    void m5_impossibleTravelWarnedOnce() {
        home();
        mapApi.places.put("회의실", new MapApi.Place("회의실", "", 37.4500, 127.0100));
        mapApi.places.put("치과", new MapApi.Place("치과", "", 37.5000, 127.0300));
        mapApi.routeMinutes.put("37.4500>37.5000", 50);
        fixed("회의", "2026-11-02T13:00", 120, null, "회의실");
        fixed("치과", "2026-11-02T15:30", 60, null, "치과");
        assertThat(context("user_message")).contains("이동 불가능: 회의 끝 15:00 + 이동 50분 > 치과 15:30");
        assertThat(context("user_message")).doesNotContain("이동 불가능");
    }

    @Test
    void m7_kakaoKeyNeverInResponses(@org.junit.jupiter.api.io.TempDir Path dir) throws IOException {
        KakaoMapApi kakao = new KakaoMapApi(dir.toString(), "");
        assertThat(kakao.status()).isEqualTo("no_key");
        kakao.saveKey("SECRETKEY123abc");
        assertThat(kakao.hasKey()).isTrue();
        assertThat(kakao.status()).doesNotContain("SECRETKEY123abc");
        assertThat(tx.run(() -> travel.status()).toString()).doesNotContain("SECRETKEY123abc");
    }

    @Autowired
    com.seobin.phonedrop.assistant.api.TravelController travelApi;

    @Test
    void p1_pinnedPlaceAndUserMinutesSurviveRecompute() {
        home();
        mapApi.places.put("열쇠집", new MapApi.Place("OO열쇠", "", 37.4500, 127.0100));
        long cafe = fixed("카페", "2026-11-05T13:00", 60, null, null);
        long keys = fixed("열쇠집", "2026-11-05T15:00", 30, null, "열쇠집");
        assertThat(block(cafe).hasCoords()).isFalse();

        travelApi.setPlace(cafe, Json.parse("{\"lat\":37.4100,\"lng\":127.0050,\"name\":\"OO카페\"}"));
        Store.Block c = block(cafe);
        assertThat(c.placeSource()).isEqualTo("user");
        assertThat(c.location()).isEqualTo("OO카페");
        assertThat(travel.leg(keys, "arrive").orElseThrow().from()).isEqualTo("카페");

        travelApi.setTravel(keys, Json.parse("{\"direction\":\"arrive\",\"minutes\":33}"));
        tx.exec(() -> travel.computeDay(LocalDate.parse("2026-11-05")));
        TravelService.Leg leg = travel.leg(keys, "arrive").orElseThrow();
        assertThat(leg.minutes()).isEqualTo(33);
        assertThat(leg.source()).isEqualTo("user");
        assertThat(context("pulse")).contains("약 33분(사용자가 정함)");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> places = (List<Map<String, Object>>) travelApi.places().get("places");
        assertThat(places).extracting(m -> m.get("title")).containsExactly("카페", "열쇠집");
        assertThatThrownBy(() -> travelApi.setPlace(cafe, Json.parse("{\"lat\":10,\"lng\":10}")))
                .isInstanceOf(ApiException.class);
        assertThat(travelApi.search("열쇠집", null, null).get("places").toString()).contains("OO열쇠");
    }

    // ------------------------------------------------------------------ §4 문서 받기

    static byte[] pdf(String line) throws IOException {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 700);
                cs.showText(line);
                cs.endText();
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    static final String SUMMARY = """
            {"summary_md":"보고서 과제 공지. 제출 형식은 PDF 10쪽","deadlines":[{"text":"11/20 18:00 제출","at":"2026-11-20T18:00:00+09:00"}],
            "requirements":["PDF 10쪽"],"items":[]}""";

    long upload(String name, byte[] data, Long goalId, boolean fromChat) {
        Map<String, Object> r = tx.run(() -> documents.upload(name, data, goalId, null, null, null, fromChat));
        return ((Number) r.get("id")).longValue();
    }

    @Test
    void d1_d4_textPdfIsChunkedSummarizedAndDeadlineIsNotRegistered() throws IOException {
        long goal = db.insert("INSERT INTO goals(title, label) VALUES ('보고서', '보고서')");
        llm.then(text(SUMMARY));
        long id = upload("notice.pdf", pdf("Report due 11/20 18:00. Format: PDF, 10 pages. Submit online."), goal, false);
        runAgent();
        DocumentService.Document d = documents.find(id).orElseThrow();
        assertThat(d.status()).isEqualTo("ready");
        assertThat(d.summaryMd()).contains("PDF 10쪽");
        assertThat(d.extracted()).contains("11/20 18:00");
        assertThat(count("SELECT COUNT(*) FROM document_chunks WHERE document_id = ?", id)).isPositive();
        assertThat(count("SELECT COUNT(*) FROM turns WHERE trigger = 'document_ready'")).isEqualTo(1);
        assertThat(llm.onceImages).containsExactly(0);
        // 자동 등록 없음: 할 일·마감이 생기지 않는다. 비서 turn 맥락에 추출이 있다.
        assertThat(count("SELECT COUNT(*) FROM tasks")).isZero();
        assertThat(llm.userContents.getLast()).contains("document_ready").contains("11/20 18:00 제출")
                .contains("자동 등록하지 말고");
    }

    @Test
    void d2_imageIsReadWithImageInput() throws IOException {
        BufferedImage img = new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        llm.then(text("시험 범위: 1~3장")).then(text("{\"summary_md\":\"시험 범위 1~3장\"}"));
        long id = upload("range.png", out.toByteArray(), null, true);
        runAgent();
        assertThat(llm.onceImages.getFirst()).isEqualTo(1);
        assertThat(documents.find(id).orElseThrow().status()).isEqualTo("ready");
        assertThat(count("SELECT COUNT(*) FROM messages WHERE role = 'user' AND document_id = ?", id)).isEqualTo(1);
    }

    @Test
    void d3_tooLargeOrWrongTypeIsRejected() {
        assertThatThrownBy(() -> upload("big.pdf", new byte[25 * 1024 * 1024], null, false))
                .isInstanceOf(ApiException.class).hasMessageContaining("20MB");
        assertThatThrownBy(() -> upload("run.exe", new byte[10], null, false)).isInstanceOf(ApiException.class);
    }

    @Test
    void d5_d6_contextHasTitlesOnlyAndReadFindsChunks() {
        String body = "제출 형식: 한글 파일, 10쪽 이내.\n" + "가".repeat(3000) + "\n평가 기준: 독창성 50%";
        llm.then(text(SUMMARY));
        long id = upload("guide.txt", body.getBytes(java.nio.charset.StandardCharsets.UTF_8), null, false);
        runAgent();
        String ctx = context("pulse");
        assertThat(ctx).contains("자료#" + id + " guide.txt").doesNotContain("평가 기준");
        JsonNode r = tool("user_message", "read_document", "{\"document_id\":" + id + ",\"query\":\"평가 기준\"}");
        assertThat(r.path("parts").size()).isBetween(1, 3);
        assertThat(r.path("parts").get(0).path("text").asString()).contains("독창성");
    }

    @Test
    void d7_deleteRemovesFileChunksSummary() throws IOException {
        llm.then(text(SUMMARY));
        long id = upload("memo.md", "# 준비물\n신분증".getBytes(java.nio.charset.StandardCharsets.UTF_8), null, false);
        runAgent();
        Path file = documents.dir().resolve(documents.find(id).orElseThrow().storagePath());
        assertThat(Files.exists(file)).isTrue();
        tx.exec(() -> documents.delete(id));
        assertThat(documents.find(id)).isEmpty();
        assertThat(count("SELECT COUNT(*) FROM document_chunks WHERE document_id = ?", id)).isZero();
        assertThat(Files.exists(file)).isFalse();
    }
}
