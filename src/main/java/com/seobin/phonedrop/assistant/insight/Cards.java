package com.seobin.phonedrop.assistant.insight;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.seobin.phonedrop.assistant.ApiException;
import com.seobin.phonedrop.assistant.AssistantClock;
import com.seobin.phonedrop.assistant.AssistantDb;
import com.seobin.phonedrop.assistant.BlockLogs;
import com.seobin.phonedrop.assistant.GoalCoverage;
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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 정리 카드(빌드문서 v3.5 §3)와 아침 카드(§4). 카드는 코드가 템플릿으로 만들고 LLM 을 부르지 않는다.
 * 버튼 답은 코드가 바로 저장한다(turn 없음). 글이 있으면 백그라운드 작업 outcome_notes 를 한 번.
 */
@Component
public class Cards {

    public static final String CHECK_JOB = "card_check";
    public static final String EVENING_AFTER = "evening_after_card";
    public static final String OUTCOME_KIND = "outcome_card";
    public static final String DAY_KIND = "day_card";
    static final Duration CHECK_EVERY = Duration.ofMinutes(15);
    static final Duration NEXT_BLOCK_MARGIN = Duration.ofMinutes(15);
    static final Map<String, String> RESULT_LABELS = Map.of("done", "완료", "partial", "일부 완료", "missed", "놓침");

    private final AssistantDb db;
    private final AssistantClock clock;
    private final Tx tx;
    private final Jobs jobs;
    private final Store store;
    private final TimeUtil time;
    private final SettingsService settings;
    private final ChatService chat;
    private final TurnQueue turns;
    private final BlockFacts facts;
    private final DayFacts days;
    private final MemoryJobs memoryJobs;
    private final PatternStats stats;
    private final InterventionService interventions;
    private final GoalCoverage coverage;
    private final BlockLogs logs;
    private final PlanService plan;
    private final boolean workerEnabled;
    private volatile boolean enabled;

    public Cards(AssistantDb db, AssistantClock clock, Tx tx, Jobs jobs, Store store, TimeUtil time,
                 SettingsService settings, ChatService chat, TurnQueue turns, BlockFacts facts, DayFacts days,
                 MemoryJobs memoryJobs, PatternStats stats, InterventionService interventions, GoalCoverage coverage,
                 BlockLogs logs, PlanService plan, @Value("${assistant.worker.enabled:true}") boolean workerEnabled,
                 @Value("${assistant.cards.enabled:true}") boolean enabled) {
        this.db = db;
        this.clock = clock;
        this.tx = tx;
        this.jobs = jobs;
        this.store = store;
        this.time = time;
        this.settings = settings;
        this.chat = chat;
        this.turns = turns;
        this.facts = facts;
        this.days = days;
        this.memoryJobs = memoryJobs;
        this.stats = stats;
        this.interventions = interventions;
        this.coverage = coverage;
        this.logs = logs;
        this.plan = plan;
        this.workerEnabled = workerEnabled;
        this.enabled = enabled;
        jobs.register(CHECK_JOB, this::onCheck);
        jobs.register(EVENING_AFTER, this::onEveningAfter);
    }

    /** 테스트 전용 스위치(운영은 설정 assistant.cards.enabled=true). */
    public void setEnabled(boolean on) {
        this.enabled = on;
    }

    public boolean enabled() {
        return enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (workerEnabled) {
            tx.exec(this::ensureCheckJob);
            // 한 번만 하는 작업(기존 블록 채우기·memory 이전 예약)이 실패해도 서버 시작은 막지 않는다(다음 시작 때 다시).
            try {
                tx.exec(memoryJobs::ensureOneTimeJobs);
            } catch (RuntimeException e) {
                org.slf4j.LoggerFactory.getLogger(Cards.class).warn("v3.5 one-time jobs failed", e);
            }
        }
    }

    public void ensureCheckJob() {
        db.update("UPDATE jobs SET status = 'cancelled', updated_at = CURRENT_TIMESTAMP WHERE status = 'scheduled' AND type = ?",
                CHECK_JOB);
        jobs.schedule(CHECK_JOB, 0, clock.now().plus(CHECK_EVERY), null);
    }

    private boolean onCheck(Jobs.Job job) {
        jobs.schedule(CHECK_JOB, 0, clock.now().plus(CHECK_EVERY), null);
        return checkDaytime() != null;
    }

    // ------------------------------------------------------------------ 대상

    record Target(BlockFacts.Fact fact, Store.Block block) {
    }

    /** §3.1 대상. 오래된 것은 expired, 채팅에서 다 채운 것은 answered 로 정리한다. */
    List<Target> targets(Instant now) {
        LocalDate today = time.logicalDay(now);
        int expireDays = settings.getInt("card.expire_days");
        facts.safeRefreshBetween(now.minus(Duration.ofDays(expireDays + 1L)), now.plus(Duration.ofHours(1)));
        db.update("""
                        UPDATE block_facts SET card_state = 'expired', updated_at = ? WHERE card_state IN ('none', 'queued', 'sent')
                        AND logical_day <= ?""", now, today.minusDays(expireDays));
        List<Target> out = new ArrayList<>();
        for (BlockFacts.Fact f : facts.between(today.minusDays(expireDays - 1L), today)) {
            if (!List.of("planned", "fixed").contains(f.kind()) || "cancelled".equals(f.statusFinal())
                    || !List.of("none", "queued").contains(f.cardState())) {
                continue;
            }
            Store.Block b = store.block(f.blockId()).orElse(null);
            if (b == null || b.start().isAfter(now) || !f.ended(now, b.end())) {
                continue;
            }
            if (BlockFacts.rank(f.outcomeSrc()) >= 3 && BlockFacts.rank(f.focusSrc()) >= 3
                    && BlockFacts.rank(f.reasonSrc()) >= 3) {
                facts.setCardState(List.of(f.blockId()), "answered", null);
                continue;
            }
            out.add(new Target(f, b));
        }
        return out;
    }

    // ------------------------------------------------------------------ 낮 카드(§3.2)

    /** 낮 카드를 보낼지 판단하고 보냈으면 카드 id. 이유가 있어 안 보냈으면 null. */
    public Long checkDaytime() {
        if (!enabled) {
            return null;
        }
        Instant now = clock.now();
        LocalDate today = time.logicalDay(now);
        TimeUtil.Window active = time.activeWindow(today);
        Instant evening = eveningAt(today);
        if (!active.contains(now) || !now.isBefore(evening) || time.isQuiet(now)) {
            return null;
        }
        List<Map<String, Object>> daytime = db.jdbc().sql("""
                        SELECT id, status, created_at FROM cards WHERE kind = 'outcome' AND evening = FALSE AND logical_day = ?
                        ORDER BY id""")
                .param(today).query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("status", rs.getString(2));
                    m.put("at", AssistantDb.instant(rs, "created_at"));
                    return m;
                }).list();
        if (daytime.size() >= settings.getInt("card.daytime_max")) {
            return null;
        }
        if (daytime.size() >= 2) {
            List<Map<String, Object>> last2 = daytime.subList(daytime.size() - 2, daytime.size());
            if (last2.stream().noneMatch(m -> "submitted".equals(m.get("status")))) {
                return null; // 두 번 연속 무시 → 그날 남은 건 저녁으로
            }
        }
        if (!store.startedBlocks().isEmpty() || !store.runningInterventions().isEmpty()) {
            return null;
        }
        boolean soon = store.activeBlocks().stream()
                .anyMatch(b -> !b.travel() && b.interventionAt().isAfter(now) && !b.interventionAt().isAfter(now.plus(NEXT_BLOCK_MARGIN)));
        if (soon || store.dndInProgress(now, null).isPresent()) {
            return null;
        }
        List<Target> targets = targets(now);
        if (targets.isEmpty()) {
            return null;
        }
        Instant last = db.jdbc().sql("SELECT MAX(created_at) AS m FROM cards WHERE kind = 'outcome' AND logical_day = ?")
                .param(today).query((rs, n) -> AssistantDb.instant(rs, "m")).optional().orElse(null);
        Instant since = last == null ? active.start() : last;
        boolean enough = targets.size() >= settings.getInt("card.min_blocks")
                || Duration.between(since, now).toMinutes() >= settings.getInt("card.min_gap_hours") * 60L;
        if (!enough) {
            return null;
        }
        boolean quiet = store.fixedInProgress(now).stream().anyMatch(b -> "quiet".equals(b.level()));
        return send(today, targets, false, quiet);
    }

    private Instant eveningAt(LocalDate day) {
        return time.activeWindow(day).end().minus(Duration.ofMinutes(settings.getInt("evening.before_sleep_minutes")));
    }

    /** 카드를 만든다. 이전 미제출 카드는 닫고 그 블록을 새 카드로 옮긴다. */
    long send(LocalDate day, List<Target> targets, boolean evening, boolean silent) {
        Instant now = clock.now();
        List<Long> open = db.jdbc().sql("SELECT id FROM cards WHERE kind = 'outcome' AND status = 'sent'").query(Long.class).list();
        for (long id : open) {
            closeCard(id, "closed");
        }
        // 닫은 카드의 블록이 대상에 다시 들어오도록 한 번 더 읽는다
        List<Target> all = open.isEmpty() ? targets : targets(now);
        List<Long> ids = all.stream().map(t -> t.fact().blockId()).toList();
        ObjectNode payload = payload(all, evening);
        long cardId = db.insert("""
                        INSERT INTO cards(kind, logical_day, evening, status, block_ids, payload, created_at)
                        VALUES ('outcome', ?, ?, 'sent', ?, ?, ?)""",
                day, evening, Json.write(Json.MAPPER.valueToTree(ids)), Json.write(payload), now);
        String text = (evening ? "하루 마무리 전에 " : "") + (ids.isEmpty() ? "오늘 하루 정리 부탁해요"
                : "오늘 " + ids.size() + "개 정리 부탁해요");
        long messageId = chat.sendAssistant(text, OUTCOME_KIND, "normal", null, null, silent);
        db.update("UPDATE messages SET card_id = ? WHERE id = ?", cardId, messageId);
        db.update("UPDATE cards SET message_id = ? WHERE id = ?", messageId, cardId);
        facts.setCardState(ids, "sent", cardId);
        return cardId;
    }

    private void closeCard(long cardId, String status) {
        db.update("UPDATE cards SET status = ?, closed_at = ? WHERE id = ?", status, clock.now(), cardId);
        db.update("UPDATE block_facts SET card_state = 'queued', updated_at = ? WHERE card_id = ? AND card_state = 'sent'",
                clock.now(), cardId);
    }

    ObjectNode payload(List<Target> targets, boolean evening) {
        ObjectNode p = Json.object();
        p.put("evening", evening);
        ArrayNode blocks = p.putArray("blocks");
        for (Target t : targets) {
            BlockFacts.Fact f = t.fact();
            Store.Block b = t.block();
            ObjectNode o = blocks.addObject();
            o.put("block_id", b.id());
            o.put("title", store.blockTitle(b));
            o.put("start", time.iso(b.start()));
            o.put("end", time.iso(b.end()));
            o.put("fixed", b.fixed());
            o.put("category", f.category());
            String unit = progressUnit(f.goalId());
            if (unit != null) {
                o.put("progress_unit", unit);
            }
            o.set("outcome", field(f.outcome(), f.outcomeSrc()));
            o.set("focus", field(f.focus(), f.focusSrc()));
            o.set("reason_tags", field(f.reasonTags() == null ? null : Json.parse(f.reasonTags()), f.reasonSrc()));
            o.set("progress_done", field(f.progressDone(), f.progressSrc()));
            db.jdbc().sql("SELECT distraction_minutes, distraction_top_app FROM block_facts WHERE block_id = ? AND distraction_minutes > 0")
                    .param(b.id()).query((rs, n) -> "딴짓 " + rs.getInt(1) + "분" + (rs.getString(2) == null ? "" : "(" + rs.getString(2) + ")"))
                    .optional().ifPresent(line -> o.put("distraction", line)); // v4 §6
        }
        ObjectNode reasons = p.putObject("reason_chips");
        reasons.set("done", chips(BlockFacts.DONE_REASONS));
        reasons.set("not_done", chips(BlockFacts.NOT_DONE_REASONS));
        return p;
    }

    /** {value, src, confirmed}: user·text 는 확정(진하게), status·inferred 는 추정(연하게). */
    private static ObjectNode field(Object value, String src) {
        ObjectNode o = Json.object();
        if (value == null) {
            o.putNull("value");
        } else if (value instanceof JsonNode n) {
            o.set("value", n);
        } else if (value instanceof Integer i) {
            o.put("value", i);
        } else {
            o.put("value", value.toString());
        }
        if (src == null) {
            o.putNull("src");
        } else {
            o.put("src", src);
        }
        o.put("confirmed", BlockFacts.rank(src) >= 3);
        return o;
    }

    private static ArrayNode chips(Map<String, String> m) {
        ArrayNode a = Json.array();
        m.forEach((k, v) -> {
            ObjectNode o = a.addObject();
            o.put("tag", k);
            o.put("label", v);
        });
        return a;
    }

    private String progressUnit(Long goalId) {
        if (goalId == null) {
            return null;
        }
        String progress = store.goal(goalId).map(Store.Goal::progress).orElse(null);
        if (progress == null || progress.isBlank()) {
            return null;
        }
        try {
            JsonNode p = Json.parse(progress);
            String unit = p.path("unit").asString("");
            return unit.isBlank() ? null : unit;
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ 저녁(§3.2 저녁 마지막 정리)

    /**
     * 저녁 회고 시각. 통계를 먼저 계산하고(§5), 대상이 있으면 정리 카드를 보낸 뒤 제출되거나 card.evening_wait_min 분이
     * 지나면 기존 저녁 회고 turn 을 실행한다. 대상이 없으면 바로 회고 turn.
     */
    public void evening(LocalDate day) {
        stats.compute();
        days.computeCounts(day);
        Instant now = clock.now();
        if (!enabled || store.dndInProgress(now, null).isPresent()) {
            enqueueEveningTurn(day);
            return;
        }
        List<Target> targets = targets(now);
        if (targets.isEmpty()) {
            enqueueEveningTurn(day);
            return;
        }
        long cardId = send(day, targets, true, false);
        jobs.schedule(EVENING_AFTER, cardId, now.plus(Duration.ofMinutes(settings.getInt("card.evening_wait_min"))), null);
    }

    private boolean onEveningAfter(Jobs.Job job) {
        String day = db.jdbc().sql("SELECT logical_day FROM cards WHERE id = ?").param(job.targetId())
                .query((rs, n) -> rs.getObject(1, LocalDate.class).toString()).optional().orElse(null);
        if (day == null) {
            return false;
        }
        return enqueueEveningTurn(LocalDate.parse(day));
    }

    /** 저녁 회고 turn 은 하루 한 번만(새 turn 을 따로 만들지 않는다). */
    boolean enqueueEveningTurn(LocalDate day) {
        ObjectNode ref = Json.object();
        ref.put("logical_day", day.toString());
        boolean exists = db.jdbc().sql("SELECT COUNT(*) FROM turns WHERE trigger = 'evening' AND trigger_ref = ?")
                .param(Json.write(ref)).query(Integer.class).single() > 0;
        if (exists) {
            return false;
        }
        turns.enqueue("evening", ref, TurnQueue.PRIORITY_DEFAULT);
        return true;
    }

    // ------------------------------------------------------------------ 제출(§3.4)

    public Map<String, Object> submit(long cardId, JsonNode body) {
        record Card(String kind, LocalDate day, boolean evening, String status, List<Long> ids) {
        }
        Card card = db.jdbc().sql("SELECT kind, logical_day, evening, status, block_ids FROM cards WHERE id = ?").param(cardId)
                .query((rs, n) -> {
                    List<Long> ids = new ArrayList<>();
                    Json.parse(rs.getString(5)).forEach(x -> ids.add(x.asLong()));
                    return new Card(rs.getString(1), rs.getObject(2, LocalDate.class), rs.getBoolean(3), rs.getString(4), ids);
                }).optional().orElseThrow(() -> ApiException.notFound("카드#" + cardId + "이 없습니다"));
        if (!"outcome".equals(card.kind())) {
            throw ApiException.badRequest("정리 카드가 아닙니다");
        }
        if (!List.of("sent", "later").contains(card.status())) {
            throw ApiException.unprocessable("이미 닫힌 카드예요(" + card.status() + ")");
        }
        Map<Long, JsonNode> entries = new LinkedHashMap<>();
        for (JsonNode e : body.path("blocks")) {
            long id = e.path("block_id").asLong(-1);
            if (card.ids().contains(id)) {
                entries.put(id, e);
            }
        }
        boolean anyNote = false;
        boolean changedStatus = false;
        int saved = 0;
        for (long id : card.ids()) {
            JsonNode e = entries.get(id);
            boolean touched = false;
            if (e != null) {
                String outcome = e.path("outcome").asString("");
                if (RESULT_LABELS.containsKey(outcome)) {
                    changedStatus |= applyOutcome(id, outcome);
                    facts.setHuman(id, "outcome", outcome, "user");
                    touched = true;
                }
                if (e.path("progress_done").isNumber() && e.get("progress_done").asInt() > 0) {
                    int n = Math.min(e.get("progress_done").asInt(), 1000);
                    if (facts.setHuman(id, "progress", n, "user")) {
                        addProgress(id, n);
                    }
                    touched = true;
                }
                int focus = e.path("focus").asInt(0);
                if (focus >= 1 && focus <= 3) {
                    facts.setHuman(id, "focus", focus, "user");
                    touched = true;
                }
                if (e.path("reason_tags").isArray()) {
                    List<String> tags = new ArrayList<>();
                    e.get("reason_tags").forEach(t -> {
                        if (BlockFacts.validReason(t.asString("")) && !tags.contains(t.asString())) {
                            tags.add(t.asString());
                        }
                    });
                    if (!tags.isEmpty()) {
                        facts.setHuman(id, "reason", tags, "user");
                        touched = true;
                    }
                }
                for (String note : List.of("outcome_note", "focus_note", "reason_note", "extra_note")) {
                    String text = e.path(note).asString("");
                    if (!text.isBlank()) {
                        facts.setNote(id, note, text);
                        touched = true;
                        anyNote = true;
                    }
                }
            }
            facts.setCardState(List.of(id), touched ? "answered" : "skipped", cardId);
            if (touched) {
                saved++;
            }
        }
        String dayNote = body.path("day_note").asString("");
        if (card.evening() && !dayNote.isBlank()) {
            days.setNote(card.day(), "day_note", dayNote);
            anyNote = true;
        }
        db.update("UPDATE cards SET status = 'submitted', closed_at = ? WHERE id = ?", clock.now(), cardId);
        if (changedStatus) {
            plan.bumpAndSync();
        }
        if (anyNote) {
            ObjectNode ref = Json.object();
            ref.put("card_id", cardId);
            memoryJobs.enqueue("outcome_notes", ref);
        }
        if (card.evening()) {
            jobs.cancelForTarget(EVENING_AFTER, cardId);
            enqueueEveningTurn(card.day());
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true);
        r.put("card_id", cardId);
        r.put("saved", saved);
        r.put("notes", anyNote);
        return r;
    }

    /** [나중에]: 그 블록들을 queued 로 되돌린다. */
    public Map<String, Object> later(long cardId) {
        String status = db.jdbc().sql("SELECT status FROM cards WHERE id = ?").param(cardId).query(String.class).optional()
                .orElseThrow(() -> ApiException.notFound("카드#" + cardId + "이 없습니다"));
        if ("sent".equals(status)) {
            closeCard(cardId, "later");
        }
        boolean evening = db.jdbc().sql("SELECT evening FROM cards WHERE id = ?").param(cardId).query(Boolean.class).single();
        if (evening) {
            String day = db.jdbc().sql("SELECT logical_day FROM cards WHERE id = ?").param(cardId)
                    .query((rs, n) -> rs.getObject(1, LocalDate.class).toString()).single();
            jobs.cancelForTarget(EVENING_AFTER, cardId);
            enqueueEveningTurn(LocalDate.parse(day));
        }
        return Map.of("ok", true, "card_id", cardId);
    }

    /**
     * 카드에서 고른 결과를 블록 상태에 반영한다(turn 없음, T7). 아직 열려 있던 블록이 missed·partial 이 되면 기존 missed 흐름
     * (block_missed turn)을 그대로 따른다. 이미 같은 상태면 아무것도 안 한다. 바뀌었으면 true.
     */
    boolean applyOutcome(long blockId, String outcome) {
        Store.Block b = store.block(blockId).orElse(null);
        Instant now = clock.now();
        if (b == null || b.travel() || "cancelled".equals(b.status()) || b.start().isAfter(now) || outcome.equals(b.status())) {
            return false;
        }
        String before = b.status();
        boolean wasOpen = List.of("planned", "nudging", "started").contains(before);
        interventions.cancelRunningForBlock(blockId);
        interventions.cancelBlockJobs(blockId);
        Instant end = b.actualEnd() != null ? b.actualEnd() : (now.isBefore(b.end()) ? now : b.end());
        Instant start = "missed".equals(outcome) ? b.actualStart() : (b.actualStart() != null ? b.actualStart() : b.start());
        db.update("""
                        UPDATE blocks SET status = ?, actual_start = ?, actual_end = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?""",
                outcome, start, "missed".equals(outcome) ? null : end, blockId);
        logs.add(blockId, "note", "user", "정리 카드에서 결과: " + RESULT_LABELS.getOrDefault(before, before) + " → "
                + RESULT_LABELS.get(outcome));
        coverage.onBlockClosed(blockId);
        facts.refresh(blockId);
        if (wasOpen && !"done".equals(outcome)) {
            ObjectNode ref = Json.object();
            ref.put("block_id", blockId);
            ref.put("reason", "card_" + outcome);
            turns.enqueue("block_missed", ref, TurnQueue.PRIORITY_DEFAULT);
        }
        return true;
    }

    /** 진도 스테퍼 값을 목표 진도(done)에 더한다(기존 progress JSON). */
    private void addProgress(long blockId, int n) {
        BlockFacts.Fact f = facts.get(blockId).orElse(null);
        if (f == null || f.goalId() == null) {
            return;
        }
        Store.Goal g = store.goal(f.goalId()).orElse(null);
        if (g == null || g.progress() == null || g.progress().isBlank()) {
            return;
        }
        ObjectNode p = (ObjectNode) Json.parse(g.progress());
        int done = p.path("done").asInt(0) + n;
        if (p.hasNonNull("total") && p.get("total").asInt() > 0) {
            done = Math.min(done, p.get("total").asInt());
        }
        p.put("done", done);
        db.update("UPDATE goals SET progress = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?", Json.write(p), g.id());
        logs.add(blockId, "progress", "user", "정리 카드: " + n + p.path("unit").asString("") + " 진행");
    }

    // ------------------------------------------------------------------ 아침 카드(§4)

    /** 아침 브리핑 뒤에 붙인다. 이미 보냈거나 답이 있으면 다시 붙이지 않는다. */
    public Long sendDayCard(LocalDate day) {
        if (!enabled) {
            return null;
        }
        DayFacts.Day d = days.get(day).orElse(null);
        if (d != null && (d.cardSent() || d.sleep() != null || d.condition() != null)) {
            return null;
        }
        days.markCardSent(day);
        ObjectNode payload = Json.object();
        payload.put("day", day.toString());
        long cardId = db.insert("""
                        INSERT INTO cards(kind, logical_day, evening, status, payload, created_at) VALUES ('day', ?, FALSE, 'sent', ?, ?)""",
                day, Json.write(payload), clock.now());
        long messageId = chat.sendAssistant("오늘 시작 전에", DAY_KIND, "normal", null, null, true);
        db.update("UPDATE messages SET card_id = ? WHERE id = ?", cardId, messageId);
        db.update("UPDATE cards SET message_id = ? WHERE id = ?", messageId, cardId);
        return cardId;
    }

    /** POST /v1/day-cards/{day} {sleep?, condition?, sleep_note?, condition_note?}. 버튼은 바로 저장(turn 0). */
    public Map<String, Object> submitDay(LocalDate day, JsonNode body) {
        days.ensure(day);
        int sleep = body.path("sleep").asInt(0);
        if (sleep >= 1 && sleep <= 3) {
            days.setLevel(day, "sleep", sleep, "user");
        }
        int condition = body.path("condition").asInt(0);
        if (condition >= 1 && condition <= 3) {
            days.setLevel(day, "condition", condition, "user");
        }
        boolean notes = false;
        for (String[] f : new String[][]{{"sleep_note", "sleep_note"}, {"condition_note", "condition_note"}}) {
            String text = body.path(f[0]).asString("");
            if (!text.isBlank()) {
                days.setNote(day, f[1], text);
                notes = true;
            }
        }
        db.update("UPDATE cards SET status = 'submitted', closed_at = ? WHERE kind = 'day' AND logical_day = ? AND status = 'sent'",
                clock.now(), day);
        if (notes) {
            ObjectNode ref = Json.object();
            ref.put("day", day.toString());
            memoryJobs.enqueue("day_notes", ref);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true);
        r.put("day", day.toString());
        r.put("notes", notes);
        return r;
    }

    // ------------------------------------------------------------------ 채팅 목록에 붙이는 카드 정보

    /** messages.card_id 가 있는 메시지에 붙일 카드(종류·상태·내용). */
    public Map<String, Object> cardView(long cardId) {
        return db.jdbc().sql("SELECT * FROM cards WHERE id = ?").param(cardId).query((rs, n) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getLong("id"));
            m.put("kind", rs.getString("kind"));
            m.put("logical_day", rs.getObject("logical_day", LocalDate.class).toString());
            m.put("evening", rs.getBoolean("evening"));
            m.put("status", rs.getString("status"));
            m.put("payload", Json.parse(rs.getString("payload")));
            return m;
        }).optional().orElse(null);
    }
}
