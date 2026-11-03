package com.seobin.phonedrop.assistant.agent;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import com.seobin.phonedrop.assistant.ApiException;
import com.seobin.phonedrop.assistant.AssistantClock;
import com.seobin.phonedrop.assistant.AssistantDb;
import com.seobin.phonedrop.assistant.BlockLogs;
import com.seobin.phonedrop.assistant.InterventionService;
import com.seobin.phonedrop.assistant.Jobs;
import com.seobin.phonedrop.assistant.Json;
import com.seobin.phonedrop.assistant.PlanService;
import com.seobin.phonedrop.assistant.SettingsService;
import com.seobin.phonedrop.assistant.Store;
import com.seobin.phonedrop.assistant.TimeUtil;
import com.seobin.phonedrop.assistant.TurnQueue;
import com.seobin.phonedrop.assistant.Tx;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 반복 고정 일정(빌드문서 v3.2 §2). 시리즈는 규칙만 갖고, 앞으로 series.horizon_days 안의 회차를 고정 블록으로 만들어 둔다
 * (매일 1회 + 시리즈 생성·수정 때). 회차 블록은 일반 고정 블록과 같은 규칙(개입·권한·밀어내기)을 따른다.
 */
@Service
public class SeriesService {

    private static final Logger log = LoggerFactory.getLogger(SeriesService.class);
    public static final String JOB = "series_generate";
    static final LocalTime DAILY_AT = LocalTime.of(4, 10);

    public record Series(long id, String title, String freq, int interval, List<Integer> byDays, Integer monthDay,
                         LocalTime startTime, int durationMin, LocalDate startDate, LocalDate untilDate,
                         int leadMinutes, int bufferAfterMin, String prepSetBy, String location, Long goalId,
                         String firstAction, String memo, boolean active) {
    }

    /** 결과: error 가 있으면 실패. created = 이번에 만든 회차 블록. */
    public record Result(long id, List<Long> created, String note, String error) {

        static Result fail(String error) {
            return new Result(0, List.of(), null, error);
        }

        public boolean ok() {
            return error == null;
        }
    }

    private final AssistantDb db;
    private final AssistantClock clock;
    private final Store store;
    private final TimeUtil time;
    private final SettingsService settings;
    private final Validator validator;
    private final InterventionService interventions;
    private final PlanService plan;
    private final FixedBlockService fixedBlocks;
    private final ChatService chat;
    private final TurnQueue turns;
    private final BlockLogs logs;
    private final Jobs jobs;
    private final Tx tx;
    private final TravelService travel;
    private final boolean enabled;

    public SeriesService(AssistantDb db, AssistantClock clock, Store store, TimeUtil time, SettingsService settings,
                         Validator validator, InterventionService interventions, PlanService plan,
                         FixedBlockService fixedBlocks, ChatService chat, TurnQueue turns, BlockLogs logs, Jobs jobs,
                         Tx tx, TravelService travel, @Value("${assistant.worker.enabled:true}") boolean enabled) {
        this.db = db;
        this.clock = clock;
        this.store = store;
        this.time = time;
        this.settings = settings;
        this.validator = validator;
        this.interventions = interventions;
        this.plan = plan;
        this.fixedBlocks = fixedBlocks;
        this.chat = chat;
        this.turns = turns;
        this.logs = logs;
        this.jobs = jobs;
        this.tx = tx;
        this.travel = travel;
        this.enabled = enabled;
        jobs.register(JOB, this::onDaily);
    }

    // ---------------------------------------------------------------- 매일 생성

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (enabled) {
            tx.exec(() -> {
                ensureJob();
                generateAll();
            });
        }
    }

    public void ensureJob() {
        db.update("UPDATE jobs SET status = 'cancelled', updated_at = CURRENT_TIMESTAMP WHERE status = 'scheduled' AND type = ?",
                JOB);
        jobs.schedule(JOB, 0, nextRun(clock.now()), null);
    }

    Instant nextRun(Instant now) {
        LocalDate today = time.local(now).toLocalDate();
        Instant at = today.atTime(DAILY_AT).atZone(time.zone()).toInstant();
        return at.isAfter(now) ? at : today.plusDays(1).atTime(DAILY_AT).atZone(time.zone()).toInstant();
    }

    private boolean onDaily(Jobs.Job job) {
        jobs.schedule(JOB, 0, nextRun(clock.now().plusSeconds(60)), null);
        generateAll();
        return true;
    }

    /** 모든 활성 시리즈의 앞으로 horizon_days 안 회차를 만든다. 이미 있는 회차는 건너뛴다(중복 없음). */
    public List<Long> generateAll() {
        List<Long> created = new ArrayList<>();
        for (Series s : activeSeries()) {
            created.addAll(generate(s));
        }
        return created;
    }

    // ---------------------------------------------------------------- 조회

    public Optional<Series> series(long id) {
        return db.jdbc().sql("SELECT * FROM fixed_series WHERE id = ?").param(id).query(SeriesService::series).optional();
    }

    public List<Series> activeSeries() {
        return db.jdbc().sql("SELECT * FROM fixed_series WHERE active = TRUE ORDER BY id")
                .query(SeriesService::series).list();
    }

    /** from~to(포함) 사이 이 시리즈의 회차 날짜(예외는 빼지 않는다). */
    public static List<LocalDate> occurrences(Series s, LocalDate from, LocalDate to) {
        List<LocalDate> dates = new ArrayList<>();
        LocalDate d = from.isBefore(s.startDate()) ? s.startDate() : from;
        LocalDate end = s.untilDate() != null && s.untilDate().isBefore(to) ? s.untilDate() : to;
        for (; !d.isAfter(end); d = d.plusDays(1)) {
            if (matches(s, d)) {
                dates.add(d);
            }
        }
        return dates;
    }

    static boolean matches(Series s, LocalDate d) {
        int interval = Math.max(1, s.interval());
        return switch (s.freq()) {
            case "daily" -> ChronoUnit.DAYS.between(s.startDate(), d) % interval == 0;
            case "weekly" -> {
                LocalDate w0 = s.startDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                LocalDate w = d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                yield s.byDays().contains(d.getDayOfWeek().getValue())
                        && ChronoUnit.WEEKS.between(w0, w) % interval == 0;
            }
            case "monthly" -> {
                long months = ChronoUnit.MONTHS.between(s.startDate().withDayOfMonth(1), d.withDayOfMonth(1));
                int day = Math.min(s.monthDay() == null ? s.startDate().getDayOfMonth() : s.monthDay(), d.lengthOfMonth());
                yield months % interval == 0 && d.getDayOfMonth() == day;
            }
            default -> false;
        };
    }

    public Instant startOf(Series s, LocalDate date) {
        return date.atTime(s.startTime()).atZone(time.zone()).toInstant();
    }

    /** "매주 화·목 19:00" 같은 규칙 설명. */
    public static String describe(Series s) {
        String names = "월화수목금토일";
        String rule = switch (s.freq()) {
            case "daily" -> s.interval() > 1 ? s.interval() + "일마다" : "매일";
            case "weekly" -> {
                StringBuilder days = new StringBuilder();
                for (int d : s.byDays()) {
                    days.append(days.isEmpty() ? "" : "·").append(names.charAt(d - 1));
                }
                yield (s.interval() == 2 ? "격주 " : s.interval() > 2 ? s.interval() + "주마다 " : "매주 ") + days;
            }
            case "monthly" -> (s.interval() > 1 ? s.interval() + "개월마다 " : "매월 ")
                    + (s.monthDay() == null ? s.startDate().getDayOfMonth() : s.monthDay()) + "일";
            default -> s.freq();
        };
        return rule + " " + s.startTime();
    }

    // ---------------------------------------------------------------- 회차 생성

    /** 오늘 ~ 오늘 + horizon_days 안의 회차를 고정 블록으로 만든다. */
    public List<Long> generate(Series s) {
        if (!s.active()) {
            return List.of();
        }
        Instant now = clock.now();
        LocalDate today = time.local(now).toLocalDate();
        LocalDate to = today.plusDays(settings.getInt("series.horizon_days"));
        List<Long> created = new ArrayList<>();
        for (LocalDate date : occurrences(s, today, to)) {
            if (hasException(s.id(), date) || blockFor(s.id(), date).isPresent()) {
                continue;
            }
            Instant start = startOf(s, date);
            Instant end = start.plus(Duration.ofMinutes(s.durationMin()));
            if (!end.isAfter(now)) {
                continue;
            }
            Validator.FixedCheck check = validator.validateFixed(null, start, end, s.leadMinutes(), s.bufferAfterMin(), now);
            if (!check.errors().isEmpty()) {
                // 다른 고정 블록과 겹치면 만들지 않고 한 번만 알린다(conflict 예외로 남겨 다시 시도하지 않는다).
                db.insert("INSERT INTO series_exceptions(series_id, occurrence_date, action) VALUES (?, ?, 'conflict')",
                        s.id(), date);
                Store.Block other = store.activeFixedBlocksOverlapping(start.minus(Duration.ofMinutes(s.leadMinutes())),
                        end.plus(Duration.ofMinutes(s.bufferAfterMin()))).stream().findFirst().orElse(null);
                log.warn("series {} occurrence {} conflicts with fixed block {}", s.id(), date,
                        other == null ? null : other.id());
                chat.sendAssistant(date.getMonthValue() + "/" + date.getDayOfMonth() + " "
                        + (other == null ? "다른 고정 일정" : store.blockTitle(other)) + "과 겹쳐서 이 날 " + s.title()
                        + "은 넣지 않았어요", "system", "normal", null, null);
                continue;
            }
            boolean prepGiven = s.leadMinutes() > 0 || s.bufferAfterMin() > 0;
            String screen = s.firstAction() == null || s.firstAction().isBlank()
                    ? InterventionService.fixedScreenText(s.title(), s.leadMinutes())
                    : InterventionService.fixedScreenText(s.title(), s.leadMinutes()) + " 첫 행동: " + s.firstAction();
            long id = db.insert("""
                            INSERT INTO blocks(kind, title, goal_id, start_at, end_at, status, screen_text, lead_minutes,
                            buffer_after_min, location, source, prep_set_by, series_id, occurrence_date)
                            VALUES ('fixed', ?, ?, ?, ?, 'planned', ?, ?, ?, ?, 'series', ?, ?, ?)""",
                    s.title(), s.goalId(), start, end, screen, s.leadMinutes(), s.bufferAfterMin(), s.location(),
                    prepGiven ? s.prepSetBy() : null, s.id(), date);
            // v3.3: 시리즈의 방해 허용 정도·찾은 장소를 회차에 복사한다(회차별로 바꾸면 그 회차만 바뀐다).
            db.update("""
                            UPDATE blocks SET (interrupt_level, interrupt_set_by, place_name, lat, lng, place_source) =
                            (SELECT interrupt_level, interrupt_set_by, place_name, lat, lng, place_source FROM fixed_series
                            WHERE id = ?) WHERE id = ?""", s.id(), id);
            logs.add(id, "created", "system", "반복 일정#" + s.id() + "(" + describe(s) + ")에서 생성");
            List<Long> displaced = new ArrayList<>();
            for (Store.Block b : check.displaced()) {
                fixedBlocks.displace(b, null);
                displaced.add(b.id());
            }
            for (Store.Block b : check.cutoff()) {
                interventions.scheduleCutoff(b.id(), start.minus(Duration.ofMinutes(s.leadMinutes())));
            }
            if (!displaced.isEmpty()) {
                ObjectNode ref = Json.object();
                ref.put("block_id", id);
                ref.set("displaced", Json.MAPPER.valueToTree(displaced));
                turns.enqueue("blocks_displaced", ref, TurnQueue.PRIORITY_DEFAULT);
            }
            interventions.scheduleBlockJobs(store.block(id).orElseThrow());
            if (store.block(id).map(Store.Block::hasCoords).orElse(false)) {
                travel.onDayChanged(id);
            }
            created.add(id);
        }
        if (!created.isEmpty()) {
            plan.bumpAndSync();
        }
        return created;
    }

    private boolean hasException(long seriesId, LocalDate date) {
        return db.jdbc().sql("SELECT COUNT(*) FROM series_exceptions WHERE series_id = ? AND occurrence_date = ?")
                .param(seriesId).param(date).query(Integer.class).single() > 0;
    }

    private Optional<Store.Block> blockFor(long seriesId, LocalDate date) {
        return db.jdbc().sql("SELECT * FROM blocks WHERE series_id = ? AND occurrence_date = ?")
                .param(seriesId).param(date).query(Store::block).optional();
    }

    // ---------------------------------------------------------------- 만들기·바꾸기·지우기

    /**
     * in: {id?, title, freq, interval?, by_days?, month_day?, start_time, duration_min, start_date?, until_date?,
     * lead_minutes?, buffer_after_min?, prep_from_user?, location?, goal_id?, first_action?, memo?(앱만),
     * scope?: "all"|"following", from_date?}. turnId 가 null 이면 앱(사용자 조작).
     */
    public Result upsert(JsonNode in, Long turnId) {
        Series existing = null;
        if (in.hasNonNull("id")) {
            existing = series(in.get("id").asLong()).filter(Series::active).orElse(null);
            if (existing == null) {
                return Result.fail("반복 일정#" + in.get("id").asLong() + "이 없습니다");
            }
        }
        Series s;
        try {
            s = merge(existing, in, turnId);
        } catch (IllegalArgumentException | ApiException e) {
            return Result.fail(e.getMessage());
        }
        String[] level;
        try {
            level = interruptLevel(existing == null ? null : existing.id(), in, turnId);
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        }
        if (existing == null) {
            long id = insert(s);
            applyExtras(id, level, true);
            return new Result(id, generate(series(id).orElseThrow()), null, null);
        }
        boolean locationChanged = !java.util.Objects.equals(s.location(), existing.location());
        String scope = in.path("scope").asString("all");
        LocalDate today = time.local(clock.now()).toLocalDate();
        LocalDate from = in.hasNonNull("from_date") ? LocalDate.parse(in.get("from_date").asString()) : today;
        if ("following".equals(scope) && from.isAfter(existing.startDate())) {
            // 이후 전부: 원 시리즈를 전날로 끊고 그날부터 새 시리즈
            db.update("UPDATE fixed_series SET until_date = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                    from.minusDays(1), existing.id());
            Series next = new Series(0, s.title(), s.freq(), s.interval(), s.byDays(), s.monthDay(), s.startTime(),
                    s.durationMin(), from, s.untilDate(), s.leadMinutes(), s.bufferAfterMin(), s.prepSetBy(),
                    s.location(), s.goalId(), s.firstAction(), s.memo(), true);
            long newId = insert(next);
            db.update("""
                            UPDATE fixed_series SET (place_name, lat, lng, place_source) =
                            (SELECT place_name, lat, lng, place_source FROM fixed_series WHERE id = ?) WHERE id = ?""",
                    existing.id(), newId);
            applyExtras(newId, level, locationChanged);
            db.update("UPDATE series_exceptions SET series_id = ? WHERE series_id = ? AND occurrence_date >= ?",
                    newId, existing.id(), from);
            Map<LocalDate, Store.Block> old = removeFuture(existing.id(), from);
            List<Long> created = generate(series(newId).orElseThrow());
            carryOver(old, newId);
            return new Result(newId, created, null, null);
        }
        // 전체: 시리즈를 고치고 미래 회차를 지우고 다시 만든다(과거·진행 중 제외).
        update(existing.id(), s);
        applyExtras(existing.id(), level, locationChanged);
        Map<LocalDate, Store.Block> old = removeFuture(existing.id(), today);
        List<Long> created = generate(series(existing.id()).orElseThrow());
        carryOver(old, existing.id());
        return new Result(existing.id(), created, null, null);
    }

    /** 반복 일정 지우기. scope=all 이면 시리즈 끝, following 이면 from_date 전날까지로 끊는다. */
    public Result cancel(long id, String scope, LocalDate fromDate, Long turnId) {
        Series s = series(id).filter(Series::active).orElse(null);
        if (s == null) {
            return Result.fail("반복 일정#" + id + "이 없습니다");
        }
        LocalDate today = time.local(clock.now()).toLocalDate();
        LocalDate from = fromDate == null ? today : fromDate;
        if ("following".equals(scope) && from.isAfter(s.startDate())) {
            db.update("UPDATE fixed_series SET until_date = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                    from.minusDays(1), id);
        } else {
            db.update("UPDATE fixed_series SET active = FALSE, updated_at = CURRENT_TIMESTAMP WHERE id = ?", id);
        }
        List<Long> cancelled = new ArrayList<>();
        for (Store.Block b : futureBlocks(id, "following".equals(scope) ? from : today)) {
            if (fixedBlocks.cancel(b.id(), turnId).ok()) {
                cancelled.add(b.id());
            }
        }
        plan.bumpAndSync();
        return new Result(id, List.of(), cancelled.isEmpty() ? null : "취소된 회차: " + cancelled, null);
    }

    /** 아직 시작 전(planned)인 미래 회차 중 '이 날만' 옮긴 것이 아닌 것. */
    private List<Store.Block> futureBlocks(long seriesId, LocalDate from) {
        Instant now = clock.now();
        return db.jdbc().sql("""
                        SELECT * FROM blocks WHERE series_id = ? AND occurrence_date >= ? AND status = 'planned'
                        AND start_at > ? AND NOT EXISTS (SELECT 1 FROM series_exceptions e WHERE e.series_id = blocks.series_id
                        AND e.occurrence_date = blocks.occurrence_date AND e.action = 'moved') ORDER BY start_at""")
                .param(seriesId).param(from).param(AssistantDb.ts(now)).query(Store::block).list();
    }

    /** 미래 회차를 지운다(취소하고 회차 날짜를 비워 다시 만들 수 있게). 날짜별 옛 블록을 돌려준다(메모·연결 옮기기용). */
    private Map<LocalDate, Store.Block> removeFuture(long seriesId, LocalDate from) {
        Map<LocalDate, Store.Block> old = new HashMap<>();
        for (Store.Block b : futureBlocks(seriesId, from)) {
            old.put(b.occurrenceDate(), b);
            interventions.cancelRunningForBlock(b.id());
            interventions.cancelBlockJobs(b.id());
            db.update("""
                            UPDATE blocks SET status = 'cancelled', occurrence_date = NULL, updated_at = CURRENT_TIMESTAMP
                            WHERE id = ?""", b.id());
            logs.add(b.id(), "note", "system", "반복 일정이 바뀌어 다시 만듦");
        }
        return old;
    }

    /** 다시 만든 회차로 '이번만' 메모와 연결 블록을 옮긴다. 시각이 바뀌었으면 연결 블록 재배치를 비서에게 맡긴다. */
    private void carryOver(Map<LocalDate, Store.Block> old, long seriesId) {
        for (Map.Entry<LocalDate, Store.Block> e : old.entrySet()) {
            Store.Block now = blockFor(seriesId, e.getKey()).orElse(null);
            if (now == null) {
                continue;
            }
            Store.Block before = e.getValue();
            if (!before.memo().isBlank()) {
                db.update("UPDATE blocks SET memo = ?, memo_updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                        before.memo(), now.id());
            }
            List<Long> linked = db.jdbc().sql("""
                            SELECT id FROM blocks WHERE linked_fixed_block_id = ? AND status IN ('planned', 'nudging')""")
                    .param(before.id()).query(Long.class).list();
            if (!linked.isEmpty()) {
                db.update("UPDATE blocks SET linked_fixed_block_id = ? WHERE linked_fixed_block_id = ? AND status IN ('planned', 'nudging')",
                        now.id(), before.id());
                if (!now.start().equals(before.start()) || !now.end().equals(before.end())) {
                    fixedBlocks.notifyFixedChanged(now.id(), linked, "moved");
                }
            }
        }
    }

    private Series merge(Series e, JsonNode in, Long turnId) {
        String title = in.hasNonNull("title") ? in.get("title").asString("").trim() : e == null ? "" : e.title();
        if (title.isEmpty()) {
            throw new IllegalArgumentException("반복 일정에는 title 이 필요합니다");
        }
        String freq = in.hasNonNull("freq") ? in.get("freq").asString("") : e == null ? "" : e.freq();
        if (!List.of("daily", "weekly", "monthly").contains(freq)) {
            throw new IllegalArgumentException("freq 는 daily·weekly·monthly 중 하나입니다");
        }
        int interval = in.hasNonNull("interval") ? in.get("interval").asInt() : e == null ? 1 : e.interval();
        if (interval < 1 || interval > 12) {
            throw new IllegalArgumentException("interval 은 1~12 입니다");
        }
        LocalDate today = time.local(clock.now()).toLocalDate();
        LocalDate startDate = in.hasNonNull("start_date") ? LocalDate.parse(in.get("start_date").asString())
                : e == null ? today : e.startDate();
        LocalDate untilDate = in.has("until_date") ? (in.get("until_date").isNull() || in.get("until_date").asString("").isBlank()
                ? null : LocalDate.parse(in.get("until_date").asString())) : e == null ? null : e.untilDate();
        if (untilDate != null && untilDate.isBefore(startDate)) {
            throw new IllegalArgumentException("until_date 는 start_date 이후여야 합니다");
        }
        List<Integer> byDays = new ArrayList<>();
        if (in.has("by_days")) {
            for (JsonNode d : in.path("by_days")) {
                int v = d.asInt();
                if (v < 1 || v > 7) {
                    throw new IllegalArgumentException("by_days 는 1(월)~7(일) 입니다");
                }
                if (!byDays.contains(v)) {
                    byDays.add(v);
                }
            }
            byDays.sort(Integer::compare);
        } else if (e != null) {
            byDays.addAll(e.byDays());
        }
        if ("weekly".equals(freq) && byDays.isEmpty()) {
            byDays.add(startDate.getDayOfWeek().getValue());
        }
        Integer monthDay = in.hasNonNull("month_day") ? Integer.valueOf(in.get("month_day").asInt()) : e == null ? null : e.monthDay();
        if ("monthly".equals(freq) && monthDay == null) {
            monthDay = startDate.getDayOfMonth();
        }
        if (monthDay != null && (monthDay < 1 || monthDay > 31)) {
            throw new IllegalArgumentException("month_day 는 1~31 입니다");
        }
        LocalTime startTime = in.hasNonNull("start_time") ? LocalTime.parse(in.get("start_time").asString())
                : e == null ? null : e.startTime();
        if (startTime == null) {
            throw new IllegalArgumentException("반복 일정에는 start_time(HH:mm) 이 필요합니다");
        }
        int duration = in.hasNonNull("duration_min") ? in.get("duration_min").asInt() : e == null ? 0 : e.durationMin();
        if (duration < 5) {
            throw new IllegalArgumentException("duration_min 은 5분 이상이어야 합니다");
        }
        boolean prepGiven = in.hasNonNull("lead_minutes") || in.hasNonNull("buffer_after_min");
        int lead = in.hasNonNull("lead_minutes") ? Math.max(0, in.get("lead_minutes").asInt()) : e == null ? 0 : e.leadMinutes();
        int buffer = in.hasNonNull("buffer_after_min") ? Math.max(0, in.get("buffer_after_min").asInt())
                : e == null ? 0 : e.bufferAfterMin();
        String prepSetBy = e == null ? null : e.prepSetBy();
        if (prepGiven) {
            boolean byUser = turnId == null || in.path("prep_from_user").asBoolean(false);
            boolean changed = e == null || lead != e.leadMinutes() || buffer != e.bufferAfterMin();
            if (!byUser && changed && e != null && "user".equals(e.prepSetBy())) {
                throw new IllegalArgumentException("사용자가 정한 준비 시간이라 바꾸지 않습니다. 필요하면 사용자에게 말로 제안하세요.");
            }
            if (changed || prepSetBy == null) {
                prepSetBy = byUser ? "user" : "assistant";
            }
        }
        Long goalId = in.has("goal_id") ? (in.get("goal_id").isNull() ? null : in.get("goal_id").asLong())
                : e == null ? null : e.goalId();
        if (goalId != null && store.goal(goalId).filter(g -> "active".equals(g.status())).isEmpty()) {
            throw new IllegalArgumentException("활성 목표#" + goalId + "이 없습니다");
        }
        String location = in.has("location") ? blankToNull(in.path("location").asString("")) : e == null ? null : e.location();
        String firstAction = in.has("first_action") ? blankToNull(in.path("first_action").asString(""))
                : e == null ? null : e.firstAction();
        // 메모는 사용자만 쓴다(앱). 도구(turnId 있음)로는 바꾸지 않는다(채팅 요청은 append_fixed_memo).
        String memo = e == null ? "" : e.memo();
        if (turnId == null && in.has("memo")) {
            memo = checkMemo(in.path("memo").asString(""));
        }
        return new Series(e == null ? 0 : e.id(), title, freq, interval, byDays, monthDay, startTime, duration, startDate,
                untilDate, lead, buffer, prepSetBy, location, goalId, firstAction, memo, true);
    }

    /** 방해 허용 정도(v3.3 §1.3): 고정 블록과 같은 규칙. {level, set_by}. */
    private String[] interruptLevel(Long seriesId, JsonNode in, Long turnId) {
        String[] current = seriesId == null ? new String[]{null, null} : db.jdbc().sql(
                        "SELECT interrupt_level, interrupt_set_by FROM fixed_series WHERE id = ?")
                .param(seriesId).query((rs, n) -> new String[]{rs.getString(1), rs.getString(2)}).single();
        if (!in.has("interrupt_level")) {
            return current;
        }
        String given = in.get("interrupt_level").isNull() ? "" : in.get("interrupt_level").asString("").trim();
        boolean byUser = turnId == null || in.path("interrupt_from_user").asBoolean(false);
        if (given.isEmpty()) {
            return byUser ? new String[]{null, null} : current;
        }
        if (!InterruptPolicy.LEVELS.contains(given)) {
            throw new IllegalArgumentException("interrupt_level 은 dnd·quiet·allow 중 하나입니다");
        }
        if (!byUser && "user".equals(current[1]) && !given.equals(current[0])) {
            throw new IllegalArgumentException("사용자가 정한 방해 허용 정도(" + FixedBlockService.levelText(current[0])
                    + ")라 바꾸지 않습니다");
        }
        return new String[]{given, byUser ? "user" : "assistant"};
    }

    /** 방해 허용 정도 저장, 장소가 바뀌었으면 시리즈 단위로 한 번만 좌표를 찾는다(§3.2). */
    private void applyExtras(long seriesId, String[] level, boolean locationChanged) {
        db.update("UPDATE fixed_series SET interrupt_level = ?, interrupt_set_by = ? WHERE id = ?", level[0], level[1],
                seriesId);
        if (locationChanged) {
            travel.geocodeSeries(seriesId);
        }
    }

    /** set_interrupt_level 도구(series_id): 시리즈와 아직 시작 전인 미래 회차에 적용한다. */
    public Result setInterruptLevel(long seriesId, String level, Long turnId) {
        if (series(seriesId).filter(Series::active).isEmpty()) {
            return Result.fail("반복 일정#" + seriesId + "이 없습니다");
        }
        tools.jackson.databind.node.ObjectNode in = Json.object();
        in.put("interrupt_level", level);
        String[] v;
        try {
            v = interruptLevel(seriesId, in, turnId);
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        }
        applyExtras(seriesId, v, false);
        db.update("""
                        UPDATE blocks SET interrupt_level = ?, interrupt_set_by = ? WHERE series_id = ? AND status = 'planned'
                        AND start_at > ? AND (interrupt_set_by IS NULL OR interrupt_set_by <> 'user' OR ? = 'user')""",
                v[0], v[1], seriesId, AssistantDb.ts(clock.now()), v[1]);
        plan.bumpAndSync();
        return new Result(seriesId, List.of(), null, null);
    }

    String checkMemo(String memo) {
        String m = memo == null ? "" : memo.strip();
        int max = settings.getInt("memo.max_chars");
        if (m.length() > max) {
            throw ApiException.unprocessable("메모는 " + max + "자 이하여야 합니다");
        }
        return m;
    }

    private long insert(Series s) {
        return db.insert("""
                        INSERT INTO fixed_series(title, freq, interval_n, by_days, month_day, start_time, duration_min,
                        start_date, until_date, lead_minutes, buffer_after_min, prep_set_by, location, goal_id,
                        first_action, memo, memo_updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                s.title(), s.freq(), s.interval(), daysText(s.byDays()), s.monthDay(), s.startTime(), s.durationMin(),
                s.startDate(), s.untilDate(), s.leadMinutes(), s.bufferAfterMin(), s.prepSetBy(), s.location(),
                s.goalId(), s.firstAction(), s.memo(), s.memo().isEmpty() ? null : clock.now());
    }

    private void update(long id, Series s) {
        db.update("""
                        UPDATE fixed_series SET title = ?, freq = ?, interval_n = ?, by_days = ?, month_day = ?,
                        start_time = ?, duration_min = ?, start_date = ?, until_date = ?, lead_minutes = ?,
                        buffer_after_min = ?, prep_set_by = ?, location = ?, goal_id = ?, first_action = ?, memo = ?,
                        updated_at = CURRENT_TIMESTAMP WHERE id = ?""",
                s.title(), s.freq(), s.interval(), daysText(s.byDays()), s.monthDay(), s.startTime(), s.durationMin(),
                s.startDate(), s.untilDate(), s.leadMinutes(), s.bufferAfterMin(), s.prepSetBy(), s.location(),
                s.goalId(), s.firstAction(), s.memo(), id);
    }

    public Map<String, Object> toMap(Series s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.id());
        m.put("title", s.title());
        m.put("freq", s.freq());
        m.put("interval", s.interval());
        m.put("by_days", s.byDays());
        m.put("month_day", s.monthDay());
        m.put("start_time", s.startTime().toString());
        m.put("duration_min", s.durationMin());
        m.put("start_date", s.startDate().toString());
        m.put("until_date", s.untilDate() == null ? null : s.untilDate().toString());
        m.put("lead_minutes", s.leadMinutes());
        m.put("buffer_after_min", s.bufferAfterMin());
        m.put("prep_set_by", s.prepSetBy());
        m.put("location", s.location());
        m.put("goal_id", s.goalId());
        m.put("first_action", s.firstAction());
        m.put("memo", s.memo());
        m.put("rule_text", describe(s));
        db.jdbc().sql("SELECT interrupt_level, interrupt_set_by, place_name FROM fixed_series WHERE id = ?").param(s.id())
                .query((rs, n) -> {
                    m.put("interrupt_level", rs.getString(1));
                    m.put("interrupt_set_by", rs.getString(2));
                    m.put("place_name", rs.getString(3));
                    return null;
                }).list();
        return m;
    }

    private static String daysText(List<Integer> days) {
        return days.isEmpty() ? null : String.join(",", days.stream().map(String::valueOf).toList());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    static Series series(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        List<Integer> days = new ArrayList<>();
        String text = rs.getString("by_days");
        if (text != null && !text.isBlank()) {
            for (String d : text.split(",")) {
                days.add(Integer.parseInt(d.trim()));
            }
        }
        Object md = rs.getObject("month_day");
        return new Series(rs.getLong("id"), rs.getString("title"), rs.getString("freq"), rs.getInt("interval_n"), days,
                md == null ? null : ((Number) md).intValue(), rs.getObject("start_time", LocalTime.class),
                rs.getInt("duration_min"), rs.getObject("start_date", LocalDate.class),
                rs.getObject("until_date", LocalDate.class), rs.getInt("lead_minutes"), rs.getInt("buffer_after_min"),
                rs.getString("prep_set_by"), rs.getString("location"), AssistantDb.nullableLong(rs, "goal_id"),
                rs.getString("first_action"), rs.getString("memo"), rs.getBoolean("active"));
    }
}
