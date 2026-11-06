package com.seobin.phonedrop.assistant.agent;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.seobin.phonedrop.assistant.AssistantClock;
import com.seobin.phonedrop.assistant.AssistantDb;
import com.seobin.phonedrop.assistant.BlockLogs;
import com.seobin.phonedrop.assistant.Json;
import com.seobin.phonedrop.assistant.Store;
import com.seobin.phonedrop.assistant.TimeUtil;
import com.seobin.phonedrop.assistant.insight.BlockFacts;

import tools.jackson.databind.node.ObjectNode;

/**
 * 다시 계획할 때 블록이 사라지지 않게 한다(DECISIONS "다시 계획").
 * <ul>
 * <li>다시 계획하며 취소한 블록에는 replanned 표시를 남긴다.</li>
 * <li>그 결과 아직 안 끝난 할 일에 앞으로 블록이 하나도 없으면 "계획에서 빠짐"(dropped)으로 표시하고 알린다.</li>
 * <li>같은 할 일의 새 블록이 생기면 취소된 블록의 처음 계획 시각·옮긴 횟수를 이어 받는다(moved 기록, from_block).</li>
 * </ul>
 * 블록 상태는 새로 만들지 않는다(취소는 cancelled 그대로, 할 일이 남은 시간을 들고 있다).
 */
@Component
public class Replans {

    /** 취소된 블록을 이어 받을 수 있는 기간. */
    static final Duration INHERIT_WINDOW = Duration.ofDays(7);
    /** "계획에서 빠진 할 일"을 맥락에 보여 주는 기간. */
    static final Duration SHOW_WINDOW = Duration.ofDays(2);

    public record Dropped(long taskId, String title, long blockId, Instant start) {
        public ObjectNode toJson(TimeUtil time) {
            ObjectNode o = Json.object();
            o.put("task_id", taskId);
            o.put("title", title);
            o.put("block_id", blockId);
            o.put("was_start", time.iso(start));
            return o;
        }
    }

    private final AssistantDb db;
    private final AssistantClock clock;
    private final Store store;
    private final BlockLogs logs;
    private final BlockFacts facts;
    private final TimeUtil time;

    public Replans(AssistantDb db, AssistantClock clock, Store store, BlockLogs logs, BlockFacts facts, TimeUtil time) {
        this.db = db;
        this.clock = clock;
        this.store = store;
        this.logs = logs;
        this.facts = facts;
        this.time = time;
    }

    /** 다시 계획하며 취소했다는 표시(cancelBlock 의 기록 data). */
    public static ObjectNode replannedData() {
        ObjectNode d = Json.object();
        d.put("replanned", true);
        return d;
    }

    /**
     * 취소한 블록 가운데, 할 일이 아직 남았는데 앞으로 블록이 하나도 없게 된 것. 블록에 dropped 기록을 남긴다.
     * 할 일 하나에 하나만 돌려준다.
     */
    public List<Dropped> detectDropped(Collection<Long> cancelledBlockIds) {
        Instant now = clock.now();
        List<Dropped> out = new ArrayList<>();
        Set<Long> seenTasks = new LinkedHashSet<>();
        for (long id : cancelledBlockIds) {
            Store.Block b = store.block(id).orElse(null);
            if (b == null || b.taskId() == null || !"cancelled".equals(b.status()) || seenTasks.contains(b.taskId())) {
                continue;
            }
            Store.Task t = store.task(b.taskId()).orElse(null);
            if (t == null || !"active".equals(t.status()) || t.remainingMinutes() <= 0 || hasFutureBlock(t.id(), now)) {
                continue;
            }
            seenTasks.add(t.id());
            ObjectNode d = Json.object();
            d.put("dropped", true);
            logs.add(id, "note", "system", "계획에서 빠짐(다시 배치 필요)", d);
            out.add(new Dropped(t.id(), t.title(), id, b.start()));
        }
        return out;
    }

    public boolean hasFutureBlock(long taskId, Instant now) {
        return db.jdbc().sql("""
                        SELECT COUNT(*) FROM blocks WHERE task_id = ? AND status IN ('planned', 'nudging', 'started')
                        AND end_at > ?""")
                .param(taskId).param(AssistantDb.ts(now)).query(Integer.class).single() > 0;
    }

    /** 이 할 일의, 다시 계획하며 취소됐고 아직 아무 블록도 이어 받지 않은 가장 최근 블록. */
    Long predecessor(long taskId, long excludeBlockId) {
        Instant since = clock.now().minus(INHERIT_WINDOW);
        return db.jdbc().sql("""
                        SELECT b.id FROM blocks b WHERE b.task_id = ? AND b.id <> ? AND b.status = 'cancelled' AND b.updated_at > ?
                        AND EXISTS (SELECT 1 FROM block_logs l WHERE l.block_id = b.id AND l.type = 'note'
                                    AND (l.data LIKE '%"replanned":true%' OR l.data LIKE '%"dropped":true%'))
                        ORDER BY b.updated_at DESC, b.id DESC""")
                .param(taskId).param(excludeBlockId).param(AssistantDb.ts(since)).query(Long.class).list()
                .stream().filter(id -> !hasSuccessor(id)).findFirst().orElse(null);
    }

    private boolean hasSuccessor(long blockId) {
        return db.jdbc().sql("""
                        SELECT COUNT(*) FROM block_logs WHERE type = 'moved'
                        AND (data LIKE ? OR data LIKE ?)""")
                .param("%\"from_block\":" + blockId + ",%").param("%\"from_block\":" + blockId + "}%")
                .query(Integer.class).single() > 0;
    }

    /**
     * 새로 만든 계획 블록이 같은 할 일의 취소된 블록을 이어 받는다. 이어 받았으면 원래 블록 id.
     * 기록: 새 블록에 moved(from_block, from_start = 처음 계획 시각, carried_moves = 그 블록이 옮겨진 횟수).
     */
    public Long inherit(long newBlockId, String actor) {
        Store.Block nb = store.block(newBlockId).orElse(null);
        if (nb == null || nb.taskId() == null) {
            return null;
        }
        Long pred = predecessor(nb.taskId(), newBlockId);
        if (pred == null) {
            return null;
        }
        Store.Block pb = store.block(pred).orElseThrow();
        facts.refresh(pred);
        BlockFacts.Fact pf = facts.get(pred).orElse(null);
        Instant first = pf != null && pf.plannedStartFirst() != null ? pf.plannedStartFirst() : pb.start();
        int carried = pf == null ? 0 : pf.moveCount();
        ObjectNode d = Json.object();
        d.put("from_block", pred);
        d.put("from_start", time.iso(first));
        d.put("to_start", time.iso(nb.start()));
        d.put("carried_moves", carried);
        d.put("reason", "다시 계획");
        logs.add(newBlockId, "moved", actor, time.hm(pb.start()) + "→" + time.hm(nb.start()) + " (블록#" + pred
                + " 다시 배치)", d);
        // 새 블록의 기록 줄이 이미 생겼으면 처음 계획 시각을 맞춘다(없으면 refresh 가 위 기록에서 읽는다).
        db.update("UPDATE block_facts SET planned_start_first = ? WHERE block_id = ?", first, newBlockId);
        return pred;
    }

    /** 맥락용: 최근 계획에서 빠졌고 아직 다시 배치되지 않은 할 일. */
    public List<Dropped> unresolved() {
        Instant now = clock.now();
        Instant since = now.minus(SHOW_WINDOW);
        record Row(long blockId, long taskId) {
        }
        List<Row> rows = db.jdbc().sql("""
                        SELECT b.id, b.task_id FROM blocks b JOIN block_logs l ON l.block_id = b.id
                        WHERE b.status = 'cancelled' AND b.task_id IS NOT NULL AND l.type = 'note'
                        AND l.data LIKE '%"dropped":true%' AND l.at > ? ORDER BY l.at DESC, b.id DESC""")
                .param(AssistantDb.ts(since)).query((rs, n) -> new Row(rs.getLong(1), rs.getLong(2))).list();
        List<Dropped> out = new ArrayList<>();
        Set<Long> seen = new LinkedHashSet<>();
        for (Row r : rows) {
            if (!seen.add(r.taskId())) {
                continue;
            }
            Store.Task t = store.task(r.taskId()).orElse(null);
            if (t == null || !"active".equals(t.status()) || t.remainingMinutes() <= 0 || hasFutureBlock(t.id(), now)
                    || hasSuccessor(r.blockId())) {
                continue;
            }
            Store.Block b = store.block(r.blockId()).orElseThrow();
            out.add(new Dropped(t.id(), t.title(), b.id(), b.start()));
        }
        return out;
    }
}
