package com.seobin.phonedrop.assistant.insight;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.seobin.phonedrop.assistant.AssistantClock;
import com.seobin.phonedrop.assistant.AssistantDb;
import com.seobin.phonedrop.assistant.SettingsService;
import com.seobin.phonedrop.assistant.Store;
import com.seobin.phonedrop.assistant.TimeUtil;

import tools.jackson.databind.JsonNode;

/**
 * 꺼내 쓰기(빌드문서 v3.5 §9). 모든 turn: 프로필 + guideline(시스템 프롬프트, 캐시). turn 종류마다 필요한 통계·기억만 맥락에 붙인다.
 */
@Component
public class MemoryContext {

    static final Set<String> PLANNING = Set.of("user_message", "morning", "goal_needs_blocks", "blocks_displaced",
            "goal_changed_by_user", "task_changed_by_user", "fixed_block_added", "goal_waiting_check", "document_ready");
    static final Set<String> PERSUADE = Set.of("intervention_talk", "snooze_request", "block_missed", "block_started");
    static final Set<String> CHECKIN = Set.of("block_ended", "pulse", "wakeup", "block_result_by_user");
    static final int ITEMS_MAX = 8;

    private final AssistantDb db;
    private final AssistantClock clock;
    private final TimeUtil time;
    private final SettingsService settings;
    private final Store store;
    private final MemoryStore memory;
    private final PatternStats stats;
    private final DayFacts days;

    public MemoryContext(AssistantDb db, AssistantClock clock, TimeUtil time, SettingsService settings, Store store,
                         MemoryStore memory, PatternStats stats, DayFacts days) {
        this.db = db;
        this.clock = clock;
        this.time = time;
        this.settings = settings;
        this.store = store;
        this.memory = memory;
        this.stats = stats;
        this.days = days;
    }

    /** 시스템 프롬프트에 넣는 부분(모든 turn 공통, 프롬프트 캐시 대상): 프로필 5섹션 + 대하는 법 최대 10개. */
    public String systemSection() {
        StringBuilder sb = new StringBuilder();
        String profile = memory.profileText();
        sb.append("### 프로필\n").append(profile.isBlank() ? legacyMemory() : profile);
        List<MemoryStore.Item> guides = memory.guidelines(10);
        sb.append("### 대하는 법(guideline)\n");
        if (guides.isEmpty()) {
            sb.append("없음\n");
        }
        guides.forEach(g -> sb.append("- #").append(g.id()).append(' ').append(g.content())
                .append(" (확신 ").append(PatternStats.fmt(g.confidence())).append(")\n"));
        return sb.toString();
    }

    /** 기존 memory 이전 전이면 옛 글을 그대로 보여 준다. */
    private String legacyMemory() {
        if (memory.state("memory_migrate_v1") != null) {
            return "(비어 있음)\n";
        }
        String legacy = db.jdbc().sql("SELECT content FROM memory WHERE id = 1").query(String.class).optional().orElse("");
        return legacy.isBlank() ? "(비어 있음)\n" : legacy + "\n";
    }

    /** turn 종류별 맥락(user content 끝 부분). */
    public String section(String trigger, JsonNode ref) {
        StringBuilder sb = new StringBuilder();
        int min = settings.getInt("memory.min_samples");
        LocalDate today = time.logicalDay(clock.now());
        Store.Block block = ref.hasNonNull("block_id") ? store.block(ref.get("block_id").asLong()).orElse(null) : null;
        if (block == null && ref.hasNonNull("intervention_id")) {
            block = store.intervention(ref.get("intervention_id").asLong()).flatMap(i -> store.block(i.blockId())).orElse(null);
        }
        Long goalId = ref.hasNonNull("goal_id") ? Long.valueOf(ref.get("goal_id").asLong()) : null;
        String category = null;
        if (block != null) {
            category = db.jdbc().sql("SELECT category FROM block_facts WHERE block_id = ?").param(block.id())
                    .query(String.class).optional().orElse(null);
            if (goalId == null) {
                goalId = block.goalId() != null ? block.goalId()
                        : block.taskId() == null ? null : store.task(block.taskId()).map(Store.Task::goalId).orElse(null);
            }
        }
        Set<String> cats = new LinkedHashSet<>();
        if (category != null) {
            cats.add(category);
        }

        sb.append("# 사용자 패턴(숫자 근거, n<").append(min).append("은 근거 부족)\n");
        boolean any = false;
        if (PLANNING.contains(trigger)) {
            any |= planning(sb, today, goalId, min);
        }
        if (PERSUADE.contains(trigger) || block != null && "intervention_talk".equals(trigger)) {
            any |= persuasion(sb, block, category, goalId, min);
        }
        if (CHECKIN.contains(trigger)) {
            any |= checkin(sb, goalId, category, min);
        }
        if (block != null && block.travel() || block != null && block.fixed() && store.activeArriveTravel(block.id()).isPresent()) {
            any |= travel(sb, block, min);
        }
        if ("evening".equals(trigger)) {
            any |= evening(sb, today);
        }
        if (!any) {
            sb.append("없음\n");
        }
        sb.append('\n');

        // 사실·일화(분류·목표·최근 사용 순, 최대 8개)
        List<MemoryStore.Item> items = memory.relevant(cats, goalId, ITEMS_MAX);
        sb.append("# 기억(사실·일화)\n");
        if (items.isEmpty()) {
            sb.append("없음\n");
        }
        items.forEach(i -> sb.append("- #").append(i.id()).append(" [").append(i.kind()).append('/').append(i.category())
                .append("] ").append(i.content()).append('\n'));
        memory.markUsed(items);
        if ("morning".equals(trigger)) {
            List<String> checks = memory.openChecks(3);
            if (!checks.isEmpty()) {
                sb.append("# 확인 필요(브리핑에서 한 번만 짧게 물어본다)\n");
                checks.forEach(c -> sb.append("- ").append(c).append('\n'));
            }
        }
        sb.append('\n');
        return sb.toString();
    }

    private boolean planning(StringBuilder sb, LocalDate today, Long goalId, int min) {
        boolean any = false;
        List<MemoryStore.Item> insights = memory.search(null, "insight", null, null, "active", 20).stream()
                .filter(i -> i.goalId() == null || i.goalId().equals(goalId)).limit(6).toList();
        for (MemoryStore.Item i : insights) {
            sb.append("- insight #").append(i.id()).append(' ').append(i.content()).append('\n');
            any = true;
        }
        any |= topBottom(sb, "completion_rate", "band", min);
        any |= topBottom(sb, "focus_avg", "band", min);
        for (PatternStats.Stat s : stats.list("estimate_ratio", null, null)) {
            if (s.n() >= min || goalId != null && "goal".equals(s.dimension()) && s.key().equals(String.valueOf(goalId))) {
                sb.append("- ").append(stats.line(s)).append('\n');
                any = true;
            }
        }
        var d = days.get(today).orElse(null);
        if (d != null && (d.sleep() != null || d.condition() != null)) {
            sb.append("- 오늘 잠 ").append(level(d.sleep(), "못 잠", "보통", "잘 잠")).append(", 컨디션 ")
                    .append(level(d.condition(), "나쁨", "보통", "좋음")).append('\n');
            any = true;
        }
        return any;
    }

    private static String level(Integer v, String a, String b, String c) {
        if (v == null) {
            return "모름";
        }
        return switch (v) {
            case 1 -> a;
            case 2 -> b;
            default -> c;
        };
    }

    /** band 별 값 중 근거가 충분한 상·하위 하나씩. */
    private boolean topBottom(StringBuilder sb, String metric, String dim, int min) {
        List<PatternStats.Stat> list = new ArrayList<>(stats.list(metric, dim, null).stream()
                .filter(s -> s.n() >= min && s.value() != null).toList());
        if (list.isEmpty()) {
            return false;
        }
        list.sort(Comparator.comparingDouble(PatternStats.Stat::value));
        sb.append("- 높음: ").append(stats.line(list.get(list.size() - 1))).append('\n');
        if (list.size() > 1) {
            sb.append("- 낮음: ").append(stats.line(list.get(0))).append('\n');
        }
        return true;
    }

    private boolean persuasion(StringBuilder sb, Store.Block block, String category, Long goalId, int min) {
        boolean any = false;
        String band = block == null ? BlockFacts.band(time.local(clock.now()).getHour())
                : BlockFacts.band(time.local(block.start()).getHour());
        for (PatternStats.Stat s : stats.list("snooze_rate", null, null)) {
            if ("band".equals(s.dimension()) && s.key().equals(band) || "category".equals(s.dimension()) && s.key().equals(category)) {
                sb.append("- ").append(stats.line(s)).append('\n');
                any = true;
            }
        }
        for (PatternStats.Stat s : stats.list("reason_share", "all", null)) {
            if (s.n() > 0 && s.raw() != null && s.raw() > 0) {
                sb.append("- ").append(stats.line(s)).append('\n');
                any = true;
            }
        }
        List<PatternStats.Stat> tactics = new ArrayList<>(stats.list("tactic_success_rate", null, null));
        tactics.sort((a, b) -> Double.compare(b.value() == null ? 0 : b.value(), a.value() == null ? 0 : a.value()));
        for (PatternStats.Stat s : tactics.stream().limit(8).toList()) {
            sb.append("- ").append(stats.line(s)).append('\n');
            any = true;
        }
        List<MemoryStore.Item> episodes = memory.search(null, "episode", null, goalId, "active", 3);
        if (episodes.isEmpty() && category != null) {
            episodes = memory.search(null, "episode", category, null, "active", 3);
        }
        for (MemoryStore.Item e : episodes) {
            sb.append("- 일화 #").append(e.id()).append(' ').append(e.content()).append('\n');
            any = true;
        }
        if (goalId != null) {
            String reason = store.goal(goalId).map(Store.Goal::reason).orElse("");
            if (!reason.isBlank()) {
                sb.append("- 목표 이유: ").append(reason).append('\n');
                any = true;
            }
        }
        return any;
    }

    private boolean checkin(StringBuilder sb, Long goalId, String category, int min) {
        boolean any = false;
        for (PatternStats.Stat s : stats.list("focus_minutes_p50", null, null)) {
            if (s.n() >= min || "category".equals(s.dimension()) && s.key().equals(category)) {
                sb.append("- ").append(stats.line(s)).append('\n');
                any = true;
            }
        }
        if (goalId != null) {
            for (PatternStats.Stat s : stats.list("stall_steps", "goal", null)) {
                if (s.key().startsWith(goalId + ":")) {
                    long taskId = Long.parseLong(s.key().substring(s.key().indexOf(':') + 1));
                    sb.append("- 막힌 단계: ").append(store.task(taskId).map(Store.Task::title).orElse("할일#" + taskId))
                            .append(" (막힘 ").append(s.n()).append("번)\n");
                    any = true;
                }
            }
        }
        return any;
    }

    private boolean travel(StringBuilder sb, Store.Block block, int min) {
        boolean any = false;
        for (PatternStats.Stat s : stats.list("travel_delay_ratio", "route", null)) {
            String name = block.placeName() != null ? block.placeName() : block.location();
            if (name != null && s.key().startsWith(name)) {
                sb.append("- ").append(stats.line(s)).append('\n');
                any = true;
            }
        }
        return any;
    }

    /** 저녁 회고: notable 상위 3개 + 오늘 day_facts. 일요일은 이번 주 notable·목표별 속도도. */
    private boolean evening(StringBuilder sb, LocalDate today) {
        boolean any = false;
        List<PatternStats.Stat> notable = stats.notable();
        boolean sunday = today.getDayOfWeek() == DayOfWeek.SUNDAY;
        for (PatternStats.Stat s : notable.stream().limit(sunday ? 5 : 3).toList()) {
            sb.append("- 눈에 띔: ").append(stats.line(s)).append('\n');
            any = true;
        }
        var d = days.get(today).orElse(null);
        if (d != null) {
            sb.append("- 오늘: 계획 ").append(d.plannedCount()).append("개 중 완료 ").append(d.doneCount()).append(", 일부 ")
                    .append(d.partialCount()).append(", 놓침 ").append(d.missedCount())
                    .append(d.focusAvg() == null ? "" : ", 집중 평균 " + PatternStats.fmt(d.focusAvg()))
                    .append(", 잠 ").append(level(d.sleep(), "못 잠", "보통", "잘 잠")).append(", 컨디션 ")
                    .append(level(d.condition(), "나쁨", "보통", "좋음"))
                    .append(d.dayNote() == null ? "" : ", 한마디: " + d.dayNote()).append('\n');
            any = true;
        }
        if (sunday) {
            sb.append("- [주간 요약] 오늘은 일요일: 이번 주를 짧게 요약하고 다음 주 제안 1~2개를 써 준다.\n");
            for (PatternStats.Stat s : stats.list("pace_ratio", "goal", null)) {
                long id = Long.parseLong(s.key());
                sb.append("- 목표 속도: ").append(store.goal(id).map(Store.Goal::title).orElse("목표#" + id)).append(' ')
                        .append(PatternStats.fmt(s.value())).append("(필요 속도 대비)\n");
            }
            any = true;
        }
        return any;
    }
}
