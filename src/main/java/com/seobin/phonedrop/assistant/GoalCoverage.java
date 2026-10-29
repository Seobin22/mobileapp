package com.seobin.phonedrop.assistant;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import tools.jackson.databind.node.ObjectNode;

/**
 * 목표 진행 보장(빌드문서 v3.1 §3.4)과 목표 색(§3.5). 판단은 하지 않는다. "블록이 있는가"만 본다.
 * 미래 블록 = goal_id 가 이 목표인 블록(고정·계획) 중 status=planned 이고 start > now.
 * 누락 = active 이고 (waiting_until 이 없거나 지났고) now ~ now+min_horizon_days 안에 미래 블록이 없는 목표.
 */
@Component
public class GoalCoverage {

    private static final Logger log = LoggerFactory.getLogger(GoalCoverage.class);

    public static final String WAITING_CHECK = "goal_waiting_check";
    public static final String NEEDS_BLOCKS = "goal_needs_blocks";
    /** turn 끝 검사에서도 누락이면 이만큼 뒤에 goal_needs_blocks (§3.4-1). */
    public static final Duration RETRY_LATER = Duration.ofMinutes(30);

    /** 구분이 잘 되는 8색(DECISIONS.md). 순서대로 배정한다. */
    public static final List<String> PALETTE = List.of(
            "#E64980", "#4C6EF5", "#12B886", "#FAB005", "#7950F2", "#FD7E14", "#15AABF", "#82C91E");

    private final AssistantDb db;
    private final AssistantClock clock;
    private final Store store;
    private final SettingsService settings;
    private final TurnQueue turns;
    private final Jobs jobs;

    public GoalCoverage(AssistantDb db, AssistantClock clock, Store store, SettingsService settings, TurnQueue turns,
                        Jobs jobs) {
        this.db = db;
        this.clock = clock;
        this.store = store;
        this.settings = settings;
        this.turns = turns;
        this.jobs = jobs;
        jobs.register(WAITING_CHECK, this::onWaitingCheck);
    }

    /** 다음 미래 블록 시작(없으면 null). */
    public Instant nextBlockStart(long goalId, Instant now) {
        return store.blocksOfGoal(goalId).stream()
                .filter(b -> "planned".equals(b.status()) && b.start().isAfter(now))
                .map(Store.Block::start).min(Comparator.naturalOrder()).orElse(null);
    }

    public boolean waiting(Store.Goal g, Instant now) {
        return g.waitingUntil() != null && g.waitingUntil().isAfter(now);
    }

    public boolean isMissing(Store.Goal g, Instant now) {
        if (!"active".equals(g.status()) || waiting(g, now)) {
            return false;
        }
        Instant next = nextBlockStart(g.id(), now);
        return next == null || !next.isBefore(now.plus(Duration.ofDays(settings.getInt("goal.min_horizon_days"))));
    }

    /** ok | missing | waiting (get_goal_coverage). */
    public String status(Store.Goal g, Instant now) {
        if (waiting(g, now)) {
            return "waiting";
        }
        return isMissing(g, now) ? "missing" : "ok";
    }

    public List<Store.Goal> missing() {
        Instant now = clock.now();
        return store.activeGoals().stream().filter(g -> isMissing(g, now)).toList();
    }

    /** 블록의 목표: goal_id, 없으면 할 일의 goal_id. */
    public Optional<Long> goalOf(Store.Block b) {
        if (b.goalId() != null) {
            return Optional.of(b.goalId());
        }
        return b.taskId() == null ? Optional.empty() : store.task(b.taskId()).map(Store.Task::goalId);
    }

    /**
     * §3.4-2 이어 잡기: 블록이 done·partial·missed·cancelled 가 된 뒤(turn 밖에서) 부른다.
     * 그 목표가 누락이 되면 goal_needs_blocks turn 을 예약한다(이미 대기 중이면 두지 않는다).
     */
    public void onBlockClosed(long blockId) {
        store.block(blockId).flatMap(this::goalOf).flatMap(store::goal).ifPresent(g -> {
            if (isMissing(g, clock.now())) {
                enqueueNeedsBlocks(g.id(), clock.now());
            }
        });
    }

    public boolean enqueueNeedsBlocks(long goalId, Instant at) {
        ObjectNode ref = Json.object();
        ref.put("goal_id", goalId);
        return turns.enqueueIfAbsent(NEEDS_BLOCKS, ref, TurnQueue.PRIORITY_DEFAULT, at);
    }

    /** turn 끝 검사(§3.4-1)에서 coverage_retry 회를 넘겨도 누락일 때. */
    public void giveUpForNow(long goalId, long turnId) {
        log.warn("goal {} still has no block after turn {} (coverage retries exhausted); retry in {} min",
                goalId, turnId, RETRY_LATER.toMinutes());
        enqueueNeedsBlocks(goalId, clock.now().plus(RETRY_LATER));
    }

    /** §3.4-4: waiting_until 에 goal_waiting_check. 바뀌면 다시 예약한다. */
    public void scheduleWaitingCheck(long goalId, Instant waitingUntil) {
        jobs.cancelForTarget(WAITING_CHECK, goalId);
        if (waitingUntil != null) {
            jobs.schedule(WAITING_CHECK, goalId, waitingUntil.isAfter(clock.now()) ? waitingUntil : clock.now(), null);
        }
    }

    private boolean onWaitingCheck(Jobs.Job job) {
        Store.Goal g = store.goal(job.targetId()).orElse(null);
        if (g == null || !"active".equals(g.status()) || g.waitingUntil() == null
                || g.waitingUntil().isAfter(clock.now())) {
            return false;
        }
        ObjectNode ref = Json.object();
        ref.put("goal_id", g.id());
        return turns.enqueueIfAbsent(WAITING_CHECK, ref, TurnQueue.PRIORITY_DEFAULT, clock.now());
    }

    /** §3.5: 지금 active 목표가 쓰지 않는 색을 팔레트 순서대로. 모두 쓰면 순환한다. */
    public String assignColor() {
        List<String> used = db.jdbc().sql("SELECT color FROM goals WHERE status = 'active'").query(String.class).list();
        for (String c : PALETTE) {
            if (!used.contains(c)) {
                return c;
            }
        }
        return PALETTE.get(used.size() % PALETTE.size());
    }

    /** 이름표 기본값: 제목 앞 6자. */
    public static String defaultLabel(String title) {
        String t = title == null ? "" : title.strip();
        return t.codePointCount(0, t.length()) <= 6 ? t : t.substring(0, t.offsetByCodePoints(0, 6));
    }
}
