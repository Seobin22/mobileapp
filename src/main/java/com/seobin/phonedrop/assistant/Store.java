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

    public record Task(long id, String title, String firstAction, int estMinutes, String status, int version,
                       Long goalId, Instant deadline, int remainingMinutes, int priority, String notes,
                       boolean sensitive) {
    }

    public record Block(long id, long taskId, Instant start, Instant end, String status, String screenText,
                        Instant actualStart, Instant actualEnd, int version, Long workProductId) {
    }

    public record Intervention(long id, long blockId, String status, int level, int blockVersion,
                               Instant startedAt, Instant endedAt, String endReason, int version,
                               Instant negotiateUntil) {

        /** 화면을 띄우는 중(재표시 대상). */
        public boolean running() {
            return "running".equals(status);
        }

        /** 끝나지 않은 개입: running 또는 negotiating(미루기 협상 중, 빌드문서 v3 §3). */
        public boolean active() {
            return "running".equals(status) || "negotiating".equals(status);
        }
    }

    public record Event(long id, String title, Instant start, Instant end, int bufferBeforeMin, int bufferAfterMin,
                        String source, String externalId, String status, boolean sensitive) {

        /** 버퍼를 포함한 구간 시작. */
        public Instant blockedStart() {
            return start.minusSeconds(bufferBeforeMin * 60L);
        }

        public Instant blockedEnd() {
            return end.plusSeconds(bufferAfterMin * 60L);
        }
    }

    public record Goal(long id, String title, Instant deadline, String reason, String status, String strategy,
                       boolean sensitive) {
    }

    private final AssistantDb db;

    public Store(AssistantDb db) {
        this.db = db;
    }

    public Optional<Event> event(long id) {
        return db.jdbc().sql("SELECT * FROM events WHERE id = ?").param(id).query(Store::event).optional();
    }

    /** 버퍼 포함 구간이 [from, to) 와 겹치는 활성 일정. 버퍼는 최대 하루로 보고 넉넉히 읽은 뒤 거른다. */
    public List<Event> activeEventsOverlapping(Instant from, Instant to) {
        return db.jdbc().sql("""
                        SELECT * FROM events WHERE status = 'active' AND start_at < ? AND end_at > ?
                        ORDER BY start_at, id""")
                .param(AssistantDb.ts(to.plusSeconds(86400))).param(AssistantDb.ts(from.minusSeconds(86400)))
                .query(Store::event).list().stream()
                .filter(e -> e.blockedStart().isBefore(to) && from.isBefore(e.blockedEnd()))
                .toList();
    }

    public Optional<Goal> goal(long id) {
        return db.jdbc().sql("SELECT * FROM goals WHERE id = ?").param(id).query(Store::goal).optional();
    }

    public List<Goal> activeGoals() {
        return db.jdbc().sql("SELECT * FROM goals WHERE status = 'active' ORDER BY id").query(Store::goal).list();
    }

    public List<Task> activeTasks() {
        return db.jdbc().sql("SELECT * FROM tasks WHERE status = 'active' ORDER BY id").query(Store::task).list();
    }

    /** 활성 블록 전부(planned·nudging·started). */
    public List<Block> activeBlocks() {
        return db.jdbc().sql("""
                        SELECT * FROM blocks WHERE status IN ('planned', 'nudging', 'started') ORDER BY start_at, id""")
                .query(Store::block).list();
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

    /** 끝나지 않은 개입(running·negotiating). 개입은 동시에 하나만 둔다. */
    public List<Intervention> runningInterventions() {
        return db.jdbc().sql("SELECT * FROM interventions WHERE status IN ('running', 'negotiating') ORDER BY id")
                .query(Store::intervention).list();
    }

    public Optional<Intervention> runningForBlock(long blockId) {
        return db.jdbc().sql("""
                        SELECT * FROM interventions WHERE status IN ('running', 'negotiating') AND block_id = ?
                        ORDER BY id""")
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

    /** 진행 중(started) 블록. */
    public List<Block> startedBlocks() {
        return db.jdbc().sql("SELECT * FROM blocks WHERE status = 'started' ORDER BY start_at, id")
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
                rs.getInt("est_minutes"), rs.getString("status"), rs.getInt("version"),
                AssistantDb.nullableLong(rs, "goal_id"), AssistantDb.instant(rs, "deadline"),
                rs.getInt("remaining_minutes"), rs.getInt("priority"), rs.getString("notes"),
                rs.getBoolean("sensitive"));
    }

    static Event event(ResultSet rs, int n) throws SQLException {
        return new Event(rs.getLong("id"), rs.getString("title"), AssistantDb.instant(rs, "start_at"),
                AssistantDb.instant(rs, "end_at"), rs.getInt("buffer_before_min"), rs.getInt("buffer_after_min"),
                rs.getString("source"), rs.getString("external_id"), rs.getString("status"),
                rs.getBoolean("sensitive"));
    }

    static Goal goal(ResultSet rs, int n) throws SQLException {
        return new Goal(rs.getLong("id"), rs.getString("title"), AssistantDb.instant(rs, "deadline"),
                rs.getString("reason"), rs.getString("status"), rs.getString("strategy"), rs.getBoolean("sensitive"));
    }

    static Block block(ResultSet rs, int n) throws SQLException {
        return new Block(rs.getLong("id"), rs.getLong("task_id"), AssistantDb.instant(rs, "start_at"),
                AssistantDb.instant(rs, "end_at"), rs.getString("status"), rs.getString("screen_text"),
                AssistantDb.instant(rs, "actual_start"), AssistantDb.instant(rs, "actual_end"),
                rs.getInt("version"), AssistantDb.nullableLong(rs, "work_product_id"));
    }

    static Intervention intervention(ResultSet rs, int n) throws SQLException {
        return new Intervention(rs.getLong("id"), rs.getLong("block_id"), rs.getString("status"),
                rs.getInt("level"), rs.getInt("block_version"), AssistantDb.instant(rs, "started_at"),
                AssistantDb.instant(rs, "ended_at"), rs.getString("end_reason"), rs.getInt("version"),
                AssistantDb.instant(rs, "negotiate_until"));
    }
}
