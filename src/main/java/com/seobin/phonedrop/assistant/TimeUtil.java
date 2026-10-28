package com.seobin.phonedrop.assistant;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;

/** 시간대·활동 구간·조용한 구간(수면 + 방해 금지) 계산. */
@Component
public class TimeUtil {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");
    private static final DateTimeFormatter SHORT = DateTimeFormatter.ofPattern("MM/dd HH:mm");
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

    public record Window(Instant start, Instant end) {

        public boolean contains(Instant t) {
            return !t.isBefore(start) && t.isBefore(end);
        }

        public boolean overlaps(Instant s, Instant e) {
            return start.isBefore(e) && s.isBefore(end);
        }
    }

    private final SettingsService settings;

    public TimeUtil(SettingsService settings) {
        this.settings = settings;
    }

    public ZoneId zone() {
        return ZoneId.of(settings.getString("user.timezone"));
    }

    public ZonedDateTime local(Instant t) {
        return t.atZone(zone());
    }

    public String iso(Instant t) {
        return t == null ? null : local(t).format(ISO);
    }

    /** "11/02 10:00" 형태. 사유 문장용. */
    public String shortText(Instant t) {
        return local(t).format(SHORT);
    }

    public String hm(Instant t) {
        return local(t).format(HM);
    }

    public static Instant parse(String text) {
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException | NullPointerException e) {
            throw ApiException.badRequest("시각 형식이 맞지 않습니다(ISO-8601, 오프셋 포함): " + text);
        }
    }

    private LocalTime wake() {
        return LocalTime.parse(settings.getString("life.wake"));
    }

    private LocalTime sleep() {
        return LocalTime.parse(settings.getString("life.sleep"));
    }

    /** 그날 기상~취침. 취침이 기상보다 이르면(예 01:00) 다음날 새벽으로 본다. */
    public Window activeWindow(LocalDate day) {
        ZoneId z = zone();
        LocalTime wake = wake();
        LocalTime sleep = sleep();
        Instant start = day.atTime(wake).atZone(z).toInstant();
        LocalDate endDay = sleep.isAfter(wake) ? day : day.plusDays(1);
        Instant end = endDay.atTime(sleep).atZone(z).toInstant();
        return new Window(start, end);
    }

    /** t 가 그날 기상 시각 이전이면 전날로 본다. */
    public LocalDate logicalDay(Instant t) {
        ZonedDateTime l = local(t);
        return l.toLocalTime().isBefore(wake()) ? l.toLocalDate().minusDays(1) : l.toLocalDate();
    }

    /** [from, to) 와 겹치는 수면·방해 금지 구간(합치고 잘라낸 것). */
    public List<Window> quietWindows(Instant from, Instant to) {
        ZoneId z = zone();
        List<Window> raw = new ArrayList<>();
        LocalDate first = local(from).toLocalDate().minusDays(1);
        LocalDate last = local(to).toLocalDate().plusDays(1);
        JsonNode dnd = settings.get("dnd.windows");

        for (LocalDate d = first; !d.isAfter(last); d = d.plusDays(1)) {
            Window today = activeWindow(d);
            Window tomorrow = activeWindow(d.plusDays(1));
            if (today.end().isBefore(tomorrow.start())) {
                raw.add(new Window(today.end(), tomorrow.start()));
            }
            int dow = d.getDayOfWeek().getValue();
            for (JsonNode w : dnd) {
                boolean dayMatches = false;
                for (JsonNode day : w.path("days")) {
                    dayMatches |= day.asInt() == dow;
                }
                if (!dayMatches) {
                    continue;
                }
                LocalTime s = LocalTime.parse(w.path("start").asString());
                LocalTime e = LocalTime.parse(w.path("end").asString());
                LocalDate endDay = e.isAfter(s) ? d : d.plusDays(1);
                raw.add(new Window(d.atTime(s).atZone(z).toInstant(), endDay.atTime(e).atZone(z).toInstant()));
            }
        }

        raw.sort(Comparator.comparing(Window::start));
        List<Window> merged = new ArrayList<>();
        for (Window w : raw) {
            if (!merged.isEmpty() && !w.start().isAfter(merged.getLast().end())) {
                Window prev = merged.removeLast();
                merged.add(new Window(prev.start(), w.end().isAfter(prev.end()) ? w.end() : prev.end()));
            } else {
                merged.add(w);
            }
        }

        List<Window> result = new ArrayList<>();
        for (Window w : merged) {
            if (w.overlaps(from, to)) {
                result.add(new Window(w.start().isBefore(from) ? from : w.start(),
                        w.end().isAfter(to) ? to : w.end()));
            }
        }
        return result;
    }

    public boolean isQuiet(Instant t) {
        return quietWindows(t, t.plusSeconds(1)).stream().anyMatch(w -> w.contains(t));
    }

    /** [start, end) 와 겹치는 첫 조용한 구간(겹침 검사용). */
    public Optional<Window> firstQuietOverlap(Instant start, Instant end) {
        return quietWindows(start, end).stream().findFirst();
    }

    /** from 이후 to 전에 시작하는 첫 조용한 구간의 시작 시각. from 이 이미 조용하면 from. */
    public Optional<Instant> firstQuietStart(Instant from, Instant to) {
        return quietWindows(from, to).stream().map(Window::start).findFirst();
    }

    public static long minutesBetween(Instant a, Instant b) {
        return Duration.between(a, b).toMinutes();
    }
}
