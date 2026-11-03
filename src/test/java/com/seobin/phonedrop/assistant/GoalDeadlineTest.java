package com.seobin.phonedrop.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.seobin.phonedrop.assistant.agent.GoalPrompts;
import com.seobin.phonedrop.assistant.agent.GoalService;
import com.seobin.phonedrop.assistant.agent.Tools;
import com.seobin.phonedrop.assistant.api.GoalController;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** 목표 마감 지남·시간 없음·할 일 모두 끝남(2026-11-02 사용자 지시). 시작 시각 2026-11-02(월) 09:00. */
class GoalDeadlineTest extends AssistantTestBase {

    @Autowired
    Tools agentTools;
    @Autowired
    GoalPrompts prompts;
    @Autowired
    GoalCoverage coverage;
    @Autowired
    GoalService goals;
    @Autowired
    GoalController goalApi;
    @Autowired
    com.seobin.phonedrop.assistant.agent.ContextBuilder contextBuilder;

    static String iso(String local) {
        return at(local).atZone(KST).toOffsetDateTime().toString();
    }

    ObjectNode tool(String name, String json) {
        long turnId = db.insert("INSERT INTO turns(trigger, status, started_at) VALUES ('user_message', 'running', ?)",
                clock.now());
        return tx.run(() -> agentTools.dispatch(new Tools.Context(turnId, "user_message", false), name, Json.parse(json)));
    }

    long goal(String title, String deadlineLocal) {
        return db.insert("INSERT INTO goals(title, deadline, label, color) VALUES (?, ?, ?, '#4C6EF5')",
                title, deadlineLocal == null ? null : at(deadlineLocal), title);
    }

    long task(long goalId, String title, String status) {
        return db.insert("""
                        INSERT INTO tasks(title, first_action, est_minutes, remaining_minutes, goal_id, status)
                        VALUES (?, '열기', 60, 60, ?, ?)""", title, goalId, status);
    }

    long planned(long taskId, long goalId, String start, String end) {
        long id = db.insert("""
                        INSERT INTO blocks(kind, task_id, title, goal_id, start_at, end_at, screen_text)
                        VALUES ('planned', ?, '', ?, ?, ?, '')""", taskId, goalId, at(start), at(end));
        tx.exec(() -> interventions.scheduleBlockJobs(block(id)));
        return id;
    }

    Store.Goal goalRow(long id) {
        return store.goal(id).orElseThrow();
    }

    List<String> kinds() {
        return db.jdbc().sql("SELECT kind FROM messages WHERE role = 'assistant' ORDER BY id").query(String.class).list();
    }

    void sweep(long goalId) {
        tx.exec(() -> prompts.sweep(goalRow(goalId)));
    }

    // ------------------------------------------------------------------ 검사 예외

    @Test
    void noFreeTime3HoursBeforeDeadlineAsksOnce() {
        long g = goal("보고서 제출", "2026-11-02T12:00");
        task(g, "보고서", "active");
        ObjectNode meeting = Json.object();
        meeting.put("title", "회의");
        meeting.put("start", iso("2026-11-02T09:00"));
        meeting.put("duration_min", 180);
        meeting.put("lead_minutes", 0);
        goalApi.createFixed(meeting);

        assertThat(coverage.gap(goalRow(g), clock.now())).isEqualTo(GoalCoverage.Gap.NO_TIME);
        assertThat(coverage.missing()).isEmpty();
        sweep(g);
        sweep(g);
        assertThat(kinds()).containsExactly(GoalPrompts.KIND_SHORTAGE);
    }

    @Test
    void allTasksDoneAsksCompleteOnceAndNewTaskClears() {
        long g = goal("컴활", "2026-11-30T23:00");
        task(g, "기출", "done");
        assertThat(coverage.gap(goalRow(g), clock.now())).isEqualTo(GoalCoverage.Gap.TASKS_DONE);
        sweep(g);
        sweep(g);
        assertThat(kinds()).containsExactly(GoalPrompts.KIND_DONE);
        assertThat(db.jdbc().sql("SELECT goal_id FROM messages").query(Long.class).single()).isEqualTo(g);

        task(g, "실기", "active");
        sweep(g);
        assertThat(db.jdbc().sql("SELECT done_prompted_at FROM goals WHERE id = ?").param(g)
                .query((rs, n) -> rs.getObject(1)).list().getFirst()).isNull();
        assertThat(coverage.gap(goalRow(g), clock.now())).isEqualTo(GoalCoverage.Gap.MISSING);
    }

    @Test
    void brandNewGoalWithoutTasksIsStillMissingNotDone() {
        long g = goal("새 목표", "2026-11-30T23:00");
        assertThat(coverage.gap(goalRow(g), clock.now())).isEqualTo(GoalCoverage.Gap.MISSING);
        sweep(g);
        assertThat(kinds()).isEmpty();
    }

    @Test
    void horizonIsCappedAtDeadline() {
        long g = goal("내일 마감", "2026-11-02T18:00");
        long t = task(g, "준비", "active");
        planned(t, g, "2026-11-02T10:00", "2026-11-02T11:00");
        assertThat(coverage.gap(goalRow(g), clock.now())).isEqualTo(GoalCoverage.Gap.OK);
    }

    // ------------------------------------------------------------------ 마감 지남

    @Test
    void deadlinePassedAsksOnceCancelsBlocksAndRemindsNextMorningOnce() {
        long g = goal("쇼핑몰 개설", "2026-11-02T08:00");
        long t = task(g, "입점 조건 확인", "active");
        long b = planned(t, g, "2026-11-02T10:00", "2026-11-02T11:00");

        tx.exec(prompts::sweepAll);
        tx.exec(prompts::sweepAll);
        assertThat(kinds()).containsExactly(GoalPrompts.KIND_DEADLINE);
        assertThat(block(b).status()).isEqualTo("cancelled");
        assertThat(count("SELECT COUNT(*) FROM jobs WHERE type = 'start_intervention' AND target_id = ? AND status = 'scheduled'", b))
                .isZero();
        assertThat(coverage.missing()).isEmpty();

        // 같은 날 아침 맥락: 상기하지 않는다(이미 물음)
        assertThat(tx.run(() -> prompts.contextSection("morning"))).contains("다시 묻지 말 것");
        // 다음 날 아침: 한 번만 상기
        clock.set(at("2026-11-03T08:00"));
        assertThat(tx.run(() -> prompts.contextSection("morning"))).contains("한 번만 짧게 상기");
        tx.exec(prompts::markReminded);
        assertThat(tx.run(() -> prompts.contextSection("morning"))).contains("다시 묻지 말 것");
        clock.set(at("2026-11-04T08:00"));
        assertThat(tx.run(() -> prompts.contextSection("morning"))).doesNotContain("상기할 것");
        tx.exec(prompts::sweepAll);
        assertThat(kinds()).hasSize(1);
    }

    @Test
    void deadlineJobFiresAtDeadline() {
        long g = goal("과제", "2026-11-02T10:00");
        task(g, "과제", "active");
        tx.exec(() -> prompts.scheduleDeadline(g, at("2026-11-02T10:00")));
        runUntil("2026-11-02T10:01");
        assertThat(kinds()).containsExactly(GoalPrompts.KIND_DEADLINE);
    }

    @Test
    void extendingDeadlineClearsPromptAndAsksAssistantToReplan() {
        long g = goal("쇼핑몰 개설", "2026-11-02T08:00");
        task(g, "입점 조건 확인", "active");
        tx.exec(prompts::sweepAll);
        assertThat(kinds()).containsExactly(GoalPrompts.KIND_DEADLINE);

        goalApi.patch(g, Json.parse("{\"deadline\":\"" + iso("2026-11-09T23:00") + "\"}"));
        assertThat(db.jdbc().sql("SELECT deadline_prompted_at FROM goals WHERE id = ?").param(g)
                .query((rs, n) -> rs.getObject(1)).list().getFirst()).isNull();
        assertThat(count("SELECT COUNT(*) FROM turns WHERE trigger = 'goal_changed_by_user'")).isEqualTo(1);
        assertThat(coverage.gap(goalRow(g), clock.now())).isEqualTo(GoalCoverage.Gap.MISSING);
        // 새 마감 안의 블록은 받아들인다
        JsonNode ok = tool("set_schedule", "{\"from\":\"" + iso("2026-11-02T09:00") + "\",\"to\":\"" + iso("2026-11-04T00:00")
                + "\",\"blocks\":[{\"task_id\":" + lastTask() + ",\"start\":\"" + iso("2026-11-03T10:00") + "\",\"end\":\""
                + iso("2026-11-03T11:00") + "\"}]}");
        assertThat(ok.path("ok").asBoolean()).isTrue();
    }

    long lastTask() {
        return db.jdbc().sql("SELECT MAX(id) FROM tasks").query(Long.class).single();
    }

    // ------------------------------------------------------------------ 검증기

    @Test
    void plannedBlockAfterGoalDeadlineRejectedButFixedAllowed() {
        long g = goal("컴활", "2026-11-03T12:00");
        long t = task(g, "기출", "active");
        JsonNode bad = tool("set_schedule", "{\"from\":\"" + iso("2026-11-02T09:00") + "\",\"to\":\"" + iso("2026-11-04T00:00")
                + "\",\"blocks\":[{\"task_id\":" + t + ",\"start\":\"" + iso("2026-11-03T15:00") + "\",\"end\":\""
                + iso("2026-11-03T16:00") + "\"}]}");
        assertThat(bad.path("ok").asBoolean()).isFalse();
        assertThat(bad.path("violations").toString()).contains("목표 '컴활' 마감");

        ObjectNode fixed = Json.object();
        fixed.put("title", "컴활 스터디");
        fixed.put("start", iso("2026-11-03T15:00"));
        fixed.put("duration_min", 60);
        fixed.put("goal_id", g);
        assertThat(goalApi.createFixed(fixed).get("ok")).isEqualTo(true);
    }

    // ------------------------------------------------------------------ 배포 시 정리

    @Test
    void startupCleanupHandlesBothKindsOnce() {
        long passed = goal("지난 목표", "2026-11-02T08:00");
        long pt = task(passed, "할 일", "active");
        long pb = planned(pt, passed, "2026-11-02T10:00", "2026-11-02T11:00");
        long future = goal("다음 목표", "2026-11-03T12:00");
        long ft = task(future, "할 일", "active");
        long fb = planned(ft, future, "2026-11-03T13:00", "2026-11-03T14:00");

        tx.exec(prompts::startup);
        tx.exec(prompts::startup);
        assertThat(block(pb).status()).isEqualTo("cancelled");
        assertThat(kinds()).containsExactly(GoalPrompts.KIND_DEADLINE);
        assertThat(count("SELECT COUNT(*) FROM turns WHERE trigger = ?", GoalPrompts.AFTER_DEADLINE_TRIGGER)).isEqualTo(1);
        String ref = db.jdbc().sql("SELECT trigger_ref FROM turns WHERE trigger = ?").param(GoalPrompts.AFTER_DEADLINE_TRIGGER)
                .query(String.class).single();
        assertThat(ref).contains("\"goal_id\":" + future).contains(String.valueOf(fb));
        assertThat(block(fb).status()).isEqualTo("planned");
        assertThat(goalRow(future).deadline()).isEqualTo(at("2026-11-03T12:00"));
        assertThat(goalRow(passed).status()).isEqualTo("active");
    }

    @Test
    void contextMarksAskedDeadlineAsDoNotAskAgain() {
        long g = goal("지난 목표", "2026-11-02T08:00");
        task(g, "할 일", "active");
        tx.exec(prompts::sweepAll);
        String ctx = tx.run(() -> contextBuilder.build("user_message", Json.parse("{\"message_id\":0}")));
        assertThat(ctx).contains("지남 — 이미 사용자에게 물었다, 다시 묻지 말 것");
        assertThat(ctx).contains("# 답을 기다리는 목표 질문");
    }

    int count(String sql, Object... params) {
        var spec = db.jdbc().sql(sql);
        for (Object p : params) {
            spec = spec.param(p);
        }
        return spec.query(Integer.class).single();
    }
}
