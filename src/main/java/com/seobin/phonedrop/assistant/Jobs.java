package com.seobin.phonedrop.assistant;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;

/** 예약 작업 등록·취소·실행. 처리기는 각 서비스가 생성자에서 register 한다. */
@Component
public class Jobs {

    private static final Logger log = LoggerFactory.getLogger(Jobs.class);
    private static final int MAX_ATTEMPTS = 5;
    private static final int RETRY_SECONDS = 30;

    public record Job(long id, String type, Long targetId, Instant fireAt, Integer expectedVersion,
                      JsonNode payload, String status, int attempts) {
    }

    @FunctionalInterface
    public interface Handler {
        /** false 를 돌려주면 skipped 로 기록된다. */
        boolean handle(Job job);
    }

    private final AssistantDb db;
    private final AssistantClock clock;
    private final Tx tx;
    private final SettingsService settings;
    private final Map<String, Handler> handlers = new HashMap<>();

    public Jobs(AssistantDb db, AssistantClock clock, Tx tx, SettingsService settings) {
        this.db = db;
        this.clock = clock;
        this.tx = tx;
        this.settings = settings;
    }

    public void register(String type, Handler handler) {
        handlers.put(type, handler);
    }

    public long schedule(String type, long targetId, Instant fireAt, Integer expectedVersion) {
        return schedule(type, targetId, fireAt, expectedVersion, Json.object());
    }

    public long schedule(String type, long targetId, Instant fireAt, Integer expectedVersion, JsonNode payload) {
        return db.insert("""
                        INSERT INTO jobs(type, target_id, fire_at, expected_version, payload)
                        VALUES (?, ?, ?, ?, ?)""",
                type, targetId, fireAt, expectedVersion, Json.write(payload));
    }

    public void cancelForTarget(String type, long targetId) {
        db.jdbc().sql("""
                        UPDATE jobs SET status = 'cancelled', updated_at = CURRENT_TIMESTAMP
                        WHERE status = 'scheduled' AND type = ? AND target_id = ?""")
                .param(type).param(targetId).update();
    }

    public List<Job> scheduled(int limit) {
        return db.jdbc().sql("SELECT * FROM jobs WHERE status = 'scheduled' ORDER BY fire_at, id LIMIT ?")
                .param(limit).query(Jobs::map).list();
    }

    /** 시각이 된 작업을 실행한다. 처리기가 바로 실행할 작업을 만들면 같은 호출 안에서 이어서 처리한다. */
    public int runDue() {
        int processed = 0;
        for (int round = 0; round < 20; round++) {
            Instant now = clock.now();
            List<Long> due = db.jdbc().sql("""
                            SELECT id FROM jobs WHERE status = 'scheduled' AND fire_at <= ?
                            ORDER BY fire_at, id LIMIT 50""")
                    .param(AssistantDb.ts(now)).query(Long.class).list();
            if (due.isEmpty()) {
                break;
            }
            for (long id : due) {
                runOne(id);
                processed++;
            }
        }
        return processed;
    }

    private void runOne(long id) {
        try {
            tx.exec(() -> {
                Job job = db.jdbc().sql("SELECT * FROM jobs WHERE id = ?").param(id).query(Jobs::map).optional()
                        .orElse(null);
                Instant now = clock.now();
                if (job == null || !"scheduled".equals(job.status()) || job.fireAt().isAfter(now)) {
                    return;
                }
                long lateSeconds = now.getEpochSecond() - job.fireAt().getEpochSecond();
                if (lateSeconds > settings.getInt("jobs.late_threshold_seconds")) {
                    log.warn("job {} {} running {}s late", job.id(), job.type(), lateSeconds);
                }
                Handler handler = handlers.get(job.type());
                if (handler == null) {
                    throw new IllegalStateException("no handler for job type " + job.type());
                }
                boolean ok = handler.handle(job);
                db.jdbc().sql("UPDATE jobs SET status = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?")
                        .param(ok ? "done" : "skipped").param(id).update();
            });
        } catch (RuntimeException e) {
            log.error("job {} failed", id, e);
            tx.exec(() -> {
                int attempts = db.jdbc().sql("SELECT attempts FROM jobs WHERE id = ?").param(id)
                        .query(Integer.class).single() + 1;
                String error = String.valueOf(e.getMessage());
                if (error.length() > 1900) {
                    error = error.substring(0, 1900);
                }
                if (attempts < MAX_ATTEMPTS) {
                    db.jdbc().sql("""
                                    UPDATE jobs SET attempts = ?, last_error = ?, fire_at = ?,
                                    updated_at = CURRENT_TIMESTAMP WHERE id = ?""")
                            .param(attempts).param(error)
                            .param(AssistantDb.ts(clock.now().plusSeconds(RETRY_SECONDS))).param(id).update();
                } else {
                    db.jdbc().sql("""
                                    UPDATE jobs SET attempts = ?, last_error = ?, status = 'failed',
                                    updated_at = CURRENT_TIMESTAMP WHERE id = ?""")
                            .param(attempts).param(error).param(id).update();
                }
            });
        }
    }

    static Job map(ResultSet rs, int n) throws SQLException {
        return new Job(rs.getLong("id"), rs.getString("type"), AssistantDb.nullableLong(rs, "target_id"),
                AssistantDb.instant(rs, "fire_at"), AssistantDb.nullableInt(rs, "expected_version"),
                Json.parse(rs.getString("payload")), rs.getString("status"), rs.getInt("attempts"));
    }
}
