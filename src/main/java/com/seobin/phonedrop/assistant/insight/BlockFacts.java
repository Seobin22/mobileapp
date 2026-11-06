package com.seobin.phonedrop.assistant.insight;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.seobin.phonedrop.assistant.AssistantClock;
import com.seobin.phonedrop.assistant.AssistantDb;
import com.seobin.phonedrop.assistant.Json;
import com.seobin.phonedrop.assistant.Store;
import com.seobin.phonedrop.assistant.TimeUtil;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 블록 하나당 분석용 한 줄(빌드문서 v3.5 §2.1). 코드 칸은 블록·기록에서 언제든 다시 계산할 수 있게 만들었다
 * (블록을 바꾸는 경로마다 따로 부르지 않고, plan.sync·카드·통계·API 를 읽기 전에 refresh 한다).
 * 사람 칸(결과·진도·집중·이유·서술)은 출처 우선순위(user > text > status > inferred)로만 바뀐다.
 */
@Component
public class BlockFacts {

    private static final Logger log = LoggerFactory.getLogger(BlockFacts.class);

    /** 출처 우선순위. status·inferred 는 블록 상태를 그대로 옮긴 값이라 블록이 바뀌면 다시 계산한다. */
    public static int rank(String src) {
        if (src == null) {
            return 0;
        }
        return switch (src) {
            case "user" -> 4;
            case "text" -> 3;
            case "status" -> 2;
            case "inferred" -> 1;
            default -> 0;
        };
    }

    /** 통계에 넣는 출처(§2.1 표). */
    public static boolean counted(String src) {
        return rank(src) >= 2;
    }

    public static final List<String> TACTICS = List.of("micro_step", "reason_reminder", "deadline_fact", "time_commit",
            "streak", "empathy", "reschedule", "other");

    /** 이유 칩(§3.3). 코드 값 → 화면 글. */
    public static final Map<String, String> DONE_REASONS = orderedMap(
            "good_condition", "컨디션 좋음", "deadline", "마감 압박", "fun", "재밌음", "good_env", "환경 좋음", "easy", "쉬운 일");
    public static final Map<String, String> NOT_DONE_REASONS = orderedMap(
            "tired", "피곤", "stuck", "막힘", "other_task", "다른 일 생김", "unwilling", "하기 싫음", "forgot", "잊음",
            "no_time", "시간 부족", "unwell", "몸이 안 좋음");

    private static Map<String, String> orderedMap(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return java.util.Collections.unmodifiableMap(m);
    }

    public static boolean validReason(String tag) {
        return DONE_REASONS.containsKey(tag) || NOT_DONE_REASONS.containsKey(tag);
    }

    public record Fact(long blockId, LocalDate logicalDay, String kind, String category, Long goalId, Long taskId,
                       Instant plannedStartFirst, Instant plannedStartLast, Integer plannedMinutes, Instant actualStart,
                       Instant actualEnd, Integer actualMinutes, Integer startDelayMin, Integer driftMin, int moveCount,
                       int snoozeCount, String band, Integer weekday, String tactics, String statusFinal,
                       String outcome, String outcomeSrc, Integer progressDone, String progressSrc, Integer focus,
                       String focusSrc, String reasonTags, String reasonSrc, String outcomeNote, String focusNote,
                       String reasonNote, String extraNote, String cardState, Long cardId) {

        public List<String> reasons() {
            if (reasonTags == null || reasonTags.isBlank()) {
                return List.of();
            }
            List<String> out = new ArrayList<>();
            Json.parse(reasonTags).forEach(n -> out.add(n.asString()));
            return out;
        }

        public boolean ended(Instant now, Instant end) {
            return !"pending".equals(statusFinal) || !now.isBefore(end);
        }
    }

    private final AssistantDb db;
    private final AssistantClock clock;
    private final Store store;
    private final TimeUtil time;
    private final Categories categories;

    public BlockFacts(AssistantDb db, AssistantClock clock, Store store, TimeUtil time, Categories categories) {
        this.db = db;
        this.clock = clock;
        this.store = store;
        this.time = time;
        this.categories = categories;
    }

    // ------------------------------------------------------------------ 코드 칸

    public static String band(int hour) {
        if (hour < 6) {
            return "dawn";
        }
        if (hour < 12) {
            return "morning";
        }
        if (hour < 18) {
            return "afternoon";
        }
        return hour < 22 ? "evening" : "night";
    }

    public static String statusFinal(String status) {
        return switch (status) {
            case "done", "partial", "missed", "cancelled" -> status;
            default -> "pending";
        };
    }

    /** 근처 블록(어제~내일)만 다시 계산한다. 실패해도 호출한 쪽 작업은 계속한다. */
    public void refreshNear() {
        Instant now = clock.now();
        safeRefreshBetween(now.minus(Duration.ofDays(1)), now.plus(Duration.ofDays(1)));
    }

    public void safeRefreshBetween(Instant from, Instant to) {
        try {
            refreshBetween(from, to);
        } catch (RuntimeException e) {
            log.warn("block_facts refresh failed", e);
        }
    }

    public void refreshBetween(Instant from, Instant to) {
        List<Long> ids = db.jdbc().sql("SELECT id FROM blocks WHERE start_at >= ? AND start_at < ? ORDER BY id")
                .param(AssistantDb.ts(from)).param(AssistantDb.ts(to)).query(Long.class).list();
        refresh(ids);
    }

    /** 이 논리일의 블록. */
    public void refreshDay(LocalDate day) {
        // 논리일은 기상~다음 기상. 넉넉히 읽고 logicalDay 로 거른다.
        Instant from = day.atTime(LocalTime.MIN).atZone(time.zone()).toInstant();
        List<Long> ids = db.jdbc().sql("SELECT id, start_at FROM blocks WHERE start_at >= ? AND start_at < ? ORDER BY id")
                .param(AssistantDb.ts(from)).param(AssistantDb.ts(from.plus(Duration.ofHours(48))))
                .query((rs, n) -> time.logicalDay(AssistantDb.instant(rs, "start_at")).equals(day) ? rs.getLong(1) : null)
                .list().stream().filter(java.util.Objects::nonNull).toList();
        refresh(ids);
    }

    public void refresh(Collection<Long> blockIds) {
        for (long id : blockIds) {
            refresh(id);
        }
    }

    public void refresh(long blockId) {
        Store.Block b = store.block(blockId).orElse(null);
        if (b == null) {
            return;
        }
        Fact old = get(blockId).orElse(null);
        Instant now = clock.now();
        String kind = b.travel() ? "travel" : b.kind();
        LocalDate day = time.logicalDay(b.start());
        String category = categories.effective(blockId);
        Long goalId = b.goalId() != null ? b.goalId()
                : b.taskId() == null ? null : store.task(b.taskId()).map(Store.Task::goalId).orElse(null);
        Instant first = old != null ? old.plannedStartFirst() : firstPlannedStart(b);
        Integer plannedMinutes = (int) Duration.between(b.start(), b.end()).toMinutes();
        Integer actualMinutes = b.actualStart() != null && b.actualEnd() != null
                ? (int) Math.max(0, Duration.between(b.actualStart(), b.actualEnd()).toMinutes()) : null;
        Integer delay = b.actualStart() == null ? null : (int) Duration.between(b.start(), b.actualStart()).toMinutes();
        Integer drift = first == null ? null : (int) Duration.between(first, b.start()).toMinutes();
        int moves = db.jdbc().sql("""
                        SELECT COUNT(*) FROM block_logs WHERE block_id = ? AND type = 'moved' AND content NOT LIKE '끝%'""")
                .param(blockId).query(Integer.class).single() + carriedMoves(blockId);
        int snoozes = db.jdbc().sql("SELECT COUNT(*) FROM interventions WHERE block_id = ? AND status = 'snoozed'")
                .param(blockId).query(Integer.class).single();
        ZonedDateTime local = time.local(b.start());
        String statusFinal = statusFinal(b.status());
        String tactics = evaluateTactics(old == null ? "[]" : old.tactics(), b, now);

        // 사람 칸 중 결과는 출처가 status·inferred·없음이면 블록 상태에서 다시 옮긴다(되돌리기·상태 변경 반영).
        String outcome = old == null ? null : old.outcome();
        String outcomeSrc = old == null ? null : old.outcomeSrc();
        if (rank(outcomeSrc) <= 2 && !"cancelled".equals(statusFinal)) {
            if (List.of("done", "partial").contains(statusFinal) && !b.travel()) {
                outcome = statusFinal;
                outcomeSrc = "status";
            } else if ("missed".equals(statusFinal) || b.travel() && !"pending".equals(statusFinal)) {
                outcome = statusFinal;
                outcomeSrc = "inferred";
            } else {
                outcome = null;
                outcomeSrc = null;
            }
        }

        if (old == null) {
            String cardState = day.isBefore(time.logicalDay(now)) && backfilling ? "expired" : "none";
            db.update("""
                            INSERT INTO block_facts(block_id, logical_day, kind, category, goal_id, task_id, planned_start_first,
                            planned_start_last, planned_minutes, actual_start, actual_end, actual_minutes, start_delay_min,
                            drift_min, move_count, snooze_count, band, weekday, tactics, status_final, outcome, outcome_src,
                            card_state, created_at, updated_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    blockId, day, kind, category, goalId, b.taskId(), first, b.start(), plannedMinutes,
                    b.actualStart(), b.actualEnd(), actualMinutes, delay, drift, moves, snoozes,
                    band(local.getHour()), day.getDayOfWeek().getValue(), tactics, statusFinal, outcome, outcomeSrc,
                    cardState, now, now);
            return;
        }
        db.update("""
                        UPDATE block_facts SET logical_day = ?, kind = ?, category = ?, goal_id = ?, task_id = ?,
                        planned_start_last = ?, planned_minutes = ?, actual_start = ?, actual_end = ?, actual_minutes = ?,
                        start_delay_min = ?, drift_min = ?, move_count = ?, snooze_count = ?, band = ?, weekday = ?,
                        tactics = ?, status_final = ?, outcome = ?, outcome_src = ?, updated_at = ? WHERE block_id = ?""",
                day, kind, category, goalId, b.taskId(), b.start(), plannedMinutes, b.actualStart(), b.actualEnd(),
                actualMinutes, delay, drift, moves, snoozes,
                band(local.getHour()), day.getDayOfWeek().getValue(), tactics, statusFinal, outcome, outcomeSrc, now,
                blockId);
        com.seobin.phonedrop.assistant.distract.DistractionService.inferFocus(db, blockId);
    }

    /** 다시 계획으로 이어 받은 블록(moved 기록의 carried_moves): 원래 블록이 옮겨진 횟수. */
    private int carriedMoves(long blockId) {
        int sum = 0;
        for (String data : db.jdbc().sql("""
                        SELECT data FROM block_logs WHERE block_id = ? AND type = 'moved' AND data LIKE '%"carried_moves"%'""")
                .param(blockId).query(String.class).list()) {
            sum += Json.parse(data).path("carried_moves").asInt(0);
        }
        return sum;
    }

    /** 처음 예정 시작: 첫 이동 기록의 원래 시각. 이동한 적이 없으면 지금 시작. 이동했는데 원래 시각을 못 읽으면 null. */
    Instant firstPlannedStart(Store.Block b) {
        record Moved(String content, String data) {
        }
        List<Moved> moved = db.jdbc().sql("""
                        SELECT content, data FROM block_logs WHERE block_id = ? AND type = 'moved' AND content NOT LIKE '끝%'
                        ORDER BY at, id""")
                .param(b.id()).query((rs, n) -> new Moved(rs.getString(1), rs.getString(2))).list();
        if (moved.isEmpty()) {
            return b.start();
        }
        Moved m = moved.get(0);
        if (m.data() != null) {
            JsonNode d = Json.parse(m.data());
            if (d.hasNonNull("from_start")) {
                try {
                    return TimeUtil.parse(d.get("from_start").asString());
                } catch (RuntimeException ignored) {
                    // 아래 글자 읽기로
                }
            }
        }
        // 예전 기록: "HH:mm→HH:mm ..." 또는 "이동 블록 재계산: HH:mm–HH:mm → ..."
        java.util.regex.Matcher mt = java.util.regex.Pattern.compile("(\\d{1,2}):(\\d{2})").matcher(m.content());
        if (mt.find()) {
            LocalDate date = time.local(b.start()).toLocalDate();
            return date.atTime(Integer.parseInt(mt.group(1)), Integer.parseInt(mt.group(2))).atZone(time.zone()).toInstant();
        }
        return null;
    }

    /**
     * §6 설득 성공 판정: 설득 후 15분 안에 시작 = success, 새로 정한 시각 +10분 안에 시작 = success_after_delay,
     * 그 밖(블록이 끝났거나 끝 시각이 지남) = fail. 아직 모르면 pending.
     */
    String evaluateTactics(String json, Store.Block b, Instant now) {
        JsonNode arr = Json.parse(json == null || json.isBlank() ? "[]" : json);
        if (!arr.isArray() || arr.isEmpty()) {
            return "[]";
        }
        ArrayNode out = Json.array();
        for (JsonNode t : arr) {
            ObjectNode o = (ObjectNode) t.deepCopy();
            Instant at = TimeUtil.parse(o.path("at").asString());
            String result;
            Instant started = b.actualStart();
            if (started != null && !started.isBefore(at.minusSeconds(60)) && !started.isAfter(at.plus(Duration.ofMinutes(15)))) {
                result = "success";
            } else if (started != null && b.start().isAfter(at) && !started.isAfter(b.start().plus(Duration.ofMinutes(10)))
                    && !started.isBefore(at)) {
                result = "success_after_delay";
            } else if (!"pending".equals(statusFinal(b.status())) || !now.isBefore(b.end())
                    || started != null) {
                result = "fail";
            } else {
                result = "pending";
            }
            o.put("result", result);
            out.add(o);
        }
        return Json.write(out);
    }

    /** §6: 비서가 설득에 쓴 방식을 더한다(add_block_log data.tactic). */
    public void addTactic(long blockId, String tactic, String reasonTag) {
        refresh(blockId);
        Fact f = get(blockId).orElse(null);
        if (f == null) {
            return;
        }
        ArrayNode arr = (ArrayNode) Json.parse(f.tactics() == null ? "[]" : f.tactics());
        ObjectNode o = arr.addObject();
        o.put("tactic", TACTICS.contains(tactic) ? tactic : "other");
        o.put("at", time.iso(clock.now()));
        if (reasonTag != null && validReason(reasonTag)) {
            o.put("reason", reasonTag);
        }
        o.put("result", "pending");
        db.update("UPDATE block_facts SET tactics = ?, updated_at = ? WHERE block_id = ?", Json.write(arr), clock.now(),
                blockId);
    }

    // ------------------------------------------------------------------ 사람 칸

    /** 사람 칸 하나를 출처 우선순위대로 쓴다. 쓰였으면 true. field: outcome|progress|focus|reason. */
    public boolean setHuman(long blockId, String field, Object value, String src) {
        Fact f = get(blockId).orElse(null);
        if (f == null) {
            refresh(blockId);
            f = get(blockId).orElse(null);
            if (f == null) {
                return false;
            }
        }
        String current = switch (field) {
            case "outcome" -> f.outcomeSrc();
            case "progress" -> f.progressSrc();
            case "focus" -> f.focusSrc();
            case "reason" -> f.reasonSrc();
            default -> throw new IllegalArgumentException(field);
        };
        boolean allowed = "user".equals(src) ? true : rank(src) >= rank(current) && !"user".equals(current);
        if (!allowed) {
            return false;
        }
        String column = switch (field) {
            case "outcome" -> "outcome";
            case "progress" -> "progress_done";
            case "focus" -> "focus";
            default -> "reason_tags";
        };
        String srcColumn = switch (field) {
            case "outcome" -> "outcome_src";
            case "progress" -> "progress_src";
            case "focus" -> "focus_src";
            default -> "reason_src";
        };
        Object v = value instanceof List<?> list ? Json.write(list) : value;
        db.update("UPDATE block_facts SET " + column + " = ?, " + srcColumn + " = ?, updated_at = ? WHERE block_id = ?",
                v, src, clock.now(), blockId);
        return true;
    }

    /** 서술 칸(outcome_note·focus_note·reason_note·extra_note). 빈 글은 무시. */
    public void setNote(long blockId, String column, String text) {
        if (!Set.of("outcome_note", "focus_note", "reason_note", "extra_note").contains(column) || text == null
                || text.isBlank()) {
            return;
        }
        String t = text.strip();
        db.update("UPDATE block_facts SET " + column + " = ?, updated_at = ? WHERE block_id = ?",
                t.length() > 1000 ? t.substring(0, 1000) : t, clock.now(), blockId);
    }

    public void setCardState(Collection<Long> blockIds, String state, Long cardId) {
        for (long id : blockIds) {
            db.update("UPDATE block_facts SET card_state = ?, card_id = ?, updated_at = ? WHERE block_id = ?",
                    state, cardId, clock.now(), id);
        }
    }

    // ------------------------------------------------------------------ 읽기

    /** 블록의 분류(물려받기 포함). */
    public String category(long blockId) {
        return categories.effective(blockId);
    }

    public java.util.Optional<Fact> get(long blockId) {
        return db.jdbc().sql("SELECT * FROM block_facts WHERE block_id = ?").param(blockId).query(BlockFacts::fact)
                .optional();
    }

    public List<Fact> between(LocalDate fromDay, LocalDate toDayInclusive) {
        return db.jdbc().sql("SELECT * FROM block_facts WHERE logical_day >= ? AND logical_day <= ? ORDER BY planned_start_last, block_id")
                .param(fromDay).param(toDayInclusive).query(BlockFacts::fact).list();
    }

    public List<Fact> ofDay(LocalDate day) {
        return between(day, day);
    }

    static Fact fact(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Fact(rs.getLong("block_id"), rs.getObject("logical_day", LocalDate.class), rs.getString("kind"),
                rs.getString("category"), AssistantDb.nullableLong(rs, "goal_id"), AssistantDb.nullableLong(rs, "task_id"),
                AssistantDb.instant(rs, "planned_start_first"), AssistantDb.instant(rs, "planned_start_last"),
                AssistantDb.nullableInt(rs, "planned_minutes"), AssistantDb.instant(rs, "actual_start"),
                AssistantDb.instant(rs, "actual_end"), AssistantDb.nullableInt(rs, "actual_minutes"),
                AssistantDb.nullableInt(rs, "start_delay_min"), AssistantDb.nullableInt(rs, "drift_min"),
                rs.getInt("move_count"), rs.getInt("snooze_count"), rs.getString("band"),
                AssistantDb.nullableInt(rs, "weekday"), rs.getString("tactics"), rs.getString("status_final"),
                rs.getString("outcome"), rs.getString("outcome_src"), AssistantDb.nullableInt(rs, "progress_done"),
                rs.getString("progress_src"), AssistantDb.nullableInt(rs, "focus"), rs.getString("focus_src"),
                rs.getString("reason_tags"), rs.getString("reason_src"), rs.getString("outcome_note"),
                rs.getString("focus_note"), rs.getString("reason_note"), rs.getString("extra_note"),
                rs.getString("card_state"), AssistantDb.nullableLong(rs, "card_id"));
    }

    /** API·화면용. */
    public Map<String, Object> toMap(Fact f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("block_id", f.blockId());
        m.put("logical_day", f.logicalDay().toString());
        m.put("kind", f.kind());
        m.put("category", f.category());
        m.put("title", store.block(f.blockId()).map(store::blockTitle).orElse(""));
        m.put("goal_id", f.goalId());
        m.put("planned_start", time.iso(f.plannedStartLast()));
        m.put("planned_start_first", time.iso(f.plannedStartFirst()));
        m.put("planned_minutes", f.plannedMinutes());
        m.put("actual_start", time.iso(f.actualStart()));
        m.put("actual_end", time.iso(f.actualEnd()));
        m.put("actual_minutes", f.actualMinutes());
        m.put("start_delay_min", f.startDelayMin());
        m.put("drift_min", f.driftMin());
        m.put("move_count", f.moveCount());
        m.put("snooze_count", f.snoozeCount());
        m.put("band", f.band());
        m.put("status", f.statusFinal());
        m.put("outcome", f.outcome());
        m.put("outcome_src", f.outcomeSrc());
        m.put("progress_done", f.progressDone());
        m.put("focus", f.focus());
        m.put("focus_src", f.focusSrc());
        m.put("reason_tags", f.reasons());
        m.put("reason_src", f.reasonSrc());
        m.put("outcome_note", f.outcomeNote());
        m.put("focus_note", f.focusNote());
        m.put("reason_note", f.reasonNote());
        m.put("extra_note", f.extraNote());
        m.put("card_state", f.cardState());
        return m;
    }

    // ------------------------------------------------------------------ 기존 블록 채우기(한 번)

    private boolean backfilling;

    /** 마이그레이션 뒤 한 번: 모든 블록의 줄을 만든다. 다시 불러도 이미 있는 줄은 코드 칸만 다시 계산한다. */
    public int backfill() {
        List<Long> ids = db.jdbc().sql("SELECT id FROM blocks ORDER BY id").query(Long.class).list();
        backfilling = true;
        try {
            refresh(ids);
        } finally {
            backfilling = false;
        }
        return ids.size();
    }
}
