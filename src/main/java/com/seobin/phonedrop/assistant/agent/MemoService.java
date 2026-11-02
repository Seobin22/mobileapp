package com.seobin.phonedrop.assistant.agent;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Service;

import com.seobin.phonedrop.assistant.ApiException;
import com.seobin.phonedrop.assistant.AssistantClock;
import com.seobin.phonedrop.assistant.AssistantDb;
import com.seobin.phonedrop.assistant.BlockLogs;
import com.seobin.phonedrop.assistant.Json;
import com.seobin.phonedrop.assistant.SettingsService;
import com.seobin.phonedrop.assistant.Store;
import com.seobin.phonedrop.assistant.TimeUtil;
import com.seobin.phonedrop.assistant.TurnQueue;

import tools.jackson.databind.node.ObjectNode;

/**
 * 고정 일정 메모(빌드문서 v3.2 §3). 사용자만 쓰고 비서는 읽기만 한다(채팅으로 요청하면 append 만).
 * 단발 고정 블록 = blocks.memo, 반복 일정 = '매번'(fixed_series.memo) + '이번만'(회차 blocks.memo).
 */
@Service
public class MemoService {

    public static final String MEMO_CHANGED = "fixed_memo_changed";
    static final Duration DEBOUNCE = Duration.ofMinutes(2);

    /** 맥락에 넣을 고정 일정 하나(반복의 아직 안 만든 회차는 blockId 가 null). */
    public record Entry(Long blockId, Long seriesId, Instant start, String title, String memo, List<BlockLogs.Log> notices) {
    }

    private final AssistantDb db;
    private final AssistantClock clock;
    private final Store store;
    private final TimeUtil time;
    private final SettingsService settings;
    private final SeriesService series;
    private final TurnQueue turns;
    private final BlockLogs logs;
    private final Timeline timeline;

    public MemoService(AssistantDb db, AssistantClock clock, Store store, TimeUtil time, SettingsService settings,
                       SeriesService series, TurnQueue turns, BlockLogs logs, Timeline timeline) {
        this.db = db;
        this.clock = clock;
        this.store = store;
        this.time = time;
        this.settings = settings;
        this.series = series;
        this.turns = turns;
        this.logs = logs;
        this.timeline = timeline;
    }

    // ---------------------------------------------------------------- 쓰기

    /** 앱에서 고정 블록 메모(단발 또는 반복 '이번만') 저장. */
    public void setBlockMemo(long blockId, String memo) {
        Store.Block b = fixedBlock(blockId);
        db.update("UPDATE blocks SET memo = ?, memo_updated_at = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                series.checkMemo(memo), clock.now(), b.id());
        maybeUrgent(b);
    }

    /** 앱에서 반복 일정 '매번' 메모 저장. */
    public void setSeriesMemo(long seriesId, String memo) {
        db.update("UPDATE fixed_series SET memo = ?, memo_updated_at = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                series.checkMemo(memo), clock.now(), seriesId);
        nextOccurrence(seriesId).ifPresent(this::maybeUrgent);
    }

    /** append_fixed_memo: 기존 메모 뒤에 덧붙이기만 한다(지우거나 고치지 못한다). */
    public long append(Long blockId, Long seriesId, String text) {
        String add = text == null ? "" : text.strip();
        if (add.isEmpty()) {
            throw new IllegalArgumentException("text 가 비었습니다");
        }
        if (blockId != null) {
            Store.Block b = fixedBlock(blockId);
            setBlockMemo(b.id(), join(b.memo(), add));
            return b.id();
        }
        if (seriesId == null) {
            throw new IllegalArgumentException("block_id 또는 series_id 가 필요합니다");
        }
        SeriesService.Series s = series.series(seriesId).filter(SeriesService.Series::active)
                .orElseThrow(() -> new IllegalArgumentException("반복 일정#" + seriesId + "이 없습니다"));
        setSeriesMemo(s.id(), join(s.memo(), add));
        return s.id();
    }

    private static String join(String memo, String add) {
        return memo == null || memo.isBlank() ? add : memo + "\n" + add;
    }

    /** 곧(memo.urgent_hours 안) 시작하는 일정의 메모가 바뀌면 비서를 부른다. 2분 안의 연속 수정은 한 번만. */
    private void maybeUrgent(Store.Block b) {
        Instant now = clock.now();
        boolean upcoming = List.of("planned", "nudging").contains(b.status()) && b.start().isAfter(now)
                && b.start().isBefore(now.plus(Duration.ofHours(settings.getInt("memo.urgent_hours"))));
        if (!upcoming) {
            return;
        }
        ObjectNode ref = Json.object();
        ref.put("block_id", b.id());
        turns.enqueueIfAbsent(MEMO_CHANGED, ref, TurnQueue.PRIORITY_DEFAULT, now.plus(DEBOUNCE));
    }

    private java.util.Optional<Store.Block> nextOccurrence(long seriesId) {
        return db.jdbc().sql("""
                        SELECT * FROM blocks WHERE series_id = ? AND status IN ('planned', 'nudging') AND start_at > ?
                        ORDER BY start_at LIMIT 1""")
                .param(seriesId).param(AssistantDb.ts(clock.now())).query(Store::block).optional();
    }

    private Store.Block fixedBlock(long blockId) {
        Store.Block b = store.block(blockId).orElse(null);
        if (b == null || !b.fixed()) {
            throw ApiException.notFound("고정 블록#" + blockId + "이 없습니다");
        }
        return b;
    }

    // ---------------------------------------------------------------- 읽기

    /** 이 고정 블록의 메모 전체(반복이면 '매번' + '이번만'). */
    public String fullMemo(Store.Block b) {
        return store.fullMemo(b);
    }

    /**
     * from~to 의 고정 일정(시각 순). 반복 일정의 아직 만들지 않은 회차(horizon 밖)도 넣는다.
     * withoutMemo=false 면 메모가 있는 일정만.
     */
    public List<Entry> entries(Instant from, Instant to, boolean withoutMemo) {
        List<Entry> list = new ArrayList<>();
        List<Store.Block> blocks = db.jdbc().sql("""
                        SELECT * FROM blocks WHERE kind = 'fixed' AND status IN ('planned', 'nudging', 'started')
                        AND start_at >= ? AND start_at < ? ORDER BY start_at, id""")
                .param(AssistantDb.ts(from)).param(AssistantDb.ts(to)).query(Store::block).list();
        for (Store.Block b : blocks) {
            String memo = fullMemo(b);
            if (withoutMemo || !memo.isBlank()) {
                list.add(new Entry(b.id(), b.seriesId(), b.start(), timeline.blockTitle(b), memo, logs.notices(b.id())));
            }
        }
        // horizon 밖 반복 회차(블록이 아직 없음)
        LocalDate fromDay = time.local(from).toLocalDate();
        LocalDate toDay = time.local(to).toLocalDate();
        for (SeriesService.Series s : series.activeSeries()) {
            for (LocalDate d : SeriesService.occurrences(s, fromDay, toDay)) {
                Instant start = series.startOf(s, d);
                if (start.isBefore(from) || !start.isBefore(to) || exists(s.id(), d)) {
                    continue;
                }
                if (withoutMemo || !s.memo().isBlank()) {
                    list.add(new Entry(null, s.id(), start, s.title(), s.memo(), List.of()));
                }
            }
        }
        list.sort(Comparator.comparing(Entry::start));
        return list;
    }

    private boolean exists(long seriesId, LocalDate date) {
        return db.jdbc().sql("""
                        SELECT (SELECT COUNT(*) FROM blocks WHERE series_id = ? AND occurrence_date = ?)
                        + (SELECT COUNT(*) FROM series_exceptions WHERE series_id = ? AND occurrence_date = ?)""")
                .param(seriesId).param(date).param(seriesId).param(date).query(Integer.class).single() > 0;
    }

    /** `- 11/05(수) 15:00 [고정] 치과 블록#3 · 메모: 보험증 · 고지함: 11/03 "보험증 확인"` */
    public String line(Entry e) {
        StringBuilder sb = new StringBuilder("- ").append(time.dayText(e.start())).append(" [고정] ").append(e.title());
        if (e.blockId() != null) {
            sb.append(" 블록#").append(e.blockId());
        } else {
            sb.append(" (반복 일정#").append(e.seriesId()).append(", 아직 블록 없음)");
        }
        if (!e.memo().isBlank()) {
            sb.append(" · 메모: ").append(e.memo().replace("\n", " "));
        }
        if (!e.notices().isEmpty()) {
            sb.append(" · 고지함: ");
            for (int i = 0; i < e.notices().size(); i++) {
                BlockLogs.Log n = e.notices().get(i);
                sb.append(i == 0 ? "" : ", ").append(time.local(n.at()).getMonthValue()).append('/')
                        .append(time.local(n.at()).getDayOfMonth()).append(" \"").append(n.content()).append('"');
            }
        }
        return sb.toString();
    }
}
