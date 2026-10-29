package com.seobin.phonedrop.assistant.agent;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.seobin.phonedrop.assistant.AssistantClock;
import com.seobin.phonedrop.assistant.AssistantDb;
import com.seobin.phonedrop.assistant.SettingsService;
import com.seobin.phonedrop.assistant.Store;
import com.seobin.phonedrop.assistant.TimeUtil;

/**
 * 시간표 조회(get_timeline, GET /v1/timeline)와 부하 요약(get_load_summary).
 * free = 활동 구간 − 일정(버퍼 포함) − 활성 블록 − 조용한 시간 − 식사.
 */
@Component
public class Timeline {

    public static final int MAX_DAYS = 14;

    private final AssistantDb db;
    private final AssistantClock clock;
    private final Store store;
    private final TimeUtil time;
    private final SettingsService settings;

    public Timeline(AssistantDb db, AssistantClock clock, Store store, TimeUtil time, SettingsService settings) {
        this.db = db;
        this.clock = clock;
        this.store = store;
        this.time = time;
        this.settings = settings;
    }

    /** sensitive 모드면 제목을 가린다(id 는 유지). */
    public String eventTitle(Store.Event e) {
        return e.sensitive() && settings.getBoolean("privacy.sensitive_mode") ? "비공개 일정" : e.title();
    }

    public String taskTitle(Store.Task t) {
        return t.sensitive() && settings.getBoolean("privacy.sensitive_mode") ? "비공개 할 일" : t.title();
    }

    public String goalTitle(Store.Goal g) {
        return g.sensitive() && settings.getBoolean("privacy.sensitive_mode") ? "비공개 목표" : g.title();
    }

    /** from~to(최대 14일)를 활동일(logical day) 단위로 나눈 시간표. */
    public Map<String, Object> timeline(Instant from, Instant to, boolean mask) {
        if (!to.isAfter(from)) {
            throw new IllegalArgumentException("to 는 from 보다 뒤여야 합니다");
        }
        if (Duration.between(from, to).toDays() > MAX_DAYS) {
            to = from.plus(Duration.ofDays(MAX_DAYS));
        }
        List<Map<String, Object>> days = new ArrayList<>();
        for (LocalDate d = time.logicalDay(from); !d.isAfter(time.logicalDay(to.minusSeconds(1))); d = d.plusDays(1)) {
            TimeUtil.Window active = time.activeWindow(d);
            Instant dayStart = active.start();
            Instant dayEnd = time.activeWindow(d.plusDays(1)).start(); // 다음 기상까지가 이 날
            Instant s = max(dayStart, from);
            Instant e = min(dayEnd, to);
            if (!s.isBefore(e)) {
                continue;
            }
            List<Map<String, Object>> items = new ArrayList<>();
            List<TimeUtil.Window> busy = new ArrayList<>();

            for (Store.Event ev : store.activeEventsOverlapping(s, e)) {
                Map<String, Object> item = item("event", ev.start(), ev.end());
                item.put("id", ev.id());
                item.put("title", mask ? eventTitle(ev) : ev.title());
                item.put("buffer_before_min", ev.bufferBeforeMin());
                item.put("buffer_after_min", ev.bufferAfterMin());
                item.put("source", ev.source());
                items.add(item);
                busy.add(new TimeUtil.Window(ev.blockedStart(), ev.blockedEnd()));
            }
            for (Store.Block b : blocksOverlapping(s, e)) {
                Map<String, Object> item = item("block", b.start(), b.end());
                item.put("id", b.id());
                item.put("status", b.status());
                item.put("task_id", b.taskId());
                if (b.workProductId() != null) {
                    item.put("work_product_id", b.workProductId());
                }
                store.task(b.taskId()).ifPresent(t -> item.put("title", mask ? taskTitle(t) : t.title()));
                items.add(item);
                if (List.of("planned", "nudging", "started").contains(b.status())) {
                    busy.add(new TimeUtil.Window(b.start(), b.end()));
                }
            }
            for (TimeUtil.Window q : time.quietWindows(s, e)) {
                // 수면은 하루의 앞뒤에 붙으므로 활동 구간 안의 조용한 시간(방해 금지)만 항목으로 보인다.
                if (q.overlaps(active.start(), active.end())) {
                    items.add(item("quiet", max(q.start(), active.start()), min(q.end(), active.end())));
                }
                busy.add(q);
            }
            for (TimeUtil.Window m : time.dailyWindows("life.meals", s, e)) {
                items.add(item("meal", m.start(), m.end()));
                busy.add(m);
            }
            Instant freeFrom = max(s, active.start());
            Instant freeTo = min(e, active.end());
            for (TimeUtil.Window f : subtract(freeFrom, freeTo, busy)) {
                items.add(item("free", f.start(), f.end()));
            }
            items.sort(Comparator.comparing(i -> (String) i.get("start")));

            Map<String, Object> day = new LinkedHashMap<>();
            day.put("date", d.toString());
            day.put("wake", time.iso(active.start()));
            day.put("sleep", time.iso(active.end()));
            day.put("items", items);
            days.add(day);
        }
        return Map.of("days", days);
    }

    /** now~until 의 빈 시간(분). */
    public long freeMinutes(Instant from, Instant until) {
        return freeMinutes(from, until, null);
    }

    /** 빈 시간(분). ownTaskId 의 블록은 그 할 일을 할 시간이므로 빈 시간으로 센다(get_facts). */
    public long freeMinutes(Instant from, Instant until, Long ownTaskId) {
        if (!until.isAfter(from)) {
            return 0;
        }
        long total = 0;
        for (LocalDate d = time.logicalDay(from); !d.isAfter(time.logicalDay(until.minusSeconds(1))); d = d.plusDays(1)) {
            TimeUtil.Window active = time.activeWindow(d);
            Instant s = max(active.start(), from);
            Instant e = min(active.end(), until);
            if (!s.isBefore(e)) {
                continue;
            }
            List<TimeUtil.Window> busy = new ArrayList<>();
            store.activeEventsOverlapping(s, e).forEach(ev -> busy.add(new TimeUtil.Window(ev.blockedStart(), ev.blockedEnd())));
            store.activeBlocks().stream().filter(b -> b.start().isBefore(e) && s.isBefore(b.end()))
                    .filter(b -> ownTaskId == null || b.taskId() != ownTaskId)
                    .forEach(b -> busy.add(new TimeUtil.Window(b.start(), b.end())));
            busy.addAll(time.quietWindows(s, e));
            busy.addAll(time.dailyWindows("life.meals", s, e));
            for (TimeUtil.Window f : subtract(s, e, busy)) {
                total += Duration.between(f.start(), f.end()).toMinutes();
            }
        }
        return total;
    }

    /** get_load_summary (빌드문서 v2 §5.1). */
    public Map<String, Object> loadSummary() {
        Instant now = clock.now();
        double okRatio = settings.getDouble("load.ok_ratio");
        double tightRatio = settings.getDouble("load.tight_ratio");
        List<Map<String, Object>> groups = new ArrayList<>();
        List<Store.Task> tasks = store.activeTasks();

        List<Store.Goal> goals = store.activeGoals();
        for (Store.Goal g : goals) {
            List<Store.Task> own = tasks.stream().filter(t -> Long.valueOf(g.id()).equals(t.goalId())).toList();
            groups.add(group(g.id(), goalTitle(g), g.deadline(), own, now, okRatio, tightRatio));
        }
        List<Store.Task> loose = tasks.stream().filter(t -> t.goalId() == null
                || goals.stream().noneMatch(g -> g.id() == t.goalId())).toList();
        if (!loose.isEmpty()) {
            // 목표 없는 할 일: 그룹 마감은 가장 이른 할 일 마감
            Instant deadline = loose.stream().map(Store.Task::deadline).filter(java.util.Objects::nonNull)
                    .min(Comparator.naturalOrder()).orElse(null);
            groups.add(group(null, "목표 없는 할 일", deadline, loose, now, okRatio, tightRatio));
        }
        return Map.of("groups", groups);
    }

    private Map<String, Object> group(Long goalId, String title, Instant deadline, List<Store.Task> tasks,
                                      Instant now, double okRatio, double tightRatio) {
        int remaining = tasks.stream().mapToInt(Store.Task::remainingMinutes).sum();
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("goal_id", goalId);
        g.put("title", title);
        g.put("deadline", time.iso(deadline));
        g.put("remaining_minutes", remaining);
        Long free = deadline == null ? null : freeMinutes(now, deadline);
        g.put("free_minutes_until_deadline", free);
        g.put("status", status(remaining, free, okRatio, tightRatio));
        g.put("progress_minutes_7d", progress7d(tasks, now));
        Instant next = tasks.stream().flatMap(t -> store.blocksOfTask(t.id()).stream())
                .filter(b -> "planned".equals(b.status()) && b.start().isAfter(now))
                .map(Store.Block::start).min(Comparator.naturalOrder()).orElse(null);
        g.put("next_block_start", time.iso(next));
        return g;
    }

    /** ok: free ≥ remaining × ok_ratio, tight: ≥ remaining × tight_ratio, 그 미만 short, 마감 없음 no_deadline. */
    public static String status(int remaining, Long free, double okRatio, double tightRatio) {
        if (free == null) {
            return "no_deadline";
        }
        if (free >= remaining * okRatio) {
            return "ok";
        }
        if (free >= remaining * tightRatio) {
            return "tight";
        }
        return "short";
    }

    /** 최근 7일 started 된 블록의 실제 진행 시간 합(actual_start ~ actual_end, 진행 중이면 now). */
    private long progress7d(List<Store.Task> tasks, Instant now) {
        Instant since = now.minus(Duration.ofDays(7));
        long total = 0;
        for (Store.Task t : tasks) {
            for (Store.Block b : store.blocksOfTask(t.id())) {
                if (b.actualStart() == null || b.actualStart().isBefore(since)) {
                    continue;
                }
                Instant end = b.actualEnd() != null ? b.actualEnd() : ("started".equals(b.status()) ? now : b.end());
                total += Math.max(0, Duration.between(b.actualStart(), min(end, now)).toMinutes());
            }
        }
        return total;
    }

    private List<Store.Block> blocksOverlapping(Instant s, Instant e) {
        return db.jdbc().sql("""
                        SELECT * FROM blocks WHERE status <> 'cancelled' AND start_at < ? AND end_at > ?
                        ORDER BY start_at, id""")
                .param(AssistantDb.ts(e)).param(AssistantDb.ts(s))
                .query((rs, n) -> store.block(rs.getLong("id")).orElseThrow()).list();
    }

    private Map<String, Object> item(String kind, Instant start, Instant end) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("kind", kind);
        item.put("start", time.iso(start));
        item.put("end", time.iso(end));
        return item;
    }

    /** [from, to) 에서 busy 구간을 뺀 나머지. */
    public static List<TimeUtil.Window> subtract(Instant from, Instant to, List<TimeUtil.Window> busy) {
        List<TimeUtil.Window> sorted = new ArrayList<>(busy);
        sorted.sort(Comparator.comparing(TimeUtil.Window::start));
        List<TimeUtil.Window> free = new ArrayList<>();
        Instant cursor = from;
        for (TimeUtil.Window b : sorted) {
            if (!b.end().isAfter(cursor)) {
                continue;
            }
            if (!b.start().isBefore(to)) {
                break;
            }
            if (b.start().isAfter(cursor)) {
                free.add(new TimeUtil.Window(cursor, b.start()));
            }
            cursor = max(cursor, b.end());
        }
        if (cursor.isBefore(to)) {
            free.add(new TimeUtil.Window(cursor, to));
        }
        return free;
    }

    static Instant max(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }

    static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }
}
