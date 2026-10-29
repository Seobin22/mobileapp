package com.seobin.phonedrop.assistant.agent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.seobin.phonedrop.assistant.ApiException;
import com.seobin.phonedrop.assistant.AssistantClock;
import com.seobin.phonedrop.assistant.AssistantDb;
import com.seobin.phonedrop.assistant.GoalCoverage;
import com.seobin.phonedrop.assistant.InterventionService;
import com.seobin.phonedrop.assistant.Jobs;
import com.seobin.phonedrop.assistant.Json;
import com.seobin.phonedrop.assistant.PlanService;
import com.seobin.phonedrop.assistant.SettingsService;
import com.seobin.phonedrop.assistant.Store;
import com.seobin.phonedrop.assistant.TimeUtil;
import com.seobin.phonedrop.assistant.push.PushService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 에이전트 도구(빌드문서 v2 §5, v3 §4, v3.1 §3.3). 결과는 모두 JSON, 실패는 {"ok":false,"error":"..."}.
 * 엔티티 변경은 모두 changes 에 기록한다. 호출하는 쪽이 도구마다 트랜잭션을 연다.
 */
@Component
public class Tools {

    public static final String CONFIRMATION_EXPIRE = "confirmation_expire";
    private static final String SCREEN_PREFIX = "지금 할 건 하나예요. ";

    /** turn 하나 동안의 도구 실행 상태. */
    public static class Context {
        public final long turnId;
        public final String trigger;
        /** 확인 카드 수락으로 코드가 실행할 때 true(권한 검사 생략). */
        public final boolean bypassPermissions;
        public boolean sentReply;
        /** 풀스크린 대화 turn 이면 그 개입(답장 메시지에 붙인다, v3.1 §3.6-3). */
        public Long interventionId;
        /** 이 turn 에서 만들거나 바꾼 목표·할 일·블록의 목표(turn 끝 목표 검사, v3.1 §3.4-1). */
        public final Set<Long> touchedGoals = new LinkedHashSet<>();

        public Context(long turnId, String trigger, boolean bypassPermissions) {
            this.turnId = turnId;
            this.trigger = trigger;
            this.bypassPermissions = bypassPermissions;
        }
    }

    private final AssistantDb db;
    private final AssistantClock clock;
    private final Store store;
    private final TimeUtil time;
    private final SettingsService settings;
    private final Timeline timeline;
    private final Validator validator;
    private final Permissions permissions;
    private final Changes changes;
    private final ChatService chat;
    private final InterventionService interventions;
    private final PlanService plan;
    private final PushService push;
    private final Jobs jobs;
    private final Facts facts;
    private final ProactiveService proactive;
    private final FixedBlockService fixedBlocks;
    private final GoalService goals;
    private final GoalCoverage coverage;
    private final JsonNode definitions;

    public Tools(AssistantDb db, AssistantClock clock, Store store, TimeUtil time, SettingsService settings,
                 Timeline timeline, Validator validator, Permissions permissions, Changes changes, ChatService chat,
                 InterventionService interventions, PlanService plan, PushService push, Jobs jobs, Facts facts,
                 ProactiveService proactive, FixedBlockService fixedBlocks, GoalService goals,
                 GoalCoverage coverage) {
        this.db = db;
        this.clock = clock;
        this.store = store;
        this.time = time;
        this.settings = settings;
        this.timeline = timeline;
        this.validator = validator;
        this.permissions = permissions;
        this.changes = changes;
        this.chat = chat;
        this.interventions = interventions;
        this.plan = plan;
        this.push = push;
        this.jobs = jobs;
        this.facts = facts;
        this.proactive = proactive;
        this.fixedBlocks = fixedBlocks;
        this.goals = goals;
        this.coverage = coverage;
        try (InputStream in = new ClassPathResource("assistant/agent/tools.json").getInputStream()) {
            this.definitions = Json.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("tools.json 을 읽을 수 없습니다", e);
        }
    }

    /** [{name, description, input_schema}] */
    public JsonNode definitions() {
        return definitions;
    }

    public ObjectNode dispatch(Context ctx, String name, JsonNode input) {
        try {
            if (!ctx.bypassPermissions) {
                Permissions.Decision d = permissions.decide(ctx.trigger, name, input);
                if (d.kind() == Permissions.Kind.REJECT) {
                    return error(d.message());
                }
                if (d.kind() == Permissions.Kind.CONFIRM) {
                    return confirm(d.confirmationKind(), name, input);
                }
            }
            return switch (name) {
                case "get_timeline" -> getTimeline(input);
                case "get_history" -> getHistory(input);
                case "get_load_summary" -> ok(Json.MAPPER.valueToTree(timeline.loadSummary()));
                case "upsert_goal" -> upsertGoal(ctx, input);
                case "upsert_task" -> upsertTask(ctx, input);
                case "upsert_fixed_block" -> upsertFixedBlock(ctx, input);
                case "get_goal_coverage" -> getGoalCoverage();
                case "upsert_note" -> upsertNote(ctx, input);
                case "cancel" -> cancel(ctx, input);
                case "save_memory" -> saveMemory(input);
                case "undo_last" -> undoLast(ctx);
                case "set_schedule" -> setSchedule(ctx, input);
                case "move_block" -> moveBlock(ctx, input);
                case "send_message" -> sendMessage(ctx, input);
                case "ask_user" -> askUser(ctx, input);
                case "get_facts" -> getFacts(input);
                case "start_now" -> startNow(ctx, input);
                case "extend_block" -> extendBlock(ctx, input);
                case "end_block" -> endBlock(ctx, input);
                case "schedule_wakeup" -> scheduleWakeup(ctx, input);
                case "cancel_wakeup" -> cancelWakeup(input);
                case "create_work_product" -> createWorkProduct(input);
                case "attach_work_product" -> attachWorkProduct(ctx, input);
                case "get_work_products" -> getWorkProducts(input);
                default -> error("알 수 없는 도구입니다: " + name);
            };
        } catch (ApiException | IllegalArgumentException e) {
            return error(e.getMessage());
        }
    }

    // ------------------------------------------------------------------ 확인 카드

    private ObjectNode confirm(String kind, String tool, JsonNode args) {
        String text = args.path("confirm_text").asString("").trim();
        if (text.isEmpty()) {
            text = autoConfirmText(kind, args);
        }
        Instant now = clock.now();
        Instant expiresAt = now.plus(Duration.ofMinutes(settings.getInt("confirmation.expire_minutes")));
        long id = db.insert("""
                        INSERT INTO confirmations(kind, tool, args, text, expires_at, created_at)
                        VALUES (?, ?, ?, ?, ?, ?)""",
                kind, tool, Json.write(args), text, expiresAt, now);
        chat.sendAssistant(text, "confirmation", "normal", null, id);
        Map<String, String> data = new LinkedHashMap<>();
        data.put("type", "confirmation");
        data.put("confirmation_id", String.valueOf(id));
        data.put("text", text);
        push.pushAll(data);
        jobs.schedule(CONFIRMATION_EXPIRE, id, expiresAt, null);
        ObjectNode r = Json.object();
        r.put("ok", true);
        r.put("pending_confirmation_id", id);
        r.put("note", "사용자 확인을 기다립니다. 확인 카드로 물었으니 같은 내용을 다시 묻지 않아도 됩니다.");
        return r;
    }

    private String autoConfirmText(String kind, JsonNode args) {
        return switch (kind) {
            case "fixed_change" -> args.hasNonNull("block_id")
                    ? "고정 블록#" + args.get("block_id").asLong() + "을 " + describeMove(args) + "(으)로 옮길까요?"
                    : args.hasNonNull("id")
                    ? "고정 블록#" + args.get("id").asLong() + " 시각을 " + describeRange(args) + "(으)로 바꿀까요?"
                    : "'" + args.path("title").asString("") + "'(" + describeRange(args) + ")을 시간표에 넣을까요?";
            case "fixed_cancel" -> "고정 블록#" + args.path("id").asLong() + "을 취소할까요?";
            case "goal_cancel" -> "목표#" + args.path("id").asLong() + "을 취소할까요?";
            default -> "목표#" + args.path("id").asLong() + "의 마감·상태를 바꿀까요?";
        };
    }

    private String describeMove(JsonNode args) {
        try {
            return time.shortText(TimeUtil.parse(args.path("new_start").asString(null)));
        } catch (ApiException ex) {
            return args.path("new_start").asString("");
        }
    }

    private String describeRange(JsonNode args) {
        try {
            String s = args.hasNonNull("start") ? time.shortText(TimeUtil.parse(args.get("start").asString())) : "";
            String e = args.hasNonNull("end") ? time.hm(TimeUtil.parse(args.get("end").asString())) : "";
            return s + "–" + e;
        } catch (ApiException ex) {
            return args.path("start").asString("") + "–" + args.path("end").asString("");
        }
    }

    // ------------------------------------------------------------------ 조회

    private ObjectNode getTimeline(JsonNode in) {
        Instant from = TimeUtil.parse(in.path("from").asString(null));
        Instant to = TimeUtil.parse(in.path("to").asString(null));
        return ok(Json.MAPPER.valueToTree(timeline.timeline(from, to, true)));
    }

    private ObjectNode getHistory(JsonNode in) {
        String kind = in.path("kind").asString("");
        Instant since = TimeUtil.parse(in.path("since").asString(null));
        int limit = Math.min(Math.max(in.path("limit").asInt(50), 1), 200);
        Long taskId = in.hasNonNull("task_id") ? in.get("task_id").asLong() : null;
        List<Map<String, Object>> rows = switch (kind) {
            case "completions" -> db.jdbc().sql("""
                            SELECT b.id, b.task_id, COALESCE(t.title, b.title) title, COALESCE(t.sensitive, b.sensitive) sensitive,
                            b.start_at, b.end_at, b.status, b.actual_start, b.actual_end
                            FROM blocks b LEFT JOIN tasks t ON t.id = b.task_id
                            WHERE b.start_at >= ? AND b.status IN ('done', 'partial', 'missed', 'started')
                            AND (? IS NULL OR b.task_id = ?) ORDER BY b.start_at DESC LIMIT ?""")
                    .param(AssistantDb.ts(since)).param(taskId).param(taskId).param(limit)
                    .query((rs, n) -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("block_id", rs.getLong("id"));
                        m.put("task_id", rs.getObject("task_id"));
                        m.put("title", mask(rs.getString("title"), rs.getBoolean("sensitive"), "비공개 할 일"));
                        m.put("start", time.iso(AssistantDb.instant(rs, "start_at")));
                        m.put("end", time.iso(AssistantDb.instant(rs, "end_at")));
                        m.put("status", rs.getString("status"));
                        m.put("actual_start", time.iso(AssistantDb.instant(rs, "actual_start")));
                        m.put("actual_end", time.iso(AssistantDb.instant(rs, "actual_end")));
                        return m;
                    }).list();
            case "interventions" -> db.jdbc().sql("""
                            SELECT i.id, i.block_id, b.task_id, i.status, i.level, i.started_at, i.ended_at, i.end_reason,
                            (SELECT COUNT(*) FROM intervention_logs l WHERE l.intervention_id = i.id AND l.reaction = 'dismissed') dismissed
                            FROM interventions i JOIN blocks b ON b.id = i.block_id
                            WHERE i.started_at >= ? AND (? IS NULL OR b.task_id = ?) ORDER BY i.started_at DESC LIMIT ?""")
                    .param(AssistantDb.ts(since)).param(taskId).param(taskId).param(limit)
                    .query((rs, n) -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("intervention_id", rs.getLong("id"));
                        m.put("block_id", rs.getLong("block_id"));
                        m.put("task_id", rs.getObject("task_id"));
                        m.put("status", rs.getString("status"));
                        m.put("end_reason", rs.getString("end_reason"));
                        m.put("started_at", time.iso(AssistantDb.instant(rs, "started_at")));
                        m.put("ended_at", time.iso(AssistantDb.instant(rs, "ended_at")));
                        m.put("dismissed_count", rs.getLong("dismissed"));
                        return m;
                    }).list();
            case "messages" -> db.jdbc().sql("""
                            SELECT id, role, kind, content, created_at FROM messages WHERE created_at >= ?
                            ORDER BY id DESC LIMIT ?""")
                    .param(AssistantDb.ts(since)).param(limit)
                    .query((rs, n) -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("id", rs.getLong("id"));
                        m.put("role", rs.getString("role"));
                        m.put("kind", rs.getString("kind"));
                        m.put("content", rs.getString("content"));
                        m.put("created_at", time.iso(AssistantDb.instant(rs, "created_at")));
                        return m;
                    }).list();
            default -> throw new IllegalArgumentException("kind 는 completions·interventions·messages 중 하나입니다");
        };
        ObjectNode r = Json.object();
        r.put("ok", true);
        r.set("rows", Json.MAPPER.valueToTree(rows));
        return r;
    }

    private String mask(String title, boolean sensitive, String replacement) {
        return sensitive && settings.getBoolean("privacy.sensitive_mode") ? replacement : title;
    }

    // ------------------------------------------------------------------ 기록

    private ObjectNode upsertGoal(Context ctx, JsonNode in) {
        if (in.hasNonNull("priority") && (in.get("priority").asInt() < 1 || in.get("priority").asInt() > 5)) {
            return error("priority 는 1~5 입니다");
        }
        String progress = progress(in);
        if (!in.hasNonNull("id")) {
            String title = text(in, "title");
            if (title.isEmpty()) {
                return error("목표 생성에는 title 이 필요합니다");
            }
            String label = text(in, "label");
            long id = db.insert("""
                            INSERT INTO goals(title, deadline, reason, strategy, sensitive, label, color, strategy_summary,
                            progress, waiting_until, waiting_reason, priority) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    title, optTime(in, "deadline"), text(in, "reason"), text(in, "strategy"),
                    in.path("sensitive").asBoolean(false),
                    GoalCoverage.defaultLabel(label.isEmpty() ? title : label), coverage.assignColor(),
                    text(in, "strategy_summary"), progress, optTime(in, "waiting_until"),
                    in.hasNonNull("waiting_reason") ? text(in, "waiting_reason") : null, in.path("priority").asInt(3));
            changes.record(ctx.turnId, "goal", id, null, changes.snapshot("goal", id));
            coverage.scheduleWaitingCheck(id, optTime(in, "waiting_until"));
            ctx.touchedGoals.add(id);
            return okWithId(id);
        }
        long id = in.get("id").asLong();
        Store.Goal g = store.goal(id).orElse(null);
        if (g == null) {
            return error("목표#" + id + "이 없습니다");
        }
        if (in.hasNonNull("status") && !"active".equals(in.get("status").asString(""))) {
            // 끝내기(done)는 목표 탭 [완료]와 같은 처리(할 일·미래 계획 블록 정리, 고정 블록은 연결만 해제).
            String status = enumValue(in, "status", "active", "done");
            return ok(Json.MAPPER.valueToTree(goals.finish(id, status, ctx.turnId)));
        }
        JsonNode before = changes.snapshot("goal", id);
        Map<String, Object> set = new LinkedHashMap<>();
        if (in.hasNonNull("title")) set.put("title", text(in, "title"));
        if (in.has("deadline")) set.put("deadline", optTime(in, "deadline"));
        if (in.hasNonNull("reason")) set.put("reason", text(in, "reason"));
        if (in.hasNonNull("strategy")) set.put("strategy", text(in, "strategy"));
        if (in.hasNonNull("status")) set.put("status", enumValue(in, "status", "active", "done"));
        if (in.hasNonNull("sensitive")) set.put("sensitive", in.get("sensitive").asBoolean());
        if (in.hasNonNull("label")) set.put("label", GoalCoverage.defaultLabel(text(in, "label").isEmpty() ? g.title() : text(in, "label")));
        if (in.hasNonNull("strategy_summary")) set.put("strategy_summary", text(in, "strategy_summary"));
        if (in.has("progress")) set.put("progress", progress);
        if (in.has("waiting_until")) set.put("waiting_until", optTime(in, "waiting_until"));
        if (in.has("waiting_reason")) set.put("waiting_reason", in.get("waiting_reason").isNull() ? null : text(in, "waiting_reason"));
        if (in.hasNonNull("priority")) set.put("priority", in.get("priority").asInt());
        update("goals", id, set);
        changes.record(ctx.turnId, "goal", id, before, changes.snapshot("goal", id));
        if (in.has("waiting_until")) {
            coverage.scheduleWaitingCheck(id, optTime(in, "waiting_until"));
        }
        ctx.touchedGoals.add(id);
        return okWithId(id);
    }

    /** progress: {"unit":"챕터","total":12,"done":5} 객체(또는 JSON 문자열). null 이면 지운다. */
    private static String progress(JsonNode in) {
        JsonNode p = in.path("progress");
        if (p.isMissingNode() || p.isNull()) {
            return null;
        }
        JsonNode obj = p.isString() ? Json.parse(p.asString()) : p;
        if (!obj.isObject()) {
            throw new IllegalArgumentException("progress 는 {\"unit\",\"total\",\"done\"} 객체입니다");
        }
        return Json.write(obj);
    }

    private ObjectNode upsertTask(Context ctx, JsonNode in) {
        Long goalId = in.hasNonNull("goal_id") ? in.get("goal_id").asLong() : null;
        if (goalId != null && store.goal(goalId).isEmpty()) {
            return error("목표#" + goalId + "이 없습니다");
        }
        if (in.hasNonNull("priority") && (in.get("priority").asInt() < 1 || in.get("priority").asInt() > 5)) {
            return error("priority 는 1~5 입니다");
        }
        if (!in.hasNonNull("id")) {
            String title = text(in, "title");
            String firstAction = text(in, "first_action");
            if (title.isEmpty() || firstAction.isEmpty() || !in.hasNonNull("est_minutes")) {
                return error("할 일 생성에는 title·est_minutes·first_action 이 필요합니다");
            }
            int est = in.get("est_minutes").asInt();
            if (est <= 0) {
                return error("est_minutes 는 1 이상이어야 합니다");
            }
            int remaining = in.hasNonNull("remaining_minutes") ? in.get("remaining_minutes").asInt() : est;
            long id = db.insert("""
                            INSERT INTO tasks(title, first_action, est_minutes, remaining_minutes, goal_id, deadline,
                            priority, notes, sensitive) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    title, firstAction, est, remaining, goalId, optTime(in, "deadline"),
                    in.path("priority").asInt(3), text(in, "notes"), in.path("sensitive").asBoolean(false));
            changes.record(ctx.turnId, "task", id, null, changes.snapshot("task", id));
            if (goalId != null) {
                ctx.touchedGoals.add(goalId);
            }
            return okWithId(id);
        }
        long id = in.get("id").asLong();
        Store.Task t = store.task(id).orElse(null);
        if (t == null) {
            return error("할일#" + id + "이 없습니다");
        }
        if (t.goalId() != null) {
            ctx.touchedGoals.add(t.goalId());
        }
        if (goalId != null) {
            ctx.touchedGoals.add(goalId);
        }
        JsonNode before = changes.snapshot("task", id);
        Map<String, Object> set = new LinkedHashMap<>();
        if (in.has("goal_id")) set.put("goal_id", goalId);
        if (in.hasNonNull("title")) set.put("title", text(in, "title"));
        if (in.has("deadline")) set.put("deadline", optTime(in, "deadline"));
        if (in.hasNonNull("est_minutes")) set.put("est_minutes", in.get("est_minutes").asInt());
        if (in.hasNonNull("remaining_minutes")) set.put("remaining_minutes", Math.max(0, in.get("remaining_minutes").asInt()));
        if (in.hasNonNull("priority")) set.put("priority", in.get("priority").asInt());
        if (in.hasNonNull("first_action")) set.put("first_action", text(in, "first_action"));
        if (in.hasNonNull("notes")) set.put("notes", text(in, "notes"));
        if (in.hasNonNull("sensitive")) set.put("sensitive", in.get("sensitive").asBoolean());
        boolean done = false;
        if (in.hasNonNull("status")) {
            String status = enumValue(in, "status", "active", "done");
            set.put("status", status);
            if ("done".equals(status)) {
                set.put("remaining_minutes", 0);
                done = true;
            }
        }
        update("tasks", id, set);
        if (set.containsKey("goal_id")) {
            // 계획 블록의 goal_id 는 할 일의 목표를 따른다.
            db.update("UPDATE blocks SET goal_id = ? WHERE task_id = ? AND kind = 'planned'", goalId, id);
        }
        if (set.containsKey("title")) {
            db.update("UPDATE blocks SET title = ? WHERE task_id = ? AND kind = 'planned'", set.get("title"), id);
        }
        List<Long> cancelled = new ArrayList<>();
        if (done) {
            for (Store.Block b : store.blocksOfTask(id)) {
                if ("planned".equals(b.status()) && b.start().isAfter(clock.now())) {
                    cancelBlock(ctx, b);
                    cancelled.add(b.id());
                }
            }
        }
        if (set.containsKey("first_action")) {
            // 아직 시작 안 한 블록의 기본 화면 문구를 새 첫 행동으로 맞춘다
            for (Store.Block b : store.blocksOfTask(id)) {
                if ("planned".equals(b.status()) && b.screenText().equals(SCREEN_PREFIX + t.firstAction())) {
                    db.update("UPDATE blocks SET screen_text = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                            SCREEN_PREFIX + set.get("first_action"), b.id());
                }
            }
        }
        changes.record(ctx.turnId, "task", id, before, changes.snapshot("task", id));
        if (!cancelled.isEmpty()) {
            plan.bumpAndSync();
        }
        ObjectNode r = okWithId(id);
        if (!cancelled.isEmpty()) {
            r.set("cancelled_blocks", Json.MAPPER.valueToTree(cancelled));
        }
        return r;
    }

    private ObjectNode upsertFixedBlock(Context ctx, JsonNode in) {
        FixedBlockService.Result r = fixedBlocks.upsert(in, ctx.turnId);
        if (!r.ok()) {
            return error(r.error());
        }
        store.block(r.id()).flatMap(coverage::goalOf).ifPresent(ctx.touchedGoals::add);
        ObjectNode out = okWithId(r.id());
        out.set("displaced", Json.MAPPER.valueToTree(r.displaced()));
        if (!r.displaced().isEmpty()) {
            out.put("note", "displaced 계획 블록은 취소됐습니다. 그 할 일을 다시 배치하세요");
        }
        if (!r.cutoff().isEmpty()) {
            out.set("cutoff_started_blocks", Json.MAPPER.valueToTree(r.cutoff()));
        }
        Store.Block b = store.block(r.id()).orElseThrow();
        out.put("intervention_at", time.iso(b.interventionAt()));
        if (time.firstQuietOverlap(b.interventionAt(), b.end()).isPresent()) {
            out.put("warning", "수면·방해 금지 시간과 겹쳐 이 블록의 알람(시작 화면)이 뜨지 않을 수 있습니다. 사용자에게 알려 주세요");
        }
        return out;
    }

    private ObjectNode getGoalCoverage() {
        Instant now = clock.now();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Store.Goal g : store.activeGoals()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("goal_id", g.id());
            m.put("label", g.label());
            m.put("next_block_start", time.iso(coverage.nextBlockStart(g.id(), now)));
            m.put("waiting_until", time.iso(g.waitingUntil()));
            m.put("status", coverage.status(g, now));
            rows.add(m);
        }
        ObjectNode r = Json.object();
        r.put("ok", true);
        r.set("goals", Json.MAPPER.valueToTree(rows));
        return r;
    }

    private ObjectNode upsertNote(Context ctx, JsonNode in) {
        if (!in.hasNonNull("id")) {
            String content = text(in, "content");
            if (content.isEmpty()) {
                return error("메모 생성에는 content 가 필요합니다");
            }
            long id = db.insert("INSERT INTO notes(content) VALUES (?)", content);
            changes.record(ctx.turnId, "note", id, null, changes.snapshot("note", id));
            return okWithId(id);
        }
        long id = in.get("id").asLong();
        JsonNode before = changes.snapshot("note", id);
        if (before == null) {
            return error("메모#" + id + "이 없습니다");
        }
        Map<String, Object> set = new LinkedHashMap<>();
        if (in.hasNonNull("content")) set.put("content", text(in, "content"));
        if (in.hasNonNull("status")) set.put("status", enumValue(in, "status", "open", "converted", "dismissed"));
        update("notes", id, set);
        changes.record(ctx.turnId, "note", id, before, changes.snapshot("note", id));
        return okWithId(id);
    }

    private ObjectNode cancel(Context ctx, JsonNode in) {
        String entity = in.path("entity").asString("");
        long id = in.path("id").asLong(-1);
        List<Long> cancelledBlocks = new ArrayList<>();
        switch (entity) {
            case "goal" -> {
                if (store.goal(id).isEmpty()) return error("목표#" + id + "이 없습니다");
                return ok(Json.MAPPER.valueToTree(goals.finish(id, "cancelled", ctx.turnId)));
            }
            case "task" -> {
                Store.Task t = store.task(id).orElse(null);
                if (t == null) return error("할일#" + id + "이 없습니다");
                if (t.goalId() != null) ctx.touchedGoals.add(t.goalId());
                setStatus(ctx, "task", "tasks", id, "cancelled");
                cancelledBlocks.addAll(cancelFutureBlocks(ctx, id));
            }
            case "fixed_block" -> {
                Store.Block b = store.block(id).orElse(null);
                if (b != null) coverage.goalOf(b).ifPresent(ctx.touchedGoals::add);
                FixedBlockService.Result r = fixedBlocks.cancel(id, ctx.turnId);
                if (!r.ok()) return error(r.error());
            }
            case "note" -> {
                if (changes.snapshot("note", id) == null) return error("메모#" + id + "이 없습니다");
                setStatus(ctx, "note", "notes", id, "dismissed");
            }
            default -> {
                return error("entity 는 goal·task·fixed_block·note 중 하나입니다");
            }
        }
        if (!cancelledBlocks.isEmpty()) {
            plan.bumpAndSync();
        }
        ObjectNode r = okWithId(id);
        r.set("cancelled_blocks", Json.MAPPER.valueToTree(cancelledBlocks));
        return r;
    }

    /** 연결된 미래 블록(planned·nudging) 취소. 진행 중 개입은 cancelled + intervention.end. */
    private List<Long> cancelFutureBlocks(Context ctx, long taskId) {
        List<Long> ids = new ArrayList<>();
        for (Store.Block b : store.blocksOfTask(taskId)) {
            if ("nudging".equals(b.status()) || ("planned".equals(b.status()) && b.end().isAfter(clock.now()))) {
                cancelBlock(ctx, b);
                ids.add(b.id());
            }
        }
        return ids;
    }

    private void cancelBlock(Context ctx, Store.Block b) {
        coverage.goalOf(b).ifPresent(ctx.touchedGoals::add);
        JsonNode before = changes.snapshot("block", b.id());
        interventions.cancelRunningForBlock(b.id());
        store.setBlockStatus(b.id(), "cancelled");
        interventions.cancelBlockJobs(b.id());
        changes.record(ctx.turnId, "block", b.id(), before, changes.snapshot("block", b.id()));
    }

    private void setStatus(Context ctx, String entity, String table, long id, String status) {
        JsonNode before = changes.snapshot(entity, id);
        db.update("UPDATE " + table + " SET status = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?", status, id);
        changes.record(ctx.turnId, entity, id, before, changes.snapshot(entity, id));
    }

    private ObjectNode saveMemory(JsonNode in) {
        String content = in.path("content").asString("");
        int max = settings.getInt("memory.max_chars");
        if (content.length() > max) {
            return error("memory 는 " + max + "자 이하여야 합니다(지금 " + content.length() + "자). 줄여서 다시 저장하세요");
        }
        db.update("UPDATE memory SET content = ?, updated_at = CURRENT_TIMESTAMP WHERE id = 1", content);
        ObjectNode r = Json.object();
        r.put("ok", true);
        r.put("chars", content.length());
        return r;
    }

    private ObjectNode undoLast(Context ctx) {
        Changes.UndoResult u = changes.undoLast(ctx.turnId);
        ObjectNode r = Json.object();
        r.put("ok", true);
        r.put("restored", u.restored());
        r.put("skipped", u.skipped());
        r.put("partial", u.partial());
        return r;
    }

    // ------------------------------------------------------------------ 시간표

    private ObjectNode setSchedule(Context ctx, JsonNode in) {
        Instant from = TimeUtil.parse(in.path("from").asString(null));
        Instant to = TimeUtil.parse(in.path("to").asString(null));
        if (!to.isAfter(from)) {
            return error("to 는 from 보다 뒤여야 합니다");
        }
        // v3.1 §3.3: 계획 블록만 다룬다. 고정 블록은 교체 대상이 아니며 유지되는 블록으로 취급한다.
        List<Store.Block> active = store.activeBlocks();
        List<Store.Block> replaced = active.stream()
                .filter(b -> !b.fixed() && "planned".equals(b.status()) && !b.start().isBefore(from)
                        && b.start().isBefore(to))
                .toList();
        List<Store.Block> keep = active.stream().filter(b -> !replaced.contains(b)).toList();

        record Planned(long taskId, Instant start, Instant end, String screenText) {
        }
        List<Planned> planned = new ArrayList<>();
        for (JsonNode b : in.path("blocks")) {
            if (!b.hasNonNull("task_id")) {
                return error("set_schedule 의 blocks 항목에는 task_id 가 필요합니다(고정 블록은 upsert_fixed_block)");
            }
            planned.add(new Planned(b.path("task_id").asLong(), TimeUtil.parse(b.path("start").asString(null)),
                    TimeUtil.parse(b.path("end").asString(null)), b.path("screen_text").asString("").trim()));
        }
        List<String> violations = validator.validateBlocks(
                planned.stream().map(p -> new Validator.NewBlock(p.taskId(), p.start(), p.end())).toList(),
                keep, clock.now());
        if (!violations.isEmpty()) {
            ObjectNode r = Json.object();
            r.put("ok", false);
            r.set("violations", Json.MAPPER.valueToTree(violations));
            return r;
        }

        List<Long> cancelled = new ArrayList<>();
        for (Store.Block b : replaced) {
            cancelBlock(ctx, b);
            cancelled.add(b.id());
        }
        List<Long> created = new ArrayList<>();
        for (Planned p : planned) {
            Store.Task t = store.task(p.taskId()).orElseThrow();
            String screen = p.screenText().isEmpty() ? SCREEN_PREFIX + t.firstAction() : p.screenText();
            long id = db.insert("""
                            INSERT INTO blocks(kind, task_id, title, goal_id, start_at, end_at, screen_text)
                            VALUES ('planned', ?, ?, ?, ?, ?, ?)""",
                    p.taskId(), t.title(), t.goalId(), p.start(), p.end(), screen);
            if (t.goalId() != null) {
                ctx.touchedGoals.add(t.goalId());
            }
            interventions.scheduleBlockJobs(store.block(id).orElseThrow());
            changes.record(ctx.turnId, "block", id, null, changes.snapshot("block", id));
            created.add(id);
        }
        plan.bumpAndSync();
        ObjectNode r = Json.object();
        r.put("ok", true);
        r.set("created", Json.MAPPER.valueToTree(created));
        r.set("cancelled", Json.MAPPER.valueToTree(cancelled));
        return r;
    }

    private ObjectNode moveBlock(Context ctx, JsonNode in) {
        long id = in.path("block_id").asLong(-1);
        Store.Block b = store.block(id).orElse(null);
        if (b == null) {
            return error("블록#" + id + "이 없습니다");
        }
        if (!List.of("planned", "nudging").contains(b.status())) {
            return error("블록#" + id + "은 " + b.status() + " 상태라 옮길 수 없습니다(planned·nudging 만)");
        }
        Instant start = TimeUtil.parse(in.path("new_start").asString(null));
        Instant end = in.hasNonNull("new_end") ? TimeUtil.parse(in.get("new_end").asString())
                : start.plus(Duration.between(b.start(), b.end()));
        coverage.goalOf(b).ifPresent(ctx.touchedGoals::add);
        if (b.fixed()) {
            // 고정 블록은 고정 블록 규칙(고정끼리 겹침 거절, 계획 블록은 밀어냄)으로 옮긴다. 권한은 Permissions 가 본다.
            Validator.FixedCheck pre = validator.validateFixed(id, start, end, b.leadMinutes(), b.bufferAfterMin(),
                    clock.now());
            if (!pre.errors().isEmpty()) {
                return error(String.join("\n", pre.errors()));
            }
            if ("nudging".equals(b.status())) {
                interventions.snoozeForMove(id, Duration.between(b.start(), start).toMinutes());
            }
            ObjectNode req = Json.object();
            req.put("id", id);
            req.put("start", time.iso(start));
            req.put("end", time.iso(end));
            FixedBlockService.Result r = fixedBlocks.upsert(req, ctx.turnId);
            if (!r.ok()) {
                return error(r.error());
            }
            ObjectNode out = okWithId(id);
            out.set("displaced", Json.MAPPER.valueToTree(r.displaced()));
            return out;
        }
        List<Store.Block> keep = store.activeBlocks().stream().filter(k -> k.id() != id).toList();
        List<String> violations = validator.validateBlocks(
                List.of(new Validator.NewBlock(b.taskId(), start, end)), keep, clock.now());
        if (!violations.isEmpty()) {
            ObjectNode r = Json.object();
            r.put("ok", false);
            r.set("violations", Json.MAPPER.valueToTree(violations));
            return r;
        }
        JsonNode before = changes.snapshot("block", id);
        if ("nudging".equals(b.status())) {
            // v3 §3.4: 진행 중 개입은 snoozed(end_reason=moved). 새 시각에 다시 개입한다.
            interventions.snoozeForMove(id, Duration.between(b.start(), start).toMinutes());
        }
        db.update("""
                        UPDATE blocks SET start_at = ?, end_at = ?, status = 'planned', version = version + 1,
                        updated_at = CURRENT_TIMESTAMP WHERE id = ?""",
                start, end, id);
        interventions.scheduleBlockJobs(store.block(id).orElseThrow());
        changes.record(ctx.turnId, "block", id, before, changes.snapshot("block", id));
        plan.bumpAndSync();
        return okWithId(id);
    }

    // ------------------------------------------------------------------ 대화

    private ObjectNode sendMessage(Context ctx, JsonNode in) {
        String text = in.path("text").asString("").trim();
        if (text.isEmpty()) {
            return error("text 가 비었습니다");
        }
        String kind = in.path("kind").asString("reply");
        if (!List.of("reply", "proactive", "briefing", "checkin", "intervention").contains(kind)) {
            kind = "reply";
        }
        String importance = in.path("importance").asString("normal");
        if (!List.of("low", "normal", "high").contains(importance)) {
            importance = "normal";
        }
        boolean silent = silentFor(kind, importance);
        // 풀스크린 대화의 답은 그 개입에 붙인다(앱이 화면에 표시하고 TTS 로 읽는다).
        Long interventionId = "reply".equals(kind) ? ctx.interventionId : null;
        long id = chat.sendAssistant(text, kind, importance, null, null, silent, interventionId);
        if ("reply".equals(kind)) {
            ctx.sentReply = true;
        }
        ObjectNode r = Json.object();
        r.put("ok", true);
        r.put("message_id", id);
        r.put("silent", silent);
        return r;
    }

    /** v3 §4.5: 알림 여부는 코드가 정한다. silent 여도 메시지는 저장되고 채팅에 보인다. */
    boolean silentFor(String kind, String importance) {
        if ("reply".equals(kind) || "intervention".equals(kind)) {
            return false;
        }
        Instant now = clock.now();
        if (time.isQuiet(now) || proactive.fixedBlockInProgress(now)) {
            return true;
        }
        if (!"checkin".equals(kind) && !store.startedBlocks().isEmpty()) {
            return true;
        }
        int[] today = chat.proactiveToday();
        if (today[0] >= settings.getInt("messages.daily_budget")) {
            return !("high".equals(importance) && today[1] < settings.getInt("messages.high_per_day"));
        }
        return false;
    }

    private ObjectNode askUser(Context ctx, JsonNode in) {
        String text = in.path("text").asString("").trim();
        if (text.isEmpty()) {
            return error("text 가 비었습니다");
        }
        List<String> options = new ArrayList<>();
        for (JsonNode o : in.path("options")) {
            if (!o.asString("").isBlank() && options.size() < 4) {
                options.add(o.asString().trim());
            }
        }
        long questionId = chat.askUser(text, options);
        ctx.sentReply = true; // 질문도 사용자에게 한 답이다(텍스트를 다시 reply 로 보내지 않는다)
        ObjectNode r = Json.object();
        r.put("ok", true);
        r.put("question_id", questionId);
        return r;
    }

    // ------------------------------------------------------------------ v3: 사실·블록 조작 (§4.1~4.2)

    private ObjectNode getFacts(JsonNode in) {
        Map<String, Object> f = facts.of(in.path("task_id").asLong(-1));
        if (f == null) {
            return error("할일#" + in.path("task_id").asLong(-1) + "이 없습니다");
        }
        return ok(Json.MAPPER.valueToTree(f));
    }

    private ObjectNode startNow(Context ctx, JsonNode in) {
        // v3.1: 풀스크린 대화 중 "할게요" → 그 개입 블록(고정 블록 포함)을 block_id 로 시작할 수 있다.
        if (in.hasNonNull("block_id")) {
            long blockId = in.get("block_id").asLong();
            Store.Intervention i = store.runningForBlock(blockId).orElse(null);
            if (i == null) {
                return error("블록#" + blockId + "에 진행 중인 개입이 없습니다. task_id 로 지금 시작하세요");
            }
            return ackIntervention(ctx, i);
        }
        long taskId = in.path("task_id").asLong(-1);
        Store.Task t = store.task(taskId).orElse(null);
        if (t == null) {
            return error("할일#" + taskId + "이 없습니다");
        }
        if (!"active".equals(t.status())) {
            return error("할일#" + taskId + "은 " + t.status() + " 상태입니다");
        }
        // 진행 중 개입이 이 할 일 블록이면 그 개입을 acked 로
        for (Store.Intervention i : store.runningInterventions()) {
            Store.Block b = store.block(i.blockId()).orElseThrow();
            if (Long.valueOf(taskId).equals(b.taskId())) {
                return ackIntervention(ctx, i);
            }
        }

        Instant now = clock.now();
        int nextLength = store.blocksOfTask(taskId).stream()
                .filter(b -> "planned".equals(b.status()) && b.start().isAfter(now))
                .map(b -> (int) Duration.between(b.start(), b.end()).toMinutes())
                .findFirst().orElse(60);
        int minutes = Math.max(5, t.remainingMinutes() > 0 ? Math.min(t.remainingMinutes(), nextLength) : nextLength);
        Instant end = now.plus(Duration.ofMinutes(minutes));

        List<Store.Block> fixedOverlap = store.activeFixedBlocksOverlapping(now, end);
        if (!fixedOverlap.isEmpty()) {
            Store.Block f = fixedOverlap.getFirst();
            return error("지금부터 " + minutes + "분은 고정 블록 '" + timeline.blockTitle(f) + "'(블록#" + f.id()
                    + ", 준비·이동 포함)과 겹칩니다");
        }
        if (time.firstQuietOverlap(now, end).isPresent()) {
            return error("지금부터 " + minutes + "분은 수면·방해 금지 시간과 겹칩니다");
        }
        List<Store.Block> overlapping = store.activeBlocksOverlapping(now, end);
        for (Store.Block b : overlapping) {
            if ("started".equals(b.status())) {
                return error("블록#" + b.id() + "이 진행 중입니다. end_block 으로 먼저 끝내세요");
            }
        }
        List<Long> displaced = new ArrayList<>();
        for (Store.Block b : overlapping) {
            cancelBlock(ctx, b);
            displaced.add(b.id());
        }
        long id = db.insert("""
                        INSERT INTO blocks(kind, task_id, title, goal_id, start_at, end_at, status, screen_text, actual_start)
                        VALUES ('planned', ?, ?, ?, ?, ?, 'started', ?, ?)""",
                taskId, t.title(), t.goalId(), now, end, SCREEN_PREFIX + t.firstAction(), now);
        if (t.goalId() != null) {
            ctx.touchedGoals.add(t.goalId());
        }
        jobs.schedule(InterventionService.BLOCK_END, id, end, null);
        changes.record(ctx.turnId, "block", id, null, changes.snapshot("block", id));
        plan.bumpAndSync();
        ObjectNode r = okWithId(id);
        r.put("block_id", id);
        r.put("end", time.iso(end));
        r.set("displaced", Json.MAPPER.valueToTree(displaced));
        if (!displaced.isEmpty()) {
            r.put("note", "displaced 블록은 취소됐습니다. 남은 일을 다시 배치하세요");
        }
        return r;
    }

    private ObjectNode ackIntervention(Context ctx, Store.Intervention i) {
        Store.Block b = store.block(i.blockId()).orElseThrow();
        JsonNode before = changes.snapshot("block", b.id());
        interventions.ack(i);
        changes.record(ctx.turnId, "block", b.id(), before, changes.snapshot("block", b.id()));
        plan.bumpAndSync();
        ObjectNode r = okWithId(b.id());
        r.put("block_id", b.id());
        r.put("acked_intervention_id", i.id());
        if (b.workProductId() != null) {
            r.put("work_product_id", b.workProductId());
        }
        return r;
    }

    private ObjectNode extendBlock(Context ctx, JsonNode in) {
        long id = in.path("block_id").asLong(-1);
        Store.Block b = store.block(id).orElse(null);
        if (b == null) {
            return error("블록#" + id + "이 없습니다");
        }
        if (!"started".equals(b.status())) {
            return error("블록#" + id + "은 " + b.status() + " 상태라 늘릴 수 없습니다(started 만)");
        }
        Instant newEnd = TimeUtil.parse(in.path("new_end").asString(null));
        Instant now = clock.now();
        if (!newEnd.isAfter(b.end()) || !newEnd.isAfter(now)) {
            return error("new_end 는 지금 끝 시각(" + time.hm(b.end()) + ")과 현재 시각보다 뒤여야 합니다");
        }
        Instant from = b.end().isAfter(now) ? b.end() : now;
        List<Store.Block> keep = store.activeBlocks().stream().filter(k -> k.id() != id).toList();
        List<String> violations = validator.validateRange(b.taskId(), from, newEnd, keep);
        if (!violations.isEmpty()) {
            ObjectNode r = Json.object();
            r.put("ok", false);
            r.set("violations", Json.MAPPER.valueToTree(violations));
            return r;
        }
        JsonNode before = changes.snapshot("block", id);
        db.update("UPDATE blocks SET end_at = ?, version = version + 1, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                newEnd, id);
        // 끝 시각만 바뀌므로 block_end 작업만 다시 예약한다.
        jobs.cancelForTarget(InterventionService.BLOCK_END, id);
        jobs.schedule(InterventionService.BLOCK_END, id, newEnd, null);
        changes.record(ctx.turnId, "block", id, before, changes.snapshot("block", id));
        plan.bumpAndSync();
        return okWithId(id);
    }

    private ObjectNode endBlock(Context ctx, JsonNode in) {
        long id = in.path("block_id").asLong(-1);
        Store.Block b = store.block(id).orElse(null);
        if (b == null) {
            return error("블록#" + id + "이 없습니다");
        }
        if (!"started".equals(b.status())) {
            return error("블록#" + id + "은 " + b.status() + " 상태라 끝낼 수 없습니다(started 만)");
        }
        String outcome = enumValue(in, "outcome", "done", "partial");
        JsonNode before = changes.snapshot("block", id);
        db.update("UPDATE blocks SET status = ?, actual_end = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                outcome, clock.now(), id);
        interventions.cancelBlockJobs(id);
        changes.record(ctx.turnId, "block", id, before, changes.snapshot("block", id));
        coverage.goalOf(b).ifPresent(ctx.touchedGoals::add);
        if (in.hasNonNull("remaining_minutes") && b.taskId() != null) {
            JsonNode taskBefore = changes.snapshot("task", b.taskId());
            db.update("UPDATE tasks SET remaining_minutes = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                    Math.max(0, in.get("remaining_minutes").asInt()), b.taskId());
            changes.record(ctx.turnId, "task", b.taskId(), taskBefore, changes.snapshot("task", b.taskId()));
        }
        plan.bumpAndSync();
        return okWithId(id);
    }

    // ------------------------------------------------------------------ v3: 예약 (§4.3)

    private ObjectNode scheduleWakeup(Context ctx, JsonNode in) {
        Instant at = TimeUtil.parse(in.path("at").asString(null));
        if (!at.isAfter(clock.now())) {
            return error("at 은 지금보다 미래여야 합니다");
        }
        String reason = text(in, "reason");
        if (reason.isEmpty()) {
            return error("reason 이 필요합니다");
        }
        Long taskId = in.hasNonNull("task_id") ? in.get("task_id").asLong() : null;
        if (taskId != null) {
            if (store.task(taskId).isEmpty()) {
                return error("할일#" + taskId + "이 없습니다");
            }
            int max = settings.getInt("wakeup.max_per_task");
            int scheduled = db.jdbc().sql("SELECT COUNT(*) FROM wakeups WHERE task_id = ? AND status = 'scheduled'")
                    .param(taskId).query(Integer.class).single();
            if (scheduled >= max) {
                return error("할일#" + taskId + "에는 이미 예약된 wakeup 이 " + scheduled + "개입니다(최대 " + max
                        + "). 필요 없는 것을 cancel_wakeup 하세요");
            }
        }
        Instant now = clock.now();
        long id = db.insert("""
                        INSERT INTO wakeups(fire_at, reason, task_id, created_by_turn, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?)""",
                at, reason, taskId, ctx.turnId, now, now);
        jobs.schedule(ProactiveService.WAKEUP_FIRE, id, at, null);
        return okWithId(id);
    }

    private ObjectNode cancelWakeup(JsonNode in) {
        long id = in.path("id").asLong(-1);
        int updated = db.update("""
                        UPDATE wakeups SET status = 'cancelled', updated_at = ? WHERE id = ? AND status = 'scheduled'""",
                clock.now(), id);
        if (updated == 0) {
            return error("예약된 wakeup#" + id + "이 없습니다");
        }
        jobs.cancelForTarget(ProactiveService.WAKEUP_FIRE, id);
        return okWithId(id);
    }

    // ------------------------------------------------------------------ v3: 대신 처리 (§4.4)

    private ObjectNode createWorkProduct(JsonNode in) {
        if (proactive.overSoftLimit()) {
            return error("오늘 처리량이 많아 작업물 생성을 멈췄습니다(agent.daily_turn_soft)");
        }
        long taskId = in.path("task_id").asLong(-1);
        if (store.task(taskId).isEmpty()) {
            return error("할일#" + taskId + "이 없습니다");
        }
        String title = text(in, "title");
        String content = in.path("content_md").asString("").trim();
        if (title.isEmpty() || content.isEmpty()) {
            return error("title 과 content_md 가 필요합니다");
        }
        String kind = enumValue(in, "kind", "checklist", "research", "draft", "study_plan", "other");
        tools.jackson.databind.node.ArrayNode sources = Json.array();
        for (JsonNode s : in.path("sources")) {
            String url = s.path("url").asString("").trim();
            if (!url.isEmpty()) {
                ObjectNode o = sources.addObject();
                o.put("title", s.path("title").asString(url).trim());
                o.put("url", url);
            }
        }
        Instant now = clock.now();
        long id = db.insert("""
                        INSERT INTO work_products(task_id, title, kind, content_md, sources, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)""",
                taskId, title, kind, content, Json.write(sources), now, now);
        return okWithId(id);
    }

    private ObjectNode attachWorkProduct(Context ctx, JsonNode in) {
        long blockId = in.path("block_id").asLong(-1);
        long wpId = in.path("work_product_id").asLong(-1);
        Store.Block b = store.block(blockId).orElse(null);
        if (b == null) {
            return error("블록#" + blockId + "이 없습니다");
        }
        if (!List.of("planned", "nudging").contains(b.status())) {
            return error("블록#" + blockId + "은 " + b.status() + " 상태라 붙일 수 없습니다(planned·nudging 만)");
        }
        int exists = db.jdbc().sql("SELECT COUNT(*) FROM work_products WHERE id = ?").param(wpId)
                .query(Integer.class).single();
        if (exists == 0) {
            return error("작업물#" + wpId + "이 없습니다");
        }
        JsonNode before = changes.snapshot("block", blockId);
        db.update("UPDATE blocks SET work_product_id = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?", wpId, blockId);
        changes.record(ctx.turnId, "block", blockId, before, changes.snapshot("block", blockId));
        plan.bumpAndSync();
        return okWithId(blockId);
    }

    private ObjectNode getWorkProducts(JsonNode in) {
        long taskId = in.path("task_id").asLong(-1);
        List<Map<String, Object>> rows = db.jdbc().sql("""
                        SELECT id, title, kind, content_md, sources, created_at FROM work_products
                        WHERE task_id = ? ORDER BY id DESC LIMIT 20""")
                .param(taskId)
                .query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("title", rs.getString("title"));
                    m.put("kind", rs.getString("kind"));
                    m.put("content_md", rs.getString("content_md"));
                    m.put("sources", Json.parse(rs.getString("sources")));
                    m.put("created_at", time.iso(AssistantDb.instant(rs, "created_at")));
                    return m;
                }).list();
        ObjectNode r = Json.object();
        r.put("ok", true);
        r.set("work_products", Json.MAPPER.valueToTree(rows));
        return r;
    }

    // ------------------------------------------------------------------ 도우미

    private void update(String table, long id, Map<String, Object> set) {
        if (set.isEmpty()) {
            return;
        }
        StringBuilder sql = new StringBuilder("UPDATE " + table + " SET ");
        List<Object> params = new ArrayList<>();
        set.forEach((k, v) -> {
            sql.append(k).append(" = ?, ");
            params.add(v);
        });
        sql.append("updated_at = CURRENT_TIMESTAMP WHERE id = ?");
        params.add(id);
        db.update(sql.toString(), params.toArray());
    }

    private static String text(JsonNode in, String field) {
        return in.path(field).asString("").trim();
    }

    private static Instant optTime(JsonNode in, String field) {
        JsonNode v = in.path(field);
        return v.isNull() || v.isMissingNode() || v.asString("").isBlank() ? null : TimeUtil.parse(v.asString());
    }

    private static String enumValue(JsonNode in, String field, String... allowed) {
        String v = in.path(field).asString("");
        for (String a : allowed) {
            if (a.equals(v)) {
                return v;
            }
        }
        throw new IllegalArgumentException(field + " 는 " + String.join("·", allowed) + " 중 하나입니다");
    }

    public static ObjectNode ok(JsonNode data) {
        ObjectNode r = Json.object();
        r.put("ok", true);
        if (data != null && data.isObject()) {
            data.properties().forEach(e -> r.set(e.getKey(), e.getValue()));
        }
        return r;
    }

    private static ObjectNode okWithId(long id) {
        ObjectNode r = Json.object();
        r.put("ok", true);
        r.put("id", id);
        return r;
    }

    public static ObjectNode error(String message) {
        ObjectNode r = Json.object();
        r.put("ok", false);
        r.put("error", message == null ? "오류" : message);
        return r;
    }
}
