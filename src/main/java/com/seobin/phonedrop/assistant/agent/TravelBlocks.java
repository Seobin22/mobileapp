package com.seobin.phonedrop.assistant.agent;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

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
 * 이동 블록(빌드문서 v3.4). 장소가 있는 고정 일정마다 코드가 만드는 연결 블록(kind=planned, link_role=travel).
 * - arrive: [B.start − M, B.start] (앞 고정 일정 A 의 장소 또는 집에서). 준비(lead)는 이동 블록 앞에 온다.
 * - home: 그날 마지막 장소 L 뒤 [L.end + buffer, + 귀가 시간]. 집 좌표가 없으면 만들지 않는다.
 * - 이동 촉박: A 끝(+buffer)~B.start 가 M + 준비보다 짧으면 이동 블록을 그 틈에 맞추고 부족한 분을 기록한다.
 * - 알림: 준비(가벼운 알림) → 출발(기존 개입 화면, 이동 블록) → 도착(가벼운 알림). 귀가는 가벼운 알림만.
 * LLM 은 부르지 않는다. 날짜 단위로 다시 계산하고 달라진 것만 고친다.
 */
@Service
public class TravelBlocks {

    private static final Logger log = LoggerFactory.getLogger(TravelBlocks.class);
    public static final String NOTICE_JOB = "travel_notice";
    static final String LEAD_MIGRATION = "travel_lead_v1";
    static final int MIN_MINUTES = 5;
    static final int TIGHT_NOTICE_BEFORE_MIN = 5;

    private final AssistantDb db;
    private final AssistantClock clock;
    private final Store store;
    private final TimeUtil time;
    private final SettingsService settings;
    private final InterventionService interventions;
    private final ChatService chat;
    private final TurnQueue turns;
    private final BlockLogs logs;
    private final Jobs jobs;
    private final PlanService plan;
    private final Tx tx;
    private final boolean enabled;

    public TravelBlocks(AssistantDb db, AssistantClock clock, Store store, TimeUtil time, SettingsService settings,
                        InterventionService interventions, ChatService chat, TurnQueue turns, BlockLogs logs, Jobs jobs,
                        PlanService plan, Tx tx, @Value("${assistant.worker.enabled:true}") boolean enabled) {
        this.db = db;
        this.clock = clock;
        this.store = store;
        this.time = time;
        this.settings = settings;
        this.interventions = interventions;
        this.chat = chat;
        this.turns = turns;
        this.logs = logs;
        this.jobs = jobs;
        this.plan = plan;
        this.tx = tx;
        this.enabled = enabled;
        jobs.register(NOTICE_JOB, this::onNotice);
    }

    /** 원하는 이동 블록 하나. */
    record Want(long fixedId, String direction, Instant start, Instant end, int lead, int tight, String title,
                String screen, int minutes) {
    }

    // ---------------------------------------------------------------- 날짜 단위 재계산 (§2.2)

    /**
     * 그날의 이동 블록을 맞춘다. located = 그날 좌표 있는 활성 고정 블록(시간순), home = 집 좌표(없으면 null).
     * 이동 시간 M 은 이동 블록의 사용자 값 → block_travel(카카오·사용자·어림값) 순.
     */
    void sync(LocalDate day, List<Store.Block> located, double[] home) {
        Instant now = clock.now();
        Instant dayStart = day.atStartOfDay(time.zone()).toInstant();
        Instant dayEnd = day.plusDays(1).atStartOfDay(time.zone()).toInstant();
        List<Store.Block> existing = db.jdbc().sql("""
                        SELECT * FROM blocks WHERE link_role = 'travel' AND status IN ('planned', 'nudging', 'started')
                        AND (linked_fixed_block_id IN (SELECT id FROM blocks WHERE kind = 'fixed' AND start_at >= ? AND start_at < ?)
                        OR (start_at >= ? AND start_at < ?)) ORDER BY start_at""")
                .param(AssistantDb.ts(dayStart)).param(AssistantDb.ts(dayEnd))
                .param(AssistantDb.ts(dayStart)).param(AssistantDb.ts(dayEnd)).query(Store::block).list();

        List<Want> wants = new ArrayList<>();
        boolean needHome = false;
        String mode = TravelService.modeText(settings.getString("travel.mode"));
        for (int i = 0; i < located.size(); i++) {
            Store.Block b = located.get(i);
            Store.Block a = i > 0 ? located.get(i - 1) : null;
            if (a == null && home == null) {
                needHome = true;
                continue;
            }
            double fromLat = a != null ? a.lat() : home[0];
            double fromLng = a != null ? a.lng() : home[1];
            if (samePlace(fromLat, fromLng, b.lat(), b.lng()) || !List.of("planned", "nudging").contains(b.status())) {
                continue;
            }
            Store.Block old = find(existing, b.id(), "arrive");
            Integer minutes = old != null && old.travelMinutesUser() != null ? old.travelMinutesUser() : legMinutes(b.id(), "arrive");
            if (minutes == null || minutes < MIN_MINUTES) {
                continue;
            }
            int prep = prepMinutes(b, minutes);
            Instant start = b.start().minus(Duration.ofMinutes(minutes));
            int tight = 0;
            if (a != null) {
                Instant gapStart = a.end().plus(Duration.ofMinutes(a.bufferAfterMin()));
                long gap = Duration.between(gapStart, b.start()).toMinutes();
                if (gap < minutes + prep) {
                    tight = (int) (minutes + prep - gap);
                    if (start.isBefore(gapStart)) {
                        start = gapStart;
                    }
                }
            }
            if (Duration.between(start, b.start()).toMinutes() < 1) {
                // 틈이 없다: 블록은 만들지 않고 경고만(촉박 기록은 앞 일정 쪽에 남지 않으므로 맥락 경고에서 계산)
                continue;
            }
            String from = a != null ? store.blockTitle(a) : TravelService.HOME;
            String to = store.blockTitle(b);
            wants.add(new Want(b.id(), "arrive", start, b.start(), tight > 0 ? 0 : prep, tight,
                    "이동: " + from + " → " + to + " (" + mode + " 약 " + minutes + "분)",
                    "지금 출발하세요 · " + to + "까지 약 " + minutes + "분", minutes));
        }
        if (!located.isEmpty()) {
            Store.Block last = located.getLast();
            if (home == null) {
                needHome = true;
            } else if (!samePlace(last.lat(), last.lng(), home[0], home[1])) {
                Store.Block old = find(existing, last.id(), "home");
                Integer minutes = old != null && old.travelMinutesUser() != null ? old.travelMinutesUser()
                        : legMinutes(last.id(), "depart");
                if (minutes != null && minutes >= MIN_MINUTES) {
                    Instant start = last.end().plus(Duration.ofMinutes(last.bufferAfterMin()));
                    wants.add(new Want(last.id(), "home", start, start.plus(Duration.ofMinutes(minutes)), 0, 0,
                            "귀가: " + store.blockTitle(last) + " → " + TravelService.HOME + " (약 " + minutes + "분)",
                            "집으로 가요 · 약 " + minutes + "분", minutes));
                }
            }
        }

        List<Long> displaced = new ArrayList<>();
        List<Long> keep = new ArrayList<>();
        for (Want w : wants) {
            Store.Block old = find(existing, w.fixedId(), w.direction());
            long id;
            if (old == null) {
                if (!w.end().isAfter(now)) {
                    continue;
                }
                id = db.insert("""
                                INSERT INTO blocks(kind, title, start_at, end_at, status, screen_text, lead_minutes,
                                linked_fixed_block_id, link_role, travel_direction, travel_tight_min, source)
                                VALUES ('planned', ?, ?, ?, 'planned', ?, ?, ?, 'travel', ?, ?, 'system')""",
                        w.title(), w.start(), w.end(), w.screen(), w.lead(), w.fixedId(), w.direction(),
                        w.tight() > 0 ? w.tight() : null);
                logs.add(id, "created", "system", "이동 블록 생성: " + time.hm(w.start()) + "–" + time.hm(w.end()));
            } else {
                id = old.id();
                keep.add(id);
                boolean timeChanged = !old.start().equals(w.start()) || !old.end().equals(w.end())
                        || old.leadMinutes() != w.lead();
                boolean other = !old.title().equals(w.title()) || !Objects.equals(old.screenText(), w.screen())
                        || !Objects.equals(nz(old.travelTightMin()), w.tight());
                if (!timeChanged && !other) {
                    continue;
                }
                if (timeChanged && !"planned".equals(old.status())) {
                    // 이미 출발 화면이 떴거나 출발했다: 끝 시각만 맞춘다.
                    db.update("UPDATE blocks SET end_at = ?, title = ?, travel_tight_min = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                            w.end(), w.title(), w.tight() > 0 ? w.tight() : null, id);
                } else {
                    db.update("""
                                    UPDATE blocks SET start_at = ?, end_at = ?, title = ?, screen_text = ?, lead_minutes = ?,
                                    travel_tight_min = ?, version = version + CASE WHEN ? THEN 1 ELSE 0 END,
                                    updated_at = CURRENT_TIMESTAMP WHERE id = ?""",
                            w.start(), w.end(), w.title(), w.screen(), w.lead(), w.tight() > 0 ? w.tight() : null,
                            timeChanged, id);
                }
                if (timeChanged) {
                    logs.add(id, "moved", "system", "이동 블록 재계산: " + time.hm(old.start()) + "→" + time.hm(w.start()));
                }
            }
            Store.Block tb = store.block(id).orElseThrow();
            // 이동 블록(+ 앞쪽 준비 시간)과 겹치는 일반 계획 블록은 밀어낸다(비서가 재배치).
            for (Store.Block other : store.activeBlocksOverlapping(tb.blockedStart(), tb.end())) {
                if (!other.fixed() && !other.travel() && other.id() != id && "planned".equals(other.status())) {
                    interventions.cancelRunningForBlock(other.id());
                    store.setBlockStatus(other.id(), "cancelled");
                    interventions.cancelBlockJobs(other.id());
                    logs.add(other.id(), "note", "system", "이동 블록에 밀려 취소");
                    displaced.add(other.id());
                }
            }
            scheduleJobs(tb);
        }
        for (Store.Block old : existing) {
            boolean wanted = wants.stream().anyMatch(w -> w.fixedId() == Objects.requireNonNullElse(old.linkedFixedBlockId(), -1L)
                    && w.direction().equals(old.travelDirection()));
            if (!wanted) {
                cancelTravel(old, "필요 없어져 취소");
            }
        }
        // 고정 블록의 시작 화면: 이동 블록이 있으면 이동 블록이 출발 화면을 맡는다(§5).
        for (Store.Block b : located) {
            if ("planned".equals(b.status())) {
                interventions.scheduleBlockJobs(store.block(b.id()).orElseThrow());
            }
        }
        if (!displaced.isEmpty()) {
            ObjectNode ref = Json.object();
            ref.put("block_id", located.isEmpty() ? 0 : located.getFirst().id());
            ref.set("displaced", Json.MAPPER.valueToTree(displaced));
            ref.put("reason", "travel");
            turns.enqueue("blocks_displaced", ref, TurnQueue.PRIORITY_DEFAULT);
        }
        if (needHome) {
            homeHintOnce();
        }
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private static Store.Block find(List<Store.Block> list, long fixedId, String direction) {
        return list.stream().filter(t -> Objects.equals(t.linkedFixedBlockId(), fixedId)
                && direction.equals(t.travelDirection())).findFirst().orElse(null);
    }

    private static boolean samePlace(double lat1, double lng1, double lat2, double lng2) {
        return String.format(Locale.ROOT, "%.4f,%.4f", lat1, lng1).equals(String.format(Locale.ROOT, "%.4f,%.4f", lat2, lng2));
    }

    private Integer legMinutes(long fixedId, String direction) {
        return db.jdbc().sql("SELECT minutes FROM block_travel WHERE block_id = ? AND direction = ? ORDER BY id DESC LIMIT 1")
                .param(fixedId).param(direction).query(Integer.class).optional().orElse(null);
    }

    /** 준비 시간(이동 블록 앞). v3.3 이전 사용자 lead 는 '출발 전체'라 이동분을 뺀다(§6-2). */
    static int prepMinutes(Store.Block b, int travelMinutes) {
        if (b.leadIncludesTravel()) {
            return Math.max(0, b.leadMinutes() - travelMinutes);
        }
        return b.leadMinutes();
    }

    /** 고정 일정이 지워지거나 끝나 필요 없어진 이동 블록. */
    void cancelTravel(Store.Block t, String why) {
        interventions.cancelRunningForBlock(t.id());
        store.setBlockStatus(t.id(), "cancelled");
        interventions.cancelBlockJobs(t.id());
        jobs.cancelForTarget(NOTICE_JOB, t.id());
        logs.add(t.id(), "note", "system", why);
    }

    // ---------------------------------------------------------------- 알림 (§3·§5)

    /**
     * 이동 블록의 알림 예약. 출발 화면(arrive 의 START)·끝 자동 완료(BLOCK_END)는 InterventionService 가 맡고,
     * 여기서는 가벼운 알림만: 준비(이동 시작 − lead), 도착(B.start), 촉박(A 끝 5분 전, A 가 dnd 면 끝 시각), 귀가(시작).
     */
    void scheduleJobs(Store.Block t) {
        jobs.cancelForTarget(NOTICE_JOB, t.id());
        interventions.scheduleBlockJobs(t);
        Instant now = clock.now();
        Store.Block b = t.linkedFixedBlockId() == null ? null : store.block(t.linkedFixedBlockId()).orElse(null);
        if (b == null) {
            return;
        }
        if ("home".equals(t.travelDirection())) {
            notice(t, "home", t.start(), now);
            return;
        }
        if (t.leadMinutes() > 0 && nz(t.travelTightMin()) == 0) {
            notice(t, "prep", t.start().minus(Duration.ofMinutes(t.leadMinutes())), now);
        }
        notice(t, "arrive", b.start(), now);
        if (nz(t.travelTightMin()) > 0) {
            Store.Block a = previousFixed(b);
            if (a != null) {
                Instant at = "dnd".equals(a.level()) ? a.end() : a.end().minus(Duration.ofMinutes(TIGHT_NOTICE_BEFORE_MIN));
                notice(t, "tight", at, now);
            }
        }
    }

    private void notice(Store.Block t, String kind, Instant at, Instant now) {
        if (!at.isAfter(now.minusSeconds(30))) {
            return;
        }
        ObjectNode p = Json.object();
        p.put("kind", kind);
        jobs.schedule(NOTICE_JOB, t.id(), at, t.version(), p);
    }

    /** 같은 날 B 직전에 끝나는 좌표 있는 활성 고정 블록. */
    Store.Block previousFixed(Store.Block b) {
        Instant dayStart = time.local(b.start()).toLocalDate().atStartOfDay(time.zone()).toInstant();
        return db.jdbc().sql("""
                        SELECT * FROM blocks WHERE kind = 'fixed' AND status IN ('planned', 'nudging', 'started', 'done')
                        AND lat IS NOT NULL AND id <> ? AND start_at >= ? AND start_at < ? ORDER BY start_at DESC LIMIT 1""")
                .param(b.id()).param(AssistantDb.ts(dayStart)).param(AssistantDb.ts(b.start()))
                .query(Store::block).optional().orElse(null);
    }

    private boolean onNotice(Jobs.Job job) {
        Store.Block t = store.block(job.targetId()).orElse(null);
        if (t == null || !t.travel() || job.expectedVersion() == null || t.version() != job.expectedVersion()
                || "cancelled".equals(t.status())) {
            return false;
        }
        Store.Block b = t.linkedFixedBlockId() == null ? null : store.block(t.linkedFixedBlockId()).orElse(null);
        if (b == null || "cancelled".equals(b.status())) {
            return false;
        }
        String title = store.blockTitle(b);
        String memo = store.fullMemo(b);
        String memoLine = memo.isBlank() ? "" : " · " + memo.lines().findFirst().orElse("");
        int minutes = (int) Duration.between(t.start(), t.end()).toMinutes();
        String kind = job.payload() == null ? "" : job.payload().path("kind").asString("");
        String text = switch (kind) {
            case "prep" -> t.leadMinutes() + "분 뒤 " + title + "(으)로 출발해요. 준비 시작하세요" + memoLine;
            case "arrive" -> {
                // 이동 블록에서 [시작](출발함)을 눌렀으면 알림 없이 일정을 시작한 것으로 본다.
                if (t.actualStart() != null) {
                    markFixedStarted(b);
                    yield null;
                }
                yield "지금 " + title + " 시간이에요" + memoLine;
            }
            case "tight" -> "끝나면 바로 출발해야 해요(" + title + "까지 약 " + minutes + "분, " + nz(t.travelTightMin())
                    + "분 부족)";
            case "home" -> "집으로 가요 · 약 " + minutes + "분";
            default -> null;
        };
        if (text == null) {
            return true;
        }
        // 이동 블록 알림은 방해 허용 정도와 관계없이 일반 알림(§4). 수면·방해 금지 시간대에는 무음.
        chat.sendAssistant(text, "notice", "normal", null, null, time.isQuiet(clock.now()));
        return true;
    }

    private void markFixedStarted(Store.Block b) {
        if (!"planned".equals(b.status())) {
            return;
        }
        db.update("""
                        UPDATE blocks SET status = 'started', actual_start = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?""",
                b.start(), b.id());
        logs.add(b.id(), "started", "system", "이동 블록에서 출발해 도착한 것으로 시작");
        plan.bumpAndSync();
    }

    private void homeHintOnce() {
        JsonNode at = settings.get("travel.home_hint_at");
        if (at != null && !at.isNull() && !at.asString("").isBlank()) {
            return;
        }
        settings.put("travel.home_hint_at", Json.MAPPER.valueToTree(time.iso(clock.now())));
        chat.sendAssistant("집 주소를 설정에 넣으면 첫 일정까지 이동 시간도 계산해요", "system", "normal", null, null, true);
    }

    // ---------------------------------------------------------------- 사용자 이동 시간 (§8)

    /** 이동 블록의 이동 시간을 사용자가 고친다(null 이면 자동으로 되돌리기). 그날을 다시 계산하는 것은 호출하는 쪽. */
    public Store.Block setUserMinutes(long travelId, Integer minutes) {
        Store.Block t = store.block(travelId).filter(Store.Block::travel)
                .orElseThrow(() -> com.seobin.phonedrop.assistant.ApiException.notFound("이동 블록#" + travelId + "이 없습니다"));
        if (minutes != null && (minutes < 1 || minutes > 600)) {
            throw com.seobin.phonedrop.assistant.ApiException.badRequest("이동 시간은 1~600분입니다");
        }
        db.update("UPDATE blocks SET travel_minutes_user = ? WHERE id = ?", minutes, travelId);
        logs.add(travelId, "note", "user", minutes == null ? "이동 시간을 자동으로 되돌림" : "이동 시간을 " + minutes + "분으로 직접 고침");
        return t;
    }

    // ---------------------------------------------------------------- 맥락 경고 (§3)

    /** 이동 촉박 경고. 예: "이동 촉박: 회의(15:00 끝) → 치과(15:30) 이동 약 42분 · 12분 부족". */
    public List<TravelService.Warning> tightWarnings(Instant from, Instant to) {
        List<TravelService.Warning> out = new ArrayList<>();
        for (Store.Block t : db.jdbc().sql("""
                        SELECT * FROM blocks WHERE link_role = 'travel' AND travel_direction = 'arrive' AND travel_tight_min > 0
                        AND status IN ('planned', 'nudging', 'started') AND start_at < ? AND end_at > ? ORDER BY start_at""")
                .param(AssistantDb.ts(to)).param(AssistantDb.ts(from)).query(Store::block).list()) {
            Store.Block b = store.block(t.linkedFixedBlockId()).orElse(null);
            if (b == null) {
                continue;
            }
            Store.Block a = previousFixed(b);
            Integer m = t.travelMinutesUser() != null ? t.travelMinutesUser() : legMinutes(b.id(), "arrive");
            out.add(new TravelService.Warning(b.id(), "이동 촉박: " + (a == null ? "앞 일정" : store.blockTitle(a) + "("
                    + time.hm(a.end()) + " 끝)") + " → " + store.blockTitle(b) + "(" + time.hm(b.start()) + ") 이동 약 "
                    + (m == null ? "?" : m) + "분 · " + t.travelTightMin() + "분 부족"));
        }
        return out;
    }

    /** API 응답용 한 줄 정보. */
    public Map<String, Object> info(Store.Block t) {
        Map<String, Object> m = new LinkedHashMap<>();
        Integer auto = t.linkedFixedBlockId() == null ? null
                : legMinutes(t.linkedFixedBlockId(), "home".equals(t.travelDirection()) ? "depart" : "arrive");
        m.put("minutes", t.travelMinutesUser() != null ? t.travelMinutesUser() : auto);
        m.put("auto_minutes", auto);
        m.put("user_minutes", t.travelMinutesUser());
        String source = t.linkedFixedBlockId() == null ? null : db.jdbc().sql("""
                        SELECT source FROM block_travel WHERE block_id = ? AND direction = ? ORDER BY id DESC LIMIT 1""")
                .param(t.linkedFixedBlockId()).param("home".equals(t.travelDirection()) ? "depart" : "arrive")
                .query(String.class).optional().orElse(null);
        m.put("source", t.travelMinutesUser() != null ? "user" : source);
        m.put("mode", settings.getString("travel.mode"));
        return m;
    }

    // ---------------------------------------------------------------- lead 정리 (§6, 한 번)

    /**
     * 배포 후 한 번: 이동 블록이 생기는 앞으로의 고정 일정 중 비서가 정한 lead 는 0 + '아직 안 정함'으로 둔다
     * (다음 아침 브리핑에서 비서가 준비 시간만 다시 정한다). 사용자가 정한 lead 는 그대로 둔다.
     */
    public Optional<String> migrateLeads() {
        int done = db.jdbc().sql("SELECT COUNT(*) FROM data_migrations WHERE name = ?").param(LEAD_MIGRATION)
                .query(Integer.class).single();
        if (done > 0) {
            return Optional.empty();
        }
        int target = 0;
        int changed = 0;
        int skipped = 0;
        for (Store.Block b : db.jdbc().sql("""
                        SELECT b.* FROM blocks b WHERE b.kind = 'fixed' AND b.status = 'planned' AND b.start_at > ?
                        AND EXISTS (SELECT 1 FROM blocks t WHERE t.linked_fixed_block_id = b.id AND t.link_role = 'travel'
                        AND t.travel_direction = 'arrive' AND t.status IN ('planned', 'nudging'))""")
                .param(AssistantDb.ts(clock.now())).query(Store::block).list()) {
            target++;
            if ("user".equals(b.prepSetBy()) || b.leadMinutes() == 0) {
                if ("user".equals(b.prepSetBy()) && b.leadMinutes() > 0) {
                    // 사용자 lead 는 그대로 두고 '이동 포함'으로 표시만 한다(준비 알림 = 이동 시작 − (lead − 이동)).
                    db.update("UPDATE blocks SET lead_includes_travel = TRUE WHERE id = ?", b.id());
                }
                skipped++;
                continue;
            }
            db.update("UPDATE blocks SET lead_minutes = 0, prep_set_by = NULL, updated_at = CURRENT_TIMESTAMP WHERE id = ?", b.id());
            logs.add(b.id(), "note", "system", "준비 시간 정리: 출발 " + b.leadMinutes() + "분 → 이동 블록으로 옮기고 준비 시간은 비서가 다시 정함");
            changed++;
        }
        String result = "target=" + target + " changed=" + changed + " skipped=" + skipped;
        db.update("INSERT INTO data_migrations(name, result, ran_at) VALUES (?, ?, ?)", LEAD_MIGRATION, result, clock.now());
        log.info("travel lead migration: {}", result);
        if (changed > 0) {
            plan.bumpAndSync();
        }
        return Optional.of(result);
    }
}
