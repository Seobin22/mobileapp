package com.seobin.phonedrop.assistant;

import static com.seobin.phonedrop.assistant.agent.llm.ScriptedLlm.call;
import static com.seobin.phonedrop.assistant.agent.llm.ScriptedLlm.text;
import static com.seobin.phonedrop.assistant.agent.llm.ScriptedLlm.tools;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.seobin.phonedrop.assistant.agent.ChatService;
import com.seobin.phonedrop.assistant.agent.GoalService;
import com.seobin.phonedrop.assistant.agent.Tools;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** 빌드문서 v3.1 §5.1 통합 테스트 (ScriptedLLM + FakeClock + FakePush). */
class V31FlowTest extends AssistantTestBase {

    @Autowired
    ChatService chat;
    @Autowired
    Tools agentTools;
    @Autowired
    GoalService goals;
    @Autowired
    V5DataMigration migration;
    @Autowired
    GoalCoverage coverage;

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

    long lastId(String table) {
        return db.jdbc().sql("SELECT MAX(id) FROM " + table).query(Long.class).single();
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

    String fixed(String title, String start, String end, int lead) {
        return "{\"title\":\"" + title + "\",\"start\":\"" + iso(start) + "\",\"end\":\"" + iso(end)
                + "\",\"lead_minutes\":" + lead + "}";
    }

    long goal(String title) {
        return tool("upsert_goal", "{\"title\":\"" + title + "\"}").path("id").asLong();
    }

    long task(Long goalId, String title) {
        return db.insert("""
                        INSERT INTO tasks(title, first_action, est_minutes, remaining_minutes, goal_id) VALUES (?, ?, 60, 60, ?)""",
                title, title + " 열기", goalId);
    }

    /** 계획 블록(할 일의 목표를 따름) + 개입 예약. */
    long planned(long taskId, String start, String end) {
        Long goalId = store.task(taskId).orElseThrow().goalId();
        long id = db.insert("""
                        INSERT INTO blocks(kind, task_id, title, goal_id, start_at, end_at, screen_text)
                        VALUES ('planned', ?, '', ?, ?, ?, '지금 할 건 하나예요.')""",
                taskId, goalId, at(start), at(end));
        tx.exec(() -> interventions.scheduleBlockJobs(block(id)));
        return id;
    }

    int turns(String trigger) {
        return count("SELECT COUNT(*) FROM turns WHERE trigger = ?", trigger);
    }

    Instant jobFire(String type) {
        return db.jdbc().sql("SELECT fire_at FROM jobs WHERE type = ? AND status = 'scheduled' ORDER BY id")
                .param(type).query((rs, n) -> AssistantDb.instant(rs, "fire_at")).list().getFirst();
    }

    Map<String, Object> talk(long interventionId, String text) {
        ObjectNode req = Json.object();
        req.put("client_msg_id", java.util.UUID.randomUUID().toString());
        req.put("text", text);
        req.put("source", "fullscreen");
        req.put("intervention_id", interventionId);
        return tx.run(() -> chat.receive(req));
    }

    /** 10:00–11:00 고정 블록(lead 0), 10:01 에 개입 중. {block, intervention} */
    long[] interventionAt1001() {
        long[] ids = createBlock("보고서 초안", "2026-11-02T10:00", 60);
        runUntil("2026-11-02T10:01");
        return new long[]{ids[1], onlyIntervention().id()};
    }

    // ------------------------------------------------------------------ F: 고정 블록

    @Test
    void f1_migrationCopiesFutureEventsOnceAndSchedulesIntervention() {
        db.insert("""
                        INSERT INTO events(title, start_at, end_at, buffer_before_min, buffer_after_min)
                        VALUES ('치과', ?, ?, 15, 10)""",
                at("2026-11-03T15:00"), at("2026-11-03T16:00"));
        db.insert("INSERT INTO events(title, start_at, end_at) VALUES ('지난 약속', ?, ?)",
                at("2026-11-01T10:00"), at("2026-11-01T11:00"));

        Map<String, Object> r = tx.run(migration::run);
        assertThat(r.get("copied_to_fixed_blocks")).isEqualTo(1);
        assertThat(r.get("skipped_past")).isEqualTo(1);
        Store.Block b = store.block(lastId("blocks")).orElseThrow();
        assertThat(b.fixed()).isTrue();
        assertThat(b.title()).isEqualTo("치과");
        assertThat(b.leadMinutes()).isEqualTo(15);
        assertThat(b.bufferAfterMin()).isEqualTo(10);
        assertThat(jobFire("start_intervention")).isEqualTo(at("2026-11-03T14:45"));

        Map<String, Object> again = tx.run(migration::run);
        assertThat(again.get("copied_to_fixed_blocks")).isEqualTo(0);
        assertThat(again.get("skipped_already_copied")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM blocks WHERE kind = 'fixed'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM jobs WHERE type = 'start_intervention' AND status = 'scheduled'")).isEqualTo(1);
    }

    @Test
    void f1b_migrationFillsPlannedBlockTitlesAndGoalLabels() {
        long g = db.insert("INSERT INTO goals(title) VALUES ('컴퓨터활용능력 2급')");
        long t = task(g, "기출 1회");
        long b = db.insert("INSERT INTO blocks(task_id, start_at, end_at, screen_text) VALUES (?, ?, ?, '')",
                t, at("2026-11-03T10:00"), at("2026-11-03T11:00"));
        tx.run(migration::run);
        assertThat(block(b).title()).isEqualTo("기출 1회");
        assertThat(block(b).goalId()).isEqualTo(g);
        Store.Goal goal = store.goal(g).orElseThrow();
        assertThat(goal.label()).isEqualTo("컴퓨터활용능");
        assertThat(goal.color()).isEqualTo(GoalCoverage.PALETTE.getFirst());
    }

    @Test
    void f2_userMessageCreatesFixedBlockWithLead() {
        JsonNode r = tool("upsert_fixed_block", fixed("치과", "2026-11-02T15:00", "2026-11-02T16:00", 20));
        assertThat(r.path("ok").asBoolean()).isTrue();
        assertThat(count("SELECT COUNT(*) FROM confirmations")).isZero();
        Store.Block b = block(r.path("id").asLong());
        assertThat(b.fixed()).isTrue();
        assertThat(b.screenText()).contains("곧 치과이에요").contains("20분 뒤 시작");
        assertThat(jobFire("start_intervention")).isEqualTo(at("2026-11-02T14:40"));
        assertThat(TimeUtil.parse(r.path("intervention_at").asString())).isEqualTo(at("2026-11-02T14:40"));
    }

    @Test
    void p3_noDefaultLeadSetting() {
        assertThat(settings.all()).doesNotContainKey("fixed.default_lead_minutes").doesNotContainKey("life.meals");
        // 비서가 준비 시간을 안 정하면 lead 0(아직 안 정함), 시작 시각에 개입
        JsonNode r = tool("upsert_fixed_block", "{\"title\":\"회의\",\"start\":\"" + iso("2026-11-02T15:00")
                + "\",\"end\":\"" + iso("2026-11-02T16:00") + "\"}");
        Store.Block b = block(r.path("id").asLong());
        assertThat(b.leadMinutes()).isZero();
        assertThat(b.prepSetBy()).isNull();
        assertThat(b.screenText()).isEqualTo("지금 회의 시간이에요");
        assertThat(jobFire("start_intervention")).isEqualTo(at("2026-11-02T15:00"));
    }

    @Test
    void f3_pulseMovingFixedBlockBecomesConfirmation() {
        long id = tool("upsert_fixed_block", fixed("치과", "2026-11-02T15:00", "2026-11-02T16:00", 0)).path("id").asLong();
        JsonNode r = tool("pulse", "move_block", "{\"block_id\":" + id + ",\"new_start\":\"" + iso("2026-11-02T17:00") + "\"}");
        assertThat(r.path("pending_confirmation_id").isNumber()).isTrue();
        assertThat(block(id).start()).isEqualTo(at("2026-11-02T15:00"));
        // 계획 블록은 pulse 에서도 바로 옮긴다
        long t = task(null, "보고서");
        long p = planned(t, "2026-11-02T10:00", "2026-11-02T11:00");
        assertThat(tool("pulse", "move_block", "{\"block_id\":" + p + ",\"new_start\":\"" + iso("2026-11-02T13:00") + "\"}")
                .path("ok").asBoolean()).isTrue();
        assertThat(block(p).start()).isEqualTo(at("2026-11-02T13:00"));
    }

    @Test
    void f4_fixedOverPlannedDisplacesIt() {
        long t = task(null, "보고서");
        long p = planned(t, "2026-11-02T15:00", "2026-11-02T16:00");
        JsonNode r = tool("upsert_fixed_block", fixed("치과", "2026-11-02T15:30", "2026-11-02T16:30", 0));
        assertThat(r.path("ok").asBoolean()).isTrue();
        assertThat(r.path("displaced").toString()).isEqualTo("[" + p + "]");
        assertThat(block(p).status()).isEqualTo("cancelled");
        assertThat(block(r.path("id").asLong()).status()).isEqualTo("planned");
    }

    @Test
    void f5_fixedOverFixedRejected() {
        tool("upsert_fixed_block", fixed("치과", "2026-11-02T10:00", "2026-11-02T11:00", 0));
        JsonNode r = tool("upsert_fixed_block", fixed("미용실", "2026-11-02T10:30", "2026-11-02T11:30", 0));
        assertThat(r.path("ok").asBoolean()).isFalse();
        assertThat(r.path("error").asString()).contains("치과(11/02 10:00–11:00").contains("겹쳐요. 시간을 바꿔 주세요.");
        assertThat(count("SELECT COUNT(*) FROM blocks WHERE kind = 'fixed'")).isEqualTo(1);
    }

    @Test
    void f6_leadTwentyStartsInterventionTwentyMinutesEarly() {
        tool("upsert_fixed_block", fixed("치과", "2026-11-02T11:00", "2026-11-02T12:00", 20));
        runUntil("2026-11-02T10:39");
        assertThat(push.ofType("intervention.level")).isEmpty();
        runUntil("2026-11-02T10:40");
        assertThat(push.ofType("intervention.level")).singleElement().satisfies(d -> {
            assertThat(d.get("kind")).isEqualTo("fixed");
            assertThat(d.get("lead_minutes")).isEqualTo("20");
            assertThat(d.get("task_title")).isEqualTo("치과");
        });
        // 계획 블록 금지 구간: 10:40~11:00(출발 준비)에는 계획 블록을 둘 수 없다
        long t = task(null, "메일");
        JsonNode bad = tool("set_schedule", "{\"from\":\"" + iso("2026-11-02T10:00") + "\",\"to\":\"" + iso("2026-11-02T12:00")
                + "\",\"blocks\":[{\"task_id\":" + t + ",\"start\":\"" + iso("2026-11-02T10:45") + "\",\"end\":\""
                + iso("2026-11-02T10:55") + "\"}]}");
        assertThat(bad.path("ok").asBoolean()).isFalse();
    }

    @Test
    void f7_fixedOverStartedBlockSchedulesPartialCutoff() {
        long t = task(null, "보고서");
        long p = planned(t, "2026-11-02T09:30", "2026-11-02T10:30");
        runUntil("2026-11-02T09:31");
        action("start", onlyIntervention().id(), p, "k");
        assertThat(block(p).status()).isEqualTo("started");

        JsonNode r = tool("upsert_fixed_block", fixed("통화", "2026-11-02T10:00", "2026-11-02T10:30", 10));
        assertThat(r.path("ok").asBoolean()).isTrue();
        assertThat(r.path("cutoff_started_blocks").toString()).isEqualTo("[" + p + "]");
        assertThat(jobFire("block_cutoff")).isEqualTo(at("2026-11-02T09:50"));
        runUntil("2026-11-02T09:50");
        assertThat(block(p).status()).isEqualTo("partial");
        assertThat(block(p).actualEnd()).isEqualTo(at("2026-11-02T09:50"));
    }

    // ------------------------------------------------------------------ P: 준비 시간은 비서의 판단 (§3.2.1)

    @Autowired
    com.seobin.phonedrop.assistant.agent.FixedBlockService fixedBlocks;

    /** 앱(POST /v1/fixed_blocks)과 같은 경로: turnId 없이 만들고 준비 시간이 비었으면 fixed_block_added. */
    long appFixed(String json) {
        return tx.run(() -> {
            var r = fixedBlocks.upsert(Json.parse(json), null);
            fixedBlocks.notifyDisplaced(r);
            fixedBlocks.notifyPrepNeeded(r);
            assertThat(r.ok()).as(r.error()).isTrue();
            return r.id();
        });
    }

    @Test
    void p1_appBlockWithoutPrepLetsAssistantDecide() {
        long id = appFixed("{\"title\":\"치과\",\"start\":\"" + iso("2026-11-02T15:00") + "\",\"duration_min\":60,"
                + "\"location\":\"강남역 치과\"}");
        Store.Block b = block(id);
        assertThat(b.prepSetBy()).isNull();
        assertThat(b.leadMinutes()).isZero();
        assertThat(b.source()).isEqualTo("manual");
        assertThat(turns("fixed_block_added")).isEqualTo(1);
        assertThat(db.jdbc().sql("SELECT priority FROM jobs WHERE type = 'turn'").query(Integer.class).single()).isEqualTo(10);

        llm.then(tools(call("upsert_fixed_block", "{\"id\":" + id + ",\"lead_minutes\":30,\"buffer_after_min\":20}")))
                .then(text(""));
        runAgent();
        assertThat(llm.userContents.getFirst()).contains("fixed_block_added").contains("(준비 시간 미정)");
        assertThat(count("SELECT COUNT(*) FROM confirmations")).isZero();
        b = block(id);
        assertThat(b.leadMinutes()).isEqualTo(30);
        assertThat(b.bufferAfterMin()).isEqualTo(20);
        assertThat(b.prepSetBy()).isEqualTo("assistant");
        assertThat(jobFire("start_intervention")).isEqualTo(at("2026-11-02T14:30"));
    }

    @Test
    void p2_userSetPrepIsNotOverwrittenByAssistant() {
        long id = appFixed("{\"title\":\"치과\",\"start\":\"" + iso("2026-11-02T15:00") + "\",\"duration_min\":60,"
                + "\"lead_minutes\":15}");
        assertThat(block(id).prepSetBy()).isEqualTo("user");
        assertThat(turns("fixed_block_added")).isZero();

        JsonNode pulse = tool("pulse", "upsert_fixed_block", "{\"id\":" + id + ",\"lead_minutes\":40}");
        assertThat(pulse.path("ok").asBoolean()).isFalse();
        assertThat(pulse.path("error").asString()).contains("사용자가 정한 준비 시간");
        JsonNode chat = tool("upsert_fixed_block", "{\"id\":" + id + ",\"lead_minutes\":40}");
        assertThat(chat.path("ok").asBoolean()).isFalse();
        assertThat(block(id).leadMinutes()).isEqualTo(15);
        // 사용자가 직접 말한 값이면 바꿀 수 있다
        assertThat(tool("upsert_fixed_block", "{\"id\":" + id + ",\"lead_minutes\":25,\"prep_from_user\":true}")
                .path("ok").asBoolean()).isTrue();
        assertThat(block(id).leadMinutes()).isEqualTo(25);
        assertThat(block(id).prepSetBy()).isEqualTo("user");
    }

    @Test
    void p2b_assistantPrepCanBeRedecidedWithoutConfirmation() {
        long id = tool("upsert_fixed_block", fixed("미용실", "2026-11-02T16:00", "2026-11-02T17:00", 10)).path("id").asLong();
        assertThat(block(id).prepSetBy()).isEqualTo("assistant");
        JsonNode r = tool("wakeup", "upsert_fixed_block", "{\"id\":" + id + ",\"lead_minutes\":30}");
        assertThat(r.path("ok").asBoolean()).isTrue();
        assertThat(block(id).leadMinutes()).isEqualTo(30);
        // 시작 시각 변경은 기존 권한 규칙 그대로(확인 카드)
        JsonNode move = tool("wakeup", "upsert_fixed_block", "{\"id\":" + id + ",\"start\":\"" + iso("2026-11-02T18:00")
                + "\",\"end\":\"" + iso("2026-11-02T19:00") + "\"}");
        assertThat(move.path("pending_confirmation_id").isNumber()).isTrue();
    }

    // ------------------------------------------------------------------ M: 기본 식사 시간 없음

    @Test
    void m1_noMealSetting() {
        assertThat(settings.all()).doesNotContainKey("life.meals");
    }

    @Test
    void m2_lunchTimeIsSchedulable() {
        long t = task(null, "보고서");
        JsonNode r = tool("set_schedule", "{\"from\":\"" + iso("2026-11-02T09:00") + "\",\"to\":\"" + iso("2026-11-03T00:00")
                + "\",\"blocks\":[{\"task_id\":" + t + ",\"start\":\"" + iso("2026-11-02T12:00") + "\",\"end\":\""
                + iso("2026-11-02T13:00") + "\"}]}");
        assertThat(r.path("ok").asBoolean()).as(r.toString()).isTrue();
    }

    @Test
    void m3_noMealInTimelineOrContext() {
        JsonNode r = tool("get_timeline", "{\"from\":\"" + iso("2026-11-02T09:00") + "\",\"to\":\"" + iso("2026-11-04T00:00") + "\"}");
        assertThat(r.toString()).doesNotContain("\"meal\"");
        assertThat(tx.run(this::agentContext)).doesNotContain("식사");
    }

    // ------------------------------------------------------------------ G: 목표 진행 보장

    @Test
    void g1_goalWithoutBlocksIsSentBackThenScheduled() {
        llm.then(tools(call("upsert_goal", "{\"title\":\"컴활 2급\",\"label\":\"컴활\"}")))
                .then(text("목표 만들었어요"))
                .thenDo(() -> tools(call("upsert_task", "{\"goal_id\":" + lastId("goals")
                        + ",\"title\":\"기출 1회\",\"est_minutes\":60,\"first_action\":\"기출 파일 열기\"}")))
                .thenDo(() -> tools(call("set_schedule", "{\"from\":\"" + iso("2026-11-02T09:00") + "\",\"to\":\""
                        + iso("2026-11-03T00:00") + "\",\"blocks\":[{\"task_id\":" + lastId("tasks") + ",\"start\":\""
                        + iso("2026-11-02T14:00") + "\",\"end\":\"" + iso("2026-11-02T15:00") + "\"}]}")))
                .then(text("오늘 14시에 기출 1회 잡았어요"));
        ObjectNode req = Json.object();
        req.put("text", "컴활 2급 따고 싶어");
        Map<String, Object> r = tx.run(() -> chat.receive(req));
        runAgent();

        assertThat(llm.userTexts).singleElement().asString()
                .contains("목표 #" + lastId("goals") + "(컴활)").contains("2일 안에 잡힌 블록이 없습니다");
        assertThat(db.jdbc().sql("SELECT status FROM turns WHERE id = ?").param(r.get("turn_id")).query(String.class).single())
                .isEqualTo("ok");
        assertThat(turns("goal_needs_blocks")).isZero();
        Store.Block b = block(lastId("blocks"));
        assertThat(b.goalId()).isEqualTo(lastId("goals"));
        assertThat(b.title()).isEqualTo("기출 1회");
    }

    @Test
    void g1b_retriesExhaustedEndsTurnAndRetriesIn30Minutes() {
        llm.then(tools(call("upsert_goal", "{\"title\":\"쇼핑몰 개설\"}"))).then(text("a")).then(text("b")).then(text("c"));
        ObjectNode req = Json.object();
        req.put("text", "쇼핑몰 열고 싶어");
        tx.run(() -> chat.receive(req));
        runAgent();
        assertThat(llm.userTexts).hasSize(2);
        assertThat(turns("goal_needs_blocks")).isEqualTo(1);
        assertThat(db.jdbc().sql("""
                        SELECT j.fire_at FROM jobs j JOIN turns t ON t.id = j.target_id
                        WHERE j.type = 'turn' AND t.trigger = 'goal_needs_blocks'""")
                .query((rs, n) -> AssistantDb.instant(rs, "fire_at")).single()).isEqualTo(clock.now().plusSeconds(1800));
    }

    @Test
    void g2_lastBlockClosedQueuesGoalNeedsBlocks() {
        long g = goal("컴활 2급");
        long t = task(g, "기출 1회");
        long p = planned(t, "2026-11-02T10:00", "2026-11-02T11:00");
        long later = planned(t, "2026-11-02T14:00", "2026-11-02T15:00");
        db.update("UPDATE blocks SET status = 'cancelled' WHERE id = ?", later);
        tx.exec(() -> interventions.cancelBlockJobs(later));
        // 10:00 개입 → 무응답 → 11:15 expire 로 missed → 그 목표의 미래 블록이 없으니 goal_needs_blocks
        runUntil("2026-11-02T11:15");
        assertThat(block(p).status()).isEqualTo("missed");
        assertThat(turns("goal_needs_blocks")).isEqualTo(1);
        assertThat(db.jdbc().sql("SELECT trigger_ref FROM turns WHERE trigger = 'goal_needs_blocks'").query(String.class).single())
                .contains("\"goal_id\":" + g);
    }

    @Test
    void g2b_doneInBlockEndedTurnWithoutNextBlockIsSentBack() {
        long g = goal("컴활 2급");
        long t = task(g, "기출 1회");
        long p = planned(t, "2026-11-02T10:00", "2026-11-02T11:00");
        runUntil("2026-11-02T10:01");
        action("start", onlyIntervention().id(), p, "k");
        llm.then(text("")); // block_started
        runAgent();
        runUntil("2026-11-02T11:00");
        llm.reset();
        llm.then(tools(call("end_block", "{\"block_id\":" + p + ",\"outcome\":\"done\"}")))
                .then(text("수고했어요"))
                .thenDo(() -> tools(call("set_schedule", "{\"from\":\"" + iso("2026-11-02T11:00") + "\",\"to\":\""
                        + iso("2026-11-04T00:00") + "\",\"blocks\":[{\"task_id\":" + t + ",\"start\":\""
                        + iso("2026-11-03T10:00") + "\",\"end\":\"" + iso("2026-11-03T11:00") + "\"}]}")))
                .then(text(""));
        runAgent();
        assertThat(block(p).status()).isEqualTo("done");
        assertThat(llm.userTexts).hasSize(1);
        assertThat(count("SELECT COUNT(*) FROM blocks WHERE goal_id = ? AND status = 'planned'", g)).isEqualTo(1);
    }

    @Test
    void g3_waitingGoalIsNotMissingAndChecksAtWaitingUntil() {
        JsonNode r = tool("upsert_goal", "{\"title\":\"사업자 등록\",\"waiting_until\":\"" + iso("2026-11-05T10:00")
                + "\",\"waiting_reason\":\"심사 결과\"}");
        long g = r.path("id").asLong();
        assertThat(coverage.missing()).isEmpty();
        assertThat(coverage.status(store.goal(g).orElseThrow(), clock.now())).isEqualTo("waiting");
        assertThat(jobFire("goal_waiting_check")).isEqualTo(at("2026-11-05T10:00"));
        clock.set(at("2026-11-05T10:00"));
        jobs.runDue();
        assertThat(turns("goal_waiting_check")).isEqualTo(1);
        assertThat(coverage.missing()).extracting(Store.Goal::id).containsExactly(g);
    }

    @Test
    void g4_goalTabCancelKeepsFixedBlocksButUnlinksThem() {
        long g = goal("컴활 2급");
        long t = task(g, "기출 1회");
        long p = planned(t, "2026-11-03T10:00", "2026-11-03T11:00");
        long f = tool("upsert_fixed_block", "{\"title\":\"컴활 공부\",\"start\":\"" + iso("2026-11-06T19:00") + "\",\"end\":\""
                + iso("2026-11-06T20:00") + "\",\"lead_minutes\":0,\"goal_id\":" + g + "}").path("id").asLong();
        assertThat(block(f).goalId()).isEqualTo(g);

        Map<String, Object> r = tx.run(() -> goals.cancelByUser(g));
        assertThat(r.get("cancelled_blocks")).isEqualTo(List.of(p));
        assertThat(store.goal(g).orElseThrow().status()).isEqualTo("cancelled");
        assertThat(store.task(t).orElseThrow().status()).isEqualTo("cancelled");
        assertThat(block(p).status()).isEqualTo("cancelled");
        assertThat(block(f).status()).isEqualTo("planned");
        assertThat(block(f).goalId()).isNull();
        assertThat(turns("goal_changed_by_user")).isEqualTo(1);
        assertThat(db.jdbc().sql("""
                        SELECT j.priority FROM jobs j JOIN turns t ON t.id = j.target_id
                        WHERE j.type = 'turn' AND t.trigger = 'goal_changed_by_user'""").query(Integer.class).single())
                .isEqualTo(10);
        assertThat(count("""
                SELECT COUNT(*) FROM changes c JOIN turns t ON t.id = c.turn_id WHERE t.trigger = 'goal_changed_by_user'"""))
                .isGreaterThanOrEqualTo(4);
    }

    @Test
    void g4b_goalTabPatchAndComplete() {
        long g = goal("컴활 2급");
        Map<String, Object> patched = tx.run(() -> goals.patch(g, Json.parse(
                "{\"priority\":1,\"label\":\"컴활2\",\"color\":\"#123abc\",\"deadline\":\"" + iso("2026-11-30T23:00") + "\"}")));
        assertThat(patched.get("ok")).isEqualTo(true);
        Store.Goal goal = store.goal(g).orElseThrow();
        assertThat(goal.priority()).isEqualTo(1);
        assertThat(goal.label()).isEqualTo("컴활2");
        assertThat(goal.color()).isEqualTo("#123ABC");
        assertThat(goal.deadline()).isEqualTo(at("2026-11-30T23:00"));
        tx.run(() -> goals.completeByUser(g));
        assertThat(store.goal(g).orElseThrow().status()).isEqualTo("done");
        assertThat(turns("goal_changed_by_user")).isEqualTo(2);
    }

    @Test
    void g5_threeGoalsGetDifferentColors() {
        long a = goal("컴활");
        long b = goal("쇼핑몰");
        long c = goal("운동");
        assertThat(new HashSet<>(List.of(store.goal(a).orElseThrow().color(), store.goal(b).orElseThrow().color(),
                store.goal(c).orElseThrow().color()))).hasSize(3);
        assertThat(store.goal(b).orElseThrow().label()).isEqualTo("쇼핑몰");
    }

    // ------------------------------------------------------------------ T: 풀스크린 대화

    @Test
    void t1_firstFullscreenMessageStartsTalk() {
        long[] ids = interventionAt1001();
        Map<String, Object> r = talk(ids[1], "지금 너무 피곤해");
        Store.Intervention i = store.intervention(ids[1]).orElseThrow();
        assertThat(i.status()).isEqualTo("talking");
        assertThat(i.talkUntil()).isEqualTo(at("2026-11-02T10:06"));
        assertThat(count("SELECT COUNT(*) FROM jobs WHERE type = 'reshow' AND status = 'scheduled'")).isZero();
        assertThat(count("SELECT COUNT(*) FROM jobs WHERE type = 'talk_timeout' AND status = 'scheduled'")).isEqualTo(1);
        assertThat(turns("intervention_talk")).isEqualTo(1);
        assertThat(db.jdbc().sql("SELECT priority FROM jobs WHERE type = 'turn'").query(Integer.class).single()).isEqualTo(10);
        assertThat(db.jdbc().sql("SELECT intervention_id FROM messages WHERE id = ?").param(r.get("message_id"))
                .query(Long.class).single()).isEqualTo(ids[1]);
        runUntil("2026-11-02T10:05");
        assertThat(push.ofType("intervention.level")).hasSize(1);
    }

    @Test
    void t2_silenceResumesIntervention() {
        long[] ids = interventionAt1001();
        talk(ids[1], "음...");
        runUntil("2026-11-02T10:06");
        assertThat(store.intervention(ids[1]).orElseThrow().status()).isEqualTo("running");
        assertThat(push.ofType("intervention.level")).hasSize(2);
        runUntil("2026-11-02T10:09");
        assertThat(push.ofType("intervention.level")).hasSize(3);
    }

    @Test
    void t3_startNowFromTalkAcksAndRepliesOnScreen() {
        long[] ids = interventionAt1001();
        talk(ids[1], "알았어 할게");
        llm.then(tools(call("send_message", "{\"text\":\"좋아요, 파일만 열어요\",\"kind\":\"reply\"}"),
                        call("start_now", "{\"block_id\":" + ids[0] + "}")))
                .then(text(""));
        runAgent();
        assertThat(store.intervention(ids[1]).orElseThrow().status()).isEqualTo("acked");
        assertThat(block(ids[0]).status()).isEqualTo("started");
        assertThat(push.ofType("intervention.end")).anySatisfy(d -> assertThat(d.get("reason")).isEqualTo("acked"));
        assertThat(push.ofType("chat.message")).anySatisfy(d -> {
            assertThat(d.get("intervention_id")).isEqualTo(String.valueOf(ids[1]));
            assertThat(d.get("text")).isEqualTo("좋아요, 파일만 열어요");
        });
        assertThat(llm.userContents.getFirst()).contains("intervention_talk").contains("이 시작 화면에서 오간 대화")
                .contains("알았어 할게");
    }

    @Test
    void t4_moveBlockFromTalkSnoozesAndComesBack() {
        long[] ids = interventionAt1001();
        talk(ids[1], "10분만 쉬고 할게");
        llm.then(tools(call("move_block", "{\"block_id\":" + ids[0] + ",\"new_start\":\"" + iso("2026-11-02T10:15") + "\"}")))
                .then(text("10:15에 다시 알려줄게요"));
        runAgent();
        Store.Intervention i = store.intervention(ids[1]).orElseThrow();
        assertThat(i.status()).isEqualTo("snoozed");
        assertThat(block(ids[0]).start()).isEqualTo(at("2026-11-02T10:15"));
        // 텍스트로만 답해도 화면 대화의 답으로 간다
        assertThat(db.jdbc().sql("SELECT intervention_id FROM messages WHERE role = 'assistant' ORDER BY id DESC LIMIT 1")
                .query(Long.class).single()).isEqualTo(ids[1]);
        runUntil("2026-11-02T10:15");
        assertThat(interventionCount()).isEqualTo(2);
        assertThat(block(ids[0]).status()).isEqualTo("nudging");
    }

    @Test
    void t5_talkTurnChangesFixedBlockDirectly() {
        long[] ids = interventionAt1001();
        talk(ids[1], "오늘 3시에 치과 있어");
        llm.then(tools(call("upsert_fixed_block", fixed("치과", "2026-11-02T15:00", "2026-11-02T16:00", 20))))
                .then(text("치과 넣었어요"));
        runAgent();
        assertThat(count("SELECT COUNT(*) FROM confirmations")).isZero();
        assertThat(count("SELECT COUNT(*) FROM blocks WHERE kind = 'fixed' AND title = '치과'")).isEqualTo(1);
    }

    @Test
    void t6_legacySnoozeRequestStartsTalk() {
        long[] ids = interventionAt1001();
        action("snooze_request", ids[1], ids[0], "snooze-1");
        assertThat(store.intervention(ids[1]).orElseThrow().status()).isEqualTo("talking");
        assertThat(turns("intervention_talk")).isEqualTo(1);
        assertThat(db.jdbc().sql("SELECT content FROM messages WHERE intervention_id = ? AND role = 'user'")
                .param(ids[1]).query(String.class).single()).isEqualTo("미루고 싶어요");
    }

    // ------------------------------------------------------------------ C: 시간표

    @Test
    void c1_timelineSeparatesFixedAndPlannedWithGoalTags() {
        long g = goal("컴활 2급");
        long t = task(g, "기출 1회");
        long p = planned(t, "2026-11-02T14:00", "2026-11-02T15:00");
        long f = tool("upsert_fixed_block", fixed("치과", "2026-11-02T16:00", "2026-11-02T17:00", 20)).path("id").asLong();
        JsonNode r = tool("get_timeline", "{\"from\":\"" + iso("2026-11-02T09:00") + "\",\"to\":\"" + iso("2026-11-03T00:00") + "\"}");
        List<JsonNode> items = new java.util.ArrayList<>();
        r.path("days").get(0).path("items").forEach(items::add);
        JsonNode planned = items.stream().filter(i -> i.path("id").asLong() == p).findFirst().orElseThrow();
        JsonNode fixedItem = items.stream().filter(i -> i.path("id").asLong() == f).findFirst().orElseThrow();
        assertThat(planned.path("kind").asString()).isEqualTo("planned_block");
        assertThat(planned.path("goal_id").asLong()).isEqualTo(g);
        assertThat(planned.path("goal_label").asString()).isEqualTo("컴활 2급");
        assertThat(planned.path("goal_color").asString()).startsWith("#");
        assertThat(fixedItem.path("kind").asString()).isEqualTo("fixed_block");
        assertThat(fixedItem.path("lead_minutes").asInt()).isEqualTo(20);
        assertThat(fixedItem.path("goal_label").asString()).isEmpty();
        assertThat(items).noneMatch(i -> "event".equals(i.path("kind").asString()) || "block".equals(i.path("kind").asString()));
        // 빈 시간은 출발 준비(15:40~)를 뺀다
        assertThat(items).anySatisfy(i -> {
            assertThat(i.path("kind").asString()).isEqualTo("free");
            assertThat(TimeUtil.parse(i.path("start").asString())).isEqualTo(at("2026-11-02T15:00"));
            assertThat(TimeUtil.parse(i.path("end").asString())).isEqualTo(at("2026-11-02T15:40"));
        });
        String context = tx.run(() -> new String(agentContext()));
        assertThat(context).contains("[고정] 치과 블록#" + f + " (출발 15:40)").contains("[컴활 2급] 블록#" + p + " 기출 1회 (할일#" + t + ")");
    }

    @Autowired
    com.seobin.phonedrop.assistant.agent.ContextBuilder contextBuilder;

    String agentContext() {
        return contextBuilder.build("user_message", Json.parse("{\"message_id\":0}"));
    }
}
