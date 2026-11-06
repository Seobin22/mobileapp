package com.seobin.phonedrop.assistant.insight;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.springframework.stereotype.Component;

import com.seobin.phonedrop.assistant.AssistantClock;
import com.seobin.phonedrop.assistant.AssistantDb;
import com.seobin.phonedrop.assistant.Json;
import com.seobin.phonedrop.assistant.SettingsService;
import com.seobin.phonedrop.assistant.Store;
import com.seobin.phonedrop.assistant.TimeUtil;

import tools.jackson.databind.JsonNode;

/**
 * 숫자 통계(빌드문서 v3.5 §5). LLM 을 부르지 않는다. 최근 28 논리일의 block_facts·day_facts 를 묶어 세고,
 * 표본이 적은 칸은 전체값 쪽으로 당긴다(value = (n·raw + k·baseline) ÷ (n + k)). 눈에 띄는 것만 notable.
 */
@Component
public class PatternStats {

    public static final int PERIOD_DAYS = 28;
    /** 비율 metric(차이 기준 0.15p). 그 밖은 상대 차이 20%. */
    static final Set<String> RATE_METRICS = Set.of("completion_rate", "start_rate", "reason_share", "success_reason_share",
            "attendance_rate", "snooze_rate", "tactic_success_rate", "distraction_rate", "distraction_app_share", "return_rate");
    /** notable 후보에서 빼는 metric(목표 점검은 밤 정리가 따로 본다). */
    static final Set<String> NOT_HIGHLIGHTED = Set.of("pace_ratio", "stall_steps");

    public record Stat(String metric, String dimension, String key, Double value, Double raw, int n, Double baseline,
                       boolean notable, Double score) {

        public boolean insufficient(int minSamples) {
            return n < minSamples;
        }
    }

    private record Obs(String sub, double v) {
    }

    private final AssistantDb db;
    private final AssistantClock clock;
    private final TimeUtil time;
    private final SettingsService settings;
    private final BlockFacts facts;
    private final DayFacts days;
    private final Store store;

    public PatternStats(AssistantDb db, AssistantClock clock, TimeUtil time, SettingsService settings, BlockFacts facts,
                        DayFacts days, Store store) {
        this.db = db;
        this.clock = clock;
        this.time = time;
        this.settings = settings;
        this.facts = facts;
        this.days = days;
        this.store = store;
    }

    // ------------------------------------------------------------------ 계산

    /** 최근 28 논리일로 다시 계산해 pattern_stats 를 바꾼다. */
    public List<Stat> compute() {
        Instant now = clock.now();
        LocalDate today = time.logicalDay(now);
        LocalDate from = today.minusDays(PERIOD_DAYS - 1);
        // 근처 블록을 먼저 맞춘다(오늘 바뀐 상태·설득 결과).
        facts.safeRefreshBetween(now.minus(Duration.ofDays(3)), now.plus(Duration.ofDays(1)));
        for (LocalDate d = today.minusDays(2); !d.isAfter(today); d = d.plusDays(1)) {
            days.computeCounts(d);
        }
        List<BlockFacts.Fact> all = facts.between(from, today).stream()
                .filter(f -> !"cancelled".equals(f.statusFinal())).toList();
        Map<LocalDate, DayFacts.Day> dayMap = new HashMap<>();
        days.between(from, today).forEach(d -> dayMap.put(d.day(), d));
        int k = settings.getInt("memory.prior_k");

        List<Stat> out = new ArrayList<>();
        List<BlockFacts.Fact> planned = all.stream().filter(f -> "planned".equals(f.kind())).toList();
        List<BlockFacts.Fact> fixed = all.stream().filter(f -> "fixed".equals(f.kind())).toList();
        List<BlockFacts.Fact> plannedFixed = all.stream().filter(f -> !"travel".equals(f.kind())).toList();
        List<BlockFacts.Fact> travel = all.stream().filter(f -> "travel".equals(f.kind())).toList();

        // 완료율: done 1, partial 0.5, missed 0 (출처가 inferred·없음이면 뺀다)
        Function<BlockFacts.Fact, List<Obs>> completion = f -> {
            if (!BlockFacts.counted(f.outcomeSrc()) || f.outcome() == null) {
                return List.of();
            }
            return List.of(new Obs("", switch (f.outcome()) {
                case "done" -> 1.0;
                case "partial" -> 0.5;
                default -> 0.0;
            }));
        };
        group(out, "completion_rate", List.of("band", "weekday", "category", "goal", "sleep", "condition"), planned,
                dayMap, completion, Agg.RATE, k);
        group(out, "start_rate", List.of("band", "weekday", "category"), planned, dayMap,
                f -> "pending".equals(f.statusFinal()) ? List.of()
                        : List.of(new Obs("", f.startDelayMin() != null && f.startDelayMin() <= 10 ? 1 : 0)),
                Agg.RATE, k);
        group(out, "drift_minutes_p50", List.of("band", "category"), planned, dayMap,
                f -> f.driftMin() == null || "pending".equals(f.statusFinal()) ? List.of()
                        : List.of(new Obs("", Math.max(0, f.driftMin()))),
                Agg.MEDIAN, k);
        group(out, "estimate_ratio", List.of("category", "goal"), planned, dayMap,
                f -> f.actualMinutes() == null || f.plannedMinutes() == null || f.plannedMinutes() <= 0
                        || !"done".equals(f.statusFinal()) ? List.of()
                        : List.of(new Obs("", f.actualMinutes() / (double) f.plannedMinutes())),
                Agg.MEAN, k);
        group(out, "focus_avg", List.of("band", "category", "sleep", "condition"), plannedFixed, dayMap,
                f -> f.focus() == null || !BlockFacts.counted(f.focusSrc()) ? List.of() : List.of(new Obs("", f.focus())),
                Agg.MEAN, k);
        group(out, "focus_minutes_p50", List.of("category", "band"), planned, dayMap,
                f -> f.actualMinutes() == null || f.actualMinutes() <= 0 ? List.of() : List.of(new Obs("", f.actualMinutes())),
                Agg.MEDIAN, k);
        // 이유 비율: 이유를 남긴 블록 중 그 태그가 있는 비율(태그마다)
        group(out, "reason_share", List.of("all", "category", "band"), plannedFixed, dayMap,
                f -> reasonObs(f, false), Agg.RATE, k);
        group(out, "success_reason_share", List.of("all", "category"), plannedFixed, dayMap,
                f -> reasonObs(f, true), Agg.RATE, k);
        group(out, "attendance_rate", List.of("weekday", "category"), fixed, dayMap, completion, Agg.RATE, k);
        Map<Long, Integer> interventionCount = interventionCounts(from);
        group(out, "snooze_rate", List.of("band", "category"), planned, dayMap,
                f -> interventionCount.getOrDefault(f.blockId(), 0) == 0 ? List.of()
                        : List.of(new Obs("", f.snoozeCount() > 0 ? 1 : 0)),
                Agg.RATE, k);
        // v4 §6: 딴짓 비율(딴짓 분 ÷ 실제 블록 분), 앱별 비율, L1 뒤 5분 안에 돌아온 비율
        Map<Long, Integer> dmin = distractionMinutes(from);
        group(out, "distraction_rate", List.of("band", "category", "sleep", "condition"), planned, dayMap,
                f -> f.actualMinutes() == null || f.actualMinutes() <= 0 ? List.of()
                        : List.of(new Obs("", Math.min(1.0, dmin.getOrDefault(f.blockId(), 0) / (double) f.actualMinutes()))),
                Agg.MEAN, k);
        distractionShares(out, from);
        tacticStats(out, plannedFixed, k);
        latencyStats(out, from, k);
        travelStats(out, travel, k);
        paceStats(out, planned, today);
        stallStats(out, plannedFixed);

        markNotable(out);
        save(out);
        return out;
    }

    private static List<Obs> reasonObs(BlockFacts.Fact f, boolean success) {
        if (!BlockFacts.counted(f.reasonSrc()) || f.outcome() == null) {
            return List.of();
        }
        boolean done = "done".equals(f.outcome());
        if (done != success) {
            return List.of();
        }
        List<String> tags = f.reasons();
        if (tags.isEmpty()) {
            return List.of();
        }
        Map<String, String> universe = success ? BlockFacts.DONE_REASONS : BlockFacts.NOT_DONE_REASONS;
        List<Obs> obs = new ArrayList<>();
        for (String tag : universe.keySet()) {
            obs.add(new Obs(tag, tags.contains(tag) ? 1 : 0));
        }
        return obs;
    }

    enum Agg { RATE, MEAN, MEDIAN }

    private void group(List<Stat> out, String metric, List<String> dims, List<BlockFacts.Fact> rows,
                       Map<LocalDate, DayFacts.Day> dayMap, Function<BlockFacts.Fact, List<Obs>> obs, Agg agg, int k) {
        // 전체값(baseline)은 sub(이유 태그)마다
        Map<String, List<Double>> base = new LinkedHashMap<>();
        Map<String, Map<String, List<Double>>> byDim = new LinkedHashMap<>();
        for (BlockFacts.Fact f : rows) {
            List<Obs> list = obs.apply(f);
            if (list.isEmpty()) {
                continue;
            }
            for (Obs o : list) {
                base.computeIfAbsent(o.sub(), x -> new ArrayList<>()).add(o.v());
            }
            for (String dim : dims) {
                String dk = dimKey(f, dim, dayMap);
                if (dk == null) {
                    continue;
                }
                for (Obs o : list) {
                    String key = o.sub().isEmpty() ? dk : ("all".equals(dim) ? o.sub() : dk + "|" + o.sub());
                    byDim.computeIfAbsent(dim, x -> new LinkedHashMap<>()).computeIfAbsent(key, x -> new ArrayList<>())
                            .add(o.v());
                }
            }
        }
        for (String dim : dims) {
            Map<String, List<Double>> groups = byDim.getOrDefault(dim, Map.of());
            for (Map.Entry<String, List<Double>> e : groups.entrySet()) {
                String sub = e.getKey().contains("|") ? e.getKey().substring(e.getKey().indexOf('|') + 1)
                        : "all".equals(dim) ? e.getKey() : "";
                List<Double> baseValues = base.getOrDefault(sub, List.of());
                Double baseline = aggregate(baseValues, agg);
                Double raw = aggregate(e.getValue(), agg);
                int n = e.getValue().size();
                out.add(new Stat(metric, dim, e.getKey(), smooth(raw, n, baseline, k), raw, n, baseline, false, null));
            }
        }
    }

    static Double smooth(Double raw, int n, Double baseline, int k) {
        if (raw == null) {
            return null;
        }
        if (baseline == null) {
            return raw;
        }
        // 비율도 (성공수 + k·baseline) ÷ (n + k) = (n·raw + k·baseline) ÷ (n + k) 로 같다.
        return (n * raw + k * baseline) / (n + k);
    }

    static Double aggregate(List<Double> values, Agg agg) {
        if (values.isEmpty()) {
            return null;
        }
        if (agg == Agg.MEDIAN) {
            List<Double> s = new ArrayList<>(values);
            Collections.sort(s);
            int m = s.size() / 2;
            return s.size() % 2 == 1 ? s.get(m) : (s.get(m - 1) + s.get(m)) / 2;
        }
        double sum = 0;
        for (double v : values) {
            sum += v;
        }
        return sum / values.size();
    }

    private String dimKey(BlockFacts.Fact f, String dim, Map<LocalDate, DayFacts.Day> dayMap) {
        return switch (dim) {
            case "all" -> "all";
            case "band" -> f.band();
            case "weekday" -> f.weekday() == null ? null : String.valueOf(f.weekday());
            case "category" -> f.category();
            case "goal" -> f.goalId() == null ? null : String.valueOf(f.goalId());
            case "sleep" -> {
                DayFacts.Day d = dayMap.get(f.logicalDay());
                yield d == null || d.sleep() == null || !BlockFacts.counted(d.sleepSrc()) ? null : String.valueOf(d.sleep());
            }
            case "condition" -> {
                DayFacts.Day d = dayMap.get(f.logicalDay());
                yield d == null || d.condition() == null || !BlockFacts.counted(d.conditionSrc()) ? null
                        : String.valueOf(d.condition());
            }
            default -> null;
        };
    }

    private Map<Long, Integer> distractionMinutes(LocalDate from) {
        Map<Long, Integer> m = new HashMap<>();
        db.jdbc().sql("""
                        SELECT block_id, SUM(minutes) FROM distraction_logs WHERE logical_day >= ? AND judged = 'distract'
                        AND record_only = FALSE GROUP BY block_id""")
                .param(from).query((rs, n) -> m.put(rs.getLong(1), rs.getInt(2))).list();
        return m;
    }

    private void distractionShares(List<Stat> out, LocalDate from) {
        Map<String, Integer> byApp = new LinkedHashMap<>();
        db.jdbc().sql("""
                        SELECT app_label, SUM(minutes) FROM distraction_logs WHERE logical_day >= ? AND judged = 'distract'
                        AND record_only = FALSE GROUP BY app_label""")
                .param(from).query((rs, n) -> byApp.put(String.valueOf(rs.getString(1)), rs.getInt(2))).list();
        int total = byApp.values().stream().mapToInt(Integer::intValue).sum();
        if (total > 0) {
            byApp.forEach((app, m) -> out.add(new Stat("distraction_app_share", "app", app, m / (double) total,
                    m / (double) total, m, null, false, null)));
        }
        Instant since = from.atStartOfDay(time.zone()).toInstant();
        int[] r = db.jdbc().sql("""
                        SELECT COUNT(*), COALESCE(SUM(CASE WHEN end_reason = 'returned'
                        AND DATEDIFF('SECOND', started_at, ended_at) <= 300 THEN 1 ELSE 0 END), 0)
                        FROM interventions WHERE reason = 'distraction' AND started_at >= ? AND ended_at IS NOT NULL""")
                .param(AssistantDb.ts(since)).query((rs, n) -> new int[]{rs.getInt(1), rs.getInt(2)}).single();
        if (r[0] > 0) {
            out.add(new Stat("return_rate", "all", "all", r[1] / (double) r[0], r[1] / (double) r[0], r[0], null, false, null));
        }
    }

    private Map<Long, Integer> interventionCounts(LocalDate from) {
        Map<Long, Integer> m = new HashMap<>();
        db.jdbc().sql("SELECT block_id, COUNT(*) FROM interventions WHERE started_at >= ? GROUP BY block_id")
                .param(AssistantDb.ts(from.atStartOfDay(time.zone()).toInstant()))
                .query((rs, n) -> m.put(rs.getLong(1), rs.getInt(2))).list();
        return m;
    }

    /** 설득 방식 성공률: 전체(tactic)·미룬 이유별(reason_tag, key = tactic|이유). */
    private void tacticStats(List<Stat> out, List<BlockFacts.Fact> rows, int k) {
        Map<String, List<Double>> byTactic = new LinkedHashMap<>();
        Map<String, List<Double>> byReason = new LinkedHashMap<>();
        List<Double> all = new ArrayList<>();
        for (BlockFacts.Fact f : rows) {
            JsonNode arr = Json.parse(f.tactics() == null ? "[]" : f.tactics());
            for (JsonNode t : arr) {
                String result = t.path("result").asString("pending");
                if ("pending".equals(result)) {
                    continue;
                }
                double v = "fail".equals(result) ? 0 : 1;
                String tactic = t.path("tactic").asString("other");
                all.add(v);
                byTactic.computeIfAbsent(tactic, x -> new ArrayList<>()).add(v);
                String reason = t.hasNonNull("reason") ? t.get("reason").asString()
                        : BlockFacts.counted(f.reasonSrc()) && !f.reasons().isEmpty() ? f.reasons().get(0) : null;
                if (reason != null) {
                    byReason.computeIfAbsent(tactic + "|" + reason, x -> new ArrayList<>()).add(v);
                }
            }
        }
        Double baseline = aggregate(all, Agg.RATE);
        byTactic.forEach((key, v) -> out.add(new Stat("tactic_success_rate", "tactic", key,
                smooth(aggregate(v, Agg.RATE), v.size(), baseline, k), aggregate(v, Agg.RATE), v.size(), baseline, false, null)));
        byReason.forEach((key, v) -> out.add(new Stat("tactic_success_rate", "reason_tag", key,
                smooth(aggregate(v, Agg.RATE), v.size(), baseline, k), aggregate(v, Agg.RATE), v.size(), baseline, false, null)));
    }

    /** 개입 후 반응(시작·미루기)까지 걸린 분의 중앙값. */
    private void latencyStats(List<Stat> out, LocalDate from, int k) {
        Map<String, List<Double>> byLevel = new LinkedHashMap<>();
        Map<String, List<Double>> byBand = new LinkedHashMap<>();
        List<Double> all = new ArrayList<>();
        db.jdbc().sql("""
                        SELECT level, started_at, ended_at FROM interventions WHERE started_at >= ? AND ended_at IS NOT NULL
                        AND status IN ('acked', 'snoozed')""")
                .param(AssistantDb.ts(from.atStartOfDay(time.zone()).toInstant()))
                .query((rs, n) -> {
                    Instant s = AssistantDb.instant(rs, "started_at");
                    double minutes = Math.max(0, Duration.between(s, AssistantDb.instant(rs, "ended_at")).toSeconds() / 60.0);
                    all.add(minutes);
                    byLevel.computeIfAbsent(String.valueOf(rs.getInt("level")), x -> new ArrayList<>()).add(minutes);
                    byBand.computeIfAbsent(BlockFacts.band(time.local(s).getHour()), x -> new ArrayList<>()).add(minutes);
                    return null;
                }).list();
        Double baseline = aggregate(all, Agg.MEDIAN);
        byLevel.forEach((key, v) -> out.add(new Stat("response_latency_p50", "level", key,
                smooth(aggregate(v, Agg.MEDIAN), v.size(), baseline, k), aggregate(v, Agg.MEDIAN), v.size(), baseline, false, null)));
        byBand.forEach((key, v) -> out.add(new Stat("response_latency_p50", "band", key,
                smooth(aggregate(v, Agg.MEDIAN), v.size(), baseline, k), aggregate(v, Agg.MEDIAN), v.size(), baseline, false, null)));
    }

    /** 실제 ÷ 계산 이동 시간(경로 = 도착지 이름). */
    private void travelStats(List<Stat> out, List<BlockFacts.Fact> rows, int k) {
        Map<String, List<Double>> byRoute = new LinkedHashMap<>();
        List<Double> all = new ArrayList<>();
        for (BlockFacts.Fact f : rows) {
            if (f.actualMinutes() == null || f.plannedMinutes() == null || f.plannedMinutes() <= 0) {
                continue;
            }
            Store.Block t = store.block(f.blockId()).orElse(null);
            if (t == null || t.linkedFixedBlockId() == null) {
                continue;
            }
            String route = store.block(t.linkedFixedBlockId())
                    .map(b -> b.placeName() != null && !b.placeName().isBlank() ? b.placeName()
                            : b.location() == null ? store.blockTitle(b) : b.location())
                    .orElse("?") + ("home".equals(t.travelDirection()) ? "→집" : "");
            double v = f.actualMinutes() / (double) f.plannedMinutes();
            all.add(v);
            byRoute.computeIfAbsent(route.length() > 90 ? route.substring(0, 90) : route, x -> new ArrayList<>()).add(v);
        }
        Double baseline = aggregate(all, Agg.MEAN);
        byRoute.forEach((key, v) -> out.add(new Stat("travel_delay_ratio", "route", key,
                smooth(aggregate(v, Agg.MEAN), v.size(), baseline, k), aggregate(v, Agg.MEAN), v.size(), baseline, false, null)));
    }

    /** 목표별 최근 7일 진행 속도 ÷ 필요 속도(남은 분 ÷ 마감까지 날). 마감·남은 분이 없으면 뺀다. */
    private void paceStats(List<Stat> out, List<BlockFacts.Fact> planned, LocalDate today) {
        Instant now = clock.now();
        for (Store.Goal g : store.activeGoals()) {
            if (g.deadline() == null || !g.deadline().isAfter(now)) {
                continue;
            }
            int remaining = db.jdbc().sql("SELECT COALESCE(SUM(remaining_minutes), 0) FROM tasks WHERE goal_id = ? AND status = 'active'")
                    .param(g.id()).query(Integer.class).single();
            if (remaining <= 0) {
                continue;
            }
            double daysLeft = Math.max(1, Duration.between(now, g.deadline()).toHours() / 24.0);
            double needed = remaining / daysLeft;
            List<BlockFacts.Fact> recent = planned.stream()
                    .filter(f -> Long.valueOf(g.id()).equals(f.goalId()) && !f.logicalDay().isBefore(today.minusDays(6))
                            && f.actualMinutes() != null && List.of("done", "partial").contains(f.statusFinal()))
                    .toList();
            double done = recent.stream().mapToInt(BlockFacts.Fact::actualMinutes).sum() / 7.0;
            double ratio = done / needed;
            out.add(new Stat("pace_ratio", "goal", String.valueOf(g.id()), ratio, ratio, recent.size(), 1.0, false, null));
        }
    }

    /** 막힘(stuck)이 2번 이상인 단계(할 일). key = 목표id:할일id, value = 막힘 횟수. */
    private void stallStats(List<Stat> out, List<BlockFacts.Fact> rows) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (BlockFacts.Fact f : rows) {
            if (f.goalId() == null || f.taskId() == null || !BlockFacts.counted(f.reasonSrc()) || !f.reasons().contains("stuck")) {
                continue;
            }
            counts.merge(f.goalId() + ":" + f.taskId(), 1, Integer::sum);
        }
        counts.forEach((key, c) -> {
            if (c >= 2) {
                out.add(new Stat("stall_steps", "goal", key, (double) c, (double) c, c, null, false, null));
            }
        });
    }

    private void markNotable(List<Stat> out) {
        int min = settings.getInt("memory.min_samples");
        int max = settings.getInt("memory.highlight_max");
        List<Integer> candidates = new ArrayList<>();
        Double[] scores = new Double[out.size()];
        for (int i = 0; i < out.size(); i++) {
            Stat s = out.get(i);
            if (NOT_HIGHLIGHTED.contains(s.metric()) || "all".equals(s.dimension()) || s.value() == null
                    || s.baseline() == null || s.n() < min) {
                continue;
            }
            double diff = s.value() - s.baseline();
            double score;
            if (RATE_METRICS.contains(s.metric())) {
                if (Math.abs(diff) < 0.15) {
                    continue;
                }
                score = Math.abs(diff) * Math.sqrt(s.n());
            } else {
                if (s.baseline() == 0 || Math.abs(diff / s.baseline()) < 0.2) {
                    continue;
                }
                score = Math.abs(diff / s.baseline()) * Math.sqrt(s.n());
            }
            scores[i] = score;
            candidates.add(i);
        }
        candidates.sort((a, b) -> Double.compare(scores[b], scores[a]));
        Set<Integer> top = new java.util.HashSet<>(candidates.subList(0, Math.min(max, candidates.size())));
        for (int i = 0; i < out.size(); i++) {
            Stat s = out.get(i);
            out.set(i, new Stat(s.metric(), s.dimension(), s.key(), s.value(), s.raw(), s.n(), s.baseline(),
                    top.contains(i), scores[i]));
        }
    }

    private void save(List<Stat> out) {
        Instant now = clock.now();
        db.update("DELETE FROM pattern_stats");
        for (Stat s : out) {
            db.update("""
                            INSERT INTO pattern_stats(metric, dimension, stat_key, stat_value, raw_value, n, baseline, notable,
                            score, period_days, computed_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    s.metric(), s.dimension(), s.key(), s.value(), s.raw(), s.n(), s.baseline(), s.notable(), s.score(),
                    PERIOD_DAYS, now);
        }
    }

    // ------------------------------------------------------------------ 읽기

    public List<Stat> list(String metric, String dimension, String key) {
        StringBuilder sql = new StringBuilder("SELECT * FROM pattern_stats WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        if (metric != null && !metric.isBlank()) {
            sql.append(" AND metric = ?");
            params.add(metric);
        }
        if (dimension != null && !dimension.isBlank()) {
            sql.append(" AND dimension = ?");
            params.add(dimension);
        }
        if (key != null && !key.isBlank()) {
            sql.append(" AND stat_key = ?");
            params.add(key);
        }
        sql.append(" ORDER BY metric, dimension, stat_key");
        var spec = db.jdbc().sql(sql.toString());
        for (Object p : params) {
            spec = spec.param(p);
        }
        return spec.query(PatternStats::row).list();
    }

    public List<Stat> notable() {
        return db.jdbc().sql("SELECT * FROM pattern_stats WHERE notable = TRUE ORDER BY score DESC")
                .query(PatternStats::row).list();
    }

    public Instant computedAt() {
        return db.jdbc().sql("SELECT MAX(computed_at) AS m FROM pattern_stats")
                .query((rs, n) -> AssistantDb.instant(rs, "m")).optional().orElse(null);
    }

    static Stat row(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Stat(rs.getString("metric"), rs.getString("dimension"), rs.getString("stat_key"),
                rs.getObject("stat_value", Double.class), rs.getObject("raw_value", Double.class), rs.getInt("n"),
                rs.getObject("baseline", Double.class), rs.getBoolean("notable"), rs.getObject("score", Double.class));
    }

    /** 맥락·API 한 줄: "completion_rate band=morning 0.82(n=12, 전체 0.70)". 근거 부족이면 표시. */
    public String line(Stat s) {
        int min = settings.getInt("memory.min_samples");
        String label = dimLabel(s.dimension(), s.key());
        return s.metric() + " " + label + " " + fmt(s.value()) + "(n=" + s.n()
                + (s.baseline() == null ? "" : ", 전체 " + fmt(s.baseline())) + ")"
                + (s.insufficient(min) ? " [근거 부족]" : "");
    }

    static String dimLabel(String dim, String key) {
        return dim + "=" + key;
    }

    public static String fmt(Double v) {
        if (v == null) {
            return "-";
        }
        return String.valueOf(Math.round(v * 100) / 100.0);
    }

    public static Map<String, Object> toMap(Stat s, int minSamples) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("metric", s.metric());
        m.put("dimension", s.dimension());
        m.put("key", s.key());
        m.put("value", s.value());
        m.put("raw_value", s.raw());
        m.put("n", s.n());
        m.put("baseline", s.baseline());
        m.put("notable", s.notable());
        m.put("insufficient", s.insufficient(minSamples));
        return m;
    }
}
