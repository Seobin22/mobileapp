package com.seobin.phonedrop.assistant.agent;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Component;

import com.seobin.phonedrop.assistant.AssistantClock;
import com.seobin.phonedrop.assistant.AssistantDb;
import com.seobin.phonedrop.assistant.Store;
import com.seobin.phonedrop.assistant.TimeUtil;

/**
 * 맥락의 '# 지금 상태'(빌드문서 v3.2 P2.2). 블록·개입 데이터로 코드가 계산한다(LLM 호출 없음).
 * 제시간 시작 = 실제 시작이 예정 시각 + 10분 안.
 */
@Component
public class UserState {

    static final Duration ON_TIME = Duration.ofMinutes(10);

    private final AssistantDb db;
    private final AssistantClock clock;
    private final Store store;
    private final TimeUtil time;
    private final Timeline timeline;

    public UserState(AssistantDb db, AssistantClock clock, Store store, TimeUtil time, Timeline timeline) {
        this.db = db;
        this.clock = clock;
        this.store = store;
        this.time = time;
        this.timeline = timeline;
    }

    public String contextSection() {
        Instant now = clock.now();
        Instant dayStart = time.activeWindow(time.logicalDay(now)).start();
        // 오늘 시작 예정이던 블록(시작 시각이 지났고 취소되지 않음)
        List<Store.Block> due = db.jdbc().sql("""
                        SELECT * FROM blocks WHERE start_at >= ? AND start_at <= ? AND status <> 'cancelled'
                        ORDER BY start_at, id""")
                .param(AssistantDb.ts(dayStart)).param(AssistantDb.ts(now)).query(Store::block).list();
        long onTime = due.stream().filter(UserState::onTime).count();
        long missed = due.stream().filter(b -> "missed".equals(b.status())).count();
        int snoozes = db.jdbc().sql("""
                        SELECT COUNT(*) FROM interventions WHERE status = 'snoozed' AND ended_at >= ?""")
                .param(AssistantDb.ts(dayStart)).query(Integer.class).single();

        StringBuilder sb = new StringBuilder();
        sb.append("오늘 ").append(due.size()).append("개 중 ").append(onTime).append("개 제시간 시작, 미루기 ")
                .append(snoozes).append(", 놓침 ").append(missed).append(" / 연속 제시간 ").append(streak(now))
                .append("회\n");
        for (Store.Block b : store.startedBlocks()) {
            Instant from = b.actualStart() != null ? b.actualStart() : b.start();
            Store.Goal g = timeline.goalOf(b);
            sb.append("진행 중: ").append(g == null || g.label().isBlank() ? "" : "[" + g.label() + "] ")
                    .append(timeline.blockTitle(b)).append(' ')
                    .append(Math.max(0, Duration.between(from, now).toMinutes())).append("분째(예정 ")
                    .append(Duration.between(b.start(), b.end()).toMinutes()).append("분)\n");
        }
        Instant since = now.minus(Duration.ofDays(3));
        int done = count("SELECT COUNT(*) FROM blocks WHERE status IN ('done', 'partial') AND start_at >= ? AND start_at <= ?",
                since, now);
        int missed3 = count("SELECT COUNT(*) FROM blocks WHERE status = 'missed' AND start_at >= ? AND start_at <= ?",
                since, now);
        sb.append("최근 3일: 완료 ").append(done).append(", 놓침 ").append(missed3).append('\n');
        Instant lastUser = db.jdbc().sql("SELECT created_at FROM messages WHERE role = 'user' ORDER BY id DESC LIMIT 1")
                .query((rs, n) -> AssistantDb.instant(rs, "created_at")).list().stream().findFirst().orElse(null);
        sb.append("마지막 사용자 메시지: ").append(lastUser == null ? "없음"
                : Deadlines.left(Duration.between(lastUser, now)) + " 전").append('\n');
        return sb.toString();
    }

    /** 최근부터 거꾸로 센 연속 제시간 시작 횟수(아직 개입 중인 블록은 건너뛴다). */
    int streak(Instant now) {
        List<Store.Block> past = db.jdbc().sql("""
                        SELECT * FROM blocks WHERE start_at <= ? AND status NOT IN ('cancelled', 'planned', 'nudging')
                        ORDER BY start_at DESC, id DESC LIMIT 50""")
                .param(AssistantDb.ts(now)).query(Store::block).list();
        int n = 0;
        for (Store.Block b : past) {
            if (!onTime(b)) {
                break;
            }
            n++;
        }
        return n;
    }

    private static boolean onTime(Store.Block b) {
        return b.actualStart() != null && !b.actualStart().isAfter(b.start().plus(ON_TIME));
    }

    private int count(String sql, Instant from, Instant to) {
        return db.jdbc().sql(sql).param(AssistantDb.ts(from)).param(AssistantDb.ts(to)).query(Integer.class).single();
    }
}
