package com.seobin.phonedrop.assistant.agent;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.seobin.phonedrop.assistant.AssistantClock;
import com.seobin.phonedrop.assistant.AssistantDb;
import com.seobin.phonedrop.assistant.Store;
import com.seobin.phonedrop.assistant.TimeUtil;

/**
 * get_facts (빌드문서 v3 §4.1). 판단 없이 숫자만 계산한다. 미루기를 받아줄지는 LLM 이 정한다(코드에 한도 없음).
 */
@Component
public class Facts {

    private final AssistantDb db;
    private final AssistantClock clock;
    private final Store store;
    private final TimeUtil time;
    private final Timeline timeline;

    public Facts(AssistantDb db, AssistantClock clock, Store store, TimeUtil time, Timeline timeline) {
        this.db = db;
        this.clock = clock;
        this.store = store;
        this.time = time;
        this.timeline = timeline;
    }

    /** 할 일이 없으면 null. */
    public Map<String, Object> of(long taskId) {
        Store.Task t = store.task(taskId).orElse(null);
        if (t == null) {
            return null;
        }
        Instant now = clock.now();
        Store.Goal goal = t.goalId() == null ? null : store.goal(t.goalId()).orElse(null);
        Instant dayStart = time.activeWindow(time.logicalDay(now)).start();

        record Snoozed(int count, long minutes) {
        }
        Snoozed snoozed = db.jdbc().sql("""
                        SELECT COUNT(*), COALESCE(SUM(i.snoozed_minutes), 0) FROM interventions i
                        JOIN blocks b ON b.id = i.block_id
                        WHERE b.task_id = ? AND i.status = 'snoozed' AND i.ended_at >= ?""")
                .param(taskId).param(AssistantDb.ts(dayStart))
                .query((rs, n) -> new Snoozed(rs.getInt(1), rs.getLong(2))).single();

        // 할 일 마감이 없으면 목표 마감
        Instant deadline = t.deadline() != null ? t.deadline() : goal == null ? null : goal.deadline();

        Map<String, Object> f = new LinkedHashMap<>();
        f.put("task_id", t.id());
        f.put("title", timeline.taskTitle(t));
        f.put("snoozed_today_count", snoozed.count());
        f.put("snoozed_today_minutes", snoozed.minutes());
        f.put("deadline", time.iso(deadline));
        f.put("hours_until_deadline", deadline == null ? null
                : Math.round(Duration.between(now, deadline).toMinutes() / 6.0) / 10.0);
        f.put("remaining_minutes", t.remainingMinutes());
        if (deadline == null) {
            f.put("free_minutes_until_deadline", null);
            f.put("slack_minutes", null);
            f.put("slack_if_delayed_30m", null);
        } else {
            long free = timeline.freeMinutes(now, deadline, t.id());
            long freeDelayed = timeline.freeMinutes(now.plus(Duration.ofMinutes(30)), deadline, t.id());
            f.put("free_minutes_until_deadline", free);
            f.put("slack_minutes", free - t.remainingMinutes());
            f.put("slack_if_delayed_30m", freeDelayed - t.remainingMinutes());
        }
        f.put("start_rate_this_hour_7d", startRateThisHour(now));
        f.put("reason", goal != null && !goal.reason().isBlank() ? goal.reason() : t.notes());
        return f;
    }

    /** 최근 7일 같은 시(hour)에 시작 시각이 있던 블록 중 started 가 된(actual_start 있음) 비율. 블록이 없으면 null. */
    private Double startRateThisHour(Instant now) {
        int hour = time.local(now).getHour();
        List<Boolean> started = db.jdbc().sql("""
                        SELECT start_at, actual_start FROM blocks
                        WHERE status <> 'cancelled' AND start_at >= ? AND start_at < ?""")
                .param(AssistantDb.ts(now.minus(Duration.ofDays(7)))).param(AssistantDb.ts(now))
                .query((rs, n) -> time.local(AssistantDb.instant(rs, "start_at")).getHour() == hour
                        ? rs.getObject("actual_start") != null : null)
                .list().stream().filter(java.util.Objects::nonNull).toList();
        if (started.isEmpty()) {
            return null;
        }
        long yes = started.stream().filter(Boolean::booleanValue).count();
        return Math.round(yes * 100.0 / started.size()) / 100.0;
    }
}
