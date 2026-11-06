package com.seobin.phonedrop.assistant.insight;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.seobin.phonedrop.assistant.AssistantClock;
import com.seobin.phonedrop.assistant.AssistantDb;

/** 하루 한 줄(빌드문서 v3.5 §2.2, logical_day 기준). 잠·컨디션은 아침 카드, 한마디는 저녁 정리 카드에서. */
@Component
public class DayFacts {

    public record Day(LocalDate day, Integer sleep, String sleepSrc, String sleepNote, Integer condition,
                      String conditionSrc, String conditionNote, String dayNote, int plannedCount, int doneCount,
                      int partialCount, int missedCount, Double focusAvg, int totalFocusMinutes, boolean cardSent) {
    }

    private final AssistantDb db;
    private final AssistantClock clock;
    private final BlockFacts facts;

    public DayFacts(AssistantDb db, AssistantClock clock, BlockFacts facts) {
        this.db = db;
        this.clock = clock;
        this.facts = facts;
    }

    public void ensure(LocalDate day) {
        db.update("MERGE INTO day_facts(logical_day) KEY(logical_day) VALUES (?)", day);
    }

    public Optional<Day> get(LocalDate day) {
        return db.jdbc().sql("SELECT * FROM day_facts WHERE logical_day = ?").param(day).query(DayFacts::row).optional();
    }

    public List<Day> between(LocalDate from, LocalDate toInclusive) {
        return db.jdbc().sql("SELECT * FROM day_facts WHERE logical_day >= ? AND logical_day <= ? ORDER BY logical_day")
                .param(from).param(toInclusive).query(DayFacts::row).list();
    }

    /** field: sleep | condition (1~3). 사용자 값은 언제든 바꾼다, 글에서 뽑은 값(text)은 비었을 때만. */
    public boolean setLevel(LocalDate day, String field, int value, String src) {
        if (value < 1 || value > 3) {
            throw new IllegalArgumentException(field + " 는 1~3 입니다");
        }
        ensure(day);
        String column = "sleep".equals(field) ? "sleep" : "day_condition";
        String srcColumn = "sleep".equals(field) ? "sleep_src" : "condition_src";
        String current = db.jdbc().sql("SELECT " + srcColumn + " FROM day_facts WHERE logical_day = ?").param(day)
                .query(String.class).optional().orElse(null);
        if (!"user".equals(src) && (BlockFacts.rank(current) > BlockFacts.rank(src) || "user".equals(current))) {
            return false;
        }
        db.update("UPDATE day_facts SET " + column + " = ?, " + srcColumn + " = ?, updated_at = ? WHERE logical_day = ?",
                value, src, clock.now(), day);
        return true;
    }

    /** column: sleep_note | condition_note | day_note */
    public void setNote(LocalDate day, String column, String text) {
        if (!List.of("sleep_note", "condition_note", "day_note").contains(column) || text == null || text.isBlank()) {
            return;
        }
        ensure(day);
        String t = text.strip();
        db.update("UPDATE day_facts SET " + column + " = ?, updated_at = ? WHERE logical_day = ?",
                t.length() > 1000 ? t.substring(0, 1000) : t, clock.now(), day);
    }

    public void markCardSent(LocalDate day) {
        ensure(day);
        db.update("UPDATE day_facts SET card_sent = TRUE WHERE logical_day = ?", day);
    }

    /** 코드 칸(밤마다): 그날 계획 블록 수·결과·집중. */
    public void computeCounts(LocalDate day) {
        ensure(day);
        int planned = 0;
        int done = 0;
        int partial = 0;
        int missed = 0;
        int focusSum = 0;
        int focusN = 0;
        int focusMinutes = 0;
        for (BlockFacts.Fact f : facts.ofDay(day)) {
            if (!"planned".equals(f.kind()) || "cancelled".equals(f.statusFinal())) {
                continue;
            }
            planned++;
            String outcome = BlockFacts.counted(f.outcomeSrc()) ? f.outcome() : null;
            if ("done".equals(outcome)) {
                done++;
            } else if ("partial".equals(outcome)) {
                partial++;
            } else if ("missed".equals(f.statusFinal())) {
                missed++;
            }
            if (f.focus() != null && BlockFacts.counted(f.focusSrc())) {
                focusSum += f.focus();
                focusN++;
            }
            if (f.actualMinutes() != null && ("done".equals(f.statusFinal()) || "partial".equals(f.statusFinal()))) {
                focusMinutes += f.actualMinutes();
            }
        }
        db.update("""
                        UPDATE day_facts SET planned_count = ?, done_count = ?, partial_count = ?, missed_count = ?, focus_avg = ?,
                        total_focus_minutes = ?, updated_at = ? WHERE logical_day = ?""",
                planned, done, partial, missed, focusN == 0 ? null : focusSum / (double) focusN, focusMinutes, clock.now(),
                day);
    }

    public static Map<String, Object> toMap(Day d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("logical_day", d.day().toString());
        m.put("sleep", d.sleep());
        m.put("sleep_src", d.sleepSrc());
        m.put("sleep_note", d.sleepNote());
        m.put("condition", d.condition());
        m.put("condition_src", d.conditionSrc());
        m.put("condition_note", d.conditionNote());
        m.put("day_note", d.dayNote());
        m.put("planned_count", d.plannedCount());
        m.put("done_count", d.doneCount());
        m.put("partial_count", d.partialCount());
        m.put("missed_count", d.missedCount());
        m.put("focus_avg", d.focusAvg());
        m.put("total_focus_minutes", d.totalFocusMinutes());
        return m;
    }

    static Day row(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Day(rs.getObject("logical_day", LocalDate.class), AssistantDb.nullableInt(rs, "sleep"),
                rs.getString("sleep_src"), rs.getString("sleep_note"), AssistantDb.nullableInt(rs, "day_condition"),
                rs.getString("condition_src"), rs.getString("condition_note"), rs.getString("day_note"),
                rs.getInt("planned_count"), rs.getInt("done_count"), rs.getInt("partial_count"), rs.getInt("missed_count"),
                (Double) rs.getObject("focus_avg", Double.class), rs.getInt("total_focus_minutes"),
                rs.getBoolean("card_sent"));
    }
}
