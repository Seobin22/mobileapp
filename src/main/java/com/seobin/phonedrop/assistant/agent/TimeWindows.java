package com.seobin.phonedrop.assistant.agent;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.seobin.phonedrop.assistant.ApiException;
import com.seobin.phonedrop.assistant.AssistantClock;
import com.seobin.phonedrop.assistant.AssistantDb;
import com.seobin.phonedrop.assistant.Json;
import com.seobin.phonedrop.assistant.SettingsService;
import com.seobin.phonedrop.assistant.Store;
import com.seobin.phonedrop.assistant.TimeUtil;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;

/**
 * 방해 금지(dnd)·적극 개입(active) 시간대(빌드문서 v3.6 §1·§2). 사용자만 설정 화면에서 바꾼다(비서 도구에는 쓰는 도구가 없다).
 * 이 클래스는 계산만 한다. 알람 반복·먼저 말 걸기·묶음 메시지는 TimeWindowRunner.
 */
@Component
public class TimeWindows {

    public static final List<String> KINDS = List.of("dnd", "active");
    public static final String PAUSED_UNTIL = "active.paused_until";
    /** 저장할 때 겹침을 이 기간 동안 검사한다. */
    static final int OVERLAP_CHECK_DAYS = 14;

    public record Window(long id, String kind, String name, List<Integer> days, String startTime, String endType,
                         String endTime, String fallbackTime, boolean enabled) {

        public boolean firstBlock() {
            return "first_block".equals(endType);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("kind", kind);
            m.put("name", name);
            m.put("days", days);
            m.put("start_time", startTime);
            m.put("end_type", endType);
            m.put("end_time", endTime);
            m.put("end_fallback_time", fallbackTime);
            m.put("enabled", enabled);
            return m;
        }
    }

    /** 시간대 한 번 열림. firstBlock 이면 end 는 그날 첫 블록의 첫 알람 시각(없으면 대체 시각). */
    public record Occ(long windowId, String kind, String name, Instant start, Instant end, boolean firstBlock,
                      boolean fallback) {

        public boolean contains(Instant t) {
            return !t.isBefore(start) && t.isBefore(end);
        }

        public boolean dnd() {
            return "dnd".equals(kind);
        }
    }

    private final AssistantDb db;
    private final AssistantClock clock;
    private final SettingsService settings;
    private final TimeUtil time;

    public TimeWindows(AssistantDb db, AssistantClock clock, SettingsService settings, TimeUtil time) {
        this.db = db;
        this.clock = clock;
        this.settings = settings;
        this.time = time;
    }

    // ------------------------------------------------------------------ 읽기·저장(설정 화면 전용)

    public List<Window> list() {
        return db.jdbc().sql("SELECT * FROM time_windows ORDER BY kind, start_time, id")
                .query((rs, n) -> {
                    List<Integer> days = new ArrayList<>();
                    for (JsonNode d : Json.parse(rs.getString("days"))) {
                        days.add(d.asInt());
                    }
                    return new Window(rs.getLong("id"), rs.getString("kind"), rs.getString("name"), days,
                            rs.getString("start_time"), rs.getString("end_type"), rs.getString("end_time"),
                            rs.getString("end_fallback_time"), rs.getBoolean("enabled"));
                })
                .list();
    }

    /**
     * 목록 전체를 바꾼다(PUT /v1/settings/time-windows). id 가 있으면 그 줄을 고치고, 없으면 새로 만들고, 목록에 없는 줄은
     * 지운다. dnd 와 active 가 겹치면 저장하지 않는다(§1.3).
     */
    public List<Window> replaceAll(JsonNode body) {
        JsonNode arr = body.path("windows");
        if (!arr.isArray()) {
            throw ApiException.badRequest("windows 배열이 필요합니다");
        }
        List<Window> parsed = new ArrayList<>();
        for (JsonNode w : arr) {
            parsed.add(parse(w));
        }
        checkOverlap(parsed);

        List<Long> keep = new ArrayList<>();
        for (Window w : parsed) {
            ArrayNode days = Json.array();
            w.days().forEach(days::add);
            boolean exists = w.id() > 0 && db.jdbc().sql("SELECT COUNT(*) FROM time_windows WHERE id = ?")
                    .param(w.id()).query(Integer.class).single() > 0;
            if (exists) {
                db.update("""
                                UPDATE time_windows SET kind = ?, name = ?, days = ?, start_time = ?, end_type = ?,
                                end_time = ?, end_fallback_time = ?, enabled = ?, updated_at = ? WHERE id = ?""",
                        w.kind(), w.name(), Json.write(days), w.startTime(), w.endType(), w.endTime(),
                        w.fallbackTime(), w.enabled(), clock.now(), w.id());
                keep.add(w.id());
            } else {
                keep.add(db.insert("""
                                INSERT INTO time_windows(kind, name, days, start_time, end_type, end_time,
                                end_fallback_time, enabled, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                        w.kind(), w.name(), Json.write(days), w.startTime(), w.endType(), w.endTime(),
                        w.fallbackTime(), w.enabled(), clock.now()));
            }
        }
        for (Window old : list()) {
            if (!keep.contains(old.id())) {
                db.update("DELETE FROM time_windows WHERE id = ?", old.id());
            }
        }
        return list();
    }

    private static Window parse(JsonNode w) {
        String kind = w.path("kind").asString("");
        if (!KINDS.contains(kind)) {
            throw ApiException.unprocessable("kind 는 dnd·active 중 하나입니다");
        }
        String name = w.path("name").asString("").trim();
        if (name.isEmpty()) {
            name = "dnd".equals(kind) ? "방해 금지" : "적극 개입";
        }
        if (name.length() > 40) {
            throw ApiException.unprocessable("이름은 40자까지입니다");
        }
        List<Integer> days = new ArrayList<>();
        for (JsonNode d : w.path("days")) {
            int v = d.asInt();
            if (v < 1 || v > 7) {
                throw ApiException.unprocessable("요일은 1(월)~7(일)입니다");
            }
            if (!days.contains(v)) {
                days.add(v);
            }
        }
        if (days.isEmpty()) {
            throw ApiException.unprocessable("요일을 하나 이상 고르세요");
        }
        days.sort(Comparator.naturalOrder());
        String start = hm(w.path("start_time").asString(""), "시작 시각");
        String endType = w.path("end_type").asString("time");
        if (!List.of("time", "first_block").contains(endType)) {
            throw ApiException.unprocessable("end_type 은 time·first_block 중 하나입니다");
        }
        String end = null;
        if ("time".equals(endType)) {
            end = hm(w.path("end_time").asString(""), "끝 시각");
            if (end.equals(start)) {
                throw ApiException.unprocessable("시작과 끝이 같습니다");
            }
        }
        String fallback = w.hasNonNull("end_fallback_time") && !w.path("end_fallback_time").asString("").isBlank()
                ? hm(w.path("end_fallback_time").asString(""), "대체 끝 시각") : "12:00";
        if ("first_block".equals(endType) && fallback.equals(start)) {
            throw ApiException.unprocessable("시작과 대체 끝 시각이 같습니다");
        }
        boolean enabled = !w.has("enabled") || w.path("enabled").asBoolean(true);
        return new Window(w.path("id").asLong(0), kind, name, days, start, endType, end, fallback, enabled);
    }

    private static String hm(String text, String label) {
        try {
            return LocalTime.parse(text.trim()).toString();
        } catch (RuntimeException e) {
            throw ApiException.unprocessable(label + " 형식이 맞지 않습니다(HH:mm)");
        }
    }

    /** 켜진 dnd 와 active 가 앞으로 14일 안에 겹치면 막는다. first_block 은 가장 길게(대체 시각까지) 본다. */
    void checkOverlap(List<Window> list) {
        Instant from = time.local(clock.now()).toLocalDate().atStartOfDay(time.zone()).toInstant();
        Instant to = from.plus(Duration.ofDays(OVERLAP_CHECK_DAYS));
        List<Occ> dnd = new ArrayList<>();
        List<Occ> active = new ArrayList<>();
        for (Window w : list) {
            if (!w.enabled()) {
                continue;
            }
            (w.kind().equals("dnd") ? dnd : active).addAll(staticOccurrences(w, from, to));
        }
        for (Occ d : dnd) {
            for (Occ a : active) {
                if (d.start().isBefore(a.end()) && a.start().isBefore(d.end())) {
                    throw ApiException.unprocessable("'" + d.name() + "'(방해 금지)과 '" + a.name()
                            + "'(적극 개입)이 " + time.dayText(max(d.start(), a.start())) + "부터 겹쳐요. 시간을 나눠 주세요.");
                }
            }
        }
    }

    // ------------------------------------------------------------------ 계산

    /** [from, to) 와 겹치는 열림(켜진 것만). first_block 은 그날 첫 블록으로 끝을 정한다. */
    public List<Occ> occurrences(Instant from, Instant to) {
        List<Occ> result = new ArrayList<>();
        for (Window w : list()) {
            if (!w.enabled()) {
                continue;
            }
            for (Occ o : expand(w, from, to, true)) {
                if (o.start().isBefore(to) && from.isBefore(o.end())) {
                    result.add(o);
                }
            }
        }
        result.sort(Comparator.comparing(Occ::start));
        return result;
    }

    private List<Occ> staticOccurrences(Window w, Instant from, Instant to) {
        return expand(w, from, to, false);
    }

    private List<Occ> expand(Window w, Instant from, Instant to, boolean useBlocks) {
        ZoneId z = time.zone();
        List<Occ> result = new ArrayList<>();
        LocalDate first = time.local(from).toLocalDate().minusDays(1);
        LocalDate last = time.local(to).toLocalDate();
        LocalTime s = LocalTime.parse(w.startTime());
        for (LocalDate d = first; !d.isAfter(last); d = d.plusDays(1)) {
            if (!w.days().contains(d.getDayOfWeek().getValue())) {
                continue;
            }
            Instant start = d.atTime(s).atZone(z).toInstant();
            if (!w.firstBlock()) {
                LocalTime e = LocalTime.parse(w.endTime());
                LocalDate endDay = e.isAfter(s) ? d : d.plusDays(1);
                result.add(new Occ(w.id(), w.kind(), w.name(), start, endDay.atTime(e).atZone(z).toInstant(),
                        false, false));
                continue;
            }
            LocalTime fb = LocalTime.parse(w.fallbackTime());
            LocalDate endDay = fb.isAfter(s) ? d : d.plusDays(1);
            Instant fallback = endDay.atTime(fb).atZone(z).toInstant();
            if (!useBlocks) {
                result.add(new Occ(w.id(), w.kind(), w.name(), start, fallback, true, true));
                continue;
            }
            Optional<Instant> alarm = firstAlarm(endDay);
            if (alarm.isEmpty()) {
                result.add(new Occ(w.id(), w.kind(), w.name(), start, fallback, true, true));
            } else if (alarm.get().isAfter(start)) {
                result.add(new Occ(w.id(), w.kind(), w.name(), start, alarm.get(), true, false));
            }
            // 첫 블록이 시간대 시작 전(또는 같은 시각)이면 그날은 열리지 않는다(§1.1).
        }
        return result;
    }

    /** 그날(달력 날짜) 첫 블록(고정·계획·이동, 취소 제외)의 첫 알람 시각(준비 lead 포함). */
    Optional<Instant> firstAlarm(LocalDate day) {
        ZoneId z = time.zone();
        Instant from = day.atStartOfDay(z).toInstant();
        Instant to = day.plusDays(1).atStartOfDay(z).toInstant();
        return db.jdbc().sql("""
                        SELECT * FROM blocks WHERE status <> 'cancelled' AND start_at >= ? AND start_at < ?
                        ORDER BY start_at, id""")
                .param(AssistantDb.ts(from)).param(AssistantDb.ts(to))
                .query(Store::block).list().stream()
                .map(b -> b.travel() ? b.start() : b.interventionAt())
                .min(Comparator.naturalOrder());
    }

    public Optional<Occ> at(Instant t, String kind) {
        return occurrences(t, t.plusSeconds(1)).stream()
                .filter(o -> o.kind().equals(kind) && o.contains(t))
                .findFirst();
    }

    /** 지금 방해 금지 시간대인가(가장 우선, §2.1). */
    public Optional<Occ> dndAt(Instant t) {
        return at(t, "dnd");
    }

    /** 적극 개입 시간대(dnd 가 겹치면 없음, [10분만 조용히] 중이면 없음). */
    public Optional<Occ> activeAt(Instant t) {
        if (dndAt(t).isPresent() || paused(t)) {
            return Optional.empty();
        }
        return at(t, "active");
    }

    public boolean paused(Instant t) {
        return pausedUntil().map(t::isBefore).orElse(false);
    }

    public Optional<Instant> pausedUntil() {
        JsonNode v = settings.get(PAUSED_UNTIL);
        if (v == null || !v.isString() || v.asString().isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(TimeUtil.parse(v.asString()));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** active 시간대 안이지만, 진행 중인 고정 일정이 있으면 그 일정의 방해 허용 정도를 따른다(§2.3). */
    public boolean activeEffective(Instant t, Store store) {
        return activeEffective(t, store, null);
    }

    /** exceptBlockId: 그 블록 자신(개입 중인 고정 일정)은 '다른 고정 일정 진행 중'으로 보지 않는다. */
    public boolean activeEffective(Instant t, Store store, Long exceptBlockId) {
        if (activeAt(t).isEmpty()) {
            return false;
        }
        return store.fixedInProgress(t).stream().noneMatch(b -> !b.travel() && !"started".equals(b.status())
                && (exceptBlockId == null || b.id() != exceptBlockId));
    }

    /** 오늘(달력 날짜) 열리는 시간대 미리보기(설정 화면 막대). */
    public List<Map<String, Object>> todayPreview() {
        LocalDate today = time.local(clock.now()).toLocalDate();
        Instant from = today.atStartOfDay(time.zone()).toInstant();
        Instant to = today.plusDays(1).atStartOfDay(time.zone()).toInstant();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Occ o : occurrences(from, to)) {
            result.add(occMap(o));
        }
        return result;
    }

    public Map<String, Object> occMap(Occ o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("window_id", o.windowId());
        m.put("kind", o.kind());
        m.put("name", o.name());
        m.put("from", time.iso(o.start()));
        m.put("to", time.iso(o.end()));
        m.put("end_type", o.firstBlock() ? "first_block" : "time");
        m.put("fallback", o.fallback());
        return m;
    }

    /** 맥락 표기: "- 23:00–07:00 [방해금지 시간대] 이름", "- 06:30–첫 블록(07:40) [적극 개입 시간대] 이름". */
    public String contextLine(Occ o) {
        String end = o.firstBlock()
                ? (o.fallback() ? "첫 블록(오늘 블록 없음, " + time.hm(o.end()) + ")" : "첫 블록(" + time.hm(o.end()) + ")")
                : time.hm(o.end());
        return "- " + time.local(o.start()).getMonthValue() + "/" + time.local(o.start()).getDayOfMonth() + " "
                + time.hm(o.start()) + "–" + end + (o.dnd() ? " [방해금지 시간대] " : " [적극 개입 시간대] ") + o.name();
    }

    private static Instant max(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }
}
