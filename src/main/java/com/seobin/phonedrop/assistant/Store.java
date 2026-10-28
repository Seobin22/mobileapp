package com.seobin.phonedrop.assistant;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

/** tasks·blocks·interventions 조회와 단순 변경. */
@Component
public class Store {

    public record Task(long id, String title, String firstAction, int estMinutes, String status, int version) {
    }

    public record Block(long id, long taskId, Instant start, Instant end, String status, String screenText,
                        Instant actualStart, Instant actualEnd, int version) {
    }

    public record Intervention(long id, long blockId, String status, int level, int blockVersion,
                               Instant startedAt, Instant endedAt, String endReason, int version) {

        public boolean running() {
            return "running".equals(status);
        }
    }

    private final AssistantDb db;

    public Store(AssistantDb db) {
        this.db = db;
    }

    public Optional<Task> task(long id) {
        return db.jdbc().sql("SELECT * FROM tasks WHERE id = ?").param(id).query(Store::task).optional();
    }

    public Optional<Block> block(long id) {
        return db.jdbc().sql("SELECT * FROM blocks WHERE id = ?").param(id).query(Store::block).optional();
    }

    public Optional<Intervention> intervention(long id) {
        return db.jdbc().sql("SELECT * FROM interventions WHERE id = ?").param(id)
                .query(Store::intervention).optional();
    }

    public List<Intervention> runningInterventions() {
        return db.jdbc().sql("SELECT * FROM interventions WHERE status = 'running' ORDER BY id")
                .query(Store::intervention).list();
    }

    public Optional<Intervention> runningForBlock(long blockId) {
        return db.jdbc().sql("SELECT * FROM interventions WHERE status = 'running' AND block_id = ? ORDER BY id")
                .param(blockId).query(Store::intervention).list().stream().findFirst();
    }

    public List<Block> blocksOfTask(long taskId) {
        return db.jdbc().sql("SELECT * FROM blocks WHERE task_id = ? ORDER BY start_at, id").param(taskId)
                .query(Store::block).list();
    }

    /** [start, end) 와 겹치는 활성 블록(planned·nudging·started). */
    public List<Block> activeBlocksOverlapping(Instant start, Instant end) {
        return db.jdbc().sql("""
                        SELECT * FROM blocks WHERE status IN ('planned', 'nudging', 'started')
                        AND start_at < ? AND end_at > ? ORDER BY start_at, id""")
                .param(AssistantDb.ts(end)).param(AssistantDb.ts(start))
                .query(Store::block).list();
    }

    public List<Block> plannedBlocksStartingBefore(Instant now, Instant until) {
        return db.jdbc().sql("""
                        SELECT * FROM blocks WHERE status = 'planned' AND end_at > ? AND start_at < ?
                        ORDER BY start_at, id""")
                .param(AssistantDb.ts(now)).param(AssistantDb.ts(until))
                .query(Store::block).list();
    }

    public List<Block> blocksStartingBetween(Instant from, Instant to) {
        return db.jdbc().sql("SELECT * FROM blocks WHERE start_at >= ? AND start_at < ? ORDER BY start_at, id")
                .param(AssistantDb.ts(from)).param(AssistantDb.ts(to))
                .query(Store::block).list();
    }

    public void setBlockStatus(long blockId, String status) {
        db.update("UPDATE blocks SET status = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?", status, blockId);
    }

    public void log(Long interventionId, Long blockId, Integer level, Instant at, String reaction) {
        db.insert("""
                        INSERT INTO intervention_logs(intervention_id, block_id, level, at, reaction)
                        VALUES (?, ?, ?, ?, ?)""",
                interventionId, blockId, level, at, reaction);
    }

    static Task task(ResultSet rs, int n) throws SQLException {
        return new Task(rs.getLong("id"), rs.getString("title"), rs.getString("first_action"),
                rs.getInt("est_minutes"), rs.getString("status"), rs.getInt("version"));
    }

    static Block block(ResultSet rs, int n) throws SQLException {
        return new Block(rs.getLong("id"), rs.getLong("task_id"), AssistantDb.instant(rs, "start_at"),
                AssistantDb.instant(rs, "end_at"), rs.getString("status"), rs.getString("screen_text"),
                AssistantDb.instant(rs, "actual_start"), AssistantDb.instant(rs, "actual_end"),
                rs.getInt("version"));
    }

    static Intervention intervention(ResultSet rs, int n) throws SQLException {
        return new Intervention(rs.getLong("id"), rs.getLong("block_id"), rs.getString("status"),
                rs.getInt("level"), rs.getInt("block_version"), AssistantDb.instant(rs, "started_at"),
                AssistantDb.instant(rs, "ended_at"), rs.getString("end_reason"), rs.getInt("version"));
    }
}
