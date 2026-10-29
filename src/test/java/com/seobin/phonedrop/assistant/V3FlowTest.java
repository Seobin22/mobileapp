package com.seobin.phonedrop.assistant;

import static com.seobin.phonedrop.assistant.agent.llm.ScriptedLlm.call;
import static com.seobin.phonedrop.assistant.agent.llm.ScriptedLlm.text;
import static com.seobin.phonedrop.assistant.agent.llm.ScriptedLlm.tools;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.seobin.phonedrop.assistant.agent.AgentRunner;
import com.seobin.phonedrop.assistant.agent.ChatService;
import com.seobin.phonedrop.assistant.agent.ProactiveService;
import com.seobin.phonedrop.assistant.agent.Tools;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** 빌드문서 v3 §9.1 통합 테스트 (ScriptedLLM + FakeClock + FakePush). */
class V3FlowTest extends AssistantTestBase {

    @Autowired
    ChatService chat;
    @Autowired
    Tools agentTools;
    @Autowired
    ProactiveService proactive;
    @Autowired
    AgentRunner runner;

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

    void say(String text) {
        ObjectNode req = Json.object();
        req.put("client_msg_id", java.util.UUID.randomUUID().toString());
        req.put("text", text);
        tx.run(() -> chat.receive(req));
    }

    /** 도구를 직접 실행한다(빈 turn 하나를 만들어 그 안에서). */
    ObjectNode tool(String name, String inputJson) {
        long turnId = db.insert("INSERT INTO turns(trigger, status, started_at) VALUES ('user_message', 'running', ?)",
                clock.now());
        Tools.Context ctx = new Tools.Context(turnId, "user_message", false);
        return tx.run(() -> agentTools.dispatch(ctx, name, Json.parse(inputJson)));
    }

    Store.Intervention intervention(long id) {
        return store.intervention(id).orElseThrow();
    }

    /** 10:00–11:00 블록, 10:01 에 개입 중, [미루기 요청] 직후. {task, block, intervention} */
    long[] snoozedAt1001() {
        long[] ids = createBlock("보고서 초안", "2026-11-02T10:00", 60);
        runUntil("2026-11-02T10:01");
        long interventionId = onlyIntervention().id();
        action("snooze_request", interventionId, ids[1], "snooze-" + interventionId);
        return new long[]{ids[0], ids[1], interventionId};
    }

    int turns(String trigger) {
        return count("SELECT COUNT(*) FROM turns WHERE trigger = ?", trigger);
    }

    // ------------------------------------------------------------------ N: 미루기 협상

    @Test
    void n1_snoozeRequestStartsNegotiation() {
        long[] ids = snoozedAt1001();
        Store.Intervention i = intervention(ids[2]);
        assertThat(i.status()).isEqualTo("talking");
        assertThat(i.talkUntil()).isEqualTo(at("2026-11-02T10:06"));
        assertThat(count("SELECT COUNT(*) FROM jobs WHERE type = 'reshow' AND status = 'scheduled'")).isZero();
        assertThat(count("SELECT COUNT(*) FROM jobs WHERE type = 'talk_timeout' AND status = 'scheduled'"))
                .isEqualTo(1);
        assertThat(turns("intervention_talk")).isEqualTo(1);
        assertThat(db.jdbc().sql("SELECT priority FROM jobs WHERE type = 'turn'").query(Integer.class).single())
                .isEqualTo(10);
        // 협상 중에는 다시 띄우지 않는다
        runUntil("2026-11-02T10:05");
        assertThat(push.ofType("intervention.level")).hasSize(1);
    }

    @Test
    void n2_negotiationTimeoutResumesIntervention() {
        long[] ids = snoozedAt1001();
        runUntil("2026-11-02T10:06");
        assertThat(intervention(ids[2]).status()).isEqualTo("running");
        assertThat(push.ofType("intervention.level")).hasSize(2);
        // reshow 재예약
        runUntil("2026-11-02T10:09");
        assertThat(push.ofType("intervention.level")).hasSize(3);
    }

    @Test
    void n3_userMessageExtendsNegotiation() {
        long[] ids = snoozedAt1001();
        runUntil("2026-11-02T10:04");
        say("지금 너무 피곤해");
        assertThat(intervention(ids[2]).talkUntil()).isEqualTo(at("2026-11-02T10:09"));
        runUntil("2026-11-02T10:08");
        assertThat(intervention(ids[2]).status()).isEqualTo("talking");
        runUntil("2026-11-02T10:09");
        assertThat(intervention(ids[2]).status()).isEqualTo("running");
    }

    @Test
    void n4_acceptedSnoozeMovesBlockAndInterventionComesBackLater() {
        long[] ids = snoozedAt1001();
        llm.then(tools(call("get_facts", "{\"task_id\":" + ids[0] + "}")))
                .then(tools(call("move_block", "{\"block_id\":" + ids[1] + ",\"new_start\":\"" + iso("2026-11-02T10:30") + "\"}")))
                .then(tools(call("send_message", "{\"text\":\"10:30에 시작해요\",\"kind\":\"reply\"}")))
                .then(text(""));
        runAgent();

        Store.Intervention i = intervention(ids[2]);
        assertThat(i.status()).isEqualTo("snoozed");
        assertThat(i.endReason()).isEqualTo("moved");
        assertThat(push.ofType("intervention.end")).anySatisfy(d -> assertThat(d.get("reason")).isEqualTo("snoozed"));
        assertThat(block(ids[1]).status()).isEqualTo("planned");
        assertThat(block(ids[1]).start()).isEqualTo(at("2026-11-02T10:30"));
        assertThat(llm.userContents.getFirst()).contains("# 사실(get_facts").contains("intervention_talk");

        runUntil("2026-11-02T10:30");
        assertThat(interventionCount()).isEqualTo(2);
        assertThat(block(ids[1]).status()).isEqualTo("nudging");
    }

    @Test
    void n5_startNowAcksIntervention() {
        long[] ids = snoozedAt1001();
        llm.then(tools(call("start_now", "{\"task_id\":" + ids[0] + "}"))).then(text(""));
        runAgent();
        assertThat(intervention(ids[2]).status()).isEqualTo("acked");
        assertThat(block(ids[1]).status()).isEqualTo("started");
        assertThat(push.ofType("intervention.end")).anySatisfy(d -> assertThat(d.get("reason")).isEqualTo("acked"));
    }

    @Test
    void n6_factsCountTodaysSnoozes() {
        long[] ids = snoozedAt1001();
        llm.then(tools(call("move_block", "{\"block_id\":" + ids[1] + ",\"new_start\":\"" + iso("2026-11-02T10:30") + "\"}")))
                .then(text(""));
        runAgent();
        runUntil("2026-11-02T10:31");
        long second = db.jdbc().sql("SELECT MAX(id) FROM interventions").query(Long.class).single();
        action("snooze_request", second, ids[1], "snooze-2");
        llm.then(tools(call("move_block", "{\"block_id\":" + ids[1] + ",\"new_start\":\"" + iso("2026-11-02T10:50") + "\"}")))
                .then(text(""));
        runAgent();

        JsonNode facts = tool("get_facts", "{\"task_id\":" + ids[0] + "}");
        assertThat(facts.path("snoozed_today_count").asInt()).isEqualTo(2);
        assertThat(facts.path("snoozed_today_minutes").asInt()).isEqualTo(50);
        assertThat(facts.path("remaining_minutes").asInt()).isEqualTo(60);
    }

    @Test
    void factsSlackFromDeadline() {
        long taskId = db.insert("""
                        INSERT INTO tasks(title, first_action, est_minutes, remaining_minutes, deadline)
                        VALUES ('보고서', '열기', 120, 120, ?)""",
                at("2026-11-02T15:00"));
        // 09:00~15:00 중 식사 12:00~13:00 을 빼면 300분
        JsonNode f = tool("get_facts", "{\"task_id\":" + taskId + "}");
        assertThat(f.path("free_minutes_until_deadline").asInt()).isEqualTo(300);
        assertThat(f.path("slack_minutes").asInt()).isEqualTo(180);
        assertThat(f.path("slack_if_delayed_30m").asInt()).isEqualTo(150);
        assertThat(f.path("hours_until_deadline").asDouble()).isEqualTo(6.0);
    }

    // ------------------------------------------------------------------ B: 블록 끝·놓침

    @Test
    void b1_startedBlockEndKeepsStatusAndQueuesBlockEnded() {
        long[] ids = createBlock("보고서 초안", "2026-11-02T10:00", 60);
        runUntil("2026-11-02T10:01");
        action("start", onlyIntervention().id(), ids[1], "k");
        assertThat(turns("block_started")).isEqualTo(1);
        runUntil("2026-11-02T11:00");
        assertThat(block(ids[1]).status()).isEqualTo("started");
        assertThat(turns("block_ended")).isEqualTo(1);
    }

    @Test
    void b2_nextBlockEndsStillStartedPreviousAsPartial() {
        long[] a = createBlock("보고서 초안", "2026-11-02T10:00", 60);
        long[] b = createBlock("메일 정리", "2026-11-02T11:00", 30);
        runUntil("2026-11-02T10:01");
        action("start", onlyIntervention().id(), a[1], "k");
        runUntil("2026-11-02T11:00");

        Store.Block prev = block(a[1]);
        assertThat(prev.status()).isEqualTo("partial");
        assertThat(prev.actualEnd()).isEqualTo(at("2026-11-02T11:00"));
        assertThat(store.task(a[0]).orElseThrow().remainingMinutes()).isEqualTo(60);
        assertThat(block(b[1]).status()).isEqualTo("nudging");
        assertThat(store.runningForBlock(b[1])).isPresent();
    }

    @Test
    void b3_expiredWithoutResponseQueuesBlockMissed() {
        long[] ids = createBlock("보고서 초안", "2026-11-02T10:00", 60);
        runUntil("2026-11-02T11:15");
        assertThat(block(ids[1]).status()).isEqualTo("missed");
        assertThat(db.jdbc().sql("SELECT trigger_ref FROM turns WHERE trigger = 'block_missed'").query(String.class).single())
                .contains("\"reason\":\"no_response\"").contains("\"block_id\":" + ids[1]);
    }

    @Test
    void plannedBlockNeverStartedQueuesBlockMissed() {
        long[] ids = createBlock("보고서 초안", "2026-11-02T10:00", 60);
        // 개입 시작 작업을 지워 planned 인 채로 끝 시각을 맞게 한다
        db.update("UPDATE jobs SET status = 'cancelled' WHERE type = 'start_intervention'");
        runUntil("2026-11-02T11:00");
        assertThat(block(ids[1]).status()).isEqualTo("missed");
        assertThat(db.jdbc().sql("SELECT trigger_ref FROM turns WHERE trigger = 'block_missed'").query(String.class).single())
                .contains("never_started");
    }

    @Test
    void extendAndEndBlock() {
        long[] ids = createBlock("보고서 초안", "2026-11-02T10:00", 60);
        createBlock("메일 정리", "2026-11-02T11:30", 30);
        runUntil("2026-11-02T10:01");
        action("start", onlyIntervention().id(), ids[1], "k");

        assertThat(tool("extend_block", "{\"block_id\":" + ids[1] + ",\"new_end\":\"" + iso("2026-11-02T11:45") + "\"}")
                .path("violations").toString()).contains("메일 정리");
        assertThat(tool("extend_block", "{\"block_id\":" + ids[1] + ",\"new_end\":\"" + iso("2026-11-02T11:20") + "\"}")
                .path("ok").asBoolean()).isTrue();
        runUntil("2026-11-02T11:00");
        assertThat(turns("block_ended")).isZero();
        runUntil("2026-11-02T11:20");
        assertThat(turns("block_ended")).isEqualTo(1);

        assertThat(tool("end_block", "{\"block_id\":" + ids[1] + ",\"outcome\":\"partial\",\"remaining_minutes\":30}")
                .path("ok").asBoolean()).isTrue();
        assertThat(block(ids[1]).status()).isEqualTo("partial");
        assertThat(store.task(ids[0]).orElseThrow().remainingMinutes()).isEqualTo(30);
    }

    @Test
    void startNowWithoutInterventionDisplacesPlannedBlocks() {
        long taskId = db.insert("INSERT INTO tasks(title, first_action, est_minutes, remaining_minutes) VALUES ('운동', '신발 신기', 40, 40)");
        long[] other = createBlock("메일 정리", "2026-11-02T09:20", 30);
        db.update("UPDATE blocks SET kind = 'planned' WHERE id = ?", other[1]); // 계획 블록(고정 블록이면 start_now 가 거절)
        JsonNode r = tool("start_now", "{\"task_id\":" + taskId + "}");
        assertThat(r.path("ok").asBoolean()).isTrue();
        Store.Block started = block(r.path("block_id").asLong());
        assertThat(started.status()).isEqualTo("started");
        assertThat(started.end()).isEqualTo(at("2026-11-02T09:40"));
        assertThat(r.path("displaced").toString()).isEqualTo("[" + other[1] + "]");
        assertThat(block(other[1]).status()).isEqualTo("cancelled");
    }

    // ------------------------------------------------------------------ W: wakeup

    @Test
    void w1_wakeupSkippedWhenTaskDone() {
        long taskId = db.insert("INSERT INTO tasks(title, first_action, est_minutes, remaining_minutes) VALUES ('보고서', '열기', 60, 60)");
        assertThat(tool("schedule_wakeup", "{\"at\":\"" + iso("2026-11-02T09:30") + "\",\"reason\":\"30분 체크인\",\"task_id\":"
                + taskId + "}").path("ok").asBoolean()).isTrue();
        tool("upsert_task", "{\"id\":" + taskId + ",\"status\":\"done\"}");
        runUntil("2026-11-02T09:31");
        assertThat(db.jdbc().sql("SELECT status FROM wakeups").query(String.class).single()).isEqualTo("skipped");
        assertThat(turns("wakeup")).isZero();
    }

    @Test
    void wakeupFiresTurn() {
        long taskId = db.insert("INSERT INTO tasks(title, first_action, est_minutes, remaining_minutes) VALUES ('보고서', '열기', 60, 60)");
        tool("schedule_wakeup", "{\"at\":\"" + iso("2026-11-02T09:30") + "\",\"reason\":\"사전 브리핑\",\"task_id\":" + taskId + "}");
        runUntil("2026-11-02T09:30");
        assertThat(db.jdbc().sql("SELECT status FROM wakeups").query(String.class).single()).isEqualTo("done");
        assertThat(turns("wakeup")).isEqualTo(1);
        runAgent();
        assertThat(llm.userContents.getFirst()).contains("사전 브리핑").contains("# 사실(get_facts, 할일#" + taskId);
    }

    @Test
    void w2_fourthWakeupForSameTaskRejected() {
        long taskId = db.insert("INSERT INTO tasks(title, first_action, est_minutes, remaining_minutes) VALUES ('보고서', '열기', 60, 60)");
        for (int i = 1; i <= 3; i++) {
            assertThat(tool("schedule_wakeup", "{\"at\":\"" + iso("2026-11-02T1" + i + ":00") + "\",\"reason\":\"체크인\",\"task_id\":"
                    + taskId + "}").path("ok").asBoolean()).isTrue();
        }
        ObjectNode fourth = tool("schedule_wakeup", "{\"at\":\"" + iso("2026-11-02T15:00") + "\",\"reason\":\"체크인\",\"task_id\":"
                + taskId + "}");
        assertThat(fourth.path("ok").asBoolean()).isFalse();
        assertThat(count("SELECT COUNT(*) FROM wakeups")).isEqualTo(3);
    }

    // ------------------------------------------------------------------ P: pulse

    @Test
    void pulseCreatesTurnWhenNothingBlocks() {
        tx.exec(proactive::ensureJobs);
        runUntil("2026-11-02T11:00");
        assertThat(turns("pulse")).isEqualTo(1);
    }

    @Test
    void p1_noPulseWhileWorking() {
        long[] ids = createBlock("보고서 초안", "2026-11-02T10:00", 90);
        tx.exec(proactive::ensureJobs);
        runUntil("2026-11-02T10:01");
        action("start", onlyIntervention().id(), ids[1], "k");
        runUntil("2026-11-02T11:00");
        assertThat(turns("pulse")).isZero();
        assertThat(count("SELECT COUNT(*) FROM jobs WHERE type = 'pulse' AND status = 'skipped'")).isEqualTo(1);
        // 다음 회차는 다시 예약된다
        assertThat(count("SELECT COUNT(*) FROM jobs WHERE type = 'pulse' AND status = 'scheduled'")).isEqualTo(1);
    }

    @Test
    void p2_pulseSkippedWhenOtherTurnQueued() {
        long pulseTurn = tx.run(() -> chat.enqueueTurn("pulse", Json.object(), TurnQueue.PRIORITY_PULSE));
        say("내일 회의 있어");
        Jobs.Job pulseJob = db.jdbc().sql("SELECT * FROM jobs WHERE type = 'turn' AND target_id = ?").param(pulseTurn)
                .query(Jobs::map).single();
        runner.runJob(pulseJob);
        assertThat(db.jdbc().sql("SELECT status FROM turns WHERE id = ?").param(pulseTurn).query(String.class).single())
                .isEqualTo("skipped");
        assertThat(llm.userContents).isEmpty();
    }

    // ------------------------------------------------------------------ M: 메시지 예산·silent

    void proactiveSentToday(int n) {
        for (int i = 0; i < n; i++) {
            db.insert("INSERT INTO messages(role, content, kind, created_at) VALUES ('assistant', '먼저 말', 'proactive', ?)",
                    clock.now());
        }
    }

    boolean send(String kind, String importance) {
        return tool("send_message", "{\"text\":\"알림\",\"kind\":\"" + kind + "\",\"importance\":\"" + importance + "\"}")
                .path("silent").asBoolean();
    }

    @Test
    void m1_overBudgetIsSilent() {
        proactiveSentToday(11);
        assertThat(send("proactive", "normal")).isFalse(); // 12번째
        assertThat(send("proactive", "normal")).isTrue();  // 13번째
        assertThat(db.jdbc().sql("SELECT silent FROM messages ORDER BY id DESC LIMIT 1").query(Boolean.class).single()).isTrue();
        assertThat(push.ofType("chat.message").getLast().get("silent")).isEqualTo("true");
    }

    @Test
    void m2_highOverBudgetNotifiesThreeTimes() {
        proactiveSentToday(12);
        assertThat(send("proactive", "high")).isFalse();
        assertThat(send("proactive", "high")).isFalse();
        assertThat(send("proactive", "high")).isFalse();
        assertThat(send("proactive", "high")).isTrue();
    }

    @Test
    void m3_replyIgnoresBudget() {
        proactiveSentToday(20);
        assertThat(send("reply", "normal")).isFalse();
    }

    @Test
    void m4_dndMakesProactiveSilent() {
        putSetting("dnd.windows", "[{\"days\":[1],\"start\":\"08:30\",\"end\":\"10:00\"}]");
        assertThat(send("proactive", "normal")).isTrue();
        assertThat(send("reply", "normal")).isFalse();
    }

    @Test
    void checkinNotifiesDuringWorkButProactiveDoesNot() {
        long[] ids = createBlock("보고서 초안", "2026-11-02T09:00", 60);
        action("start", null, ids[1], "k");
        assertThat(block(ids[1]).status()).isEqualTo("started");
        assertThat(send("proactive", "normal")).isTrue();
        assertThat(send("checkin", "normal")).isFalse();
        assertThat(push.ofType("chat.message").getLast().get("channel")).isEqualTo("checkin");
    }

    // ------------------------------------------------------------------ A: 아침

    void firstUnlock(String local) {
        long deviceId = db.jdbc().sql("SELECT id FROM devices").query(Long.class).single();
        ObjectNode req = Json.object();
        req.put("device_id", deviceId);
        ObjectNode e = req.putArray("events").addObject();
        e.put("type", "first_unlock");
        e.put("at", iso(local));
        tx.exec(() -> devices.heartbeat(req));
    }

    @Test
    void a1_firstUnlockTwiceMorningOnce() {
        firstUnlock("2026-11-02T08:55");
        firstUnlock("2026-11-02T09:00");
        assertThat(turns("morning")).isEqualTo(1);
    }

    @Test
    void firstUnlockBeforeWakeIgnored() {
        clock.set(at("2026-11-02T07:10"));
        firstUnlock("2026-11-02T07:05");
        assertThat(turns("morning")).isZero();
    }

    @Test
    void a2_morningFallbackWithoutUnlock() {
        clock.set(at("2026-11-02T07:00"));
        tx.exec(proactive::ensureJobs);
        runUntil("2026-11-02T08:29");
        assertThat(turns("morning")).isZero();
        runUntil("2026-11-02T08:30");
        assertThat(turns("morning")).isEqualTo(1);
        firstUnlock("2026-11-02T08:31");
        assertThat(turns("morning")).isEqualTo(1);
    }

    @Test
    void eveningOncePerDay() {
        tx.exec(proactive::ensureJobs);
        runUntil("2026-11-02T22:30");
        assertThat(turns("evening")).isEqualTo(1);
        assertThat(db.jdbc().sql("SELECT trigger_ref FROM turns WHERE trigger = 'evening'").query(String.class).single())
                .contains("2026-11-02");
    }

    // ------------------------------------------------------------------ R: 작업물·soft 상한

    @Test
    void r1_attachedWorkProductInInterventionLevel() {
        long[] ids = createBlock("쇼핑몰 조건 확인", "2026-11-02T10:00", 60);
        JsonNode wp = tool("create_work_product", "{\"task_id\":" + ids[0] + ",\"title\":\"개설 조건 요약\",\"kind\":\"research\","
                + "\"content_md\":\"# 요약\\n- 사업자 등록 필요(확인 필요)\",\"sources\":[{\"title\":\"안내\",\"url\":\"https://example.com\"}]}");
        long wpId = wp.path("id").asLong();
        assertThat(tool("attach_work_product", "{\"block_id\":" + ids[1] + ",\"work_product_id\":" + wpId + "}")
                .path("ok").asBoolean()).isTrue();
        runUntil("2026-11-02T10:00");
        Map<String, String> level = push.ofType("intervention.level").getFirst();
        assertThat(level.get("work_product_id")).isEqualTo(String.valueOf(wpId));
        assertThat(tool("get_work_products", "{\"task_id\":" + ids[0] + "}").path("work_products").get(0)
                .path("sources").get(0).path("url").asString()).isEqualTo("https://example.com");
    }

    @Test
    void r2_softLimitDisablesWebSearchAndPulse() {
        llm.then(text("네"));
        say("안녕");
        runAgent();
        assertThat(llm.webSearchFlags).containsExactly(true);

        putSetting("agent.daily_turn_soft", "1");
        llm.reset();
        llm.then(text("네"));
        say("또 안녕");
        runAgent();
        assertThat(llm.webSearchFlags).containsExactly(false);
        assertThat(tool("create_work_product", "{\"task_id\":1,\"title\":\"x\",\"kind\":\"other\",\"content_md\":\"x\"}")
                .path("error").asString()).contains("daily_turn_soft");

        clock.set(at("2026-11-02T13:00"));
        tx.exec(proactive::ensureJobs);
        runUntil("2026-11-02T15:00");
        assertThat(turns("pulse")).isZero();
    }

    @Test
    void webDisabledSettingRemovesWebSearch() {
        putSetting("web.enabled", "false");
        llm.then(text("네"));
        say("안녕");
        runAgent();
        assertThat(llm.webSearchFlags).containsExactly(false);
    }
}
