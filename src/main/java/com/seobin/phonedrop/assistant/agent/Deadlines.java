package com.seobin.phonedrop.assistant.agent;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.seobin.phonedrop.assistant.AssistantClock;
import com.seobin.phonedrop.assistant.AssistantDb;
import com.seobin.phonedrop.assistant.Store;
import com.seobin.phonedrop.assistant.TimeUtil;

/**
 * 다가오는 마감(빌드문서 v3.2 P1.2·P1.3). 활성 할 일·목표의 deadline 과 그 마감 전에 잡힌 블록 수.
 * 맥락의 '# 다가오는 마감' 칸과 GET /v1/calendar 의 deadlines 가 같은 값을 쓴다.
 */
@Component
public class Deadlines {

    public static final int CONTEXT_DAYS = 14;

    public record Deadline(Long taskId, Long goalId, String title, Instant at, int blocksBefore) {
    }

    private final AssistantDb db;
    private final AssistantClock clock;
    private final Store store;
    private final TimeUtil time;
    private final Timeline timeline;

    public Deadlines(AssistantDb db, AssistantClock clock, Store store, TimeUtil time, Timeline timeline) {
        this.db = db;
        this.clock = clock;
        this.store = store;
        this.time = time;
        this.timeline = timeline;
    }

    /** from~to 사이 마감(from 이 null 이면 지난 미완료 마감도 포함). 시각 순. */
    public List<Deadline> between(Instant from, Instant to) {
        List<Deadline> list = new ArrayList<>();
        for (Store.Task t : store.activeTasks()) {
            if (in(t.deadline(), from, to)) {
                list.add(new Deadline(t.id(), null, timeline.taskTitle(t), t.deadline(), blocksBeforeTask(t.id(), t.deadline())));
            }
        }
        for (Store.Goal g : store.activeGoals()) {
            if (in(g.deadline(), from, to)) {
                list.add(new Deadline(null, g.id(), timeline.goalTitle(g), g.deadline(), blocksBeforeGoal(g.id(), g.deadline())));
            }
        }
        list.sort(Comparator.comparing(Deadline::at));
        return list;
    }

    /** 맥락 '# 다가오는 마감'(앞으로 14일, 지난 미완료는 "지남"). */
    public String contextSection() {
        Instant now = clock.now();
        List<Deadline> list = between(null, now.plus(Duration.ofDays(CONTEXT_DAYS)));
        if (list.isEmpty()) {
            return "없음\n";
        }
        StringBuilder sb = new StringBuilder();
        for (Deadline d : list) {
            sb.append("- ").append(d.taskId() != null ? "할일#" + d.taskId() : "목표#" + d.goalId()).append(' ')
                    .append(d.title()).append(" · 마감 ").append(time.dayText(d.at()));
            if (!d.at().isAfter(now)) {
                sb.append(d.goalId() != null && asked(d.goalId()) ? " (지남 — 이미 사용자에게 물었다, 다시 묻지 말 것" : " (지남");
            } else {
                sb.append(" (남은 ").append(left(Duration.between(now, d.at())));
            }
            sb.append(", 마감 전 블록 ").append(d.blocksBefore()).append("개)\n");
        }
        return sb.toString();
    }

    public Map<String, Object> toMap(Deadline d) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (d.taskId() != null) {
            m.put("task_id", d.taskId());
        } else {
            m.put("goal_id", d.goalId());
        }
        m.put("title", d.title());
        m.put("at", time.iso(d.at()));
        m.put("blocks_before", d.blocksBefore());
        m.put("passed", !d.at().isAfter(clock.now()));
        return m;
    }

    private boolean asked(long goalId) {
        return db.jdbc().sql("SELECT deadline_prompted_at IS NOT NULL FROM goals WHERE id = ?").param(goalId)
                .query(Boolean.class).optional().orElse(false);
    }

    private int blocksBeforeTask(long taskId, Instant deadline) {
        return db.jdbc().sql("""
                        SELECT COUNT(*) FROM blocks WHERE task_id = ? AND status IN ('planned', 'nudging', 'started')
                        AND end_at <= ?""")
                .param(taskId).param(AssistantDb.ts(deadline)).query(Integer.class).single();
    }

    private int blocksBeforeGoal(long goalId, Instant deadline) {
        return db.jdbc().sql("""
                        SELECT COUNT(*) FROM blocks b LEFT JOIN tasks t ON t.id = b.task_id
                        WHERE (b.goal_id = ? OR t.goal_id = ?) AND b.status IN ('planned', 'nudging', 'started')
                        AND b.end_at <= ?""")
                .param(goalId).param(goalId).param(AssistantDb.ts(deadline)).query(Integer.class).single();
    }

    private static boolean in(Instant at, Instant from, Instant to) {
        return at != null && (from == null || !at.isBefore(from)) && at.isBefore(to);
    }

    static String left(Duration d) {
        long hours = d.toHours();
        if (hours >= 24) {
            return hours / 24 + "일" + (hours % 24 > 0 ? " " + hours % 24 + "시간" : "");
        }
        return hours > 0 ? hours + "시간" : Math.max(1, d.toMinutes()) + "분";
    }
}
