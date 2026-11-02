package com.seobin.phonedrop.assistant;

import static com.seobin.phonedrop.assistant.agent.llm.ScriptedLlm.call;
import static com.seobin.phonedrop.assistant.agent.llm.ScriptedLlm.text;
import static com.seobin.phonedrop.assistant.agent.llm.ScriptedLlm.tools;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;

import com.seobin.phonedrop.assistant.agent.ChatService;
import com.seobin.phonedrop.assistant.agent.ContextBuilder;
import com.seobin.phonedrop.assistant.agent.MemoService;
import com.seobin.phonedrop.assistant.agent.SeriesService;
import com.seobin.phonedrop.assistant.agent.Tools;
import com.seobin.phonedrop.assistant.agent.llm.LlmClient;
import com.seobin.phonedrop.assistant.api.CalendarController;
import com.seobin.phonedrop.assistant.api.GoalController;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** 빌드문서 v3.2 §9 테스트 (ScriptedLLM + FakeClock + FakePush). 시작 시각 2026-11-02(월) 09:00. */
class V32FlowTest extends AssistantTestBase {

    @Autowired
    ChatService chat;
    @Autowired
    Tools agentTools;
    @Autowired
    SeriesService series;
    @Autowired
    MemoService memos;
    @Autowired
    ContextBuilder contextBuilder;
    @Autowired
    CalendarController calendar;
    @Autowired
    GoalController goalApi;
    @Autowired
    TurnQueue turnQueue;

    static String iso(String local) {
        return at(local).atZone(KST).toOffsetDateTime().toString();
    }

    void runAgent() {
        while (agentWorker.runOnce()) {
            // 대기열이 빌 때까지
        }
    }

    Map<String, Object> say(String text) {
        ObjectNode req = Json.object();
        req.put("client_msg_id", java.util.UUID.randomUUID().toString());
        req.put("text", text);
        req.put("source", "chat");
        return tx.run(() -> chat.receive(req));
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

    ObjectNode tool(String name, String inputJson) {
        return tool("user_message", name, inputJson);
    }

    long task(String title, String deadlineLocal) {
        return db.insert("""
                        INSERT INTO tasks(title, first_action, est_minutes, remaining_minutes, deadline) VALUES (?, ?, 60, 60, ?)""",
                title, title + " 열기", deadlineLocal == null ? null : at(deadlineLocal));
    }

    /** 앱에서 만든 고정 블록(사용자 조작). */
    long fixed(String title, String startLocal, int minutes, int lead, String memo) {
        ObjectNode req = Json.object();
        req.put("title", title);
        req.put("start", iso(startLocal));
        req.put("duration_min", minutes);
        req.put("lead_minutes", lead);
        if (memo != null) {
            req.put("memo", memo);
        }
        return ((Number) goalApi.createFixed(req).get("id")).longValue();
    }

    long seriesApi(String json) {
        return ((Number) calendar.createSeries(Json.parse(json)).get("id")).longValue();
    }

    int activeSeriesBlocks(long seriesId) {
        return count("SELECT COUNT(*) FROM blocks WHERE series_id = ? AND status IN ('planned', 'nudging')", seriesId);
    }

    Store.Block occurrence(long seriesId, String date) {
        return db.jdbc().sql("SELECT * FROM blocks WHERE series_id = ? AND occurrence_date = ?")
                .param(seriesId).param(LocalDate.parse(date)).query(Store::block).single();
    }

    List<String> assistantMessages(String kind) {
        return db.jdbc().sql("SELECT content FROM messages WHERE role = 'assistant' AND kind = ? ORDER BY id")
                .param(kind).query(String.class).list();
    }

    List<String> logTypes(long blockId) {
        return db.jdbc().sql("SELECT type FROM block_logs WHERE block_id = ? ORDER BY at, id").param(blockId)
                .query(String.class).list();
    }

    String stage(long turnId) {
        return db.jdbc().sql("SELECT stage FROM turns WHERE id = ?").param(turnId).query(String.class).single();
    }

    String systemPrompt() throws IOException {
        try (InputStream in = new ClassPathResource("assistant/prompts/system.md").getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // ------------------------------------------------------------------ P0

    @Test
    void z1_messageWithBlockLogBothRunAndTurnEnds() {
        long b = fixed("치과", "2026-11-03T15:00", 60, 0, null);
        llm.then(tools(call("add_block_log", "{\"block_id\":" + b + ",\"type\":\"notice\",\"content\":\"보험증 확인\"}"),
                        call("send_message", "{\"text\":\"내일 치과예요\",\"kind\":\"reply\"}")))
                .then(tools(call("send_message", "{\"text\":\"한 번 더\",\"kind\":\"reply\"}")));
        Map<String, Object> r = say("내일 뭐 있어?");
        runAgent();

        assertThat(assistantMessages("reply")).containsExactly("내일 치과예요");
        assertThat(logTypes(b)).contains("notice");
        assertThat(llm.toolResults).isEmpty();
        assertThat(stage((Long) r.get("turn_id"))).isEqualTo("done");
    }

    @Test
    void z2_goalWithoutBlocksIsSentBackEvenAfterMessage() {
        llm.then(tools(call("upsert_goal", "{\"title\":\"컴활 2급\"}"),
                        call("send_message", "{\"text\":\"목표 등록했어요\",\"kind\":\"reply\"}")))
                .then(text(""));
        say("컴활 따고 싶어");
        runAgent();

        // 답장을 보냈어도 목표 블록 검사가 되돌려 보낸다(설정 goal.coverage_retry 회까지)
        assertThat(llm.userTexts).isNotEmpty();
        assertThat(llm.userTexts.getFirst()).contains("블록이 없습니다");
    }

    @Test
    void z3_d1_taskDeadlineSavedLine() {
        llm.then(tools(call("upsert_task", "{\"title\":\"과제\",\"est_minutes\":120,\"first_action\":\"자료 열기\","
                        + "\"deadline\":\"" + iso("2026-11-07T23:00") + "\"}"),
                call("send_message", "{\"text\":\"토요일 밤 11시로 잡았어요\",\"kind\":\"reply\"}")));
        say("토요일 11시까지 과제 있어");
        runAgent();

        assertThat(assistantMessages("system")).containsExactly("저장됨: 과제 · 마감 11/7(토) 23:00");
    }

    @Test
    void d2_noDeadlineChangeNoSavedLine() {
        llm.then(tools(call("upsert_task", "{\"title\":\"빨래\",\"est_minutes\":30,\"first_action\":\"세탁기 열기\"}"),
                call("send_message", "{\"text\":\"등록했어요\",\"kind\":\"reply\"}")));
        say("빨래 해야 해");
        runAgent();

        assertThat(assistantMessages("system")).isEmpty();
    }

    @Test
    void z4_s2_systemPromptRules() throws IOException {
        String p = systemPrompt();
        assertThat(p).contains("## 도구 순서").contains("허락").contains("## 마감").contains("## 지금 상태를 보고 판단하기");
        assertThat(p).doesNotContain("어려워한다");
    }

    // ------------------------------------------------------------------ P3 진행 상태

    @Test
    void u1_u4_stageQueuedThinkingDone() {
        String[] seen = new String[1];
        Map<String, Object> r = say("안녕");
        long turnId = (Long) r.get("turn_id");
        assertThat(stage(turnId)).isEqualTo("queued");
        llm.thenDo(() -> {
            seen[0] = stage(turnId);
            return tools(call("send_message", "{\"text\":\"안녕하세요\",\"kind\":\"reply\"}"));
        });
        runAgent();

        assertThat(seen[0]).isEqualTo("thinking");
        assertThat(stage(turnId)).isEqualTo("done");
        Map<String, Object> status = calendar.turnStatus(turnId);
        assertThat(status.get("stage")).isEqualTo("done");
        assertThat(db.jdbc().sql("SELECT tool_names FROM turns WHERE id = ?").param(turnId).query(String.class).single())
                .isEqualTo("send_message");
    }

    @Test
    void u2_workingTextForSchedule() {
        assertThat(com.seobin.phonedrop.assistant.agent.AgentRunner.workingText(
                List.of(new LlmClient.ToolCall("1", "get_timeline", Json.object()),
                        new LlmClient.ToolCall("2", "set_schedule", Json.object())))).isEqualTo("일정 짜는 중…");
    }

    @Test
    void u3_llmFailureStageFailed() {
        llm.thenDo(() -> {
            throw new LlmClient.LlmException("down", null);
        });
        long turnId = tx.run(() -> turnQueue.enqueue("evening", Json.object(), TurnQueue.PRIORITY_DEFAULT));
        runAgent();
        assertThat(stage(turnId)).isEqualTo("failed");
    }

    // ------------------------------------------------------------------ §2 반복 일정

    String weeklyTueThu = "{\"title\":\"헬스\",\"freq\":\"weekly\",\"by_days\":[2,4],\"start_time\":\"19:00\",\"duration_min\":60}";

    @Test
    void r1_r2_weeklySeriesCreatesFourBlocksWithoutDuplicates() {
        long s = seriesApi(weeklyTueThu);
        assertThat(activeSeriesBlocks(s)).isEqualTo(4); // 11/3·5·10·12
        assertThat(count("SELECT COUNT(*) FROM jobs WHERE type = 'start_intervention' AND status = 'scheduled'"))
                .isEqualTo(4);
        tx.run(series::generateAll);
        tx.run(series::generateAll);
        assertThat(activeSeriesBlocks(s)).isEqualTo(4);
    }

    @Test
    void r3_deleteOneOccurrenceRecordsSkip() {
        long s = seriesApi(weeklyTueThu);
        Store.Block thu = occurrence(s, "2026-11-05");
        goalApi.deleteFixed(thu.id());
        assertThat(count("SELECT COUNT(*) FROM series_exceptions WHERE series_id = ? AND action = 'skip'", s)).isEqualTo(1);
        tx.run(series::generateAll);
        assertThat(activeSeriesBlocks(s)).isEqualTo(3);
    }

    @Test
    void r4_followingChangeSplitsSeriesAndKeepsOnceMemo() {
        long s = seriesApi(weeklyTueThu);
        long thu12 = occurrence(s, "2026-11-12").id();
        goalApi.patchFixed(thu12, Json.parse("{\"memo\":\"수건 챙기기\"}"));

        Map<String, Object> r = calendar.patchSeries(s, Json.parse("{\"start_time\":\"20:00\"}"), "following", "2026-11-10");
        long next = ((Number) r.get("id")).longValue();
        assertThat(next).isNotEqualTo(s);
        assertThat(series.series(s).orElseThrow().untilDate()).isEqualTo(LocalDate.parse("2026-11-09"));
        assertThat(occurrence(s, "2026-11-03").start()).isEqualTo(at("2026-11-03T19:00"));
        Store.Block newThu = occurrence(next, "2026-11-12");
        assertThat(newThu.start()).isEqualTo(at("2026-11-12T20:00"));
        assertThat(newThu.memo()).isEqualTo("수건 챙기기");
        assertThat(activeSeriesBlocks(s)).isEqualTo(2);
        assertThat(activeSeriesBlocks(next)).isEqualTo(2);
    }

    @Test
    void r5_biweeklySaturday() {
        putSetting("series.horizon_days", "30");
        long s = seriesApi("{\"title\":\"모임\",\"freq\":\"weekly\",\"interval\":2,\"by_days\":[6],\"start_date\":\"2026-11-07\","
                + "\"start_time\":\"14:00\",\"duration_min\":120}");
        List<LocalDate> dates = db.jdbc().sql("SELECT occurrence_date FROM blocks WHERE series_id = ? ORDER BY start_at")
                .param(s).query((rs, n) -> rs.getObject(1, LocalDate.class)).list();
        assertThat(dates).containsExactly(LocalDate.parse("2026-11-07"), LocalDate.parse("2026-11-21"));
    }

    @Test
    void r6_monthly31stFallsOnLastDay() {
        putSetting("series.horizon_days", "40");
        long s = seriesApi("{\"title\":\"월세\",\"freq\":\"monthly\",\"month_day\":31,\"start_date\":\"2026-10-31\","
                + "\"start_time\":\"10:00\",\"duration_min\":30}");
        assertThat(occurrence(s, "2026-11-30").start()).isEqualTo(at("2026-11-30T10:00"));
    }

    @Test
    void r7_pulseSeriesBecomesConfirmation() {
        JsonNode r = tool("pulse", "upsert_fixed_series", weeklyTueThu);
        assertThat(r.path("pending_confirmation_id").asLong()).isPositive();
        assertThat(count("SELECT COUNT(*) FROM fixed_series")).isZero();
    }

    @Test
    void r7b_seriesOverlapWithFixedSkipsDayAndTellsOnce() {
        fixed("회식", "2026-11-03T18:30", 120, 0, null);
        long s = seriesApi(weeklyTueThu);
        assertThat(activeSeriesBlocks(s)).isEqualTo(3);
        assertThat(assistantMessages("system")).hasSize(1).first().asString().contains("회식").contains("헬스");
        tx.run(series::generateAll);
        assertThat(assistantMessages("system")).hasSize(1);
    }

    // ------------------------------------------------------------------ §3 메모

    @Test
    void m1_memoThreeDaysAheadDoesNotCallAssistant() {
        long b = fixed("치과", "2026-11-05T15:00", 60, 0, null);
        goalApi.patchFixed(b, Json.parse("{\"memo\":\"보험증\"}"));
        assertThat(count("SELECT COUNT(*) FROM turns WHERE trigger = 'fixed_memo_changed'")).isZero();
        assertThat(block(b).memo()).isEqualTo("보험증");
    }

    @Test
    void m2_urgentMemoEditsDebounceToOneTurn() {
        long b = fixed("치과", "2026-11-02T19:00", 60, 0, null);
        goalApi.patchFixed(b, Json.parse("{\"memo\":\"보험증\"}"));
        goalApi.patchFixed(b, Json.parse("{\"memo\":\"보험증, 의뢰서\"}"));
        goalApi.patchFixed(b, Json.parse("{\"memo\":\"보험증, 의뢰서, 2시부터 준비\"}"));
        assertThat(count("SELECT COUNT(*) FROM turns WHERE trigger = 'fixed_memo_changed'")).isEqualTo(1);
    }

    @Test
    void m3_morningContextHas28DaysOfFixedWithMemoAndNotices() {
        long near = fixed("치과", "2026-11-05T15:00", 60, 0, "보험증 챙기기");
        fixed("빈 메모 약속", "2026-11-06T15:00", 60, 0, null);
        tool("add_block_log", "{\"block_id\":" + near + ",\"type\":\"notice\",\"content\":\"보험증 확인\"}");
        seriesApi("{\"title\":\"결혼식\",\"freq\":\"monthly\",\"month_day\":20,\"start_date\":\"2026-11-20\","
                + "\"until_date\":\"2026-11-20\",\"start_time\":\"12:00\",\"duration_min\":120}");
        long wedding = db.jdbc().sql("SELECT id FROM fixed_series").query(Long.class).single();
        tx.run(() -> {
            memos.setSeriesMemo(wedding, "정장, 축의금");
            return null;
        });
        String ctx = tx.run(() -> contextBuilder.build("morning", Json.parse("{\"logical_day\":\"2026-11-02\"}")));
        assertThat(ctx).contains("치과 블록#" + near + " · 메모: 보험증 챙기기 · 고지함: 11/2 \"보험증 확인\"");
        assertThat(ctx).contains("결혼식").contains("정장, 축의금");
        assertThat(ctx).contains("빈 메모 약속 블록#");
        assertThat(ctx).doesNotContain("빈 메모 약속 블록#" + (near + 1) + " · 메모:");
    }

    @Test
    void m4_normalContextOnly48HoursOfMemos() {
        fixed("가까운", "2026-11-03T15:00", 60, 0, "가까운 메모");
        fixed("먼", "2026-11-06T15:00", 60, 0, "먼 메모");
        String ctx = tx.run(() -> contextBuilder.build("user_message", Json.parse("{\"message_id\":0}")));
        assertThat(ctx).contains("가까운 메모").doesNotContain("먼 메모");
    }

    @Test
    void m5_pulseCannotAppendMemo() {
        long b = fixed("치과", "2026-11-05T15:00", 60, 0, "보험증");
        JsonNode r = tool("pulse", "append_fixed_memo", "{\"block_id\":" + b + ",\"text\":\"의뢰서\"}");
        assertThat(r.path("ok").asBoolean()).isFalse();
        JsonNode ok = tool("append_fixed_memo", "{\"block_id\":" + b + ",\"text\":\"의뢰서\"}");
        assertThat(ok.path("ok").asBoolean()).isTrue();
        assertThat(block(b).memo()).isEqualTo("보험증\n의뢰서");
    }

    // ------------------------------------------------------------------ §4 블록 기록

    @Test
    void l1_blockLogsRecordedAutomatically() {
        long t = task("보고서", null);
        JsonNode r = tool("set_schedule", "{\"from\":\"" + iso("2026-11-02T09:00") + "\",\"to\":\"" + iso("2026-11-03T00:00")
                + "\",\"blocks\":[{\"task_id\":" + t + ",\"start\":\"" + iso("2026-11-02T10:00") + "\",\"end\":\""
                + iso("2026-11-02T11:00") + "\"}]}");
        long b = r.path("created").get(0).asLong();
        tool("move_block", "{\"block_id\":" + b + ",\"new_start\":\"" + iso("2026-11-02T10:30") + "\"}");
        runUntil("2026-11-02T10:31");
        action("start", onlyIntervention().id(), b, "k1");
        tool("end_block", "{\"block_id\":" + b + ",\"outcome\":\"partial\",\"remaining_minutes\":40}");
        assertThat(logTypes(b)).containsExactly("created", "moved", "intervention_shown", "started", "ended");
        assertThat(db.jdbc().sql("SELECT content FROM block_logs WHERE block_id = ? AND type = 'ended'").param(b)
                .query(String.class).single()).isEqualTo("부분 완료(남은 40분)");
    }

    @Test
    void l2_assistantLogOver200CharsRejected() {
        long b = fixed("치과", "2026-11-05T15:00", 60, 0, null);
        JsonNode r = tool("add_block_log", "{\"block_id\":" + b + ",\"type\":\"note\",\"content\":\"" + "가".repeat(201) + "\"}");
        assertThat(r.path("ok").asBoolean()).isFalse();
        assertThat(r.path("error").asString()).contains("200자");
    }

    // ------------------------------------------------------------------ §6 연결 블록

    @Test
    void k1_linkedBlockMustEndBeforeFixedPrep() {
        long f = fixed("결혼식", "2026-11-03T12:00", 120, 60, null);
        long t = task("KTX 예매", null);
        JsonNode bad = tool("set_schedule", "{\"from\":\"" + iso("2026-11-02T09:00") + "\",\"to\":\"" + iso("2026-11-04T00:00")
                + "\",\"blocks\":[{\"task_id\":" + t + ",\"linked_fixed_block_id\":" + f + ",\"start\":\""
                + iso("2026-11-03T10:30") + "\",\"end\":\"" + iso("2026-11-03T11:30") + "\"}]}");
        assertThat(bad.path("ok").asBoolean()).isFalse();
        assertThat(bad.path("violations").toString()).contains("결혼식");
        JsonNode good = tool("set_schedule", "{\"from\":\"" + iso("2026-11-02T09:00") + "\",\"to\":\"" + iso("2026-11-04T00:00")
                + "\",\"blocks\":[{\"task_id\":" + t + ",\"linked_fixed_block_id\":" + f + ",\"start\":\""
                + iso("2026-11-02T20:00") + "\",\"end\":\"" + iso("2026-11-02T20:30") + "\"}]}");
        assertThat(good.path("ok").asBoolean()).isTrue();
        assertThat(logTypes(f)).contains("linked_created");
    }

    long[] fixedWithLinked() {
        long f = fixed("결혼식", "2026-11-04T12:00", 120, 60, null);
        long t = task("KTX 예매", null);
        JsonNode r = tool("set_schedule", "{\"from\":\"" + iso("2026-11-02T09:00") + "\",\"to\":\"" + iso("2026-11-05T00:00")
                + "\",\"blocks\":[{\"task_id\":" + t + ",\"linked_fixed_block_id\":" + f + ",\"start\":\""
                + iso("2026-11-02T20:00") + "\",\"end\":\"" + iso("2026-11-02T20:30") + "\"}]}");
        return new long[]{f, r.path("created").get(0).asLong(), t};
    }

    @Test
    void k2_deleteFixedCancelsLinkedAndTask() {
        long[] ids = fixedWithLinked();
        goalApi.deleteFixed(ids[0]);
        assertThat(block(ids[1]).status()).isEqualTo("cancelled");
        assertThat(store.task(ids[2]).orElseThrow().status()).isEqualTo("cancelled");
        String ref = db.jdbc().sql("SELECT trigger_ref FROM turns WHERE trigger = 'fixed_changed'").query(String.class).single();
        assertThat(ref).contains("\"change\":\"cancelled\"").contains(String.valueOf(ids[1]));
    }

    @Test
    void k3_fixedTimeChangeSchedulesFixedChangedTurn() {
        long[] ids = fixedWithLinked();
        goalApi.patchFixed(ids[0], Json.parse("{\"start\":\"" + iso("2026-11-04T13:00") + "\",\"end\":\""
                + iso("2026-11-04T15:00") + "\"}"));
        String ref = db.jdbc().sql("SELECT trigger_ref FROM turns WHERE trigger = 'fixed_changed'").query(String.class).single();
        assertThat(ref).contains("\"change\":\"moved\"").contains("\"linked\":[" + ids[1] + "]");
        assertThat(logTypes(ids[0])).contains("moved");
    }

    // ------------------------------------------------------------------ §3.3 개입 화면

    @Test
    void c1_interventionCarriesFixedMemo() {
        fixed("치과", "2026-11-02T10:00", 60, 0, "보험증 챙기기. 2시부터 준비");
        runUntil("2026-11-02T10:00");
        Map<String, String> level = push.ofType("intervention.level").getFirst();
        assertThat(level.get("memo")).isEqualTo("보험증 챙기기. 2시부터 준비");
        assertThat(level.get("tts_text")).endsWith("보험증 챙기기.");
    }

    // ------------------------------------------------------------------ P1 마감

    @Test
    void d3_blockAfterDeadlineRejected() {
        long t = task("과제", "2026-11-02T12:00");
        JsonNode r = tool("set_schedule", "{\"from\":\"" + iso("2026-11-02T09:00") + "\",\"to\":\"" + iso("2026-11-03T00:00")
                + "\",\"blocks\":[{\"task_id\":" + t + ",\"start\":\"" + iso("2026-11-02T11:30") + "\",\"end\":\""
                + iso("2026-11-02T12:30") + "\"}]}");
        assertThat(r.path("ok").asBoolean()).isFalse();
        assertThat(r.path("violations").toString()).contains("과제 마감(11/2(월) 12:00) 이후에 끝나는 블록입니다");
    }

    @Test
    void d4_contextUpcomingDeadlinesWithPassed() {
        task("과제", "2026-11-05T23:00");
        task("지난 과제", "2026-11-01T23:00");
        task("먼 과제", "2026-12-25T23:00");
        String ctx = tx.run(() -> contextBuilder.build("user_message", Json.parse("{\"message_id\":0}")));
        String section = ctx.substring(ctx.indexOf("# 다가오는 마감"), ctx.indexOf("# 다가오는 마감") + 300);
        assertThat(section).contains("과제 · 마감 11/5(목) 23:00 (남은 3일").contains("지난 과제 · 마감 11/1(일) 23:00 (지남");
        assertThat(section).doesNotContain("먼 과제");
    }

    @Test
    void d5_calendarIncludesDeadlinesAndMemos() {
        task("과제", "2026-11-05T23:00");
        fixed("치과", "2026-11-03T15:00", 60, 0, "보험증");
        Map<String, Object> r = calendar.calendar(iso("2026-11-01T00:00"), iso("2026-12-01T00:00"));
        assertThat(Json.write(Json.MAPPER.valueToTree(r))).contains("\"deadlines\":[{\"task_id\"").contains("\"memo\":\"보험증\"");
    }

    @Test
    void d6_userPatchesDeadlineInApp() {
        long t = task("과제", "2026-11-05T23:00");
        Map<String, Object> r = calendar.patchTask(t, Json.parse("{\"deadline\":\"" + iso("2026-11-06T23:00") + "\"}"));
        assertThat(store.task(t).orElseThrow().deadline()).isEqualTo(at("2026-11-06T23:00"));
        assertThat(count("SELECT COUNT(*) FROM turns WHERE trigger = 'task_changed_by_user' AND id = ?", r.get("turn_id")))
                .isEqualTo(1);
    }

    // ------------------------------------------------------------------ P2 지금 상태

    @Test
    void s1_nowStateCounts() {
        long t = task("공부", null);
        db.insert("""
                        INSERT INTO blocks(kind, task_id, title, start_at, end_at, status, screen_text, actual_start, actual_end)
                        VALUES ('planned', ?, '공부', ?, ?, 'done', '', ?, ?)""",
                t, at("2026-11-02T09:00"), at("2026-11-02T10:00"), at("2026-11-02T09:05"), at("2026-11-02T10:00"));
        db.insert("""
                        INSERT INTO blocks(kind, task_id, title, start_at, end_at, status, screen_text, actual_start, actual_end)
                        VALUES ('planned', ?, '공부', ?, ?, 'done', '', ?, ?)""",
                t, at("2026-11-02T10:30"), at("2026-11-02T11:30"), at("2026-11-02T10:31"), at("2026-11-02T11:30"));
        db.insert("""
                        INSERT INTO blocks(kind, task_id, title, start_at, end_at, status, screen_text, actual_start)
                        VALUES ('planned', ?, '공부', ?, ?, 'started', '', ?)""",
                t, at("2026-11-02T13:30"), at("2026-11-02T14:00"), at("2026-11-02T13:32"));
        clock.set(at("2026-11-02T14:12"));
        String ctx = tx.run(() -> contextBuilder.build("user_message", Json.parse("{\"message_id\":0}")));
        assertThat(ctx).contains("오늘 3개 중 3개 제시간 시작, 미루기 0, 놓침 0 / 연속 제시간 3회");
        assertThat(ctx).contains("진행 중: 공부 40분째(예정 30분)");
    }

    @Test
    void memoTooLongRejected() {
        long b = fixed("치과", "2026-11-05T15:00", 60, 0, null);
        assertThatThrownBy(() -> goalApi.patchFixed(b, Json.parse("{\"memo\":\"" + "가".repeat(1001) + "\"}")))
                .isInstanceOf(ApiException.class);
    }
}
