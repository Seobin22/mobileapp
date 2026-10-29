package com.seobin.phonedrop.assistant;

import static com.seobin.phonedrop.assistant.agent.llm.ScriptedLlm.call;
import static com.seobin.phonedrop.assistant.agent.llm.ScriptedLlm.text;
import static com.seobin.phonedrop.assistant.agent.llm.ScriptedLlm.tools;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.seobin.phonedrop.assistant.agent.ChatService;
import com.seobin.phonedrop.assistant.agent.ConfirmationService;
import com.seobin.phonedrop.assistant.agent.llm.LlmClient;

import tools.jackson.databind.node.ObjectNode;

/** 빌드문서 v2 §12.2 통합 테스트 S1~S12 (ScriptedLLM + FakeClock + FakePush). */
class AgentFlowTest extends AssistantTestBase {

    @Autowired
    ChatService chat;
    @Autowired
    ConfirmationService confirmations;

    static String iso(String local) {
        return at(local).atZone(KST).toOffsetDateTime().toString();
    }

    Map<String, Object> say(String text) {
        ObjectNode req = Json.object();
        req.put("client_msg_id", java.util.UUID.randomUUID().toString());
        req.put("text", text);
        req.put("source", "chat");
        return tx.run(() -> chat.receive(req));
    }

    void runAgent() {
        while (agentWorker.runOnce()) {
            // 대기열이 빌 때까지
        }
    }

    long lastId(String table) {
        return db.jdbc().sql("SELECT MAX(id) FROM " + table).query(Long.class).single();
    }

    int count(String sql, Object... params) {
        var spec = db.jdbc().sql(sql);
        for (Object p : params) {
            spec = spec.param(p);
        }
        return spec.query(Integer.class).single();
    }

    String turnStatus(long turnId) {
        return db.jdbc().sql("SELECT status FROM turns WHERE id = ?").param(turnId).query(String.class).single();
    }

    List<String> assistantMessages() {
        return db.jdbc().sql("SELECT content FROM messages WHERE role = 'assistant' ORDER BY id")
                .query(String.class).list();
    }

    long event(String title, String start, String end) {
        return db.insert("INSERT INTO events(title, start_at, end_at) VALUES (?, ?, ?)", title, at(start), at(end));
    }

    @Test
    void s1_reportTaskScheduledAndReplied() {
        llm.then(tools(call("upsert_task", "{\"title\":\"보고서\",\"est_minutes\":120,\"first_action\":\"개요 5분 쓰기\","
                        + "\"deadline\":\"" + iso("2026-11-06T23:30") + "\"}")))
                .thenDo(() -> tools(call("set_schedule", "{\"from\":\"" + iso("2026-11-02T09:00") + "\",\"to\":\""
                        + iso("2026-11-07T00:00") + "\",\"blocks\":[{\"task_id\":" + lastId("tasks") + ",\"start\":\""
                        + iso("2026-11-02T10:00") + "\",\"end\":\"" + iso("2026-11-02T11:00") + "\"},{\"task_id\":"
                        + lastId("tasks") + ",\"start\":\"" + iso("2026-11-03T10:00") + "\",\"end\":\""
                        + iso("2026-11-03T11:00") + "\"}]}")))
                .then(tools(call("send_message", "{\"text\":\"보고서 등록했어요. 다르면 말해주세요\",\"kind\":\"reply\"}")))
                .then(text(""));
        Map<String, Object> r = say("금요일까지 보고서 써야 해");
        runAgent();

        assertThat(turnStatus((Long) r.get("turn_id"))).isEqualTo("ok");
        assertThat(count("SELECT COUNT(*) FROM tasks WHERE title = '보고서'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM blocks WHERE status = 'planned'")).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM jobs WHERE type = 'start_intervention' AND status = 'scheduled'")).isEqualTo(2);
        assertThat(push.ofType("chat.message")).anySatisfy(d -> assertThat(d.get("preview")).startsWith("보고서 등록"));
        assertThat(push.ofType("plan.sync")).isNotEmpty();
        // 블록 시각에 v1 개입이 그대로 뜬다
        runUntil("2026-11-02T10:00");
        assertThat(push.ofType("intervention.level")).hasSize(1);
        assertThat(push.ofType("intervention.level").getFirst().get("screen_text")).isEqualTo("지금 할 건 하나예요. 개요 5분 쓰기");
    }

    @Test
    void s2_invalidScheduleChangesNothingThenFixed() {
        long taskId = db.insert("INSERT INTO tasks(title, first_action, est_minutes, remaining_minutes) VALUES ('보고서', '열기', 60, 60)");
        event("치과", "2026-11-02T15:00", "2026-11-02T16:00");
        String bad = "{\"from\":\"" + iso("2026-11-02T09:00") + "\",\"to\":\"" + iso("2026-11-03T00:00")
                + "\",\"blocks\":[{\"task_id\":" + taskId + ",\"start\":\"" + iso("2026-11-02T15:30") + "\",\"end\":\""
                + iso("2026-11-02T16:30") + "\"}]}";
        String good = bad.replace(iso("2026-11-02T15:30"), iso("2026-11-02T16:30")).replace(iso("2026-11-02T16:30") + "\"}]",
                iso("2026-11-02T17:30") + "\"}]");
        llm.then(tools(call("set_schedule", bad))).then(tools(call("set_schedule", good))).then(text("배치했어요"));
        say("보고서 오늘 해야 해");
        runAgent();

        assertThat(llm.toolResults.get(0).isError()).isTrue();
        assertThat(llm.toolResults.get(0).content()).contains("violations").contains("치과");
        assertThat(llm.toolResults.get(1).isError()).isFalse();
        assertThat(db.jdbc().sql("SELECT start_at FROM blocks").query((rs, n) -> AssistantDb.instant(rs, "start_at")).list())
                .containsExactly(at("2026-11-02T16:30"));
    }

    @Test
    void s3_userMovesEventDirectly() {
        long ev = event("치과", "2026-11-05T15:00", "2026-11-05T16:00");
        llm.then(tools(call("upsert_event", "{\"id\":" + ev + ",\"start\":\"" + iso("2026-11-12T15:00") + "\",\"end\":\""
                + iso("2026-11-12T16:00") + "\"}"))).then(text("옮겼어요"));
        say("치과 다음 주로 미뤄졌어");
        runAgent();
        assertThat(store.event(ev).orElseThrow().start()).isEqualTo(at("2026-11-12T15:00"));
        assertThat(count("SELECT COUNT(*) FROM confirmations")).isZero();
    }

    @Test
    void s4_nonUserTriggerEventChangeBecomesConfirmation() {
        long ev = event("치과", "2026-11-05T15:00", "2026-11-05T16:00");
        llm.then(tools(call("upsert_event", "{\"id\":" + ev + ",\"start\":\"" + iso("2026-11-09T15:00") + "\",\"end\":\""
                + iso("2026-11-09T16:00") + "\",\"confirm_text\":\"치과를 11/09 15:00으로 옮길까요?\"}"))).then(text(""));
        tx.run(() -> chat.enqueueTurn("confirmation_resolved", Json.parse("{\"confirmation_id\":0,\"accepted\":true}"), 5));
        runAgent();

        assertThat(store.event(ev).orElseThrow().start()).isEqualTo(at("2026-11-05T15:00"));
        assertThat(llm.toolResults.getFirst().content()).contains("pending_confirmation_id");
        assertThat(count("SELECT COUNT(*) FROM confirmations WHERE status = 'pending'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM messages WHERE kind = 'confirmation'")).isEqualTo(1);
        assertThat(push.ofType("confirmation")).singleElement()
                .satisfies(d -> assertThat(d.get("text")).isEqualTo("치과를 11/09 15:00으로 옮길까요?"));
    }

    @Test
    void s5_acceptConfirmationAppliesAndSchedulesTurn() {
        s4_nonUserTriggerEventChangeBecomesConfirmation();
        long confirmationId = lastId("confirmations");
        long ev = lastId("events");
        Map<String, Object> r = tx.run(() -> confirmations.resolve(confirmationId, true));

        assertThat(r.get("status")).isEqualTo("accepted");
        assertThat(store.event(ev).orElseThrow().start()).isEqualTo(at("2026-11-09T15:00"));
        assertThat(count("SELECT COUNT(*) FROM changes c JOIN turns t ON t.id = c.turn_id WHERE t.trigger = 'system'"))
                .isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM turns WHERE trigger = 'confirmation_resolved' AND status = 'queued'"))
                .isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM jobs WHERE type = 'turn' AND status = 'scheduled'")).isEqualTo(1);
        // 이미 처리된 카드는 409
        try {
            tx.run(() -> confirmations.resolve(confirmationId, false));
            org.junit.jupiter.api.Assertions.fail("409 가 나야 한다");
        } catch (ApiException e) {
            assertThat(e.status().value()).isEqualTo(409);
        }
    }

    @Test
    void s6_undoLastRestoresPreviousTurn() {
        s1_reportTaskScheduledAndReplied();
        long taskId = lastId("tasks");
        llm.reset();
        llm.then(tools(call("undo_last", "{}"))).then(text("되돌렸어요"));
        say("방금 거 취소");
        runAgent();

        assertThat(llm.toolResults.getFirst().content()).contains("\"restored\":2").contains("\"skipped\":1");
        assertThat(store.task(taskId).orElseThrow().status()).isEqualTo("cancelled");
        // 10:00 블록은 이미 화면이 떠 있어(nudging) 그대로 두고, 내일 블록만 취소
        assertThat(db.jdbc().sql("SELECT status FROM blocks ORDER BY start_at").query(String.class).list())
                .containsExactly("nudging", "cancelled");
    }

    @Test
    void s7_toolCallLimitEndsPartialKeepingChanges() {
        for (int i = 0; i < 20; i++) {
            llm.then(tools(call("upsert_note", "{\"content\":\"메모 " + i + "\"}")));
        }
        Map<String, Object> r = say("메모 많이");
        runAgent();
        assertThat(turnStatus((Long) r.get("turn_id"))).isEqualTo("partial");
        assertThat(count("SELECT COUNT(*) FROM notes")).isEqualTo(15);
    }

    @Test
    void s8_llmTimeoutNotifiesAndRetriesInOneMinute() {
        llm.thenDo(() -> {
            throw new LlmClient.LlmException("timeout", null);
        }).then(text("이제 됐어요"));
        say("안녕");
        runAgent();

        assertThat(assistantMessages()).containsExactly("지금 처리가 안 돼요, 곧 다시 볼게요");
        Instant retryAt = db.jdbc().sql("SELECT fire_at FROM jobs WHERE type = 'turn'")
                .query((rs, n) -> AssistantDb.instant(rs, "fire_at")).single();
        assertThat(retryAt).isEqualTo(START.plusSeconds(60));

        clock.advance(1);
        runAgent();
        assertThat(assistantMessages()).containsExactly("지금 처리가 안 돼요, 곧 다시 볼게요", "이제 됐어요");
    }

    @Test
    void s9_longTurnDoesNotDelayIntervention() throws Exception {
        createBlock("보고서", "2026-11-02T09:02", 30);
        CountDownLatch inLlm = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        llm.thenDo(() -> {
            inLlm.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return text("늦게 답해요");
        });
        say("긴 생각");
        Thread agent = new Thread(this::runAgent);
        agent.start();
        assertThat(inLlm.await(5, TimeUnit.SECONDS)).isTrue();

        runUntil("2026-11-02T09:02"); // turn 이 LLM 을 기다리는 동안 timing worker 진행
        assertThat(push.ofType("intervention.level")).hasSize(1);

        release.countDown();
        agent.join(10_000);
        assertThat(assistantMessages()).containsExactly("늦게 답해요");
    }

    @Test
    void s10_textOnlyAnswerIsSentAsReply() {
        llm.then(text("네, 알겠어요. 다르면 말해주세요"));
        say("고마워");
        runAgent();
        assertThat(db.jdbc().sql("SELECT kind FROM messages WHERE role = 'assistant'").query(String.class).single())
                .isEqualTo("reply");
        assertThat(assistantMessages()).containsExactly("네, 알겠어요. 다르면 말해주세요");
    }

    @Test
    void s11_userCancelsGoalAndFutureBlocks() {
        long goal = db.insert("INSERT INTO goals(title) VALUES ('컴활 2급')");
        long taskId = db.insert("""
                INSERT INTO tasks(title, first_action, est_minutes, remaining_minutes, goal_id) VALUES ('기출 풀기', '책 펴기', 30, 30, ?)""",
                goal);
        createBlockFor(taskId, "2026-11-03T10:00", "2026-11-03T10:30");
        llm.then(tools(call("cancel", "{\"entity\":\"goal\",\"id\":" + goal + "}"))).then(text("취소했어요"));
        say("컴활은 그만할래");
        runAgent();
        assertThat(store.goal(goal).orElseThrow().status()).isEqualTo("cancelled");
        assertThat(store.task(taskId).orElseThrow().status()).isEqualTo("cancelled");
        assertThat(db.jdbc().sql("SELECT status FROM blocks").query(String.class).single()).isEqualTo("cancelled");
        assertThat(count("SELECT COUNT(*) FROM jobs WHERE type = 'start_intervention' AND status = 'scheduled'")).isZero();
    }

    @Test
    void s12_eventConflictsThenRescheduled() {
        long taskId = db.insert("INSERT INTO tasks(title, first_action, est_minutes, remaining_minutes) VALUES ('보고서', '열기', 60, 60)");
        long blockId = createBlockFor(taskId, "2026-11-05T15:00", "2026-11-05T16:00");
        llm.then(tools(call("upsert_event", "{\"title\":\"치과\",\"start\":\"" + iso("2026-11-05T15:00") + "\",\"end\":\""
                        + iso("2026-11-05T16:00") + "\",\"buffer_before_min\":20,\"buffer_after_min\":20}")))
                .then(tools(call("set_schedule", "{\"from\":\"" + iso("2026-11-05T00:00") + "\",\"to\":\"" + iso("2026-11-06T00:00")
                        + "\",\"blocks\":[{\"task_id\":" + taskId + ",\"start\":\"" + iso("2026-11-05T16:30") + "\",\"end\":\""
                        + iso("2026-11-05T17:30") + "\"}]}")))
                .then(text("치과 넣고 보고서는 16:30으로 옮겼어요"));
        say("목요일 3시 치과");
        runAgent();

        assertThat(llm.toolResults.get(0).content()).contains("\"conflicting_blocks\":[" + blockId + "]");
        assertThat(block(blockId).status()).isEqualTo("cancelled");
        assertThat(db.jdbc().sql("SELECT start_at FROM blocks WHERE status = 'planned'")
                .query((rs, n) -> AssistantDb.instant(rs, "start_at")).single()).isEqualTo(at("2026-11-05T16:30"));
    }

    @Test
    void askUserCountsAsReplySoFinalTextIsNotResent() {
        // 평가에서 발견: ask_user 뒤 마지막 텍스트가 reply 로 한 번 더 나가던 문제
        llm.then(tools(call("ask_user", "{\"text\":\"운동, 시작해볼까요?\",\"options\":[\"네\",\"메모만\"]}")))
                .then(text("메모해 뒀어요. 운동, 시작해볼까요?"));
        say("운동 좀 해야 하는데");
        runAgent();
        assertThat(assistantMessages()).containsExactly("운동, 시작해볼까요?");
    }

    @Test
    void duplicateClientMessageIdReturnsSameTurn() {
        ObjectNode req = Json.object();
        req.put("client_msg_id", "same-uuid");
        req.put("text", "안녕");
        Map<String, Object> a = tx.run(() -> chat.receive(req));
        Map<String, Object> b = tx.run(() -> chat.receive(req));
        assertThat(b).isEqualTo(a);
        assertThat(count("SELECT COUNT(*) FROM messages")).isEqualTo(1);
    }

    long createBlockFor(long taskId, String start, String end) {
        long id = db.insert("INSERT INTO blocks(task_id, start_at, end_at, screen_text) VALUES (?, ?, ?, '지금 할 건 하나예요. 열기')",
                taskId, at(start), at(end));
        tx.exec(() -> interventions.scheduleBlockJobs(store.block(id).orElseThrow()));
        return id;
    }
}
