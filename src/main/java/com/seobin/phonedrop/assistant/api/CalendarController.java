package com.seobin.phonedrop.assistant.api;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.seobin.phonedrop.assistant.ApiException;
import com.seobin.phonedrop.assistant.AssistantClock;
import com.seobin.phonedrop.assistant.AssistantDb;
import com.seobin.phonedrop.assistant.BlockLogs;
import com.seobin.phonedrop.assistant.Json;
import com.seobin.phonedrop.assistant.PlanService;
import com.seobin.phonedrop.assistant.Store;
import com.seobin.phonedrop.assistant.TimeUtil;
import com.seobin.phonedrop.assistant.TurnQueue;
import com.seobin.phonedrop.assistant.Tx;
import com.seobin.phonedrop.assistant.agent.Changes;
import com.seobin.phonedrop.assistant.agent.Deadlines;
import com.seobin.phonedrop.assistant.agent.MemoService;
import com.seobin.phonedrop.assistant.agent.SeriesService;
import com.seobin.phonedrop.assistant.agent.Timeline;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 빌드문서 v3.2 앱 API: 달력(§8.1, 마감 P1.3), 할 일 마감 직접 수정, turn 진행 상태(P3.2), 블록 상세·기록(§8.3),
 * 반복 고정 일정(§8.5). 사용자 조작이므로 바로 반영하고, 필요하면 비서에게 turn 으로 알린다.
 */
@RestController
@RequestMapping("/api/assistant/v1")
public class CalendarController {

    static final int MAX_DAYS = 62;

    private final Tx tx;
    private final AssistantDb db;
    private final AssistantClock clock;
    private final Store store;
    private final TimeUtil time;
    private final Timeline timeline;
    private final Deadlines deadlines;
    private final SeriesService series;
    private final MemoService memos;
    private final BlockLogs logs;
    private final TurnQueue turns;
    private final Changes changes;
    private final PlanService plan;
    private final com.seobin.phonedrop.assistant.InterventionService interventions;
    private final com.seobin.phonedrop.assistant.GoalCoverage coverage;
    private final com.seobin.phonedrop.assistant.agent.TravelService travel;

    public CalendarController(Tx tx, AssistantDb db, AssistantClock clock, Store store, TimeUtil time, Timeline timeline,
                              Deadlines deadlines, SeriesService series, MemoService memos, BlockLogs logs,
                              TurnQueue turns, Changes changes, PlanService plan,
                              com.seobin.phonedrop.assistant.InterventionService interventions,
                              com.seobin.phonedrop.assistant.GoalCoverage coverage,
                              com.seobin.phonedrop.assistant.agent.TravelService travel) {
        this.tx = tx;
        this.db = db;
        this.clock = clock;
        this.store = store;
        this.time = time;
        this.timeline = timeline;
        this.deadlines = deadlines;
        this.series = series;
        this.memos = memos;
        this.logs = logs;
        this.turns = turns;
        this.changes = changes;
        this.plan = plan;
        this.interventions = interventions;
        this.coverage = coverage;
        this.travel = travel;
    }

    // ---------------------------------------------------------------- 달력

    /** 블록(고정·계획, 메모·반복·연결 정보, 목표 색)과 마감. 최대 62일. */
    @GetMapping("/calendar")
    public Map<String, Object> calendar(@RequestParam String from, @RequestParam String to) {
        Instant f = TimeUtil.parse(from);
        Instant t = TimeUtil.parse(to);
        if (!t.isAfter(f)) {
            throw ApiException.badRequest("to 는 from 보다 뒤여야 합니다");
        }
        Instant until = Duration.between(f, t).toDays() > MAX_DAYS ? f.plus(Duration.ofDays(MAX_DAYS)) : t;
        return tx.run(() -> {
            List<Store.Block> blocks = db.jdbc().sql("""
                            SELECT * FROM blocks WHERE status <> 'cancelled' AND start_at < ? AND end_at > ?
                            ORDER BY start_at, id""")
                    .param(AssistantDb.ts(until)).param(AssistantDb.ts(f)).query(Store::block).list();
            List<Map<String, Object>> items = new ArrayList<>();
            for (Store.Block b : blocks) {
                items.add(blockMap(b));
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("from", time.iso(f));
            r.put("to", time.iso(until));
            r.put("blocks", items);
            r.put("deadlines", deadlines.between(f, until).stream().map(deadlines::toMap).toList());
            r.put("plan_version", plan.version());
            return r;
        });
    }

    private Map<String, Object> blockMap(Store.Block b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", b.id());
        m.put("kind", b.kind());
        m.put("start", time.iso(b.start()));
        m.put("end", time.iso(b.end()));
        m.put("status", b.status());
        // 일찍 끝난 블록(일부 완료 등)은 실제 끝 시각까지만 그리도록(앱 시간표 겹침 방지)
        m.put("actual_start", time.iso(b.actualStart()));
        m.put("actual_end", time.iso(b.actualEnd()));
        m.put("title", timeline.blockTitle(b));
        m.put("task_id", b.taskId());
        Store.Goal g = timeline.goalOf(b);
        m.put("goal_id", g == null ? null : g.id());
        m.put("goal_label", g == null ? "" : g.label());
        m.put("goal_color", g == null ? "" : g.color());
        m.put("series_id", b.seriesId());
        m.put("lead_minutes", b.leadMinutes());
        m.put("location", b.location());
        if (b.fixed()) {
            String memo = memos.fullMemo(b);
            m.put("memo", memo);
            // v3.3: 방해 허용 정도(null = 비서가 정함, quiet 로 동작), 찾은 장소, 오는 길·가는 길
            m.put("interrupt_level", b.interruptLevel());
            m.put("interrupt_set_by", b.interruptSetBy());
            m.put("place_name", b.placeName());
            m.put("place_source", b.placeSource());
            m.put("in_progress", b.fixed() && java.util.List.of("planned", "nudging", "started").contains(b.status())
                    && ("started".equals(b.status()) || !b.start().isAfter(clock.now())));
            List<Map<String, Object>> legs = new ArrayList<>();
            for (com.seobin.phonedrop.assistant.agent.TravelService.Leg l : travel.legs(b.id())) {
                Map<String, Object> lm = new LinkedHashMap<>();
                lm.put("direction", l.direction());
                lm.put("from", l.from());
                lm.put("to", l.to());
                lm.put("mode", l.mode());
                lm.put("minutes", l.minutes());
                lm.put("estimate", "estimate".equals(l.source()));
                lm.put("source", l.source());
                legs.add(lm);
            }
            m.put("travel", legs);
            List<Map<String, Object>> prep = new ArrayList<>();
            List<Map<String, Object>> moves = new ArrayList<>();
            for (long id : db.jdbc().sql("""
                            SELECT id FROM blocks WHERE linked_fixed_block_id = ? AND status <> 'cancelled' ORDER BY start_at""")
                    .param(b.id()).query(Long.class).list()) {
                store.block(id).ifPresent(p -> {
                    Map<String, Object> pm = new LinkedHashMap<>();
                    pm.put("id", p.id());
                    pm.put("start", time.iso(p.start()));
                    pm.put("end", time.iso(p.end()));
                    pm.put("title", timeline.blockTitle(p));
                    pm.put("status", p.status());
                    if (p.travel()) {
                        pm.put("direction", p.travelDirection());
                        pm.put("tight_min", p.travelTightMin());
                        moves.add(pm); // v3.4: 이동·귀가 블록은 준비 블록과 따로
                    } else {
                        prep.add(pm);
                    }
                });
            }
            m.put("linked_prep", prep);
            m.put("travel_blocks", moves);
        } else {
            m.put("memo", "");
            Store.Task task = b.taskId() == null ? null : store.task(b.taskId()).orElse(null);
            m.put("deadline", task == null ? null : time.iso(task.deadline()));
        }
        m.put("linked_fixed_block_id", b.linkedFixedBlockId());
        // v3.4 §8: 이동 블록(시스템 관리)
        m.put("link_role", b.linkRole());
        if (b.travel()) {
            m.put("travel_direction", b.travelDirection());
            m.put("travel_tight_min", b.travelTightMin());
            m.put("travel_late_min", b.travelLateMin());
            m.put("depart_at", time.iso(b.departAt()));
            m.put("travel_info", travel.travelInfo(b));
        }
        if (b.linkedFixedBlockId() != null) {
            m.put("linked_title", store.block(b.linkedFixedBlockId()).map(timeline::blockTitle).orElse(""));
        }
        return m;
    }

    // ---------------------------------------------------------------- 블록 상세·기록

    @GetMapping("/blocks/{id}")
    public Map<String, Object> block(@PathVariable long id) {
        return tx.run(() -> {
            Store.Block b = store.block(id).orElseThrow(() -> ApiException.notFound("블록#" + id + "이 없습니다"));
            Map<String, Object> m = blockMap(b);
            m.put("once_memo", b.memo());
            if (b.seriesId() != null) {
                series.series(b.seriesId()).ifPresent(s -> {
                    m.put("series", series.toMap(s));
                    m.put("occurrence_date", b.occurrenceDate() == null ? null : b.occurrenceDate().toString());
                });
            }
            m.put("logs", logs.ofBlock(id, 100).stream().map(l -> BlockLogs.toMap(l, time)).toList());
            return m;
        });
    }

    /** 사용자가 결과를 직접 바꾼 상태 → 기록 문구 */
    private static final Map<String, String> RESULT_LABELS = Map.of("done", "완료", "partial", "일부 완료", "missed", "놓침");

    /**
     * 블록 결과를 사용자가 직접 바꾼다 {status: done | partial | missed}(2026-11-03 사용자 요청).
     * 시작 시각이 지난 블록만. 진행 중 개입·예약은 끝내고, 비서에게 block_result_by_user turn 으로 알린다.
     */
    @PatchMapping("/blocks/{id}/result")
    public Map<String, Object> patchResult(@PathVariable long id, @RequestBody JsonNode body) {
        String status = body.path("status").asString("");
        if (!RESULT_LABELS.containsKey(status)) {
            throw ApiException.badRequest("status 는 done·partial·missed 중 하나입니다");
        }
        return tx.run(() -> {
            Store.Block b = store.block(id).orElseThrow(() -> ApiException.notFound("블록#" + id + "이 없습니다"));
            if (b.travel()) {
                throw ApiException.unprocessable("이동 블록은 결과를 정하지 않아요(끝 시각에 자동으로 끝나요)");
            }
            Instant now = clock.now();
            if (b.start().isAfter(now)) {
                throw ApiException.unprocessable("아직 시작 전인 블록은 결과를 정할 수 없어요");
            }
            if ("cancelled".equals(b.status())) {
                throw ApiException.unprocessable("취소된 블록입니다");
            }
            String before = b.status();
            if (status.equals(before)) {
                return Map.<String, Object>of("ok", true, "id", id, "status", status);
            }
            ObjectNode ref = Json.object();
            ref.put("block_id", id);
            ref.put("status", status);
            ref.put("before", before);
            long turnId = turns.enqueue("block_result_by_user", ref, TurnQueue.PRIORITY_DEFAULT);
            JsonNode snapBefore = changes.snapshot("block", id);
            interventions.cancelRunningForBlock(id);
            interventions.cancelBlockJobs(id);
            Instant end = b.actualEnd() != null ? b.actualEnd() : (now.isBefore(b.end()) ? now : b.end());
            Instant start = "missed".equals(status) ? b.actualStart() : (b.actualStart() != null ? b.actualStart() : b.start());
            db.update("""
                            UPDATE blocks SET status = ?, actual_start = ?, actual_end = ?, updated_at = CURRENT_TIMESTAMP
                            WHERE id = ?""",
                    status, start, "missed".equals(status) ? null : end, id);
            changes.record(turnId, "block", id, snapBefore, changes.snapshot("block", id));
            logs.add(id, "note", "user", "사용자가 결과를 바꿈: " + RESULT_LABELS.getOrDefault(before, before) + " → "
                    + RESULT_LABELS.get(status));
            coverage.onBlockClosed(id);
            if (b.fixed()) {
                travel.reconcile(travel.dayOf(b.start())); // 출발지·귀가가 달라질 수 있다
            }
            plan.bumpAndSync();
            return Map.<String, Object>of("ok", true, "id", id, "status", status, "turn_id", turnId);
        });
    }

    /** v3.4 §8: 이동 블록의 이동 시간만 고친다 {minutes: 숫자 | null(자동으로 되돌리기)}. */
    @PatchMapping("/travel_blocks/{id}")
    public Map<String, Object> patchTravelBlock(@PathVariable long id, @RequestBody JsonNode body) {
        if (!body.has("minutes") || !(body.get("minutes").isNull() || body.get("minutes").isNumber())) {
            throw ApiException.badRequest("minutes 는 숫자 또는 null 입니다");
        }
        Integer minutes = body.get("minutes").isNull() ? null : Integer.valueOf(body.get("minutes").asInt());
        return tx.run(() -> {
            Store.Block t = travel.setTravelBlockMinutes(id, minutes);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", true);
            m.put("id", id);
            m.put("status", t.status());
            m.put("start", time.iso(t.start()));
            m.put("end", time.iso(t.end()));
            m.put("travel_info", travel.travelInfo(t));
            return m;
        });
    }

    @GetMapping("/blocks/{id}/logs")
    public Map<String, Object> blockLogs(@PathVariable long id) {
        return tx.run(() -> Map.of("logs", logs.ofBlock(id, 200).stream().map(l -> BlockLogs.toMap(l, time)).toList()));
    }

    // ---------------------------------------------------------------- 할 일(마감)

    @GetMapping("/tasks/{id}")
    public Map<String, Object> task(@PathVariable long id) {
        return tx.run(() -> {
            Store.Task t = store.task(id).orElseThrow(() -> ApiException.notFound("할일#" + id + "이 없습니다"));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.id());
            m.put("title", timeline.taskTitle(t));
            m.put("status", t.status());
            m.put("deadline", time.iso(t.deadline()));
            m.put("remaining_minutes", t.remainingMinutes());
            m.put("goal_id", t.goalId());
            m.put("left_text", t.deadline() == null ? null : t.deadline().isAfter(clock.now())
                    ? "남은 " + leftText(Duration.between(clock.now(), t.deadline())) : "지남");
            List<Map<String, Object>> blocks = new ArrayList<>();
            for (Store.Block b : store.blocksOfTask(id)) {
                if (!"cancelled".equals(b.status())) {
                    blocks.add(Map.of("id", b.id(), "start", time.iso(b.start()), "end", time.iso(b.end()),
                            "status", b.status()));
                }
            }
            m.put("blocks", blocks);
            return m;
        });
    }

    /** {deadline: ISO | null}. 사용자 조작이라 바로 반영하고 비서에게 알린다(task_changed_by_user turn). */
    @PatchMapping("/tasks/{id}")
    public Map<String, Object> patchTask(@PathVariable long id, @RequestBody JsonNode body) {
        if (!body.has("deadline")) {
            throw ApiException.badRequest("deadline 이 필요합니다");
        }
        return tx.run(() -> {
            Store.Task t = store.task(id).orElseThrow(() -> ApiException.notFound("할일#" + id + "이 없습니다"));
            if (!"active".equals(t.status())) {
                throw ApiException.unprocessable("할일#" + id + "은 " + t.status() + " 상태입니다");
            }
            JsonNode v = body.get("deadline");
            Instant deadline = v.isNull() || v.asString("").isBlank() ? null : TimeUtil.parse(v.asString());
            ObjectNode ref = Json.object();
            ref.put("task_id", id);
            ref.put("fields", "deadline");
            long turnId = turns.enqueue("task_changed_by_user", ref, TurnQueue.PRIORITY_USER);
            JsonNode before = changes.snapshot("task", id);
            db.update("UPDATE tasks SET deadline = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?", deadline, id);
            changes.record(turnId, "task", id, before, changes.snapshot("task", id));
            return Map.<String, Object>of("ok", true, "id", id, "turn_id", turnId);
        });
    }

    // ---------------------------------------------------------------- turn 진행 상태

    @GetMapping("/turns/{id}/status")
    public Map<String, Object> turnStatus(@PathVariable long id) {
        return tx.run(() -> db.jdbc().sql("SELECT status, stage, stage_text, queued_at, started_at FROM turns WHERE id = ?")
                .param(id).query((rs, n) -> {
                    String status = rs.getString("status");
                    String stage = rs.getString("stage");
                    if (stage == null) {
                        // v3.2 이전에 만든 turn 이나 코드가 바로 끝낸 turn
                        stage = "queued".equals(status) ? "queued" : "running".equals(status) ? "thinking"
                                : "failed".equals(status) ? "failed" : "done";
                    }
                    Instant since = AssistantDb.instant(rs, "queued_at");
                    if (since == null) {
                        since = AssistantDb.instant(rs, "started_at");
                    }
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("stage", stage);
                    m.put("stage_text", rs.getString("stage_text") == null ? "" : rs.getString("stage_text"));
                    m.put("elapsed_sec", since == null ? 0 : Math.max(0, Duration.between(since, clock.now()).toSeconds()));
                    return m;
                }).optional().orElseThrow(() -> ApiException.notFound("turn#" + id + "이 없습니다")));
    }

    // ---------------------------------------------------------------- 반복 고정 일정

    @GetMapping("/fixed_series")
    public Map<String, Object> listSeries() {
        return tx.run(() -> Map.of("series", series.activeSeries().stream().map(series::toMap).toList()));
    }

    @PostMapping("/fixed_series")
    public Map<String, Object> createSeries(@RequestBody JsonNode body) {
        if (body.hasNonNull("id")) {
            throw ApiException.badRequest("새 반복 일정에는 id 를 넣지 않습니다");
        }
        return tx.run(() -> seriesResponse(series.upsert(body, null)));
    }

    @PatchMapping("/fixed_series/{id}")
    public Map<String, Object> patchSeries(@PathVariable long id, @RequestBody JsonNode body,
                                           @RequestParam(defaultValue = "all") String scope,
                                           @RequestParam(name = "from_date", required = false) String fromDate) {
        ObjectNode in = body.isObject() ? ((ObjectNode) body).deepCopy() : Json.object();
        in.put("id", id);
        in.put("scope", scope);
        if (fromDate != null && !fromDate.isBlank()) {
            in.put("from_date", fromDate);
        }
        // 메모만 바꾸면 회차를 다시 만들지 않는다.
        boolean memoOnly = in.has("memo") && in.size() == 3 + (in.has("from_date") ? 1 : 0);
        return tx.run(() -> {
            if (memoOnly) {
                memos.setSeriesMemo(id, in.path("memo").asString(""));
                return Map.<String, Object>of("ok", true, "id", id);
            }
            return seriesResponse(series.upsert(in, null));
        });
    }

    @DeleteMapping("/fixed_series/{id}")
    public Map<String, Object> deleteSeries(@PathVariable long id, @RequestParam(defaultValue = "all") String scope,
                                            @RequestParam(name = "from_date", required = false) String fromDate) {
        LocalDate from = fromDate == null || fromDate.isBlank() ? null : LocalDate.parse(fromDate);
        return tx.run(() -> seriesResponse(series.cancel(id, scope, from, null)));
    }

    private Map<String, Object> seriesResponse(SeriesService.Result r) {
        if (!r.ok()) {
            throw ApiException.unprocessable(r.error());
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("id", r.id());
        m.put("created", r.created());
        m.put("plan_version", plan.version());
        return m;
    }

    private static String leftText(Duration d) {
        long hours = d.toHours();
        if (hours >= 24) {
            return hours / 24 + "일" + (hours % 24 > 0 ? " " + hours % 24 + "시간" : "");
        }
        return hours > 0 ? hours + "시간" : Math.max(1, d.toMinutes()) + "분";
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, String>> apiError(ApiException e) {
        return ResponseEntity.status(e.status()).body(Map.of("error", e.getMessage()));
    }
}
