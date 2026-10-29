package com.seobin.phonedrop.assistant.agent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

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
import com.seobin.phonedrop.assistant.push.PushService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 에이전트 도구(빌드문서 v2 §5). 결과는 모두 JSON, 실패는 {"ok":false,"error":"..."}.
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
    private final JsonNode definitions;

    public Tools(AssistantDb db, AssistantClock clock, Store store, TimeUtil time, SettingsService settings,
                 Timeline timeline, Validator validator, Permissions permissions, Changes changes, ChatService chat,
                 InterventionService interventions, PlanService plan, PushService push, Jobs jobs) {
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
                case "upsert_event" -> upsertEvent(ctx, input);
                case "upsert_note" -> upsertNote(ctx, input);
                case "cancel" -> cancel(ctx, input);
                case "save_memory" -> saveMemory(input);
                case "undo_last" -> undoLast(ctx);
                case "set_schedule" -> setSchedule(ctx, input);
                case "move_block" -> moveBlock(ctx, input);
                case "send_message" -> sendMessage(ctx, input);
                case "ask_user" -> askUser(ctx, input);
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
            case "event_change" -> args.hasNonNull("id")
                    ? "일정#" + args.get("id").asLong() + " 시각을 " + describeRange(args) + "(으)로 바꿀까요?"
                    : "일정 '" + args.path("title").asString("") + "'(" + describeRange(args) + ")을 추가할까요?";
            case "event_cancel" -> "일정#" + args.path("id").asLong() + "을 취소할까요?";
            case "goal_cancel" -> "목표#" + args.path("id").asLong() + "을 취소할까요?";
            default -> "목표#" + args.path("id").asLong() + "의 마감·상태를 바꿀까요?";
        };
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
                            SELECT b.id, b.task_id, t.title, t.sensitive, b.start_at, b.end_at, b.status, b.actual_start, b.actual_end
                            FROM blocks b JOIN tasks t ON t.id = b.task_id
                            WHERE b.start_at >= ? AND b.status IN ('done', 'partial', 'missed', 'started')
                            AND (? IS NULL OR b.task_id = ?) ORDER BY b.start_at DESC LIMIT ?""")
                    .param(AssistantDb.ts(since)).param(taskId).param(taskId).param(limit)
                    .query((rs, n) -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("block_id", rs.getLong("id"));
                        m.put("task_id", rs.getLong("task_id"));
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
                        m.put("task_id", rs.getLong("task_id"));
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
        if (!in.hasNonNull("id")) {
            String title = text(in, "title");
            if (title.isEmpty()) {
                return error("목표 생성에는 title 이 필요합니다");
            }
            long id = db.insert("""
                            INSERT INTO goals(title, deadline, reason, strategy, sensitive) VALUES (?, ?, ?, ?, ?)""",
                    title, optTime(in, "deadline"), text(in, "reason"), text(in, "strategy"),
                    in.path("sensitive").asBoolean(false));
            changes.record(ctx.turnId, "goal", id, null, changes.snapshot("goal", id));
            return okWithId(id);
        }
        long id = in.get("id").asLong();
        Store.Goal g = store.goal(id).orElse(null);
        if (g == null) {
            return error("목표#" + id + "이 없습니다");
        }
        JsonNode before = changes.snapshot("goal", id);
        Map<String, Object> set = new LinkedHashMap<>();
        if (in.hasNonNull("title")) set.put("title", text(in, "title"));
        if (in.has("deadline")) set.put("deadline", optTime(in, "deadline"));
        if (in.hasNonNull("reason")) set.put("reason", text(in, "reason"));
        if (in.hasNonNull("strategy")) set.put("strategy", text(in, "strategy"));
        if (in.hasNonNull("status")) set.put("status", enumValue(in, "status", "active", "done"));
        if (in.hasNonNull("sensitive")) set.put("sensitive", in.get("sensitive").asBoolean());
        update("goals", id, set);
        changes.record(ctx.turnId, "goal", id, before, changes.snapshot("goal", id));
        return okWithId(id);
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
            return okWithId(id);
        }
        long id = in.get("id").asLong();
        Store.Task t = store.task(id).orElse(null);
        if (t == null) {
            return error("할일#" + id + "이 없습니다");
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

    private ObjectNode upsertEvent(Context ctx, JsonNode in) {
        long id;
        if (!in.hasNonNull("id")) {
            String title = text(in, "title");
            if (title.isEmpty() || !in.hasNonNull("start") || !in.hasNonNull("end")) {
                return error("일정 생성에는 title·start·end 가 필요합니다");
            }
            Instant start = TimeUtil.parse(in.get("start").asString());
            Instant end = TimeUtil.parse(in.get("end").asString());
            if (!end.isAfter(start)) {
                return error("end 는 start 보다 뒤여야 합니다");
            }
            id = db.insert("""
                            INSERT INTO events(title, start_at, end_at, buffer_before_min, buffer_after_min, sensitive)
                            VALUES (?, ?, ?, ?, ?, ?)""",
                    title, start, end, Math.max(0, in.path("buffer_before_min").asInt(0)),
                    Math.max(0, in.path("buffer_after_min").asInt(0)), in.path("sensitive").asBoolean(false));
            changes.record(ctx.turnId, "event", id, null, changes.snapshot("event", id));
        } else {
            id = in.get("id").asLong();
            Store.Event e = store.event(id).orElse(null);
            if (e == null) {
                return error("일정#" + id + "이 없습니다");
            }
            JsonNode before = changes.snapshot("event", id);
            Map<String, Object> set = new LinkedHashMap<>();
            Instant start = in.hasNonNull("start") ? TimeUtil.parse(in.get("start").asString()) : e.start();
            Instant end = in.hasNonNull("end") ? TimeUtil.parse(in.get("end").asString()) : e.end();
            if (!end.isAfter(start)) {
                return error("end 는 start 보다 뒤여야 합니다");
            }
            if (in.hasNonNull("title")) set.put("title", text(in, "title"));
            if (in.hasNonNull("start")) set.put("start_at", start);
            if (in.hasNonNull("end")) set.put("end_at", end);
            if (in.hasNonNull("buffer_before_min")) set.put("buffer_before_min", Math.max(0, in.get("buffer_before_min").asInt()));
            if (in.hasNonNull("buffer_after_min")) set.put("buffer_after_min", Math.max(0, in.get("buffer_after_min").asInt()));
            if (in.hasNonNull("sensitive")) set.put("sensitive", in.get("sensitive").asBoolean());
            update("events", id, set);
            changes.record(ctx.turnId, "event", id, before, changes.snapshot("event", id));
        }
        Store.Event e = store.event(id).orElseThrow();
        List<Long> conflicting = store.activeBlocks().stream()
                .filter(b -> "planned".equals(b.status()))
                .filter(b -> b.start().isBefore(e.blockedEnd()) && e.blockedStart().isBefore(b.end()))
                .map(Store.Block::id).toList();
        ObjectNode r = okWithId(id);
        r.set("conflicting_blocks", Json.MAPPER.valueToTree(conflicting));
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
                setStatus(ctx, "goal", "goals", id, "cancelled");
                List<Long> taskIds = db.jdbc().sql("SELECT id FROM tasks WHERE goal_id = ? AND status = 'active'")
                        .param(id).query(Long.class).list();
                for (long taskId : taskIds) {
                    setStatus(ctx, "task", "tasks", taskId, "cancelled");
                    cancelledBlocks.addAll(cancelFutureBlocks(ctx, taskId));
                }
            }
            case "task" -> {
                if (store.task(id).isEmpty()) return error("할일#" + id + "이 없습니다");
                setStatus(ctx, "task", "tasks", id, "cancelled");
                cancelledBlocks.addAll(cancelFutureBlocks(ctx, id));
            }
            case "event" -> {
                if (store.event(id).isEmpty()) return error("일정#" + id + "이 없습니다");
                setStatus(ctx, "event", "events", id, "cancelled");
            }
            case "note" -> {
                if (changes.snapshot("note", id) == null) return error("메모#" + id + "이 없습니다");
                setStatus(ctx, "note", "notes", id, "dismissed");
            }
            default -> {
                return error("entity 는 goal·task·event·note 중 하나입니다");
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
        List<Store.Block> active = store.activeBlocks();
        List<Store.Block> replaced = active.stream()
                .filter(b -> "planned".equals(b.status()) && !b.start().isBefore(from) && b.start().isBefore(to))
                .toList();
        List<Store.Block> keep = active.stream().filter(b -> !replaced.contains(b)).toList();

        record Planned(long taskId, Instant start, Instant end, String screenText) {
        }
        List<Planned> planned = new ArrayList<>();
        for (JsonNode b : in.path("blocks")) {
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
            long id = db.insert("INSERT INTO blocks(task_id, start_at, end_at, screen_text) VALUES (?, ?, ?, ?)",
                    p.taskId(), p.start(), p.end(), screen);
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
            // v2: 진행 중 개입은 cancelled (v3 에서 snoozed 로 바뀐다)
            interventions.cancelRunningForBlock(id);
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
        if (!List.of("reply", "proactive").contains(kind)) {
            kind = "reply";
        }
        String importance = in.path("importance").asString("normal");
        if (!List.of("low", "normal", "high").contains(importance)) {
            importance = "normal";
        }
        long id = chat.sendAssistant(text, kind, importance, null, null);
        if ("reply".equals(kind)) {
            ctx.sentReply = true;
        }
        ObjectNode r = Json.object();
        r.put("ok", true);
        r.put("message_id", id);
        r.put("silent", false);
        return r;
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
