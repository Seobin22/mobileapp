package com.seobin.phonedrop.assistant.agent;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import com.seobin.phonedrop.assistant.AssistantClock;
import com.seobin.phonedrop.assistant.AssistantDb;
import com.seobin.phonedrop.assistant.BlockLogs;
import com.seobin.phonedrop.assistant.Jobs;
import com.seobin.phonedrop.assistant.Json;
import com.seobin.phonedrop.assistant.SettingsService;
import com.seobin.phonedrop.assistant.Store;
import com.seobin.phonedrop.assistant.TimeUtil;
import com.seobin.phonedrop.assistant.Tx;

import tools.jackson.databind.JsonNode;

/**
 * 지도 읽기(빌드문서 v3.3 §3). 장소 → 좌표, 고정 일정 사이 이동 시간(LLM 호출 없음).
 * - 장소가 생기거나 바뀌면 카카오 키워드 검색 첫 결과(집 근처 우선)를 저장한다. 없으면 좌표 없이 둔다.
 * - 같은 날 좌표 있는 고정 블록끼리 arrive(앞 장소/집 → 여기)·depart(여기 → 다음 장소/집)를 계산한다.
 * - 같은 출발·도착·수단·시간대(1시간)는 7일 캐시. 하루 호출이 travel.daily_api_limit 를 넘거나 실패하면 어림값.
 * - 매일 04:20 앞으로 7일분을 점검해 빠진 것만 계산한다(아침 브리핑 전).
 */
@Service
public class TravelService {

    private static final Logger log = LoggerFactory.getLogger(TravelService.class);
    public static final String JOB = "travel_daily";
    static final LocalTime DAILY_AT = LocalTime.of(4, 20);
    static final Duration CACHE_TTL = Duration.ofDays(7);
    static final String HOME = "집";

    /** 출발·도착 지점. */
    record Point(String label, double lat, double lng) {

        String key() {
            return String.format(Locale.ROOT, "%.4f,%.4f", lat, lng);
        }
    }

    public record Leg(String direction, String from, String to, String mode, int minutes, int distanceM, String source) {
    }

    private final AssistantDb db;
    private final AssistantClock clock;
    private final Store store;
    private final TimeUtil time;
    private final SettingsService settings;
    private final MapApi map;
    private final BlockLogs logs;
    private final Jobs jobs;
    private final Tx tx;
    private final boolean enabled;

    public TravelService(AssistantDb db, AssistantClock clock, Store store, TimeUtil time, SettingsService settings,
                         MapApi map, BlockLogs logs, Jobs jobs, Tx tx,
                         @Value("${assistant.worker.enabled:true}") boolean enabled) {
        this.db = db;
        this.clock = clock;
        this.store = store;
        this.time = time;
        this.settings = settings;
        this.map = map;
        this.logs = logs;
        this.jobs = jobs;
        this.tx = tx;
        this.enabled = enabled;
        jobs.register(JOB, this::onDaily);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (enabled) {
            tx.exec(() -> {
                db.update("UPDATE jobs SET status = 'cancelled', updated_at = CURRENT_TIMESTAMP WHERE status = 'scheduled' AND type = ?",
                        JOB);
                jobs.schedule(JOB, 0, nextRun(clock.now()), null);
            });
        }
    }

    Instant nextRun(Instant now) {
        LocalDate today = time.local(now).toLocalDate();
        Instant at = today.atTime(DAILY_AT).atZone(time.zone()).toInstant();
        return at.isAfter(now) ? at : today.plusDays(1).atTime(DAILY_AT).atZone(time.zone()).toInstant();
    }

    private boolean onDaily(Jobs.Job job) {
        jobs.schedule(JOB, 0, nextRun(clock.now().plusSeconds(60)), null);
        checkAhead(7);
        return true;
    }

    /** 앞으로 days 일 동안 좌표가 있는데 이동 시간이 없는 고정 블록의 날만 다시 계산한다. */
    public int checkAhead(int days) {
        Instant now = clock.now();
        List<LocalDate> missing = new ArrayList<>();
        for (Store.Block b : fixedBetween(now, now.plus(Duration.ofDays(days)))) {
            boolean hasCoords = b.hasCoords();
            if (!hasCoords && b.location() != null && b.placeSource() == null) {
                geocode(b);
                hasCoords = store.block(b.id()).map(Store.Block::hasCoords).orElse(false);
            }
            LocalDate day = time.local(b.start()).toLocalDate();
            if (hasCoords && legs(b.id()).isEmpty() && !missing.contains(day)) {
                missing.add(day);
            }
        }
        missing.forEach(this::computeDay);
        return missing.size();
    }

    // ---------------------------------------------------------------- 장소 → 좌표 (§3.2)

    /** 고정 블록의 장소가 생기거나 바뀌었을 때: 좌표를 다시 찾고 그날 이동 시간을 다시 계산한다. */
    public void onLocationChanged(long blockId) {
        Store.Block b = store.block(blockId).orElse(null);
        if (b == null || !b.fixed()) {
            return;
        }
        geocode(b);
        onDayChanged(blockId);
    }

    /** 같은 날 고정 블록 구성이 바뀌었을 때. */
    public void onDayChanged(long blockId) {
        store.block(blockId).ifPresent(b -> computeDay(time.local(b.start()).toLocalDate()));
    }

    private void geocode(Store.Block b) {
        if ("user".equals(b.placeSource())) {
            return; // 장소 탭 지도에서 사용자가 찍은 위치는 장소 글자로 다시 찾지 않는다.
        }
        if (b.location() == null || b.location().isBlank()) {
            db.update("UPDATE blocks SET place_name = NULL, lat = NULL, lng = NULL, place_source = NULL WHERE id = ?", b.id());
            return;
        }
        // 반복 회차는 시리즈에서 한 번 찾은 결과를 복사한다(같은 장소면 다시 부르지 않는다).
        if (b.seriesId() != null) {
            Optional<Object[]> fromSeries = db.jdbc().sql("""
                            SELECT place_name, lat, lng, place_source FROM fixed_series WHERE id = ? AND location = ?
                            AND place_source IS NOT NULL""")
                    .param(b.seriesId()).param(b.location())
                    .query((rs, n) -> new Object[]{rs.getString(1), Store.nullableDouble(rs, "lat"),
                            Store.nullableDouble(rs, "lng"), rs.getString(4)})
                    .optional();
            if (fromSeries.isPresent()) {
                Object[] v = fromSeries.get();
                db.update("UPDATE blocks SET place_name = ?, lat = ?, lng = ?, place_source = ? WHERE id = ?",
                        v[0], v[1], v[2], v[3], b.id());
                return;
            }
        }
        Optional<MapApi.Place> place = findPlace(b.location());
        db.update("UPDATE blocks SET place_name = ?, lat = ?, lng = ?, place_source = ? WHERE id = ?",
                place.map(MapApi.Place::name).orElse(null), place.map(MapApi.Place::lat).orElse(null),
                place.map(MapApi.Place::lng).orElse(null), place.isPresent() ? "kakao" : "estimate", b.id());
    }

    /** 반복 시리즈의 장소를 한 번만 찾는다(회차에 복사). */
    public void geocodeSeries(long seriesId) {
        String location = db.jdbc().sql("SELECT location FROM fixed_series WHERE id = ?").param(seriesId)
                .query(String.class).optional().orElse(null);
        if (location == null || location.isBlank()) {
            db.update("UPDATE fixed_series SET place_name = NULL, lat = NULL, lng = NULL, place_source = NULL WHERE id = ?",
                    seriesId);
            return;
        }
        Optional<MapApi.Place> place = findPlace(location);
        db.update("UPDATE fixed_series SET place_name = ?, lat = ?, lng = ?, place_source = ? WHERE id = ?",
                place.map(MapApi.Place::name).orElse(null), place.map(MapApi.Place::lat).orElse(null),
                place.map(MapApi.Place::lng).orElse(null), place.isPresent() ? "kakao" : "estimate", seriesId);
    }

    /** 키워드 검색(집 근처 우선). 키가 없거나 실패하거나 한도를 넘으면 empty. */
    Optional<MapApi.Place> findPlace(String query) {
        if (!map.hasKey() || overLimit()) {
            return Optional.empty();
        }
        Point home = home().orElse(null);
        try {
            Optional<MapApi.Place> p = map.searchKeyword(query, home == null ? null : home.lat(),
                    home == null ? null : home.lng());
            countCall("keyword", true);
            return p;
        } catch (MapApi.MapException e) {
            countCall("keyword", false);
            log.info("kakao keyword search failed: {}", e.code());
            return Optional.empty();
        }
    }

    /**
     * 집 주소 저장(앱 설정): 주소 검색 → 없으면 키워드 검색. 찾은 주소를 돌려준다(못 찾으면 empty, 좌표는 비운다).
     * 집 좌표가 바뀌면 앞으로 7일 이동 시간을 다시 계산한다.
     */
    public Optional<MapApi.Place> setHome(String address) {
        String a = address == null ? "" : address.strip();
        settings.put("travel.home_address", Json.MAPPER.valueToTree(a));
        Optional<MapApi.Place> place = Optional.empty();
        if (!a.isEmpty() && map.hasKey() && !overLimit()) {
            try {
                place = map.searchAddress(a);
                countCall("address", true);
                if (place.isEmpty()) {
                    place = map.searchKeyword(a, null, null);
                    countCall("keyword", true);
                }
            } catch (MapApi.MapException e) {
                countCall("address", false);
                log.info("kakao home search failed: {}", e.code());
            }
        }
        settings.put("travel.home_lat", place.isPresent() ? Json.MAPPER.valueToTree(place.get().lat()) : Json.parse("null"));
        settings.put("travel.home_lng", place.isPresent() ? Json.MAPPER.valueToTree(place.get().lng()) : Json.parse("null"));
        recomputeAhead(7);
        return place;
    }

    /** 집 좌표·이동수단이 바뀌면 앞으로 days 일을 모두 다시 계산한다. */
    public void recomputeAhead(int days) {
        Instant now = clock.now();
        List<LocalDate> daysToCompute = new ArrayList<>();
        for (Store.Block b : fixedBetween(now, now.plus(Duration.ofDays(days)))) {
            LocalDate d = time.local(b.start()).toLocalDate();
            if (!daysToCompute.contains(d)) {
                daysToCompute.add(d);
            }
        }
        daysToCompute.forEach(this::computeDay);
    }

    Optional<Point> home() {
        JsonNode lat = settings.get("travel.home_lat");
        JsonNode lng = settings.get("travel.home_lng");
        if (!lat.isNumber() || !lng.isNumber()) {
            return Optional.empty();
        }
        return Optional.of(new Point(HOME, lat.asDouble(), lng.asDouble()));
    }

    // ---------------------------------------------------------------- 이동 시간 (§3.3)

    /** 그날 좌표 있는 활성 고정 블록의 arrive·depart 를 다시 계산한다. */
    public void computeDay(LocalDate day) {
        Instant from = day.atStartOfDay(time.zone()).toInstant();
        Instant to = day.plusDays(1).atStartOfDay(time.zone()).toInstant();
        List<Store.Block> all = fixedBetween(from, to).stream()
                .filter(b -> !b.start().isBefore(from)).toList();
        for (Store.Block b : all) {
            // 사용자가 직접 고친 이동 시간(source=user)은 다시 계산하지 않는다(장소를 다시 정하면 지운다).
            db.update("DELETE FROM block_travel WHERE block_id = ? AND source <> 'user'", b.id());
        }
        List<Store.Block> located = all.stream().filter(Store.Block::hasCoords).toList();
        Point home = home().orElse(null);
        String mode = settings.getString("travel.mode");
        for (int i = 0; i < located.size(); i++) {
            Store.Block b = located.get(i);
            Point here = point(b);
            Point prev = i > 0 ? point(located.get(i - 1)) : home;
            Point next = i + 1 < located.size() ? point(located.get(i + 1)) : home;
            if (prev != null) {
                Instant departAt = i > 0 ? located.get(i - 1).end() : b.start().minus(Duration.ofHours(1));
                save(b.id(), "arrive", prev, here, mode, departAt);
            }
            if (next != null) {
                save(b.id(), "depart", here, next, mode, b.end());
            }
        }
    }

    private void save(long blockId, String direction, Point from, Point to, String mode, Instant at) {
        if (db.jdbc().sql("SELECT COUNT(*) FROM block_travel WHERE block_id = ? AND direction = ? AND source = 'user'")
                .param(blockId).param(direction).query(Integer.class).single() > 0) {
            return;
        }
        Leg leg = route(from, to, mode, at);
        db.insert("""
                        INSERT INTO block_travel(block_id, direction, from_label, to_label, mode, minutes, distance_m, source,
                        computed_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                blockId, direction, from.label(), to.label(), leg.mode(), leg.minutes(), leg.distanceM(), leg.source(),
                clock.now());
    }

    /** 캐시 → 카카오 → 어림값. car 가 안 되면 transit 으로 한 번 더 시도한다. */
    Leg route(Point from, Point to, String mode, Instant at) {
        int bucket = time.local(at).getHour();
        Optional<Leg> cached = db.jdbc().sql("""
                        SELECT mode, minutes, distance_m, source FROM travel_cache WHERE from_key = ? AND to_key = ? AND mode = ?
                        AND hour_bucket = ? AND computed_at > ? ORDER BY id DESC LIMIT 1""")
                .param(from.key()).param(to.key()).param(mode).param(bucket)
                .param(AssistantDb.ts(clock.now().minus(CACHE_TTL)))
                .query((rs, n) -> new Leg(null, from.label(), to.label(), rs.getString(1), rs.getInt(2), rs.getInt(3),
                        rs.getString(4)))
                .list().stream().findFirst();
        if (cached.isPresent()) {
            return cached.get();
        }
        Leg leg = null;
        if (map.hasKey() && !overLimit()) {
            leg = callKakao(from, to, mode);
            if (leg == null && "car".equals(mode) && !overLimit()) {
                leg = callKakao(from, to, "transit");
            }
        }
        if (leg == null) {
            leg = estimate(from, to, mode);
        }
        db.insert("""
                        INSERT INTO travel_cache(from_key, to_key, mode, hour_bucket, minutes, distance_m, source, computed_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
                from.key(), to.key(), mode, bucket, leg.minutes(), leg.distanceM(), leg.source(), clock.now());
        return leg;
    }

    private Leg callKakao(Point from, Point to, String mode) {
        try {
            Optional<MapApi.Route> r = map.route(mode, from.lat(), from.lng(), to.lat(), to.lng());
            countCall(mode, true);
            return r.map(x -> new Leg(null, from.label(), to.label(), mode, x.minutes(), x.distanceM(), "kakao"))
                    .orElse(null);
        } catch (MapApi.MapException e) {
            countCall(mode, false);
            log.info("kakao route {} failed: {}", mode, e.code());
            return null;
        }
    }

    /** §3.4 어림값: 직선거리 × 1.4 ÷ 수단별 속도 + 고정 시간. */
    static Leg estimate(Point from, Point to, String mode) {
        double km = haversineKm(from.lat(), from.lng(), to.lat(), to.lng()) * 1.4;
        double minutes = switch (mode) {
            case "car" -> km / 30.0 * 60 + 5;
            case "walk" -> km / 4.5 * 60;
            default -> km / 20.0 * 60 + 10;
        };
        return new Leg(null, from.label(), to.label(), mode, (int) Math.max(1, Math.round(minutes)),
                (int) Math.round(km * 1000), "estimate");
    }

    static double haversineKm(double lat1, double lng1, double lat2, double lng2) {
        double r = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.cos(Math.toRadians(lat1))
                * Math.cos(Math.toRadians(lat2)) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * r * Math.asin(Math.sqrt(a));
    }

    private void countCall(String api, boolean ok) {
        db.insert("INSERT INTO kakao_calls(api, ok, called_at) VALUES (?, ?, ?)", api, ok, clock.now());
    }

    public int callsToday() {
        Instant dayStart = time.local(clock.now()).toLocalDate().atStartOfDay(time.zone()).toInstant();
        return db.jdbc().sql("SELECT COUNT(*) FROM kakao_calls WHERE called_at >= ?").param(AssistantDb.ts(dayStart))
                .query(Integer.class).single();
    }

    boolean overLimit() {
        return callsToday() >= settings.getInt("travel.daily_api_limit");
    }

    /** 관리 화면: 정상 / 키 없음 / 사용 설정 꺼짐 / 오늘 한도 초과. */
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        String s = map.status();
        if (map.hasKey() && overLimit()) {
            s = "limit";
        }
        m.put("status", s);
        m.put("status_text", switch (s) {
            case "ok" -> "정상";
            case "no_key" -> "키 없음";
            case "disabled" -> "사용 설정 꺼짐 — 카카오 개발자 콘솔에서 '카카오맵 사용 설정'을 켜 주세요";
            case "limit" -> "오늘 한도 초과(어림값 사용)";
            case "unknown" -> "키 있음(아직 호출 전)";
            default -> "오류";
        });
        m.put("calls_today", callsToday());
        m.put("daily_limit", settings.getInt("travel.daily_api_limit"));
        m.put("home_address", settings.getString("travel.home_address"));
        m.put("home_found", home().isPresent());
        m.put("mode", settings.getString("travel.mode"));
        return m;
    }

    // ---------------------------------------------------------------- 비서에게 (§3.5)

    public List<Leg> legs(long blockId) {
        return db.jdbc().sql("""
                        SELECT direction, from_label, to_label, mode, minutes, distance_m, source FROM block_travel
                        WHERE block_id = ? ORDER BY direction""")
                .param(blockId)
                .query((rs, n) -> new Leg(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5),
                        rs.getInt(6), rs.getString(7)))
                .list();
    }

    public Optional<Leg> leg(long blockId, String direction) {
        return legs(blockId).stream().filter(l -> direction.equals(l.direction())).findFirst();
    }

    /** 맥락 줄 덧붙임: " · 오는 길: 집→치과 대중교통 약 42분(카카오) · 가는 길: 치과→집 약 40분". 없으면 "". */
    public String contextText(Store.Block b) {
        StringBuilder sb = new StringBuilder();
        leg(b.id(), "arrive").ifPresent(l -> sb.append(" · 오는 길: ").append(l.from()).append('→').append(l.to())
                .append(' ').append(modeText(l.mode())).append(" 약 ").append(l.minutes()).append("분")
                .append(sourceText(l.source(), "(카카오)")));
        leg(b.id(), "depart").ifPresent(l -> sb.append(" · 가는 길: ").append(l.from()).append('→').append(l.to())
                .append(" 약 ").append(l.minutes()).append("분").append(sourceText(l.source(), "")));
        return sb.toString();
    }

    private static String sourceText(String source, String kakao) {
        return switch (source) {
            case "kakao" -> kakao;
            case "user" -> "(사용자가 정함)";
            default -> "(어림값)";
        };
    }

    // ---------------------------------------------------------------- 장소 탭 (2026-11-04 사용자 요청)

    /**
     * 지도에서 찍은 위치를 저장한다(place_source=user). name 이 비면 좌표 → 주소로 이름을 붙인다.
     * series=true 면 반복 일정 전체(시리즈와 아직 시작 전인 회차). 장소 글자가 비어 있으면 이름으로 채운다.
     * 이 블록에 사용자가 고친 이동 시간이 있으면 지운다(장소가 바뀌었으므로).
     */
    public Store.Block setPlace(long blockId, double lat, double lng, String name, boolean series) {
        Store.Block b = store.block(blockId).filter(Store.Block::fixed)
                .orElseThrow(() -> com.seobin.phonedrop.assistant.ApiException.notFound("고정 블록#" + blockId + "이 없습니다"));
        String label = name == null || name.isBlank() ? null : name.strip();
        if (label == null && map.hasKey() && !overLimit()) {
            try {
                label = map.reverse(lat, lng).map(MapApi.Place::name).orElse(null);
                countCall("reverse", true);
            } catch (MapApi.MapException e) {
                countCall("reverse", false);
            }
        }
        String placeName = label == null ? "지도에서 고른 위치" : label;
        List<Long> ids = new ArrayList<>();
        ids.add(blockId);
        if (series && b.seriesId() != null) {
            db.update("""
                            UPDATE fixed_series SET place_name = ?, lat = ?, lng = ?, place_source = 'user',
                            location = COALESCE(NULLIF(location, ''), ?) WHERE id = ?""",
                    placeName, lat, lng, placeName, b.seriesId());
            ids.addAll(db.jdbc().sql("""
                            SELECT id FROM blocks WHERE series_id = ? AND id <> ? AND status = 'planned' AND start_at > ?""")
                    .param(b.seriesId()).param(blockId).param(AssistantDb.ts(clock.now())).query(Long.class).list());
        }
        List<LocalDate> days = new ArrayList<>();
        for (long id : ids) {
            db.update("""
                            UPDATE blocks SET place_name = ?, lat = ?, lng = ?, place_source = 'user',
                            location = COALESCE(NULLIF(location, ''), ?), updated_at = CURRENT_TIMESTAMP WHERE id = ?""",
                    placeName, lat, lng, placeName, id);
            db.update("DELETE FROM block_travel WHERE block_id = ? AND source = 'user'", id);
            store.block(id).ifPresent(x -> {
                LocalDate d = time.local(x.start()).toLocalDate();
                if (!days.contains(d)) {
                    days.add(d);
                }
            });
        }
        logs.add(blockId, "note", "user", "장소를 지도에서 정함: " + placeName + (ids.size() > 1 ? " (반복 전체)" : ""));
        days.forEach(this::computeDay);
        return store.block(blockId).orElseThrow();
    }

    /** 오는 길(arrive)·가는 길(depart) 분을 사용자가 직접 고친다. 다시 계산해도 바뀌지 않는다. */
    public void setLegMinutes(long blockId, String direction, int minutes) {
        if (!List.of("arrive", "depart").contains(direction)) {
            throw com.seobin.phonedrop.assistant.ApiException.badRequest("direction 은 arrive·depart 중 하나입니다");
        }
        if (minutes < 0 || minutes > 24 * 60) {
            throw com.seobin.phonedrop.assistant.ApiException.badRequest("이동 시간은 0~1440분입니다");
        }
        Store.Block b = store.block(blockId).filter(Store.Block::fixed)
                .orElseThrow(() -> com.seobin.phonedrop.assistant.ApiException.notFound("고정 블록#" + blockId + "이 없습니다"));
        Leg old = leg(blockId, direction).orElse(null);
        String from = old != null ? old.from() : "arrive".equals(direction) ? HOME : store.blockTitle(b);
        String to = old != null ? old.to() : "arrive".equals(direction) ? store.blockTitle(b) : HOME;
        db.update("DELETE FROM block_travel WHERE block_id = ? AND direction = ?", blockId, direction);
        db.insert("""
                        INSERT INTO block_travel(block_id, direction, from_label, to_label, mode, minutes, distance_m, source,
                        computed_at) VALUES (?, ?, ?, ?, ?, ?, ?, 'user', ?)""",
                blockId, direction, from, to, old != null ? old.mode() : settings.getString("travel.mode"), minutes,
                old != null ? old.distanceM() : 0, clock.now());
        logs.add(blockId, "note", "user", ("arrive".equals(direction) ? "오는 길 " : "가는 길 ") + minutes + "분으로 직접 고침");
    }

    /** 장소 탭: 앞으로 days 일의 활성 고정 일정과 장소·바로 앞 장소에서 오는 길. */
    public List<Map<String, Object>> upcomingPlaces(int days) {
        Instant now = clock.now();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Store.Block b : fixedBetween(now, now.plus(Duration.ofDays(days)))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", b.id());
            m.put("title", store.blockTitle(b));
            m.put("start", time.iso(b.start()));
            m.put("end", time.iso(b.end()));
            m.put("location", b.location());
            m.put("place_name", b.placeName());
            m.put("lat", b.lat());
            m.put("lng", b.lng());
            m.put("place_source", b.placeSource());
            m.put("series_id", b.seriesId());
            leg(b.id(), "arrive").ifPresent(l -> {
                Map<String, Object> lm = new LinkedHashMap<>();
                lm.put("from", l.from());
                lm.put("mode", l.mode());
                lm.put("minutes", l.minutes());
                lm.put("distance_m", l.distanceM());
                lm.put("source", l.source());
                m.put("arrive", lm);
            });
            out.add(m);
        }
        return out;
    }

    /** 지도 검색 후보(집 근처 우선). 키가 없거나 실패하면 빈 목록. */
    public List<MapApi.Place> search(String query, Double nearLat, Double nearLng) {
        if (query == null || query.isBlank() || !map.hasKey() || overLimit()) {
            return List.of();
        }
        Point home = home().orElse(null);
        try {
            List<MapApi.Place> list = map.searchKeywordList(query.strip(), nearLat != null ? nearLat : home == null ? null : home.lat(),
                    nearLng != null ? nearLng : home == null ? null : home.lng(), 5);
            countCall("keyword", true);
            return list;
        } catch (MapApi.MapException e) {
            countCall("keyword", false);
            return List.of();
        }
    }

    public static String modeText(String mode) {
        return switch (mode) {
            case "car" -> "자동차";
            case "walk" -> "도보";
            default -> "대중교통";
        };
    }

    /**
     * 코드 경고(§3.5): 같은 날 앞 일정 끝 + 이동 시간 > 다음 고정 일정 시작 − lead.
     * 예: "이동 불가능: 회의 끝 15:00 + 이동 50분 > 치과 15:30".
     */
    public record Warning(long blockId, String text) {
    }

    public List<Warning> warnings(Instant from, Instant to) {
        List<Warning> out = new ArrayList<>();
        List<Store.Block> located = fixedBetween(from, to).stream().filter(Store.Block::hasCoords).toList();
        for (int i = 1; i < located.size(); i++) {
            Store.Block prev = located.get(i - 1);
            Store.Block next = located.get(i);
            if (!time.local(prev.start()).toLocalDate().equals(time.local(next.start()).toLocalDate())) {
                continue;
            }
            Optional<Leg> leg = leg(next.id(), "arrive");
            if (leg.isEmpty()) {
                continue;
            }
            Instant arrive = prev.end().plus(Duration.ofMinutes(leg.get().minutes()));
            if (arrive.isAfter(next.blockedStart())) {
                out.add(new Warning(next.id(), "이동 불가능: " + store.blockTitle(prev) + " 끝 " + time.hm(prev.end())
                        + " + 이동 " + leg.get().minutes() + "분" + ("kakao".equals(leg.get().source()) ? "" : "(어림값)")
                        + " > " + store.blockTitle(next) + " " + time.hm(next.blockedStart())
                        + (next.leadMinutes() > 0 ? "(출발 준비 포함)" : "")));
            }
        }
        return out;
    }

    /** 아직 알리지 않은 경고(같은 경고는 한 번만: block_logs notice 로 기록). */
    public List<Warning> unnoticedWarnings(Instant from, Instant to) {
        return warnings(from, to).stream().filter(w -> logs.notices(w.blockId()).stream()
                .noneMatch(l -> l.content().equals(w.text()))).toList();
    }

    public void markNoticed(List<Warning> list) {
        for (Warning w : list) {
            logs.add(w.blockId(), "notice", "system", w.text());
        }
    }

    private List<Store.Block> fixedBetween(Instant from, Instant to) {
        return db.jdbc().sql("""
                        SELECT * FROM blocks WHERE kind = 'fixed' AND status IN ('planned', 'nudging', 'started')
                        AND start_at < ? AND end_at > ? ORDER BY start_at, id""")
                .param(AssistantDb.ts(to)).param(AssistantDb.ts(from)).query(Store::block).list();
    }

    private Point point(Store.Block b) {
        return new Point(store.blockTitle(b), b.lat(), b.lng());
    }
}
