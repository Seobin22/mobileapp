package com.seobin.phonedrop.assistant.agent;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.seobin.phonedrop.assistant.ApiException;
import com.seobin.phonedrop.assistant.AssistantClock;
import com.seobin.phonedrop.assistant.AssistantDb;
import com.seobin.phonedrop.assistant.BlockLogs;
import com.seobin.phonedrop.assistant.GoalCoverage;
import com.seobin.phonedrop.assistant.InterventionService;
import com.seobin.phonedrop.assistant.Json;
import com.seobin.phonedrop.assistant.PlanService;
import com.seobin.phonedrop.assistant.SettingsService;
import com.seobin.phonedrop.assistant.Store;
import com.seobin.phonedrop.assistant.TimeUtil;
import com.seobin.phonedrop.assistant.TurnQueue;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 고정 블록 만들기·바꾸기·취소(빌드문서 v3.1 §3.3 upsert_fixed_block, §4.4 /v1/fixed_blocks).
 * 도구(turnId 있음)는 changes 에 기록하고, 앱 API(turnId 없음)는 사용자 행동이라 바로 반영한 뒤 비서에게 알린다.
 */
@Service
public class FixedBlockService {

    /** 결과: error 가 있으면 실패. displaced = 밀려나 취소된 계획 블록. */
    public record Result(long id, List<Long> displaced, List<Long> cutoff, String error) {

        static Result fail(String error) {
            return new Result(0, List.of(), List.of(), error);
        }

        public boolean ok() {
            return error == null;
        }
    }

    private final AssistantDb db;
    private final AssistantClock clock;
    private final Store store;
    private final SettingsService settings;
    private final Validator validator;
    private final InterventionService interventions;
    private final PlanService plan;
    private final Changes changes;
    private final GoalCoverage coverage;
    private final TurnQueue turns;
    private final BlockLogs logs;
    private final com.seobin.phonedrop.assistant.TimeUtil time;
    private final InterruptPolicy interrupts;
    private final TravelService travel;

    public FixedBlockService(AssistantDb db, AssistantClock clock, Store store, SettingsService settings,
                             Validator validator, InterventionService interventions, PlanService plan, Changes changes,
                             GoalCoverage coverage, TurnQueue turns, BlockLogs logs,
                             com.seobin.phonedrop.assistant.TimeUtil time, InterruptPolicy interrupts,
                             TravelService travel, com.seobin.phonedrop.assistant.insight.Categories categories) {
        this.categories = categories;
        this.db = db;
        this.clock = clock;
        this.store = store;
        this.settings = settings;
        this.validator = validator;
        this.interventions = interventions;
        this.plan = plan;
        this.changes = changes;
        this.coverage = coverage;
        this.turns = turns;
        this.logs = logs;
        this.time = time;
        this.interrupts = interrupts;
        this.travel = travel;
    }

    /**
     * in: {id?, title, start, end | duration_min, lead_minutes?, buffer_after_min?, location?, goal_id?, task_id?,
     * screen_text? | first_action?, sensitive?}. turnId 가 null 이면 앱 API(source=manual).
     */
    public Result upsert(JsonNode in, Long turnId) {
        Result r = upsertInner(in, turnId);
        // v3.5 §1: 분류(앱 입력 = user, 비서 도구 = assistant)
        if (r.ok() && r.id() > 0) {
            categories.applyFrom("blocks", r.id(), in, turnId == null ? "user" : "assistant");
        }
        return r;
    }

    private final com.seobin.phonedrop.assistant.insight.Categories categories;

    private Result upsertInner(JsonNode in, Long turnId) {
        Long id = in.hasNonNull("id") ? in.get("id").asLong() : null;
        Store.Block existing = null;
        if (id != null) {
            existing = store.block(id).orElse(null);
            if (existing == null || !existing.fixed()) {
                return Result.fail("고정 블록#" + id + "이 없습니다");
            }
            // v3.3 §2: 이미 시작된(시작 시각이 지났거나 [시작]을 누른) 고정 일정은 따로 고친다(시작 시각은 그대로).
            if (inProgress(existing, clock.now()) && ("started".equals(existing.status()) || !startChange(in, existing))) {
                return editInProgress(existing, in, turnId);
            }
            if (!List.of("planned", "nudging").contains(existing.status())) {
                return Result.fail("블록#" + id + "은 " + existing.status() + " 상태라 바꿀 수 없습니다");
            }
        }
        String title = in.hasNonNull("title") ? in.get("title").asString("").trim()
                : existing == null ? "" : existing.title();
        if (title.isEmpty()) {
            return Result.fail("고정 블록에는 title 이 필요합니다");
        }
        Instant start = in.hasNonNull("start") ? TimeUtil.parse(in.get("start").asString())
                : existing == null ? null : existing.start();
        if (start == null) {
            return Result.fail("고정 블록에는 start 가 필요합니다");
        }
        Instant end;
        if (in.hasNonNull("end")) {
            end = TimeUtil.parse(in.get("end").asString());
        } else if (in.hasNonNull("duration_min")) {
            end = start.plus(Duration.ofMinutes(in.get("duration_min").asInt()));
        } else if (existing != null) {
            end = start.plus(Duration.between(existing.start(), existing.end()));
        } else {
            return Result.fail("고정 블록에는 end 또는 duration_min 이 필요합니다");
        }
        if (!end.isAfter(start)) {
            return Result.fail("end 는 start 보다 뒤여야 합니다");
        }
        // v3.1 §3.2.1: 준비 시간은 기본값이 없다. 비워 두면 아직 안 정함(lead 0 으로 계산).
        // 앱(turnId 없음)에서 넣은 값은 user, 비서 도구가 넣은 값은 assistant(사용자가 직접 말한 값이면 prep_from_user).
        boolean prepGiven = in.hasNonNull("lead_minutes") || in.hasNonNull("buffer_after_min");
        int lead = in.hasNonNull("lead_minutes") ? Math.max(0, in.get("lead_minutes").asInt())
                : existing != null ? existing.leadMinutes() : 0;
        int bufferAfter = in.hasNonNull("buffer_after_min") ? Math.max(0, in.get("buffer_after_min").asInt())
                : existing != null ? existing.bufferAfterMin() : 0;
        String prepSetBy = existing == null ? null : existing.prepSetBy();
        if (prepGiven) {
            boolean byUser = turnId == null || in.path("prep_from_user").asBoolean(false);
            boolean prepChanged = existing == null || lead != existing.leadMinutes()
                    || bufferAfter != existing.bufferAfterMin();
            if (!byUser && prepChanged && existing != null && "user".equals(existing.prepSetBy())) {
                return Result.fail("사용자가 정한 준비 시간(출발 준비 " + existing.leadMinutes() + "분, 끝난 뒤 "
                        + existing.bufferAfterMin() + "분)이라 바꾸지 않습니다. 필요하면 사용자에게 말로 제안하세요.");
            }
            if (prepChanged || prepSetBy == null) {
                prepSetBy = byUser ? "user" : "assistant";
            }
        }
        Long goalId = in.has("goal_id") ? (in.get("goal_id").isNull() ? null : in.get("goal_id").asLong())
                : existing == null ? null : existing.goalId();
        if (goalId != null && store.goal(goalId).filter(g -> "active".equals(g.status())).isEmpty()) {
            return Result.fail("활성 목표#" + goalId + "이 없습니다");
        }
        Long taskId = in.has("task_id") ? (in.get("task_id").isNull() ? null : in.get("task_id").asLong())
                : existing == null ? null : existing.taskId();
        if (taskId != null && store.task(taskId).isEmpty()) {
            return Result.fail("할일#" + taskId + "이 없습니다");
        }
        String location = in.has("location") ? emptyToNull(in.path("location").asString(""))
                : existing == null ? null : existing.location();
        boolean sensitive = in.hasNonNull("sensitive") ? in.get("sensitive").asBoolean()
                : existing != null && existing.sensitive();
        String screen = screenText(in, title, lead, existing);
        String[] level;
        try {
            level = interruptLevel(in, existing, turnId);
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        }

        Instant now = clock.now();
        boolean timeChanged = existing == null || !start.equals(existing.start()) || !end.equals(existing.end())
                || lead != existing.leadMinutes() || bufferAfter != existing.bufferAfterMin();
        Validator.FixedCheck check = timeChanged
                ? validator.validateFixed(id, start, end, lead, bufferAfter, now)
                : new Validator.FixedCheck(List.of(), List.of(), List.of());
        if (!check.errors().isEmpty()) {
            return Result.fail(String.join("\n", check.errors()));
        }

        long blockId;
        if (existing == null) {
            String source = turnId == null ? "manual" : "chat";
            // v3.2 §3.1: 메모는 사용자만 쓴다(앱). 도구로 만들 때는 비워 둔다.
            String memo = turnId == null ? memoOf(in) : "";
            blockId = db.insert("""
                            INSERT INTO blocks(kind, title, task_id, goal_id, start_at, end_at, status, screen_text,
                            lead_minutes, buffer_after_min, location, source, sensitive, prep_set_by, memo, memo_updated_at,
                            interrupt_level, interrupt_set_by)
                            VALUES ('fixed', ?, ?, ?, ?, ?, 'planned', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    title, taskId, goalId, start, end, screen, lead, bufferAfter, location, source, sensitive,
                    prepSetBy, memo, memo.isEmpty() ? null : now, level[0], level[1]);
            record(turnId, blockId, null);
            logs.add(blockId, "created", actor(turnId), turnId == null ? "사용자가 앱에서 생성" : "비서가 생성");
        } else {
            blockId = existing.id();
            JsonNode before = changes.snapshot("block", blockId);
            if (timeChanged && "nudging".equals(existing.status())) {
                // 사용자가 시각을 바꿨다: 지금 개입은 끝내고 새 시각에 다시 개입한다.
                interventions.cancelRunningForBlock(blockId);
            }
            db.update("""
                            UPDATE blocks SET title = ?, task_id = ?, goal_id = ?, start_at = ?, end_at = ?,
                            screen_text = ?, lead_minutes = ?, buffer_after_min = ?, location = ?, sensitive = ?,
                            prep_set_by = ?, status = CASE WHEN ? THEN 'planned' ELSE status END,
                            interrupt_level = ?, interrupt_set_by = ?,
                            lead_includes_travel = CASE WHEN ? THEN FALSE ELSE lead_includes_travel END,
                            version = version + CASE WHEN ? THEN 1 ELSE 0 END, updated_at = CURRENT_TIMESTAMP
                            WHERE id = ?""",
                    title, taskId, goalId, start, end, screen, lead, bufferAfter, location, sensitive, prepSetBy,
                    timeChanged, level[0], level[1], prepGiven && "user".equals(prepSetBy), timeChanged, blockId);
            record(turnId, blockId, before);
            if (!start.equals(existing.start()) || !end.equals(existing.end())) {
                ObjectNode data = Json.object();
                data.put("from_start", time.iso(existing.start()));
                data.put("from_end", time.iso(existing.end()));
                data.put("to_start", time.iso(start));
                data.put("to_end", time.iso(end));
                logs.add(blockId, "moved", actor(turnId), time.hm(existing.start()) + "→" + time.hm(start)
                        + (turnId == null ? " (사용자가 앱에서)" : " (비서)"), data);
                // v3.2 §2.2: 반복 회차의 시각을 바꾸면 '이 날만'(moved 예외).
                recordMoved(existing);
                List<Long> linked = linkedFuture(blockId);
                if (!linked.isEmpty()) {
                    notifyFixedChanged(blockId, linked, "moved");
                }
            }
        }

        List<Long> displaced = new ArrayList<>();
        for (Store.Block b : check.displaced()) {
            cancel(b, turnId);
            displaced.add(b.id());
        }
        List<Long> cutoff = new ArrayList<>();
        Instant blockedStart = start.minus(Duration.ofMinutes(lead));
        for (Store.Block b : check.cutoff()) {
            interventions.scheduleCutoff(b.id(), blockedStart);
            cutoff.add(b.id());
        }
        if (timeChanged) {
            interventions.scheduleBlockJobs(store.block(blockId).orElseThrow());
        }
        if (existing != null && !start.equals(existing.start())) {
            // 약속 시각이 바뀌면 미뤄 둔 출발은 의미가 없다(새 시각 기준으로 다시 계산).
            db.update("UPDATE blocks SET depart_at = NULL, travel_late_min = NULL WHERE linked_fixed_block_id = ? AND link_role = 'travel'",
                    blockId);
        }
        if (existing == null || !java.util.Objects.equals(location, existing.location())) {
            travel.onLocationChanged(blockId);
            if (existing != null) {
                travel.reconcile(travel.dayOf(existing.start())); // 다른 날로 옮겼으면 원래 날도
            }
        } else if (timeChanged) {
            travel.onMoved(blockId, existing.start());
        }
        if (existing != null && interrupts.hasHeld(blockId)) {
            interrupts.scheduleRelease(store.block(blockId).orElseThrow());
            interrupts.releaseIfDone(blockId);
        }
        plan.bumpAndSync();
        return new Result(blockId, displaced, cutoff, null);
    }

    /**
     * 시작 시각을 바꾸는 요청인가. 시작 화면이 뜬 채(nudging) 시작 시각이 지난 블록을 미루는 것(move_block)은
     * 진행 중 수정이 아니라 기존 미루기다. [시작]을 누른(started) 블록만 시작 시각 변경을 거절한다.
     */
    public static boolean startChange(JsonNode in, Store.Block b) {
        return in.hasNonNull("start") && !TimeUtil.parse(in.get("start").asString()).equals(b.start());
    }

    /** 시작 시각이 지났거나 [시작]을 누른, 아직 끝나지 않은 고정 블록(v3.3 §2). */
    public boolean inProgress(Store.Block b, Instant now) {
        return b.fixed() && List.of("planned", "nudging", "started").contains(b.status())
                && ("started".equals(b.status()) || !b.start().isAfter(now));
    }

    /**
     * 진행 중 고정 일정 고치기(v3.3 §2): 끝 시각(늘리기·줄이기), 제목·장소·방해 허용 정도. 시작 시각은 바꾸지 않는다.
     * 늘린 구간과 겹치는 계획 블록은 밀어내고(비서가 재배치), 고정 블록과 겹치면 거절한다.
     * 권한(비서는 확인 카드로만)은 Permissions 가 정한다. 여기에 오면 사용자 결정이다.
     */
    public Result editInProgress(Store.Block existing, JsonNode in, Long turnId) {
        Instant now = clock.now();
        long id = existing.id();
        if (in.hasNonNull("start") && !TimeUtil.parse(in.get("start").asString()).equals(existing.start())) {
            return Result.fail("이미 시작된 일정이라 시작 시각은 바꿀 수 없어요. 끝 시각만 바꿀 수 있어요");
        }
        Instant end = existing.end();
        if (in.hasNonNull("end")) {
            end = TimeUtil.parse(in.get("end").asString());
        } else if (in.hasNonNull("duration_min")) {
            end = existing.start().plus(Duration.ofMinutes(in.get("duration_min").asInt()));
        }
        if (!end.isAfter(now)) {
            return Result.fail("끝 시각이 이미 지났어요. 지금 끝내려면 [지금 끝내기]를 쓰세요");
        }
        String title = in.hasNonNull("title") ? in.get("title").asString("").trim() : existing.title();
        if (title.isEmpty()) {
            return Result.fail("고정 블록에는 title 이 필요합니다");
        }
        String location = in.has("location") ? emptyToNull(in.path("location").asString("")) : existing.location();
        String[] level;
        try {
            level = interruptLevel(in, existing, turnId);
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        }
        boolean endChanged = !end.equals(existing.end());
        Validator.FixedCheck check = new Validator.FixedCheck(List.of(), List.of(), List.of());
        if (end.isAfter(existing.end())) {
            check = validator.validateFixed(id, existing.start(), end, existing.leadMinutes(), existing.bufferAfterMin(),
                    now);
            if (!check.errors().isEmpty()) {
                return Result.fail(String.join(" ", check.errors()).replace("시간을 바꿔 주세요.", "그 일정을 먼저 옮겨 주세요."));
            }
        }
        JsonNode before = changes.snapshot("block", id);
        db.update("""
                        UPDATE blocks SET end_at = ?, title = ?, location = ?, interrupt_level = ?, interrupt_set_by = ?,
                        version = version + CASE WHEN ? THEN 1 ELSE 0 END, updated_at = CURRENT_TIMESTAMP WHERE id = ?""",
                end, title, location, level[0], level[1], endChanged, id);
        record(turnId, id, before);
        if (endChanged) {
            ObjectNode data = Json.object();
            data.put("from_end", time.iso(existing.end()));
            data.put("to_end", time.iso(end));
            logs.add(id, "moved", actor(turnId), "끝 " + time.hm(existing.end()) + "→" + time.hm(end)
                    + (end.isAfter(existing.end()) ? " (연장" : " (줄임") + (turnId == null ? ", 사용자가 앱에서)" : ")"), data);
            recordMoved(existing);
            interventions.rescheduleEnd(store.block(id).orElseThrow());
        }
        if (!title.equals(existing.title()) || !java.util.Objects.equals(location, existing.location())) {
            logs.add(id, "note", actor(turnId), "진행 중 수정:" + (title.equals(existing.title()) ? "" : " 제목")
                    + (java.util.Objects.equals(location, existing.location()) ? "" : " 장소"));
        }
        if (!java.util.Objects.equals(level[0], existing.interruptLevel())) {
            logs.add(id, "note", actor(turnId), "방해 허용 정도: " + levelText(existing.interruptLevel()) + " → "
                    + levelText(level[0]));
        }
        List<Long> displaced = new ArrayList<>();
        for (Store.Block b : check.displaced()) {
            cancel(b, turnId);
            displaced.add(b.id());
        }
        List<Long> cutoff = new ArrayList<>();
        for (Store.Block b : check.cutoff()) {
            interventions.scheduleCutoff(b.id(), now);
            cutoff.add(b.id());
        }
        Store.Block after = store.block(id).orElseThrow();
        if (interrupts.hasHeld(id)) {
            interrupts.scheduleRelease(after);
        }
        interrupts.releaseIfDone(id);
        if (!java.util.Objects.equals(location, existing.location())) {
            travel.onLocationChanged(id);
        } else if (endChanged) {
            travel.onDayChanged(id);
        }
        plan.bumpAndSync();
        return new Result(id, displaced, cutoff, null);
    }

    /** [지금 끝내기](v3.3 §2): done, actual_end = now. 모아 둔 메시지가 있으면 바로 보낸다. */
    public Result endNow(long id, Long turnId) {
        Store.Block b = store.block(id).orElse(null);
        Instant now = clock.now();
        if (b == null || !b.fixed()) {
            return Result.fail("고정 블록#" + id + "이 없습니다");
        }
        if (!inProgress(b, now)) {
            return Result.fail("진행 중인 일정만 지금 끝낼 수 있어요");
        }
        JsonNode before = changes.snapshot("block", id);
        interventions.cancelRunningForBlock(id);
        interventions.cancelBlockJobs(id);
        db.update("""
                        UPDATE blocks SET status = 'done', actual_start = COALESCE(actual_start, start_at), actual_end = ?,
                        updated_at = CURRENT_TIMESTAMP WHERE id = ?""",
                now, id);
        record(turnId, id, before);
        logs.add(id, "ended", actor(turnId), "지금 끝냄(" + time.hm(now) + (turnId == null ? ", 사용자가 앱에서)" : ")"));
        coverage.onBlockClosed(id);
        interrupts.releaseIfDone(id);
        travel.onDayChanged(id);
        plan.bumpAndSync();
        return new Result(id, List.of(), List.of(), null);
    }

    /**
     * 방해 허용 정도(v3.3 §1.3) → {level, set_by}. 앱(turnId 없음)이 정하면 user, 비서가 정하면 assistant
     * (사용자가 채팅으로 직접 말한 단계면 interrupt_from_user). 사용자가 정한 단계를 비서가 바꾸려 하면 거절한다.
     * 앱에서 "비서가 정함"(빈 값·null)을 고르면 둘 다 비운다.
     */
    String[] interruptLevel(JsonNode in, Store.Block existing, Long turnId) {
        String level = existing == null ? null : existing.interruptLevel();
        String setBy = existing == null ? null : existing.interruptSetBy();
        if (!in.has("interrupt_level")) {
            return new String[]{level, setBy};
        }
        String given = in.get("interrupt_level").isNull() ? "" : in.get("interrupt_level").asString("").trim();
        boolean byUser = turnId == null || in.path("interrupt_from_user").asBoolean(false);
        if (given.isEmpty()) {
            return byUser ? new String[]{null, null} : new String[]{level, setBy};
        }
        if (!InterruptPolicy.LEVELS.contains(given)) {
            throw new IllegalArgumentException("interrupt_level 은 dnd·quiet·allow 중 하나입니다");
        }
        if (!byUser && "user".equals(setBy) && !given.equals(level)) {
            throw new IllegalArgumentException("사용자가 정한 방해 허용 정도(" + levelText(level) + ")라 바꾸지 않습니다");
        }
        if (byUser) {
            return new String[]{given, "user"};
        }
        return new String[]{given, given.equals(level) && setBy != null ? setBy : "assistant"};
    }

    /** set_interrupt_level 도구(v3.3 §1.4): 확인 카드 없이. 사용자가 정한 단계면 거절. */
    public Result setInterruptLevel(long id, String level, Long turnId) {
        Store.Block b = store.block(id).orElse(null);
        if (b == null || !b.fixed()) {
            return Result.fail("고정 블록#" + id + "이 없습니다");
        }
        if (!List.of("planned", "nudging", "started").contains(b.status())) {
            return Result.fail("블록#" + id + "은 " + b.status() + " 상태입니다");
        }
        ObjectNode in = Json.object();
        in.put("interrupt_level", level);
        String[] v;
        try {
            v = interruptLevel(in, b, turnId);
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        }
        JsonNode before = changes.snapshot("block", id);
        db.update("UPDATE blocks SET interrupt_level = ?, interrupt_set_by = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                v[0], v[1], id);
        record(turnId, id, before);
        if (!java.util.Objects.equals(v[0], b.interruptLevel())) {
            logs.add(id, "note", actor(turnId), "방해 허용 정도: " + levelText(b.interruptLevel()) + " → " + levelText(v[0]));
        }
        interrupts.releaseIfDone(id);
        plan.bumpAndSync();
        return new Result(id, List.of(), List.of(), null);
    }

    public static String levelText(String level) {
        return level == null ? "비서가 정함" : switch (level) {
            case "dnd" -> "방해 금지";
            case "allow" -> "알림 허용";
            default -> "조용히";
        };
    }

    /** 고정 블록 취소(cancel entity=fixed_block, DELETE /v1/fixed_blocks/{id}). */
    public Result cancel(long id, Long turnId) {
        Store.Block b = store.block(id).orElse(null);
        if (b == null || !b.fixed()) {
            return Result.fail("고정 블록#" + id + "이 없습니다");
        }
        if (!List.of("planned", "nudging", "started").contains(b.status())) {
            return Result.fail("블록#" + id + "은 이미 " + b.status() + " 상태입니다");
        }
        cancel(b, turnId);
        logs.add(id, "note", actor(turnId), turnId == null ? "사용자가 앱에서 삭제" : "비서가 삭제");
        // v3.2 §2.2: 반복 회차를 지우면 '이 날만' 없음(skip 예외, 다시 만들지 않는다).
        recordSkipped(b);
        // v3.2 §6: 연결된 미래 계획 블록과 그 할 일을 취소하고 비서가 알리게 한다.
        List<Long> linked = linkedFuture(id);
        for (long linkedId : linked) {
            Store.Block lb = store.block(linkedId).orElseThrow();
            cancel(lb, turnId);
            logs.add(linkedId, "note", "system", "연결된 고정 일정#" + id + "이 삭제되어 취소");
            if (lb.taskId() != null) {
                db.update("UPDATE tasks SET status = 'cancelled', updated_at = CURRENT_TIMESTAMP WHERE id = ? AND status = 'active'",
                        lb.taskId());
            }
        }
        if (!linked.isEmpty()) {
            notifyFixedChanged(id, linked, "cancelled");
        }
        travel.onDayChanged(id); // v3.4 §2.3: 이동 블록 취소·그날 다시 계산
        plan.bumpAndSync();
        if (turnId == null) {
            coverage.onBlockClosed(id);
        }
        return new Result(id, List.of(), List.of(), null);
    }

    /** 고정 블록에 밀려난 계획 블록을 취소한다(반복 회차 생성 등 다른 서비스가 쓴다). */
    public void displace(Store.Block b, Long turnId) {
        cancel(b, turnId);
        logs.add(b.id(), "note", "system", "고정 일정에 밀려 취소");
    }

    /** 이 고정 블록에 연결된, 아직 시작 전인 미래 계획 블록. */
    public List<Long> linkedFuture(long fixedBlockId) {
        return db.jdbc().sql("""
                        SELECT id FROM blocks WHERE linked_fixed_block_id = ? AND status IN ('planned', 'nudging')
                        AND link_role IS NULL AND end_at > ? ORDER BY start_at""")
                .param(fixedBlockId).param(AssistantDb.ts(clock.now())).query(Long.class).list();
    }

    /** v3.2 §6: 고정 일정이 바뀌거나 지워지면 fixed_changed turn(priority 5, ref = 고정 블록 + 연결 블록). */
    public void notifyFixedChanged(long fixedBlockId, List<Long> linked, String change) {
        ObjectNode ref = Json.object();
        ref.put("block_id", fixedBlockId);
        ref.set("linked", Json.MAPPER.valueToTree(linked));
        ref.put("change", change);
        turns.enqueueIfAbsent("fixed_changed", ref, TurnQueue.PRIORITY_DEFAULT, clock.now());
    }

    private void recordMoved(Store.Block occurrence) {
        if (occurrence.seriesId() == null || occurrence.occurrenceDate() == null) {
            return;
        }
        int n = db.jdbc().sql("""
                        SELECT COUNT(*) FROM series_exceptions WHERE series_id = ? AND occurrence_date = ? AND action = 'moved'""")
                .param(occurrence.seriesId()).param(occurrence.occurrenceDate()).query(Integer.class).single();
        if (n == 0) {
            db.insert("""
                            INSERT INTO series_exceptions(series_id, occurrence_date, action, moved_block_id)
                            VALUES (?, ?, 'moved', ?)""",
                    occurrence.seriesId(), occurrence.occurrenceDate(), occurrence.id());
        }
    }

    private void recordSkipped(Store.Block occurrence) {
        if (occurrence.seriesId() == null || occurrence.occurrenceDate() == null) {
            return;
        }
        db.insert("INSERT INTO series_exceptions(series_id, occurrence_date, action) VALUES (?, ?, 'skip')",
                occurrence.seriesId(), occurrence.occurrenceDate());
    }

    private String memoOf(JsonNode in) {
        String memo = in.path("memo").asString("").strip();
        int max = settings.getInt("memo.max_chars");
        if (memo.length() > max) {
            throw ApiException.unprocessable("메모는 " + max + "자 이하여야 합니다");
        }
        return memo;
    }

    private static String actor(Long turnId) {
        return turnId == null ? "user" : "assistant";
    }

    private void cancel(Store.Block b, Long turnId) {
        JsonNode before = changes.snapshot("block", b.id());
        interventions.cancelRunningForBlock(b.id());
        store.setBlockStatus(b.id(), "cancelled");
        interventions.cancelBlockJobs(b.id());
        record(turnId, b.id(), before);
    }

    /**
     * 앱에서 직접 바꾼 결과를 비서에게 알린다(§4.4): displaced 가 있으면 blocks_displaced turn.
     */
    public void notifyDisplaced(Result r) {
        if (r.ok() && !r.displaced().isEmpty()) {
            ObjectNode ref = Json.object();
            ref.put("block_id", r.id());
            ref.set("displaced", Json.MAPPER.valueToTree(r.displaced()));
            turns.enqueue("blocks_displaced", ref, TurnQueue.PRIORITY_DEFAULT);
        }
    }

    /**
     * 앱에서 준비 시간을 비워 두고 만든 고정 블록이면 fixed_block_added turn(priority 10, §3.2.1).
     * 비서가 lead·buffer 를 정하거나 준비용 계획 블록을 만든다.
     */
    public void notifyPrepNeeded(Result r) {
        if (r.ok() && store.block(r.id()).map(b -> b.prepSetBy() == null).orElse(false)) {
            ObjectNode ref = Json.object();
            ref.put("block_id", r.id());
            turns.enqueue("fixed_block_added", ref, TurnQueue.PRIORITY_USER);
        }
    }

    public Map<String, Object> toResponse(Result r) {
        if (!r.ok()) {
            throw ApiException.unprocessable(r.error());
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("id", r.id());
        m.put("displaced", r.displaced());
        m.put("plan_version", plan.version());
        store.block(r.id()).ifPresent(b -> {
            m.put("interrupt_level", b.interruptLevel());
            m.put("place_name", b.placeName());
        });
        return m;
    }

    private void record(Long turnId, long blockId, JsonNode before) {
        if (turnId != null) {
            changes.record(turnId, "block", blockId, before, changes.snapshot("block", blockId));
        }
    }

    private static String screenText(JsonNode in, String title, int lead, Store.Block existing) {
        String given = in.path("screen_text").asString("").trim();
        if (!given.isEmpty()) {
            return given;
        }
        String firstAction = in.path("first_action").asString("").trim();
        if (!firstAction.isEmpty()) {
            return InterventionService.fixedScreenText(title, lead) + " 첫 행동: " + firstAction;
        }
        // 기본 문구였으면 제목·lead 에 맞춰 다시 만든다. 직접 정한 문구는 유지.
        if (existing != null && !existing.screenText().equals(
                InterventionService.fixedScreenText(existing.title(), existing.leadMinutes()))) {
            return existing.screenText();
        }
        return InterventionService.fixedScreenText(title, lead);
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
