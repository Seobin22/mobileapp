package com.seobin.phonedrop.assistant.distract;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import com.seobin.phonedrop.assistant.ApiException;
import com.seobin.phonedrop.assistant.AssistantClock;
import com.seobin.phonedrop.assistant.AssistantDb;
import com.seobin.phonedrop.assistant.InterventionService;
import com.seobin.phonedrop.assistant.Jobs;
import com.seobin.phonedrop.assistant.Json;
import com.seobin.phonedrop.assistant.PlanService;
import com.seobin.phonedrop.assistant.SettingsService;
import com.seobin.phonedrop.assistant.Store;
import com.seobin.phonedrop.assistant.TimeUtil;
import com.seobin.phonedrop.assistant.TurnQueue;
import com.seobin.phonedrop.assistant.Tx;
import com.seobin.phonedrop.assistant.agent.ChatService;
import com.seobin.phonedrop.assistant.agent.TimeWindows;
import com.seobin.phonedrop.assistant.insight.MemoryJobs;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 딴짓 감지(빌드문서 v4 §4~§6). 언제 볼지·단계는 코드가 정하고, LLM 은 애매한 제목 판단과 할 말만 한다.
 * 제목·도메인은 로그에 남기지 않고, 기록에서도 7일 뒤 지운다.
 */
@Service
public class DistractionService {

    public static final String TRIGGER = "distraction";
    public static final String CLEANUP_JOB = "distraction_cleanup";
    public static final List<String> KINDS = List.of("distract", "ambiguous", "neutral");
    static final Duration CACHE_DAYS = Duration.ofDays(7);
    private static final Logger log = LoggerFactory.getLogger(DistractionService.class);

    private final AssistantDb db;
    private final AssistantClock clock;
    private final Tx tx;
    private final Store store;
    private final TimeUtil time;
    private final SettingsService settings;
    private final Jobs jobs;
    private final TurnQueue turns;
    private final TimeWindows windows;
    private final InterventionService interventions;
    private final ObjectProvider<ChatService> chat;
    private final ObjectProvider<MemoryJobs> memoryJobs;
    private final ObjectProvider<PlanService> plan;
    private final boolean enabled;

    public DistractionService(AssistantDb db, AssistantClock clock, Tx tx, Store store, TimeUtil time,
                              SettingsService settings, Jobs jobs, TurnQueue turns, TimeWindows windows,
                              InterventionService interventions, ObjectProvider<ChatService> chat,
                              ObjectProvider<MemoryJobs> memoryJobs, ObjectProvider<PlanService> plan,
                              @Value("${assistant.worker.enabled:true}") boolean enabled) {
        this.db = db;
        this.clock = clock;
        this.tx = tx;
        this.store = store;
        this.time = time;
        this.settings = settings;
        this.jobs = jobs;
        this.turns = turns;
        this.windows = windows;
        this.interventions = interventions;
        this.chat = chat;
        this.memoryJobs = memoryJobs;
        this.plan = plan;
        this.enabled = enabled;
        jobs.register(CLEANUP_JOB, job -> {
            cleanup();
            scheduleCleanup();
            return true;
        });
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (enabled) {
            tx.exec(() -> {
                cleanup();
                scheduleCleanup();
            });
        }
    }

    private void scheduleCleanup() {
        db.update("UPDATE jobs SET status = 'cancelled' WHERE status = 'scheduled' AND type = ?", CLEANUP_JOB);
        LocalDate tomorrow = time.local(clock.now()).toLocalDate().plusDays(1);
        jobs.schedule(CLEANUP_JOB, 0, tomorrow.atTime(4, 30).atZone(time.zone()).toInstant(), null);
    }

    /** §6: 7일이 지난 제목·도메인을 지운다(앱 이름·시간은 남긴다). 판단 캐시도 7일. */
    public int cleanup() {
        Instant cut = clock.now().minus(CACHE_DAYS);
        int n = db.update("""
                        UPDATE distraction_logs SET title = NULL, domain = NULL
                        WHERE started_at < ? AND (title IS NOT NULL OR domain IS NOT NULL)""", AssistantDb.ts(cut));
        db.update("DELETE FROM distraction_judgments WHERE created_at < ?", AssistantDb.ts(cut));
        return n;
    }

    // ------------------------------------------------------------------ §4 딴짓 앱 목록(사용자 전용)

    public Map<String, Object> rules() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("rules", db.jdbc().sql("SELECT package, label, kind, set_by FROM app_rules ORDER BY kind, label")
                .query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("package", rs.getString(1));
                    m.put("label", rs.getString(2));
                    m.put("kind", rs.getString(3));
                    m.put("set_by", rs.getString(4));
                    return m;
                }).list());
        r.put("watch_categories", settings.get("distract.watch_categories"));
        return r;
    }

    /** PUT /v1/settings/app-rules {rules:[{package,label,kind,set_by?}], watch_categories?} — 목록 전체를 바꾼다. */
    public Map<String, Object> replaceRules(JsonNode body) {
        JsonNode arr = body.path("rules");
        if (!arr.isArray()) {
            throw ApiException.badRequest("rules 배열이 필요합니다");
        }
        List<Object[]> rows = new ArrayList<>();
        for (JsonNode r : arr) {
            String pkg = r.path("package").asString("").trim();
            String kind = r.path("kind").asString("");
            if (!pkg.matches("[A-Za-z0-9_.]{1,200}") || !KINDS.contains(kind)) {
                throw ApiException.unprocessable("앱 규칙 형식이 맞지 않습니다: " + pkg);
            }
            String label = r.path("label").asString(pkg);
            String setBy = "default".equals(r.path("set_by").asString("")) ? "default" : "user";
            rows.add(new Object[]{pkg, label.length() > 100 ? label.substring(0, 100) : label, kind, setBy});
        }
        db.update("DELETE FROM app_rules");
        for (Object[] row : rows) {
            if (!"neutral".equals(row[2])) {
                db.update("MERGE INTO app_rules(package, label, kind, set_by, updated_at) KEY(package) VALUES (?, ?, ?, ?, ?)",
                        row[0], row[1], row[2], row[3], clock.now());
            }
        }
        if (body.has("watch_categories")) {
            settings.put("distract.watch_categories", body.get("watch_categories"));
        }
        plan.getObject().bumpAndSync();
        return rules();
    }

    /** 블록·반복 일정의 감시 설정(사용자 전용). watch: on | off | null, allowed_apps: 패키지 배열. */
    public Map<String, Object> setBlockWatch(long blockId, JsonNode body) {
        store.block(blockId).orElseThrow(() -> ApiException.notFound("블록이 없습니다"));
        String watch = watchValue(body);
        String allowed = allowedValue(body);
        db.update("UPDATE blocks SET watch = ?, allowed_apps = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                watch, allowed, blockId);
        plan.getObject().bumpAndSync();
        return Map.of("ok", true, "id", blockId);
    }

    /** 블록 상세 화면: 지금 감시 설정. */
    public Map<String, Object> blockWatch(long blockId) {
        Store.Block b = store.block(blockId).orElseThrow(() -> ApiException.notFound("블록이 없습니다"));
        Watch w = watchOf(blockId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("block_id", blockId);
        m.put("title", store.blockTitle(b));
        m.put("watch", w.watch());
        m.put("allowed_apps", w.allowedApps() == null ? List.of() : Json.parse(w.allowedApps()));
        m.put("category", w.category());
        m.put("fixed", b.fixed());
        m.put("series_id", db.jdbc().sql("SELECT series_id FROM blocks WHERE id = ?").param(blockId).query(Long.class).optional().orElse(null));
        m.put("watched_now", watchable(b));
        return m;
    }

    public Map<String, Object> setSeriesWatch(long seriesId, JsonNode body) {
        String watch = watchValue(body);
        String allowed = allowedValue(body);
        int n = db.update("UPDATE fixed_series SET watch = ?, allowed_apps = ? WHERE id = ?", watch, allowed, seriesId);
        if (n == 0) {
            throw ApiException.notFound("반복 일정이 없습니다");
        }
        // 회차 생성 시 복사된다. 이미 만든 앞으로의 회차도 같이 바꾼다.
        db.update("""
                        UPDATE blocks SET watch = ?, allowed_apps = ? WHERE series_id = ? AND end_at > ?
                        AND status IN ('planned', 'nudging', 'started')""", watch, allowed, seriesId, clock.now());
        plan.getObject().bumpAndSync();
        return Map.of("ok", true, "id", seriesId);
    }

    private static String watchValue(JsonNode body) {
        String w = body.path("watch").isNull() ? null : body.path("watch").asString(null);
        if (w != null && !List.of("on", "off").contains(w)) {
            throw ApiException.unprocessable("watch 는 on·off·null 중 하나입니다");
        }
        return w;
    }

    private static String allowedValue(JsonNode body) {
        if (!body.path("allowed_apps").isArray()) {
            return null;
        }
        ArrayNode a = Json.array();
        for (JsonNode p : body.get("allowed_apps")) {
            String pkg = p.asString("");
            if (pkg.matches("[A-Za-z0-9_.]{1,200}")) {
                a.add(pkg);
            }
        }
        String s = Json.write(a);
        if (s.length() > 1000) {
            throw ApiException.unprocessable("허용 앱이 너무 많습니다");
        }
        return a.isEmpty() ? null : s;
    }

    // ------------------------------------------------------------------ §5.1 언제 보나

    record Watch(String watch, String allowedApps, String category) {
    }

    private Watch watchOf(long blockId) {
        return db.jdbc().sql("SELECT watch, allowed_apps, category FROM blocks WHERE id = ?").param(blockId)
                .query((rs, n) -> new Watch(rs.getString(1), rs.getString(2), rs.getString(3))).optional()
                .orElse(new Watch(null, null, null));
    }

    /** 감시 대상 블록인가: 계획 블록은 watch=on 이거나(null 이면서 분류가 watch_categories), 고정 블록은 watch=on 일 때만. */
    public boolean watchable(Store.Block b) {
        if (b.travel()) {
            return false;
        }
        Watch w = watchOf(b.id());
        if ("off".equals(w.watch())) {
            return false;
        }
        if ("on".equals(w.watch())) {
            return true;
        }
        if (b.fixed()) {
            return false;
        }
        String category = w.category() == null ? "other" : w.category();
        for (JsonNode c : settings.get("distract.watch_categories")) {
            if (c.asString("").equals(category)) {
                return true;
            }
        }
        return false;
    }

    /**
     * plan.sync 의 monitor 창(앞으로 24시간). 앱이 이 창과 로컬 시간대(dnd·멈춤)·고정 일정으로 혼자 판단한다(§5.1 끄는 조건).
     * record_only 창 = 협상으로 미룬 동안(개입이 snoozed 로 끝난 때 ~ 새 시작).
     */
    public ArrayNode monitorWindows(Instant now, Instant until) {
        ArrayNode out = Json.array();
        for (Store.Block b : db.jdbc().sql("""
                        SELECT * FROM blocks WHERE status IN ('planned', 'nudging', 'started') AND end_at > ? AND start_at < ?
                        ORDER BY start_at""")
                .param(AssistantDb.ts(now)).param(AssistantDb.ts(until))
                .query(Store::block).list()) {
            if (!watchable(b)) {
                continue;
            }
            ObjectNode o = out.addObject();
            o.put("block_id", b.id());
            o.put("title", store.blockTitle(b));
            o.put("from", time.iso(b.start()));
            o.put("to", time.iso(b.end()));
            o.put("fixed", b.fixed());
            o.put("record_only", false);
            Watch w = watchOf(b.id());
            o.set("allowed_apps", w.allowedApps() == null ? Json.array() : Json.parse(w.allowedApps()));
            snoozedSince(b).ifPresent(since -> {
                if (since.isBefore(b.start())) {
                    ObjectNode r = out.addObject();
                    r.put("block_id", b.id());
                    r.put("title", store.blockTitle(b));
                    r.put("from", time.iso(since));
                    r.put("to", time.iso(b.start()));
                    r.put("fixed", b.fixed());
                    r.put("record_only", true);
                    r.set("allowed_apps", o.get("allowed_apps"));
                }
            });
        }
        return out;
    }

    /** 협상으로 미뤘으면 그 시각(가장 최근 snoozed 개입의 끝). */
    private Optional<Instant> snoozedSince(Store.Block b) {
        return db.jdbc().sql("""
                        SELECT ended_at FROM interventions WHERE block_id = ? AND status = 'snoozed' AND ended_at IS NOT NULL
                        ORDER BY ended_at DESC LIMIT 1""")
                .param(b.id()).query((rs, n) -> AssistantDb.instant(rs, "ended_at")).optional();
    }

    /** 서버도 한 번 더 본다: 지금 그 블록을 감시해도 되나. null = 안 됨, true = 기록만, false = 단계까지. */
    Boolean monitorMode(Store.Block b, Instant now) {
        if (!List.of("planned", "nudging", "started").contains(b.status()) || !watchable(b)) {
            return null;
        }
        if (windows.dndAt(now).isPresent() || windows.paused(now)) {
            return null;
        }
        boolean otherFixed = store.fixedInProgress(now).stream()
                .anyMatch(f -> f.id() != b.id() && !f.travel() && !"started".equals(f.status()));
        if (otherFixed || store.travelInProgress(now)) {
            return null;
        }
        if (!now.isBefore(b.start()) && now.isBefore(b.end())) {
            return false;
        }
        Optional<Instant> since = snoozedSince(b);
        if (since.isPresent() && !now.isBefore(since.get()) && now.isBefore(b.start())) {
            return true;
        }
        return null;
    }

    // ------------------------------------------------------------------ §5.2·§5.5 딴짓 기록과 대응

    /**
     * POST /v1/distraction/events {client_key, block_id, package, app_label, kind, domain?, title?, started_at, minutes,
     * ended_at?}. 같은 client_key 는 한 구간(시작 보고 → 끝 보고).
     */
    public Map<String, Object> event(JsonNode e) {
        String key = e.path("client_key").asString("").trim();
        long blockId = e.path("block_id").asLong(0);
        if (key.isEmpty() || key.length() > 100 || blockId <= 0) {
            throw ApiException.badRequest("client_key·block_id 가 필요합니다");
        }
        int minutes = Math.max(0, Math.min(600, e.path("minutes").asInt(0)));
        Instant endedAt = e.hasNonNull("ended_at") ? TimeUtil.parse(e.get("ended_at").asString()) : null;
        Long existing = db.jdbc().sql("SELECT id FROM distraction_logs WHERE client_key = ?").param(key)
                .query(Long.class).optional().orElse(null);
        if (existing != null) {
            db.update("UPDATE distraction_logs SET minutes = GREATEST(minutes, ?), ended_at = COALESCE(?, ended_at) WHERE id = ?",
                    minutes, endedAt == null ? null : AssistantDb.ts(endedAt), existing);
            boolean returned = endedAt != null && interventions.endDistraction(blockId);
            refreshFacts(blockId);
            return Map.of("ok", true, "id", existing, "returned", returned);
        }
        Store.Block b = store.block(blockId).orElseThrow(() -> ApiException.notFound("블록이 없습니다"));
        Instant now = clock.now();
        Instant started = e.hasNonNull("started_at") ? TimeUtil.parse(e.get("started_at").asString()) : now;
        Boolean mode = monitorMode(b, now);
        if (mode == null && endedAt == null) {
            return Map.of("ok", true, "ignored", true);
        }
        boolean recordOnly = Boolean.TRUE.equals(mode);
        String kind = List.of("app", "web", "video").contains(e.path("kind").asString("")) ? e.get("kind").asString() : "app";
        String pkg = e.path("package").asString("").trim();
        String label = e.path("app_label").asString(pkg);
        long id = db.insert("""
                        INSERT INTO distraction_logs(block_id, logical_day, package, app_label, kind, domain, title, judged,
                        record_only, started_at, ended_at, minutes, client_key)
                        VALUES (?, ?, ?, ?, ?, ?, ?, 'distract', ?, ?, ?, ?, ?)""",
                blockId, time.logicalDay(started), cut(pkg, 200), cut(label, 100), kind,
                cut(e.path("domain").asString(null), 200), cut(e.path("title").asString(null), 300), recordOnly,
                started, endedAt, minutes, key);
        refreshFacts(blockId);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true);
        r.put("id", id);
        r.put("record_only", recordOnly);
        // §5.2: 서버와 끊긴 동안 모아 둔 기록(late)은 단계를 올리지 않는다.
        if (recordOnly || endedAt != null || e.path("late").asBoolean(false)) {
            return r; // 미룬 시간 안: 기록만(§5.5). 미룬 시각에 개입이 시작되면 맥락에 들어간다.
        }
        r.put("action", react(b, id, label, minutes));
        return r;
    }

    /** §5.5 코드가 단계를 정한다. */
    private String react(Store.Block b, long logId, String app, int minutes) {
        Optional<Store.Intervention> running = store.runningForBlock(b.id());
        if (!"started".equals(b.status())) {
            // (b) 시작 안 함
            if (running.isPresent()) {
                if (running.get().level() < InterventionService.LEVEL && interventions.raiseTo(running.get(), InterventionService.LEVEL)) {
                    return "raised_to_L3";
                }
                return "already_running";
            }
            Optional<Store.Intervention> i = interventions.beginForDistraction(b, null);
            i.ifPresent(x -> l1Message(b, x, logId, app, minutes));
            return i.isPresent() ? "started_intervention" : "blocked";
        }
        // (a) 진행 중 블록: 첫 딴짓이면 L1, 두 번째부터 L3
        if (running.isPresent()) {
            return "already_running";
        }
        int before = db.jdbc().sql("""
                        SELECT COUNT(*) FROM distraction_logs WHERE block_id = ? AND record_only = FALSE
                        AND judged = 'distract' AND id <> ?""")
                .param(b.id()).param(logId).query(Integer.class).single();
        Optional<Store.Intervention> i = interventions.beginForDistraction(b, before == 0 ? 1 : InterventionService.LEVEL);
        i.ifPresent(x -> l1Message(b, x, logId, app, minutes));
        return i.isPresent() ? (before == 0 ? "L1" : "L3") : "blocked";
    }

    /** L1 메시지: 비서 turn(distraction)으로 짧게. 블록당 distract.llm_max_per_block 을 넘으면 코드 문장. */
    private void l1Message(Store.Block b, Store.Intervention i, long logId, String app, int minutes) {
        int used = db.jdbc().sql("SELECT COUNT(*) FROM turns WHERE trigger = 'distraction' AND trigger_ref LIKE ?")
                .param("%\"block_id\":" + b.id() + ",%").query(Integer.class).single();
        if (used < settings.getInt("distract.llm_max_per_block")) {
            ObjectNode ref = Json.object();
            ref.put("block_id", b.id());
            ref.put("log_id", logId);
            ref.put("intervention_id", i.id());
            ref.put("app", app);
            ref.put("minutes", minutes);
            turns.enqueue(TRIGGER, ref, TurnQueue.PRIORITY_USER);
            return;
        }
        String text = template(app, minutes, store.blockTitle(b));
        chat.getObject().sendAssistant(text, "intervention", "high", null, null, true);
    }

    static String template(String app, int minutes, String block) {
        return app + " " + minutes + "분째예요. " + block + "으로 돌아가요.";
    }

    private static String cut(String s, int max) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return s.length() > max ? s.substring(0, max) : s;
    }

    private void refreshFacts(long blockId) {
        int[] agg = db.jdbc().sql("""
                        SELECT COALESCE(SUM(minutes), 0), COUNT(*) FROM distraction_logs
                        WHERE block_id = ? AND record_only = FALSE AND judged = 'distract'""")
                .param(blockId).query((rs, n) -> new int[]{rs.getInt(1), rs.getInt(2)}).single();
        String top = db.jdbc().sql("""
                        SELECT app_label FROM distraction_logs WHERE block_id = ? AND record_only = FALSE AND judged = 'distract'
                        GROUP BY app_label ORDER BY SUM(minutes) DESC LIMIT 1""")
                .param(blockId).query(String.class).optional().orElse(null);
        db.update("""
                        UPDATE block_facts SET distraction_minutes = ?, distraction_count = ?, distraction_top_app = ?
                        WHERE block_id = ?""", agg[0], agg[1], top, blockId);
        inferFocus(db, blockId);
    }

    /** §6 정리 카드: 딴짓이 실제 블록 시간의 30% 이상이면 집중을 '안 됨'(1)으로 미리 채운다(inferred, 통계에는 안 들어감). */
    public static void inferFocus(AssistantDb db, long blockId) {
        db.update("""
                        UPDATE block_facts SET focus = 1, focus_src = 'inferred'
                        WHERE block_id = ? AND (focus_src IS NULL OR focus_src = 'inferred') AND actual_minutes > 0
                        AND distraction_minutes IS NOT NULL AND distraction_minutes * 10 >= actual_minutes * 3""", blockId);
    }

    /** 지난 기록 화면(§7): 블록의 딴짓 구간. 제목은 7일 안이면 남아 있다(정리 작업이 지운다). */
    public List<Map<String, Object>> logsForBlock(long blockId) {
        return db.jdbc().sql("""
                        SELECT id, app_label, kind, domain, title, judged, record_only, minutes, started_at FROM distraction_logs
                        WHERE block_id = ? ORDER BY started_at""")
                .param(blockId).query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("app", rs.getString("app_label"));
                    m.put("kind", rs.getString("kind"));
                    m.put("domain", rs.getString("domain"));
                    m.put("title", rs.getString("title"));
                    m.put("judged", rs.getString("judged"));
                    m.put("record_only", rs.getBoolean("record_only"));
                    m.put("minutes", rs.getInt("minutes"));
                    m.put("started_at", time.iso(AssistantDb.instant(rs, "started_at")));
                    return m;
                }).list();
    }

    /** 정리 카드 한 줄: "딴짓 12분(인스타)". 없으면 null. */
    public String cardLine(long blockId) {
        return db.jdbc().sql("""
                        SELECT distraction_minutes, distraction_top_app FROM block_facts WHERE block_id = ?
                        AND distraction_minutes > 0""")
                .param(blockId).query((rs, n) -> "딴짓 " + rs.getInt(1) + "분" + (rs.getString(2) == null ? "" : "(" + rs.getString(2) + ")"))
                .optional().orElse(null);
    }

    /** [이건 작업이었어](§7): 그 구간을 통계에서 빼고(judged=related), 제목 판정 캐시도 related 로. */
    public Map<String, Object> markRelated(long logId) {
        record L(long blockId, String domain, String title) {
        }
        L l = db.jdbc().sql("SELECT block_id, domain, title FROM distraction_logs WHERE id = ?").param(logId)
                .query((rs, n) -> new L(rs.getLong(1), rs.getString(2), rs.getString(3))).optional()
                .orElseThrow(() -> ApiException.notFound("기록이 없습니다"));
        db.update("UPDATE distraction_logs SET judged = 'related' WHERE id = ?", logId);
        if (l.title() != null) {
            store.block(l.blockId()).ifPresent(b -> db.update(
                    "MERGE INTO distraction_judgments(cache_key, verdict, created_at) KEY(cache_key) VALUES (?, 'related', ?)",
                    cacheKey(b, l.domain(), l.title()), clock.now()));
        }
        refreshFacts(l.blockId());
        return Map.of("ok", true);
    }

    // ------------------------------------------------------------------ §5.4 애매한 제목 판단

    /** POST /v1/distraction/judge {block_id, kind, domain, title} → {verdict: related|unrelated|unsure, cached}. */
    public Map<String, Object> judge(JsonNode in) {
        Store.Block b = store.block(in.path("block_id").asLong()).orElseThrow(() -> ApiException.notFound("블록이 없습니다"));
        String domain = cut(in.path("domain").asString(null), 200);
        String title = cut(in.path("title").asString(null), 300);
        if (title == null && domain == null) {
            return Map.of("verdict", "unsure", "cached", false);
        }
        String key = cacheKey(b, domain, title);
        Instant now = clock.now();
        Optional<String> hit = db.jdbc().sql("SELECT verdict FROM distraction_judgments WHERE cache_key = ? AND created_at >= ?")
                .param(key).param(AssistantDb.ts(now.minus(CACHE_DAYS))).query(String.class).optional();
        if (hit.isPresent()) {
            return Map.of("verdict", hit.get(), "cached", true);
        }
        int lastHour = db.jdbc().sql("SELECT COUNT(*) FROM distraction_judgments WHERE created_at >= ?")
                .param(AssistantDb.ts(now.minus(Duration.ofHours(1)))).query(Integer.class).single();
        if (lastHour >= settings.getInt("distract.judge_max_per_hour")) {
            return Map.of("verdict", "unsure", "cached", false, "limited", true);
        }
        String verdict = askLlm(b, domain, title);
        db.update("MERGE INTO distraction_judgments(cache_key, verdict, created_at) KEY(cache_key) VALUES (?, ?, ?)",
                key, verdict, now);
        return Map.of("verdict", verdict, "cached", false);
    }

    String cacheKey(Store.Block b, String domain, String title) {
        Long goalId = b.goalId() != null ? b.goalId()
                : b.taskId() == null ? null : store.task(b.taskId()).map(Store.Task::goalId).orElse(null);
        String scope = b.taskId() != null ? "t" + b.taskId() : goalId != null ? "g" + goalId : "c" + watchOf(b.id()).category();
        return scope + "|" + (domain == null ? "" : domain) + "|" + sha(title == null ? "" : title);
    }

    private static String sha(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)))
                    .substring(0, 24);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String askLlm(Store.Block b, String domain, String title) {
        String task = b.taskId() == null ? "" : store.task(b.taskId()).map(Store.Task::title).orElse("");
        Long goalId = b.goalId() != null ? b.goalId()
                : b.taskId() == null ? null : store.task(b.taskId()).map(Store.Task::goalId).orElse(null);
        String goal = goalId == null ? "" : store.goal(goalId).map(Store.Goal::title).orElse("");
        String system = """
                사용자가 지금 해야 할 블록과, 폰에서 보고 있는 웹 페이지·영상 제목이 주어진다.
                그 페이지·영상이 블록의 일에 도움이 되는지 판단해 JSON 한 줄로만 답한다: {"verdict":"related"} 또는
                {"verdict":"unrelated"} 또는 {"verdict":"unsure"}. 강의·문제 풀이·자료 조사처럼 일과 관련 있으면 related,
                예능·게임·쇼핑·관계없는 영상이면 unrelated, 판단하기 어려우면 unsure.""";
        String text = "블록: " + store.blockTitle(b) + "\n할 일: " + task + "\n목표: " + goal + "\n분류: "
                + watchOf(b.id()).category() + "\n도메인: " + (domain == null ? "" : domain) + "\n제목: " + (title == null ? "" : title);
        try {
            String v = MemoryJobs.json(memoryJobs.getObject().ask(system, text).text()).path("verdict").asString("unsure");
            return List.of("related", "unrelated", "unsure").contains(v) ? v : "unsure";
        } catch (RuntimeException e) {
            log.warn("distraction judge failed: {}", e.getClass().getSimpleName());
            return "unsure";
        }
    }

    // ------------------------------------------------------------------ §5.6·§6 맥락

    /** 이 블록 오늘 딴짓 요약(앱·분, 미룬 동안은 따로). 제목은 넣지 않는다. */
    public String blockSummary(long blockId) {
        record Row(String app, int minutes, boolean recordOnly) {
        }
        List<Row> rows = db.jdbc().sql("""
                        SELECT app_label, SUM(minutes) AS m, record_only FROM distraction_logs
                        WHERE block_id = ? AND judged = 'distract' GROUP BY app_label, record_only ORDER BY m DESC""")
                .param(blockId).query((rs, n) -> new Row(rs.getString(1), rs.getInt(2), rs.getBoolean(3))).list();
        if (rows.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Row r : rows) {
            sb.append("- ").append(r.recordOnly() ? "미룬 동안 " : "블록 중 ").append(r.app()).append(' ')
                    .append(r.minutes()).append("분\n");
        }
        return sb.toString();
    }

    /** 앱 규칙(plan.sync): [{package, kind}] */
    public ArrayNode rulesForSync() {
        ArrayNode a = Json.array();
        db.jdbc().sql("SELECT package, kind FROM app_rules").query((rs, n) -> {
            ObjectNode o = a.addObject();
            o.put("package", rs.getString(1));
            o.put("kind", rs.getString(2));
            return o;
        }).list();
        return a;
    }
}
