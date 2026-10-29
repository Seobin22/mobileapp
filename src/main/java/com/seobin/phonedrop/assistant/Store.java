package com.seobin.phonedrop.assistant;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

/** tasks·blocks·interventions·goals 조회와 단순 변경. */
@Component
public class Store {

    public record Task(long id, String title, String firstAction, int estMinutes, String status, int version,
                       Long goalId, Instant deadline, int remainingMinutes, int priority, String notes,
                       boolean sensitive) {
    }

    /**
     * 시간표의 칸(빌드문서 v3.1 §1). kind=fixed 는 사용자가 시각을 정한 고정 블록(할 일 없이도 있다),
     * planned 는 비서가 할 일을 위해 잡은 계획 블록. 둘 다 개입(알람)이 간다.
     * prepSetBy: lead·buffer 를 누가 정했나(user | assistant | null=아직, lead 0 으로 계산, v3.1 §3.2.1).
     */
    public record Block(long id, Long taskId, Instant start, Instant end, String status, String screenText,
                        Instant actualStart, Instant actualEnd, int version, Long workProductId,
                        String kind, String title, Long goalId, int leadMinutes, int bufferAfterMin,
                        String location, String source, String externalId, boolean sensitive,
                        String prepSetBy) {

        public boolean fixed() {
            return "fixed".equals(kind);
        }

        /** 개입 시작 시각 = start − lead_minutes (계획 블록은 lead 0). */
        public Instant interventionAt() {
            return start.minusSeconds(leadMinutes * 60L);
        }

        /** 금지 구간 [start − lead, end + buffer_after] (§3.1). */
        public Instant blockedStart() {
            return interventionAt();
        }

        public Instant blockedEnd() {
            return end.plusSeconds(bufferAfterMin * 60L);
        }
    }

    public record Intervention(long id, long blockId, String status, int level, int blockVersion,
                               Instant startedAt, Instant endedAt, String endReason, int version,
                               Instant talkUntil) {

        /** 화면을 띄우는 중(재표시 대상). */
        public boolean running() {
            return "running".equals(status);
        }

        /** 끝나지 않은 개입: running 또는 talking(풀스크린 대화 중, 빌드문서 v3.1 §3.6). */
        public boolean active() {
            return "running".equals(status) || "talking".equals(status);
        }
    }

    public record Goal(long id, String title, Instant deadline, String reason, String status, String strategy,
                       boolean sensitive, String label, String color, String strategySummary, String progress,
                       Instant waitingUntil, String waitingReason, int priority) {
    }

    private final AssistantDb db;

    public Store(AssistantDb db) {
        this.db = db;
    }

    /** 금지 구간(lead·buffer 포함)이 [from, to) 와 겹치는 활성 고정 블록. lead·buffer 는 최대 하루로 보고 넉넉히 읽은 뒤 거른다. */
    public List<Block> activeFixedBlocksOverlapping(Instant from, Instant to) {
        return db.jdbc().sql("""
                        SELECT * FROM blocks WHERE kind = 'fixed' AND status IN ('planned', 'nudging', 'started')
                        AND start_at < ? AND end_at > ? ORDER BY start_at, id""")
                .param(AssistantDb.ts(to.plusSeconds(86400))).param(AssistantDb.ts(from.minusSeconds(86400)))
                .query(Store::block).list().stream()
                .filter(b -> b.blockedStart().isBefore(to) && from.isBefore(b.blockedEnd()))
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

    /** 끝나지 않은 개입(running·talking). 개입은 동시에 하나만 둔다. */
    public List<Intervention> runningInterventions() {
        return db.jdbc().sql("SELECT * FROM interventions WHERE status IN ('running', 'talking') ORDER BY id")
                .query(Store::intervention).list();
    }

    public Optional<Intervention> runningForBlock(long blockId) {
        return db.jdbc().sql("""
                        SELECT * FROM interventions WHERE status IN ('running', 'talking') AND block_id = ?
                        ORDER BY id""")
                .param(blockId).query(Store::intervention).list().stream().findFirst();
    }

    public List<Block> blocksOfTask(long taskId) {
        return db.jdbc().sql("SELECT * FROM blocks WHERE task_id = ? ORDER BY start_at, id").param(taskId)
                .query(Store::block).list();
    }

    /** 이 목표에 직접 연결된 블록(goal_id). */
    public List<Block> blocksOfGoal(long goalId) {
        return db.jdbc().sql("SELECT * FROM blocks WHERE goal_id = ? ORDER BY start_at, id").param(goalId)
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

    /** 개입 시각(start − lead)이 until 전인, 아직 끝나지 않은 planned 상태 블록(plan.sync upcoming). */
    public List<Block> plannedBlocksStartingBefore(Instant now, Instant until) {
        return db.jdbc().sql("""
                        SELECT * FROM blocks WHERE status = 'planned' AND end_at > ? AND start_at < ?
                        ORDER BY start_at, id""")
                .param(AssistantDb.ts(now)).param(AssistantDb.ts(until.plusSeconds(86400)))
                .query(Store::block).list().stream()
                .filter(b -> b.interventionAt().isBefore(until))
                .toList();
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

    /** 블록 제목: 고정 블록은 자기 제목, 계획 블록은 할 일 제목(비어 있으면). */
    public String blockTitle(Block b) {
        if (!b.title().isBlank()) {
            return b.title();
        }
        return b.taskId() == null ? "" : task(b.taskId()).map(Task::title).orElse("");
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

    public static Goal goal(ResultSet rs, int n) throws SQLException {
        return new Goal(rs.getLong("id"), rs.getString("title"), AssistantDb.instant(rs, "deadline"),
                rs.getString("reason"), rs.getString("status"), rs.getString("strategy"), rs.getBoolean("sensitive"),
                rs.getString("label"), rs.getString("color"), rs.getString("strategy_summary"),
                rs.getString("progress"), AssistantDb.instant(rs, "waiting_until"), rs.getString("waiting_reason"),
                rs.getInt("priority"));
    }

    public static Block block(ResultSet rs, int n) throws SQLException {
        return new Block(rs.getLong("id"), AssistantDb.nullableLong(rs, "task_id"), AssistantDb.instant(rs, "start_at"),
                AssistantDb.instant(rs, "end_at"), rs.getString("status"), rs.getString("screen_text"),
                AssistantDb.instant(rs, "actual_start"), AssistantDb.instant(rs, "actual_end"),
                rs.getInt("version"), AssistantDb.nullableLong(rs, "work_product_id"),
                rs.getString("kind"), rs.getString("title"), AssistantDb.nullableLong(rs, "goal_id"),
                rs.getInt("lead_minutes"), rs.getInt("buffer_after_min"), rs.getString("location"),
                rs.getString("source"), rs.getString("external_id"), rs.getBoolean("sensitive"),
                rs.getString("prep_set_by"));
    }

    static Intervention intervention(ResultSet rs, int n) throws SQLException {
        return new Intervention(rs.getLong("id"), rs.getLong("block_id"), rs.getString("status"),
                rs.getInt("level"), rs.getInt("block_version"), AssistantDb.instant(rs, "started_at"),
                AssistantDb.instant(rs, "ended_at"), rs.getString("end_reason"), rs.getInt("version"),
                AssistantDb.instant(rs, "talk_until"));
    }
}
