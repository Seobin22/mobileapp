package com.seobin.phonedrop.assistant;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;

/**
 * 블록 기록(빌드문서 v3.2 §4). 코드가 자동으로 남기는 것(생성·이동·개입·시작·종료·놓침·연결)과
 * 비서가 add_block_log 로 남기는 것(대화 요약·진행·미룬 이유·고지·메모)을 한 표에 쌓는다.
 */
@Component
public class BlockLogs {

    /** 비서가 남길 수 있는 type(§4.2). */
    public static final Set<String> ASSISTANT_TYPES = Set.of("talk_summary", "progress", "snooze_reason", "notice", "note");
    /** 비서가 남기는 content 상한(§4.2). 넘으면 도구가 거절한다(DECISIONS). */
    public static final int ASSISTANT_MAX_CHARS = 200;
    private static final int MAX_CHARS = 500;

    public record Log(long id, long blockId, Instant at, String type, String actor, String content, String data) {
    }

    private final AssistantDb db;
    private final AssistantClock clock;

    public BlockLogs(AssistantDb db, AssistantClock clock) {
        this.db = db;
        this.clock = clock;
    }

    public long add(long blockId, String type, String actor, String content) {
        return add(blockId, type, actor, content, null);
    }

    public long add(long blockId, String type, String actor, String content, JsonNode data) {
        String text = content == null ? "" : content.trim();
        if (text.length() > MAX_CHARS) {
            text = text.substring(0, MAX_CHARS);
        }
        return db.insert("INSERT INTO block_logs(block_id, at, type, actor, content, data) VALUES (?, ?, ?, ?, ?, ?)",
                blockId, clock.now(), type, actor, text, data == null ? null : Json.write(data));
    }

    /** 시간순(오래된 것부터) 최근 limit 개. */
    public List<Log> ofBlock(long blockId, int limit) {
        return db.jdbc().sql("""
                        SELECT * FROM (SELECT * FROM block_logs WHERE block_id = ? ORDER BY at DESC, id DESC LIMIT ?)
                        ORDER BY at, id""")
                .param(blockId).param(limit).query(BlockLogs::log).list();
    }

    /** 이 목표 블록들의 기록(시간순, 최근 limit 개). */
    public List<Log> ofGoal(long goalId, int limit) {
        return db.jdbc().sql("""
                        SELECT * FROM (SELECT l.* FROM block_logs l JOIN blocks b ON b.id = l.block_id
                        LEFT JOIN tasks t ON t.id = b.task_id
                        WHERE b.goal_id = ? OR t.goal_id = ? ORDER BY l.at DESC, l.id DESC LIMIT ?) ORDER BY at, id""")
                .param(goalId).param(goalId).param(limit).query(BlockLogs::log).list();
    }

    /** 고정 블록과 그 일정에 연결된 준비 블록의 기록. */
    public List<Log> ofFixed(long fixedBlockId, int limit) {
        return db.jdbc().sql("""
                        SELECT * FROM (SELECT l.* FROM block_logs l JOIN blocks b ON b.id = l.block_id
                        WHERE b.id = ? OR b.linked_fixed_block_id = ? ORDER BY l.at DESC, l.id DESC LIMIT ?)
                        ORDER BY at, id""")
                .param(fixedBlockId).param(fixedBlockId).param(limit).query(BlockLogs::log).list();
    }

    /** 이 블록의 고지(type=notice) 기록. */
    public List<Log> notices(long blockId) {
        return db.jdbc().sql("SELECT * FROM block_logs WHERE block_id = ? AND type = 'notice' ORDER BY at, id")
                .param(blockId).query(BlockLogs::log).list();
    }

    public static Map<String, Object> toMap(Log l, TimeUtil time) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", l.id());
        m.put("block_id", l.blockId());
        m.put("at", time.iso(l.at()));
        m.put("type", l.type());
        m.put("actor", l.actor());
        m.put("content", l.content());
        m.put("data", l.data() == null ? null : Json.parse(l.data()));
        return m;
    }

    private static Log log(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Log(rs.getLong("id"), rs.getLong("block_id"), AssistantDb.instant(rs, "at"), rs.getString("type"),
                rs.getString("actor"), rs.getString("content"), rs.getString("data"));
    }
}
