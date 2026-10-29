package com.seobin.phonedrop.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.seobin.phonedrop.assistant.agent.Changes;
import com.seobin.phonedrop.assistant.agent.ContextBuilder;
import com.seobin.phonedrop.assistant.agent.Permissions;
import com.seobin.phonedrop.assistant.agent.Timeline;
import com.seobin.phonedrop.assistant.agent.Tools;
import com.seobin.phonedrop.assistant.agent.Validator;

import tools.jackson.databind.node.ObjectNode;

/** 빌드문서 v2 §12.1 단위 테스트. */
class AgentUnitTest extends AssistantTestBase {

    @Autowired
    Validator validator;
    @Autowired
    Permissions permissions;
    @Autowired
    Changes changes;
    @Autowired
    Timeline timeline;
    @Autowired
    ContextBuilder context;
    @Autowired
    Tools tools;

    static String iso(String local) {
        return at(local).atZone(KST).toOffsetDateTime().toString();
    }

    long task(String title, int minutes) {
        return db.insert("INSERT INTO tasks(title, first_action, est_minutes, remaining_minutes) VALUES (?, ?, ?, ?)",
                title, title + " 열기", minutes, minutes);
    }

    /** v3.1: 일정 대신 고정 블록(lead = 출발 준비, buffer_after = 끝난 뒤 이동). */
    long event(String title, String start, String end, int before, int after) {
        return db.insert("""
                        INSERT INTO blocks(kind, title, start_at, end_at, screen_text, lead_minutes, buffer_after_min)
                        VALUES ('fixed', ?, ?, ?, '', ?, ?)""",
                title, at(start), at(end), before, after);
    }

    long block(long taskId, String start, String end) {
        return db.insert("INSERT INTO blocks(task_id, start_at, end_at, screen_text) VALUES (?, ?, ?, ?)",
                taskId, at(start), at(end), "지금 할 건 하나예요.");
    }

    long turn(String trigger) {
        return db.insert("INSERT INTO turns(trigger, status, started_at) VALUES (?, 'ok', ?)", trigger, clock.now());
    }

    List<String> check(Long taskId, String start, String end) {
        return validator.validateBlocks(List.of(new Validator.NewBlock(taskId, at(start), at(end))),
                store.activeBlocks(), clock.now());
    }

    // ------------------------------------------------------------------ validator: 7종

    @Test
    void validatorOverlapWithNewAndKeptBlocks() {
        long t = task("보고서", 60);
        List<String> two = validator.validateBlocks(List.of(
                new Validator.NewBlock(t, at("2026-11-02T10:00"), at("2026-11-02T11:00")),
                new Validator.NewBlock(t, at("2026-11-02T10:30"), at("2026-11-02T11:30"))), List.of(), clock.now());
        assertThat(two).singleElement().asString().contains("겹칩니다");

        block(t, "2026-11-02T14:00", "2026-11-02T15:00");
        assertThat(check(t, "2026-11-02T14:30", "2026-11-02T15:30")).singleElement().asString().contains("다른 블록");
    }

    @Test
    void validatorEventWithBuffer() {
        long t = task("보고서", 60);
        event("치과", "2026-11-02T15:00", "2026-11-02T16:00", 20, 20);
        // 14:00–14:50 는 버퍼(14:40~) 와 겹친다
        assertThat(check(t, "2026-11-02T14:00", "2026-11-02T14:50")).singleElement().asString()
                .contains("고정 블록").contains("치과").contains("준비·이동 포함 11/02 14:40–16:20");
        assertThat(check(t, "2026-11-02T16:20", "2026-11-02T17:00")).isEmpty();
    }

    @Test
    void validatorQuietMealPastShortInactive() {
        long t = task("보고서", 60);
        assertThat(check(t, "2026-11-02T23:00", "2026-11-03T00:00")).anyMatch(v -> v.contains("수면·방해 금지"));
        // v3.1: 기본 식사 시간이 없으므로 점심 시간에도 블록을 둘 수 있다(M2)
        assertThat(check(t, "2026-11-02T12:00", "2026-11-02T13:00")).isEmpty();
        assertThat(check(t, "2026-11-02T08:00", "2026-11-02T08:30")).anyMatch(v -> v.contains("지났습니다"));
        assertThat(check(t, "2026-11-02T10:00", "2026-11-02T10:04")).anyMatch(v -> v.contains("5분 미만"));
        assertThat(check(9999L, "2026-11-02T10:00", "2026-11-02T10:30")).anyMatch(v -> v.contains("없습니다"));
        db.update("UPDATE tasks SET status = 'done' WHERE id = ?", t);
        assertThat(check(t, "2026-11-02T10:00", "2026-11-02T10:30")).anyMatch(v -> v.contains("done 상태"));
    }

    // ------------------------------------------------------------------ permissions: 표 전 칸

    @Test
    void permissionsTable() {
        long chatEvent = event("치과", "2026-11-05T15:00", "2026-11-05T16:00", 0, 0);
        long calEvent = event("회의", "2026-11-05T10:00", "2026-11-05T11:00", 0, 0);
        db.update("UPDATE blocks SET source = 'calendar' WHERE id = ?", calEvent);
        String create = "{\"title\":\"x\",\"start\":\"" + iso("2026-11-05T09:00") + "\",\"end\":\"" + iso("2026-11-05T09:30") + "\"}";
        String move = "{\"id\":" + chatEvent + ",\"start\":\"" + iso("2026-11-06T15:00") + "\"}";

        for (String trigger : List.of("user_message", "intervention_talk", "confirmation_resolved", "pulse")) {
            boolean user = trigger.equals("user_message") || trigger.equals("intervention_talk");
            Permissions.Kind changeKind = user ? Permissions.Kind.EXECUTE : Permissions.Kind.CONFIRM;
            assertThat(permissions.decide(trigger, "upsert_fixed_block", Json.parse(create)).kind()).isEqualTo(changeKind);
            assertThat(permissions.decide(trigger, "upsert_fixed_block", Json.parse(move)).kind()).isEqualTo(changeKind);
            assertThat(permissions.decide(trigger, "move_block", Json.parse("{\"block_id\":" + chatEvent + ",\"new_start\":\""
                    + iso("2026-11-06T15:00") + "\"}")).kind()).isEqualTo(changeKind);
            assertThat(permissions.decide(trigger, "upsert_fixed_block", Json.parse("{\"id\":" + chatEvent + ",\"title\":\"치과 검진\"}")).kind())
                    .isEqualTo(Permissions.Kind.EXECUTE);
            assertThat(permissions.decide(trigger, "cancel", Json.parse("{\"entity\":\"fixed_block\",\"id\":" + chatEvent + "}")).kind())
                    .isEqualTo(changeKind);
            assertThat(permissions.decide(trigger, "upsert_goal", Json.parse("{\"id\":1,\"deadline\":null}")).kind())
                    .isEqualTo(changeKind);
            assertThat(permissions.decide(trigger, "upsert_goal", Json.parse("{\"id\":1,\"status\":\"done\"}")).kind())
                    .isEqualTo(changeKind);
            assertThat(permissions.decide(trigger, "cancel", Json.parse("{\"entity\":\"goal\",\"id\":1}")).kind())
                    .isEqualTo(changeKind);
            // 캘린더 출처는 어디서든 거절
            Permissions.Decision cal = permissions.decide(trigger, "upsert_fixed_block",
                    Json.parse("{\"id\":" + calEvent + ",\"start\":\"" + iso("2026-11-05T12:00") + "\"}"));
            assertThat(cal.kind()).isEqualTo(Permissions.Kind.REJECT);
            assertThat(cal.message()).isEqualTo("캘린더에서 바꿔주세요");
            assertThat(permissions.decide(trigger, "cancel", Json.parse("{\"entity\":\"fixed_block\",\"id\":" + calEvent + "}")).kind())
                    .isEqualTo(Permissions.Kind.REJECT);
            // 그 외 모든 도구
            for (String other : List.of("upsert_task", "set_schedule", "move_block", "send_message", "upsert_note",
                    "save_memory", "undo_last", "get_timeline")) {
                assertThat(permissions.decide(trigger, other, Json.object()).kind()).isEqualTo(Permissions.Kind.EXECUTE);
            }
            assertThat(permissions.decide(trigger, "upsert_goal", Json.parse("{\"title\":\"새 목표\",\"deadline\":\"" + iso("2026-11-30T23:00") + "\"}")).kind())
                    .isEqualTo(Permissions.Kind.EXECUTE);
        }
    }

    // ------------------------------------------------------------------ undo

    @Test
    void undoRestoresFieldsSkipsChangedFieldsAndPastBlocks() {
        long prev = turn("user_message");
        Tools.Context ctx = new Tools.Context(prev, "user_message", false);
        ObjectNode created = tx.run(() -> tools.dispatch(ctx, "upsert_task",
                Json.parse("{\"title\":\"보고서\",\"est_minutes\":60,\"first_action\":\"열기\"}")));
        long taskId = created.get("id").asLong();
        long prev2 = turn("user_message");
        Tools.Context ctx2 = new Tools.Context(prev2, "user_message", false);
        tx.run(() -> tools.dispatch(ctx2, "upsert_task",
                Json.parse("{\"id\":" + taskId + ",\"title\":\"보고서 v2\",\"priority\":5}")));
        // 그 사이 사용자가 priority 를 다른 값으로 바꿈 → priority 는 건너뛰고 title 만 되돌린다
        db.update("UPDATE tasks SET priority = 2 WHERE id = ?", taskId);

        long current = turn("user_message");
        Changes.UndoResult r = tx.run(() -> changes.undoLast(current));
        assertThat(r.restored()).isEqualTo(1);
        assertThat(r.partial()).isTrue();
        Store.Task t = store.task(taskId).orElseThrow();
        assertThat(t.title()).isEqualTo("보고서");
        assertThat(t.priority()).isEqualTo(2);
    }

    @Test
    void undoDoesNotRestorePastBlock() {
        long t = task("보고서", 60);
        long b = block(t, "2026-11-02T08:40", "2026-11-02T09:40"); // 지금(09:00) 이미 시작 시각이 지난 블록
        long prev = turn("user_message");
        Tools.Context ctx = new Tools.Context(prev, "user_message", false);
        ObjectNode moved = tx.run(() -> tools.dispatch(ctx, "move_block",
                Json.parse("{\"block_id\":" + b + ",\"new_start\":\"" + iso("2026-11-02T15:00") + "\"}")));
        assertThat(moved.get("ok").asBoolean()).isTrue();
        long current = turn("user_message");
        Changes.UndoResult r = tx.run(() -> changes.undoLast(current));
        assertThat(r.restored()).isZero();
        assertThat(r.skipped()).isEqualTo(1);
        assertThat(block(b).start()).isEqualTo(at("2026-11-02T15:00"));
    }

    // ------------------------------------------------------------------ load summary

    @Test
    void loadStatusBoundaries() {
        assertThat(Timeline.status(100, 150L, 1.5, 1.0)).isEqualTo("ok");
        assertThat(Timeline.status(100, 149L, 1.5, 1.0)).isEqualTo("tight");
        assertThat(Timeline.status(100, 100L, 1.5, 1.0)).isEqualTo("tight");
        assertThat(Timeline.status(100, 99L, 1.5, 1.0)).isEqualTo("short");
        assertThat(Timeline.status(100, null, 1.5, 1.0)).isEqualTo("no_deadline");
    }

    @Test
    @SuppressWarnings("unchecked")
    void loadSummaryCountsFreeMinutesUntilDeadline() {
        long goal = db.insert("INSERT INTO goals(title, deadline) VALUES (?, ?)", "보고서 목표", at("2026-11-02T12:00"));
        db.insert("INSERT INTO tasks(title, first_action, est_minutes, remaining_minutes, goal_id) VALUES (?, ?, ?, ?, ?)",
                "초안", "열기", 120, 120, goal);
        // 09:00~12:00 = 180분 빈 시간 → 180 ≥ 120×1.5 → ok
        List<Map<String, Object>> groups = (List<Map<String, Object>>) timeline.loadSummary().get("groups");
        assertThat(groups).singleElement().satisfies(g -> {
            assertThat(g.get("free_minutes_until_deadline")).isEqualTo(180L);
            assertThat(g.get("status")).isEqualTo("ok");
        });
        event("회의", "2026-11-02T10:00", "2026-11-02T11:00", 0, 0); // 빈 시간 120 → tight
        groups = (List<Map<String, Object>>) timeline.loadSummary().get("groups");
        assertThat(groups.getFirst().get("status")).isEqualTo("tight");
    }

    // ------------------------------------------------------------------ context

    @Test
    void contextMasksSensitiveAndListsFreeTime() {
        long sensitive = db.insert("""
                        INSERT INTO tasks(title, first_action, est_minutes, remaining_minutes, sensitive) VALUES (?, ?, ?, ?, TRUE)""",
                "정신과 상담 준비", "메모 열기", 30, 30);
        long ev = event("산부인과", "2026-11-02T15:00", "2026-11-02T16:00", 10, 10);
        db.update("UPDATE blocks SET sensitive = TRUE WHERE id = ?", ev);
        long t = task("보고서 초안", 60);
        block(t, "2026-11-02T10:00", "2026-11-02T11:00");

        String text = tx.run(() -> context.build("user_message", Json.parse("{\"message_id\":0}")));
        assertThat(text).doesNotContain("정신과").doesNotContain("산부인과");
        assertThat(text).contains("할일#" + sensitive + " 비공개 할 일").contains("[고정] 비공개 일정 블록#" + ev);
        // 09:00 기준, 10–11 블록·14:50–16:10 고정 블록(준비·이동 포함). 식사 시간 없음(v3.1)
        assertThat(text).contains("- 빈 시간: 09:00–10:00, 11:00–14:50, 16:10–23:30");
        assertThat(text).contains("# 목표 없는 할 일").contains("할일#" + t + " 보고서 초안");
    }
}
