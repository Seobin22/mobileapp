package com.seobin.phonedrop.assistant.agent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import com.seobin.phonedrop.assistant.AssistantClock;
import com.seobin.phonedrop.assistant.AssistantDb;
import com.seobin.phonedrop.assistant.BlockLogs;
import com.seobin.phonedrop.assistant.GoalCoverage;
import com.seobin.phonedrop.assistant.InterventionService;
import com.seobin.phonedrop.assistant.Jobs;
import com.seobin.phonedrop.assistant.Json;
import com.seobin.phonedrop.assistant.PlanService;
import com.seobin.phonedrop.assistant.Store;
import com.seobin.phonedrop.assistant.TimeUtil;
import com.seobin.phonedrop.assistant.TurnQueue;
import com.seobin.phonedrop.assistant.Tx;

import tools.jackson.databind.node.ObjectNode;

/**
 * 목표 질문(2026-11-02 사용자 지시). 마감 지남·시간 없음·할 일 모두 끝남은 '블록 없음'으로 비서를 계속 부르지 않고,
 * 코드가 버튼 달린 메시지로 한 번만 묻는다(LLM 호출 없음). 답은 앱의 목표 API(마감 변경·완료·취소)로 온다.
 * - 마감 지남: 그 목표의 미래 계획 블록을 취소하고 묻는다. 연장하면 goal_changed_by_user turn 에서 비서가 다시 배치한다.
 *   답이 없으면 다음 날 아침 브리핑에서 한 번만 상기한다(deadline_reminded_at).
 * - 마감이 바뀌면 마감 관련 질문 기록을 지운다. 새 할 일이 생기면 '할 일 모두 끝남' 기록을 지운다.
 */
@Service
public class GoalPrompts {

    private static final Logger log = LoggerFactory.getLogger(GoalPrompts.class);
    public static final String DEADLINE_JOB = "goal_deadline";
    public static final String KIND_DEADLINE = "goal_deadline";
    public static final String KIND_SHORTAGE = "goal_shortage";
    public static final String KIND_DONE = "goal_done_check";
    /** 배포 시 한 번: 마감 전인데 마감 뒤에 계획 블록이 있는 목표를 비서가 재배치(data_migrations 이름). */
    static final String CLEANUP = "goal_deadline_cleanup_v1";
    public static final String AFTER_DEADLINE_TRIGGER = "goal_blocks_after_deadline";

    private final AssistantDb db;
    private final AssistantClock clock;
    private final Store store;
    private final TimeUtil time;
    private final GoalCoverage coverage;
    private final ChatService chat;
    private final InterventionService interventions;
    private final PlanService plan;
    private final BlockLogs logs;
    private final Jobs jobs;
    private final TurnQueue turns;
    private final Tx tx;
    private final InterruptPolicy interrupts;
    private final boolean enabled;

    public GoalPrompts(AssistantDb db, AssistantClock clock, Store store, TimeUtil time, GoalCoverage coverage,
                       ChatService chat, InterventionService interventions, PlanService plan, BlockLogs logs, Jobs jobs,
                       TurnQueue turns, Tx tx, InterruptPolicy interrupts,
                       @Value("${assistant.worker.enabled:true}") boolean enabled) {
        this.db = db;
        this.clock = clock;
        this.store = store;
        this.time = time;
        this.coverage = coverage;
        this.chat = chat;
        this.interventions = interventions;
        this.plan = plan;
        this.logs = logs;
        this.jobs = jobs;
        this.turns = turns;
        this.tx = tx;
        this.interrupts = interrupts;
        this.enabled = enabled;
        jobs.register(DEADLINE_JOB, this::onDeadline);
    }

    // ---------------------------------------------------------------- 시작·배포 정리

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (enabled) {
            tx.exec(this::startup);
        }
    }

    /** 마감 작업 예약, 마감 지난 목표 질문(한 번만), 배포 시 한 번 마감 뒤 블록 재배치 요청. */
    public void startup() {
        for (Store.Goal g : store.activeGoals()) {
            scheduleDeadline(g.id(), g.deadline());
        }
        sweepAll();
        int done = db.jdbc().sql("SELECT COUNT(*) FROM data_migrations WHERE name = ?").param(CLEANUP)
                .query(Integer.class).single();
        if (done > 0) {
            return;
        }
        List<Long> asked = new ArrayList<>();
        Instant now = clock.now();
        for (Store.Goal g : store.activeGoals()) {
            if (g.deadline() == null || !g.deadline().isAfter(now)) {
                continue;
            }
            List<Long> after = plannedAfterDeadline(g);
            if (!after.isEmpty()) {
                ObjectNode ref = Json.object();
                ref.put("goal_id", g.id());
                ref.set("blocks", Json.MAPPER.valueToTree(after));
                turns.enqueueIfAbsent(AFTER_DEADLINE_TRIGGER, ref, TurnQueue.PRIORITY_DEFAULT, now);
                asked.add(g.id());
            }
        }
        db.update("INSERT INTO data_migrations(name, result, ran_at) VALUES (?, ?, ?)", CLEANUP,
                "reschedule turns for goals " + asked, now);
        log.info("goal deadline cleanup: reschedule turns for goals {}", asked);
    }

    private List<Long> plannedAfterDeadline(Store.Goal g) {
        return db.jdbc().sql("""
                        SELECT b.id FROM blocks b LEFT JOIN tasks t ON t.id = b.task_id
                        WHERE (b.goal_id = ? OR t.goal_id = ?) AND b.kind = 'planned' AND b.status IN ('planned', 'nudging')
                        AND b.end_at > ? ORDER BY b.start_at""")
                .param(g.id()).param(g.id()).param(AssistantDb.ts(g.deadline())).query(Long.class).list();
    }

    // ---------------------------------------------------------------- 마감 작업

    /** 목표 마감 시각에 확인 작업. 마감이 바뀌면 다시 예약한다(없으면 지운다). */
    public void scheduleDeadline(long goalId, Instant deadline) {
        jobs.cancelForTarget(DEADLINE_JOB, goalId);
        if (deadline != null) {
            Instant now = clock.now();
            jobs.schedule(DEADLINE_JOB, goalId, deadline.isAfter(now) ? deadline : now, null);
        }
    }

    private boolean onDeadline(Jobs.Job job) {
        Store.Goal g = store.goal(job.targetId()).orElse(null);
        if (g == null) {
            return false;
        }
        sweep(g);
        return true;
    }

    /** 마감이 바뀌었다(비서 도구·앱 목표 탭): 마감 관련 질문 기록을 지우고 마감 작업을 다시 예약한다. */
    public void deadlineChanged(long goalId) {
        db.update("""
                        UPDATE goals SET deadline_prompted_at = NULL, deadline_reminded_at = NULL, shortage_prompted_at = NULL
                        WHERE id = ?""", goalId);
        store.goal(goalId).ifPresent(g -> scheduleDeadline(g.id(), g.deadline()));
    }

    // ---------------------------------------------------------------- 질문

    public void sweepAll() {
        for (Store.Goal g : store.activeGoals()) {
            sweep(g);
        }
    }

    /** 이 목표의 상태를 보고 필요한 질문을 한 번만 보낸다. */
    public void sweep(Store.Goal g) {
        if (!"active".equals(g.status())) {
            return;
        }
        Instant now = clock.now();
        Prompted p = prompted(g.id());
        if (p.done() && hasActiveTasks(g.id())) {
            db.update("UPDATE goals SET done_prompted_at = NULL WHERE id = ?", g.id());
        }
        switch (coverage.gap(g, now)) {
            case DEADLINE_PASSED -> {
                if (!p.deadline()) {
                    promptDeadlinePassed(g, now);
                }
            }
            case NO_TIME -> {
                if (!p.shortage()) {
                    send(g, KIND_SHORTAGE, "'" + g.title() + "' 마감(" + time.dayText(g.deadline())
                            + ")까지 넣을 빈 시간이 없어요. 마감을 늦출까요?");
                    db.update("UPDATE goals SET shortage_prompted_at = ? WHERE id = ?", now, g.id());
                }
            }
            case TASKS_DONE -> {
                if (!p.done()) {
                    send(g, KIND_DONE, "'" + g.title() + "' 할 일을 모두 끝냈어요. 목표를 완료할까요?");
                    db.update("UPDATE goals SET done_prompted_at = ? WHERE id = ?", now, g.id());
                }
            }
            default -> {
            }
        }
    }

    private void promptDeadlinePassed(Store.Goal g, Instant now) {
        List<Long> cancelled = new ArrayList<>();
        for (Store.Block b : db.jdbc().sql("""
                        SELECT b.* FROM blocks b LEFT JOIN tasks t ON t.id = b.task_id
                        WHERE (b.goal_id = ? OR t.goal_id = ?) AND b.kind = 'planned' AND b.status IN ('planned', 'nudging')
                        AND b.end_at > ? ORDER BY b.start_at""")
                .param(g.id()).param(g.id()).param(AssistantDb.ts(now)).query(Store::block).list()) {
            interventions.cancelRunningForBlock(b.id());
            store.setBlockStatus(b.id(), "cancelled");
            interventions.cancelBlockJobs(b.id());
            logs.add(b.id(), "note", "system", "목표 마감이 지나 취소(마감을 연장하면 비서가 다시 배치)");
            cancelled.add(b.id());
        }
        if (!cancelled.isEmpty()) {
            plan.bumpAndSync();
        }
        send(g, KIND_DEADLINE, "'" + g.title() + "' 목표 마감(" + time.dayText(g.deadline())
                + ")이 지났어요. 마감을 연장할까요, 완료할까요, 취소할까요?"
                + (cancelled.isEmpty() ? "" : " (남은 계획 블록 " + cancelled.size() + "개는 일단 뺐어요)"));
        db.update("UPDATE goals SET deadline_prompted_at = ?, deadline_reminded_at = NULL WHERE id = ?", now, g.id());
        log.info("goal {} deadline passed: asked, cancelled blocks {}", g.id(), cancelled);
    }

    private void send(Store.Goal g, String kind, String text) {
        // v3.3 §1.2: 방해 허용 정도를 따른다(방해 금지 일정 중이면 묶음 메시지에 들어간다).
        long id = interrupts.send(interrupts.proactive(null), text, kind, "normal", null, null);
        db.update("UPDATE messages SET goal_id = ? WHERE id = ?", g.id(), id);
    }

    // ---------------------------------------------------------------- 맥락·아침 상기

    record Prompted(boolean deadline, boolean shortage, boolean done) {
    }

    private Prompted prompted(long goalId) {
        return db.jdbc().sql("""
                        SELECT deadline_prompted_at IS NOT NULL, shortage_prompted_at IS NOT NULL, done_prompted_at IS NOT NULL
                        FROM goals WHERE id = ?""")
                .param(goalId).query((rs, n) -> new Prompted(rs.getBoolean(1), rs.getBoolean(2), rs.getBoolean(3)))
                .single();
    }

    private boolean hasActiveTasks(long goalId) {
        return db.jdbc().sql("SELECT COUNT(*) FROM tasks WHERE goal_id = ? AND status = 'active'").param(goalId)
                .query(Integer.class).single() > 0;
    }

    /** 아침에 한 번 상기할 목표: 마감 지남 질문을 어제(오늘 활동일 시작 전) 보냈고 아직 상기하지 않았다. */
    private List<Long> needsReminder() {
        Instant dayStart = time.activeWindow(time.logicalDay(clock.now())).start();
        return db.jdbc().sql("""
                        SELECT id FROM goals WHERE status = 'active' AND deadline_prompted_at IS NOT NULL
                        AND deadline_reminded_at IS NULL AND deadline_prompted_at < ? ORDER BY id""")
                .param(AssistantDb.ts(dayStart)).query(Long.class).list();
    }

    /** 맥락 '# 답을 기다리는 목표 질문'. morning 이면 상기할 목표를 표시한다. */
    public String contextSection(String trigger) {
        List<Long> remind = "morning".equals(trigger) ? needsReminder() : List.of();
        List<String> lines = db.jdbc().sql("""
                        SELECT id, title, deadline, deadline_prompted_at, shortage_prompted_at, done_prompted_at FROM goals
                        WHERE status = 'active' AND (deadline_prompted_at IS NOT NULL OR shortage_prompted_at IS NOT NULL
                        OR done_prompted_at IS NOT NULL) ORDER BY id""")
                .query((rs, n) -> {
                    long id = rs.getLong("id");
                    List<String> asked = new ArrayList<>();
                    if (rs.getObject("deadline_prompted_at") != null) asked.add("마감 지남(연장·완료·취소)");
                    if (rs.getObject("shortage_prompted_at") != null) asked.add("마감까지 시간 없음(마감 늦출지)");
                    if (rs.getObject("done_prompted_at") != null) asked.add("할 일 모두 끝남(완료할지)");
                    return "- 목표#" + id + " " + rs.getString("title") + ": " + String.join(", ", asked)
                            + (remind.contains(id) ? " → 이번 아침 브리핑에서 한 번만 짧게 상기할 것"
                            : " → 이미 물었다. 다시 묻지 말 것");
                }).list();
        return lines.isEmpty() ? "없음\n" : String.join("\n", lines) + "\n";
    }

    /** morning turn 이 끝나면 상기한 것으로 기록한다(다음 아침에 또 하지 않는다). */
    public void markReminded() {
        for (long id : needsReminder()) {
            db.update("UPDATE goals SET deadline_reminded_at = ? WHERE id = ?", clock.now(), id);
        }
    }

    /** 이 목표에 마감 지남 질문을 보냈는가(맥락 '다가오는 마감' 표시용). */
    public boolean deadlineAsked(long goalId) {
        return prompted(goalId).deadline();
    }
}
