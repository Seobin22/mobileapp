package com.seobin.phonedrop.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.seobin.phonedrop.assistant.agent.ChatService;
import com.seobin.phonedrop.assistant.agent.Tools;
import com.seobin.phonedrop.assistant.agent.llm.ScriptedLlm;
import com.seobin.phonedrop.assistant.api.CalendarController;
import com.seobin.phonedrop.assistant.api.GoalController;
import com.seobin.phonedrop.assistant.insight.BlockFacts;
import com.seobin.phonedrop.assistant.insight.Cards;
import com.seobin.phonedrop.assistant.insight.DayFacts;
import com.seobin.phonedrop.assistant.insight.MemoryJobs;
import com.seobin.phonedrop.assistant.insight.MemoryStore;
import com.seobin.phonedrop.assistant.insight.PatternStats;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** 빌드문서 v3.5 §13 T1~T20(T21 = 기존 테스트). 시작 11/02(월) 09:00, 기상 07:30·취침 23:30. */
class V35FlowTest extends AssistantTestBase {

    static final LocalDate DAY = LocalDate.parse("2026-11-02");

    @Autowired
    Tools agentTools;
    @Autowired
    Cards cards;
    @Autowired
    BlockFacts facts;
    @Autowired
    DayFacts days;
    @Autowired
    PatternStats stats;
    @Autowired
    MemoryJobs memoryJobs;
    @Autowired
    MemoryStore memory;
    @Autowired
    CalendarController calendar;
    @Autowired
    GoalController goalApi;
    @Autowired
    ChatService chat;

    @AfterEach
    void off() {
        cards.setEnabled(false);
        memoryJobs.setAutoJobs(false);
    }

    static String iso(String local) {
        return at(local).atZone(KST).toOffsetDateTime().toString();
    }

    ObjectNode tool(String trigger, String name, String json) {
        long turnId = db.insert("INSERT INTO turns(trigger, status, started_at) VALUES (?, 'running', ?)", trigger, clock.now());
        return tx.run(() -> agentTools.dispatch(new Tools.Context(turnId, trigger, false), name, Json.parse(json)));
    }

    void runAgent() {
        while (agentWorker.runOnce()) {
            // 대기열이 빌 때까지
        }
    }

    int count(String sql, Object... params) {
        var spec = db.jdbc().sql(sql);
        for (Object p : params) {
            spec = spec.param(p);
        }
        return spec.query(Integer.class).single();
    }

    BlockFacts.Fact fact(long blockId) {
        tx.exec(() -> facts.refresh(blockId));
        return facts.get(blockId).orElseThrow();
    }

    long fixed(String title, String startLocal, int minutes, String level) {
        ObjectNode req = Json.object();
        req.put("title", title);
        req.put("start", iso(startLocal));
        req.put("duration_min", minutes);
        req.put("lead_minutes", 0);
        if (level != null) {
            req.put("interrupt_level", level);
        }
        return ((Number) goalApi.createFixed(req).get("id")).longValue();
    }

    /** 계획 블록(할 일 + set_schedule). */
    long planned(String title, String startLocal, int minutes) {
        long taskId = tool("user_message", "upsert_task", "{\"title\":\"" + title + "\",\"first_action\":\"열기\",\"est_minutes\":"
                + minutes + "}").get("id").asLong();
        String end = iso(java.time.LocalDateTime.parse(startLocal).plusMinutes(minutes).toString());
        ObjectNode r = tool("user_message", "set_schedule", "{\"from\":\"" + iso(startLocal) + "\",\"to\":\"" + end
                + "\",\"blocks\":[{\"task_id\":" + taskId + ",\"start\":\"" + iso(startLocal) + "\",\"end\":\"" + end + "\"}]}");
        assertThat(r.path("ok").asBoolean()).as(r.toString()).isTrue();
        return r.get("created").get(0).asLong();
    }

    Map<String, Object> submit(long cardId, String json) {
        return tx.run(() -> cards.submit(cardId, Json.parse(json)));
    }

    Long check() {
        return tx.run(() -> cards.checkDaytime());
    }

    List<Long> cardBlocks(long cardId) {
        JsonNode ids = Json.parse(db.jdbc().sql("SELECT block_ids FROM cards WHERE id = ?").param(cardId)
                .query(String.class).single());
        List<Long> out = new java.util.ArrayList<>();
        ids.forEach(n -> out.add(n.asLong()));
        return out;
    }

    // ------------------------------------------------------------------ T1·T3·T4·T12 기록 테이블

    @Test
    void t1_movedTwiceStartedLate() {
        long[] ids = {0, planned("공부", "2026-11-02T10:00", 60)};
        assertThat(tool("user_message", "move_block", "{\"block_id\":" + ids[1] + ",\"new_start\":\"" + iso("2026-11-02T10:30")
                + "\"}").path("ok").asBoolean()).isTrue();
        assertThat(tool("user_message", "move_block", "{\"block_id\":" + ids[1] + ",\"new_start\":\"" + iso("2026-11-02T11:00")
                + "\"}").path("ok").asBoolean()).isTrue();
        runUntil("2026-11-02T11:12");
        action("start", onlyIntervention().id(), ids[1], "k-start");
        BlockFacts.Fact f = fact(ids[1]);
        assertThat(f.plannedStartFirst()).isEqualTo(at("2026-11-02T10:00"));
        assertThat(f.plannedStartLast()).isEqualTo(at("2026-11-02T11:00"));
        assertThat(f.driftMin()).isEqualTo(60);
        assertThat(f.startDelayMin()).isEqualTo(12);
        assertThat(f.moveCount()).isEqualTo(2);
        assertThat(f.band()).isEqualTo("morning");
        assertThat(f.kind()).isEqualTo("planned");
    }

    @Test
    void t3_recomputeKeepsHumanValues() {
        long[] ids = createBlock("공부", "2026-11-02T09:30", 30);
        runUntil("2026-11-02T10:01");
        tx.exec(() -> {
            facts.refresh(ids[1]);
            facts.setHuman(ids[1], "outcome", "done", "user");
            facts.setHuman(ids[1], "focus", 3, "user");
        });
        // undo·재계산처럼 블록 상태·시각이 바뀌어도 사람 칸은 그대로
        db.update("UPDATE blocks SET status = 'planned', start_at = ? WHERE id = ?", AssistantDb.ts(at("2026-11-02T09:40")), ids[1]);
        BlockFacts.Fact f = fact(ids[1]);
        assertThat(f.statusFinal()).isEqualTo("pending");
        assertThat(f.plannedStartLast()).isEqualTo(at("2026-11-02T09:40"));
        assertThat(f.outcome()).isEqualTo("done");
        assertThat(f.outcomeSrc()).isEqualTo("user");
        assertThat(f.focus()).isEqualTo(3);
    }

    @Test
    void t4_noResponseMissedIsInferredAndNotCounted() {
        long[] ids = {0, planned("공부", "2026-11-02T09:30", 30)};
        runUntil("2026-11-02T10:16");
        BlockFacts.Fact f = fact(ids[1]);
        assertThat(f.statusFinal()).isEqualTo("missed");
        assertThat(f.outcome()).isEqualTo("missed");
        assertThat(f.outcomeSrc()).isEqualTo("inferred");
        tx.run(stats::compute);
        assertThat(stats.list("completion_rate", null, null)).isEmpty();
    }

    @Test
    void t12_dawnBlockBelongsToPreviousLogicalDay() {
        long id = fixed("야식", "2026-11-03T01:00", 30, null);
        BlockFacts.Fact f = fact(id);
        assertThat(f.logicalDay()).isEqualTo(DAY);
        assertThat(f.band()).isEqualTo("dawn");
    }

    // ------------------------------------------------------------------ T2·T5·T6·T7·T10 정리 카드

    @Test
    void t2_cardUserValueThenCalendarChange() {
        cards.setEnabled(true);
        putSetting("card.min_blocks", "1");
        long[] ids = createBlock("공부", "2026-11-02T09:10", 20);
        runUntil("2026-11-02T09:46");
        Long cardId = check();
        assertThat(cardId).isNotNull();
        submit(cardId, "{\"blocks\":[{\"block_id\":" + ids[1] + ",\"outcome\":\"partial\"}]}");
        BlockFacts.Fact f = fact(ids[1]);
        assertThat(f.outcome()).isEqualTo("partial");
        assertThat(f.outcomeSrc()).isEqualTo("user");
        assertThat(block(ids[1]).status()).isEqualTo("partial");
        ObjectNode body = Json.object();
        body.put("status", "done");
        calendar.patchResult(ids[1], body);
        f = fact(ids[1]);
        assertThat(f.outcome()).isEqualTo("done");
        assertThat(f.outcomeSrc()).isEqualTo("user");
    }

    @Test
    void t5_threeUnrecordedSends() {
        cards.setEnabled(true);
        createBlock("가", "2026-11-02T09:10", 10);
        createBlock("나", "2026-11-02T09:20", 10);
        createBlock("다", "2026-11-02T09:30", 10);
        runUntil("2026-11-02T09:56");
        Long cardId = check();
        assertThat(cardId).isNotNull();
        assertThat(cardBlocks(cardId)).hasSize(3);
        assertThat(count("SELECT COUNT(*) FROM messages WHERE kind = 'outcome_card' AND card_id = ?", cardId)).isEqualTo(1);
    }

    @Test
    void t5_oneBlockWaitsFourHours() {
        cards.setEnabled(true);
        createBlock("가", "2026-11-02T09:10", 10);
        runUntil("2026-11-02T09:36");
        assertThat(check()).isNull(); // 기상 07:30 부터 4시간이 안 됨
        clock.set(at("2026-11-02T11:31"));
        assertThat(check()).isNotNull();
    }

    @Test
    void t5_startedBlockSoonBlockAndDndHoldCards() {
        cards.setEnabled(true);
        putSetting("card.min_blocks", "1");
        createBlock("가", "2026-11-02T09:10", 10);
        long[] next = createBlock("나", "2026-11-02T09:50", 30);
        runUntil("2026-11-02T09:36");
        assertThat(check()).isNull(); // 다음 블록이 15분 안
        runUntil("2026-11-02T09:51");
        action("start", store.runningForBlock(next[1]).orElseThrow().id(), next[1], "k-next");
        assertThat(check()).isNull(); // 진행 중 블록
        db.update("UPDATE blocks SET status = 'done', actual_end = ? WHERE id = ?", AssistantDb.ts(clock.now()), next[1]);
        fixed("시험", "2026-11-02T10:00", 60, "dnd");
        runUntil("2026-11-02T10:16");
        db.update("UPDATE interventions SET status = 'acked' WHERE status IN ('running', 'talking')");
        assertThat(check()).isNull(); // 방해 금지 일정 중
        runUntil("2026-11-02T11:01");
        assertThat(check()).isNotNull();
    }

    @Test
    void t6_twoIgnoredDaytimeCardsGoToEvening() {
        cards.setEnabled(true);
        putSetting("card.min_blocks", "1");
        putSetting("card.min_gap_hours", "0");
        putSetting("card.daytime_max", "5");
        long[] a = createBlock("가", "2026-11-02T09:10", 10);
        runUntil("2026-11-02T09:36");
        Long first = check();
        assertThat(first).isNotNull();
        long[] b = createBlock("나", "2026-11-02T09:40", 10);
        runUntil("2026-11-02T10:06");
        Long second = check();
        assertThat(second).isNotNull();
        assertThat(cardBlocks(second)).contains(a[1], b[1]); // 이전 미제출 카드의 블록이 옮겨 옴
        assertThat(db.jdbc().sql("SELECT status FROM cards WHERE id = ?").param(first).query(String.class).single())
                .isEqualTo("closed");
        createBlock("다", "2026-11-02T10:10", 10);
        runUntil("2026-11-02T10:36");
        assertThat(check()).isNull(); // 두 번 연속 제출 안 함 → 낮 카드 중단
        clock.set(at("2026-11-02T22:30"));
        tx.exec(() -> cards.evening(DAY));
        long evening = db.jdbc().sql("SELECT id FROM cards WHERE evening = TRUE").query(Long.class).single();
        assertThat(cardBlocks(evening)).hasSize(3);
        assertThat(count("SELECT COUNT(*) FROM turns WHERE trigger = 'evening'")).isZero(); // 카드 먼저
        submit(evening, "{\"blocks\":[],\"day_note\":\"\"}");
        assertThat(count("SELECT COUNT(*) FROM turns WHERE trigger = 'evening'")).isEqualTo(1); // 제출하면 회고
    }

    @Test
    void t7_buttonsOnlyNoLlmNoTurn() {
        cards.setEnabled(true);
        putSetting("card.min_blocks", "1");
        long[] ids = createBlock("공부", "2026-11-02T09:10", 20);
        runUntil("2026-11-02T09:46");
        runAgent(); // block_missed turn 정리
        Long cardId = check();
        int turnsBefore = count("SELECT COUNT(*) FROM turns");
        int llmBefore = llm.userContents.size();
        Map<String, Object> r = submit(cardId, "{\"blocks\":[{\"block_id\":" + ids[1]
                + ",\"outcome\":\"done\",\"focus\":3,\"reason_tags\":[\"fun\"]}]}");
        assertThat(r.get("saved")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM turns")).isEqualTo(turnsBefore);
        runAgent();
        assertThat(llm.userContents.size()).isEqualTo(llmBefore);
        BlockFacts.Fact f = fact(ids[1]);
        assertThat(f.focus()).isEqualTo(3);
        assertThat(f.reasons()).containsExactly("fun");
        assertThat(f.cardState()).isEqualTo("answered");
    }

    @Test
    void t10_saveWithNothingSkips() {
        cards.setEnabled(true);
        putSetting("card.min_blocks", "1");
        long[] ids = createBlock("공부", "2026-11-02T09:10", 20);
        runUntil("2026-11-02T09:46");
        Long cardId = check();
        Map<String, Object> r = submit(cardId, "{\"blocks\":[{\"block_id\":" + ids[1] + "}]}");
        assertThat(r.get("ok")).isEqualTo(true);
        BlockFacts.Fact f = fact(ids[1]);
        assertThat(f.cardState()).isEqualTo("skipped");
        assertThat(f.outcomeSrc()).isEqualTo("inferred");
    }

    // ------------------------------------------------------------------ T8·T9 서술

    @Test
    void t8_notesOnlyFilledByOneBackgroundJob() {
        cards.setEnabled(true);
        putSetting("card.min_blocks", "1");
        long[] ids = createBlock("공부", "2026-11-02T09:10", 20);
        runUntil("2026-11-02T09:46");
        runAgent();
        Long cardId = check();
        memoryJobs.setAutoJobs(true);
        int repliesBefore = count("SELECT COUNT(*) FROM messages WHERE role = 'assistant'");
        llm.then(ScriptedLlm.text("{\"blocks\":[{\"block_id\":" + ids[1]
                + ",\"outcome\":\"partial\",\"focus\":null,\"reason_tags\":[\"tired\"],\"conflict\":null}],\"request\":null}"));
        submit(cardId, "{\"blocks\":[{\"block_id\":" + ids[1] + ",\"outcome_note\":\"피곤해서 반만 했어\"}]}");
        assertThat(count("SELECT COUNT(*) FROM turns WHERE trigger = 'memory_job'")).isEqualTo(1);
        runAgent();
        BlockFacts.Fact f = fact(ids[1]);
        assertThat(f.outcome()).isEqualTo("partial");
        assertThat(f.outcomeSrc()).isEqualTo("text");
        assertThat(f.reasons()).containsExactly("tired");
        assertThat(f.outcomeNote()).isEqualTo("피곤해서 반만 했어");
        assertThat(count("SELECT COUNT(*) FROM messages WHERE role = 'assistant'")).isEqualTo(repliesBefore);
        assertThat(count("SELECT COUNT(*) FROM turns WHERE trigger = 'memory_job' AND trigger_ref LIKE '%outcome_notes%'"))
                .isEqualTo(1);
        assertThat(chat.turnsToday()).isEqualTo(count("SELECT COUNT(*) FROM turns WHERE trigger <> 'memory_job' AND status <> 'queued'"));
    }

    @Test
    void t9_requestInNoteMakesUserTurn() {
        cards.setEnabled(true);
        putSetting("card.min_blocks", "1");
        long[] ids = createBlock("공부", "2026-11-02T09:10", 20);
        runUntil("2026-11-02T09:46");
        runAgent();
        Long cardId = check();
        memoryJobs.setAutoJobs(true);
        llm.then(ScriptedLlm.text("{\"blocks\":[],\"request\":\"내일로 옮겨줘\"}"));
        submit(cardId, "{\"blocks\":[{\"block_id\":" + ids[1] + ",\"extra_note\":\"오늘은 못 하겠어 내일로 옮겨줘\"}]}");
        while (count("SELECT COUNT(*) FROM turns WHERE trigger = 'user_message'") == 0 && agentWorker.runOnce()) {
            // memory_job 만 돌린다
        }
        assertThat(count("SELECT COUNT(*) FROM turns WHERE trigger = 'user_message'")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ T11 아침 카드

    @Test
    void t11_dayCardButtonsSaveWithoutTurn() {
        cards.setEnabled(true);
        Long cardId = tx.run(() -> cards.sendDayCard(DAY));
        assertThat(cardId).isNotNull();
        assertThat(tx.run(() -> cards.sendDayCard(DAY))).isNull(); // 다시 붙이지 않는다
        int turnsBefore = count("SELECT COUNT(*) FROM turns");
        int today = tx.run(chat::turnsToday);
        tx.run(() -> cards.submitDay(DAY, Json.parse("{\"sleep\":1,\"condition\":3}")));
        DayFacts.Day d = days.get(DAY).orElseThrow();
        assertThat(d.sleep()).isEqualTo(1);
        assertThat(d.sleepSrc()).isEqualTo("user");
        assertThat(d.condition()).isEqualTo(3);
        assertThat(count("SELECT COUNT(*) FROM turns")).isEqualTo(turnsBefore);
        assertThat(tx.run(chat::turnsToday)).isEqualTo(today);
    }

    // ------------------------------------------------------------------ T13·T14·T15 통계

    void fakeFact(long id, LocalDate day, String kind, String band, String category, String status, String outcome,
                  String src) {
        db.update("""
                        INSERT INTO block_facts(block_id, logical_day, kind, category, planned_start_last, planned_minutes,
                        band, weekday, status_final, outcome, outcome_src, card_state)
                        VALUES (?, ?, ?, ?, ?, 60, ?, ?, ?, ?, ?, 'none')""",
                id, day, kind, category, AssistantDb.ts(day.atTime(10, 0).atZone(KST).toInstant()), band,
                day.getDayOfWeek().getValue(), status, outcome, src);
    }

    @Test
    void t13_statsPopulationsAndSmoothing() {
        long id = 100000;
        for (int i = 0; i < 10; i++) {
            fakeFact(id++, DAY.minusDays(i), "planned", "morning", "study", "done", "done", "status");
            fakeFact(id++, DAY.minusDays(i), "planned", "evening", "study", "missed", "missed", "user");
        }
        for (int i = 0; i < 4; i++) {
            fakeFact(id++, DAY.minusDays(i), "fixed", "afternoon", "class", "done", "done", "user");
            fakeFact(id++, DAY.minusDays(i), "travel", "afternoon", "travel", "done", "done", "inferred");
            fakeFact(id++, DAY.minusDays(i), "planned", "morning", "study", "cancelled", "done", "user");
            fakeFact(id++, DAY.minusDays(i), "planned", "night", "study", "missed", "missed", "inferred");
        }
        tx.run(stats::compute);
        PatternStats.Stat morning = stats.list("completion_rate", "band", "morning").get(0);
        assertThat(morning.n()).isEqualTo(10);
        assertThat(morning.raw()).isEqualTo(1.0);
        assertThat(morning.baseline()).isEqualTo(0.5);
        assertThat(morning.value()).isCloseTo((10 * 1.0 + 5 * 0.5) / 15, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(stats.list("completion_rate", "band", "night")).isEmpty(); // inferred 는 빠진다
        assertThat(stats.list("completion_rate", "category", null)).extracting(PatternStats.Stat::key).containsOnly("study");
        PatternStats.Stat attendance = stats.list("attendance_rate", "category", "class").get(0);
        assertThat(attendance.n()).isEqualTo(4);
        assertThat(attendance.raw()).isEqualTo(1.0);
    }

    @Test
    void t14_insufficientSamplesNotNotableAndNoInsight() {
        long id = 200000;
        for (int i = 0; i < 5; i++) {
            fakeFact(id++, DAY.minusDays(i), "planned", "morning", "study", "done", "done", "user");
            fakeFact(id++, DAY.minusDays(i), "planned", "evening", "study", "missed", "missed", "user");
        }
        tx.run(stats::compute);
        PatternStats.Stat s = stats.list("completion_rate", "band", "morning").get(0);
        assertThat(s.insufficient(8)).isTrue();
        assertThat(s.notable()).isFalse();
        tx.exec(() -> memoryJobs.applyReflection(Json.parse("""
                {"insights":[{"op":"add","content":"아침에 잘 된다","category":"study","confidence":0.8,
                "stats":[{"metric":"completion_rate","dimension":"band","key":"morning"}]}]}""")));
        assertThat(memory.search(null, "insight", null, null, "active", 10)).isEmpty();
    }

    @Test
    void t15_notableOnlyQualifyingTopFive() {
        long id = 300000;
        String[] bands = {"morning", "afternoon", "evening", "night", "dawn"};
        String[] cats = {"study", "work", "exercise", "chores", "rest", "class"};
        for (int i = 0; i < 28; i++) {
            for (int j = 0; j < bands.length; j++) {
                String outcome = j % 2 == 0 ? "done" : "missed";
                fakeFact(id++, DAY.minusDays(i), "planned", bands[j], cats[(i + j) % cats.length], outcome, outcome, "user");
            }
        }
        tx.run(stats::compute);
        List<PatternStats.Stat> notable = stats.notable();
        assertThat(notable).isNotEmpty().hasSizeLessThanOrEqualTo(5);
        for (PatternStats.Stat s : notable) {
            assertThat(s.n()).isGreaterThanOrEqualTo(8);
            assertThat(Math.abs(s.value() - s.baseline())).isGreaterThanOrEqualTo(0.15);
        }
        for (int i = 1; i < notable.size(); i++) {
            assertThat(notable.get(i - 1).score()).isGreaterThanOrEqualTo(notable.get(i).score());
        }
    }

    // ------------------------------------------------------------------ T16·T17 분류

    @Test
    void t16_assistantCategoryOutsideListBecomesOther() {
        ObjectNode r = tool("user_message", "upsert_goal", "{\"title\":\"토익 900\",\"category\":\"영어\"}");
        long goalId = r.get("id").asLong();
        assertThat(db.jdbc().sql("SELECT category || '/' || category_src || '/' || category_hint FROM goals WHERE id = ?")
                .param(goalId).query(String.class).single()).isEqualTo("other/assistant/영어");
        ObjectNode r2 = tool("user_message", "upsert_goal", "{\"title\":\"정처기\",\"category\":\"study\"}");
        assertThat(db.jdbc().sql("SELECT category FROM goals WHERE id = ?").param(r2.get("id").asLong())
                .query(String.class).single()).isEqualTo("study");
    }

    @Test
    void t17_userScheduleWithoutCategoryInferredAtNight() {
        long id = fixed("헬스장", "2026-11-02T19:00", 60, null);
        assertThat(db.jdbc().sql("SELECT category_src FROM blocks WHERE id = ?").param(id).query(String.class).optional()
                .orElse(null)).isNull();
        memoryJobs.setAutoJobs(true);
        llm.then(ScriptedLlm.text("{\"insights\":[],\"guidelines\":[],\"categories\":[{\"type\":\"block\",\"id\":" + id
                + ",\"category\":\"exercise\"}],\"checks\":[]}"));
        tx.exec(() -> memoryJobs.enqueue("memory_reflection", null));
        runAgent();
        assertThat(db.jdbc().sql("SELECT category || '/' || category_src FROM blocks WHERE id = ?").param(id)
                .query(String.class).single()).isEqualTo("exercise/inferred");
        assertThat(fact(id).category()).isEqualTo("exercise");
        // 사용자가 고른 분류는 추정이 바꾸지 않는다
        ObjectNode req = Json.object();
        req.put("title", "요가");
        req.put("start", iso("2026-11-02T20:30"));
        req.put("duration_min", 30);
        req.put("category", "rest");
        long yoga = ((Number) goalApi.createFixed(req).get("id")).longValue();
        tx.exec(() -> memoryJobs.applyReflection(Json.parse("{\"categories\":[{\"type\":\"block\",\"id\":" + yoga
                + ",\"category\":\"exercise\"}]}")));
        assertThat(db.jdbc().sql("SELECT category || '/' || category_src FROM blocks WHERE id = ?").param(yoga)
                .query(String.class).single()).isEqualTo("rest/user");
    }

    // ------------------------------------------------------------------ T18 설득 방식

    @Test
    void t18_tacticThenStartWithin15IsSuccess() {
        long[] ids = createBlock("공부", "2026-11-02T10:00", 60);
        runUntil("2026-11-02T10:02");
        long interventionId = onlyIntervention().id();
        assertThat(tool("intervention_talk", "add_block_log", "{\"block_id\":" + ids[1]
                + ",\"type\":\"talk_summary\",\"content\":\"2분만 해 보기로\",\"data\":{\"tactic\":\"micro_step\",\"reason\":\"tired\"}}")
                .path("ok").asBoolean()).isTrue();
        assertThat(tool("intervention_talk", "add_block_log", "{\"block_id\":" + ids[1]
                + ",\"type\":\"talk_summary\",\"content\":\"x\",\"data\":{\"tactic\":\"bribe\"}}").path("ok").asBoolean()).isFalse();
        runUntil("2026-11-02T10:08");
        action("start", interventionId, ids[1], "k-t18");
        BlockFacts.Fact f = fact(ids[1]);
        assertThat(Json.parse(f.tactics()).get(0).path("result").asString()).isEqualTo("success");
        tx.run(stats::compute);
        PatternStats.Stat s = stats.list("tactic_success_rate", "tactic", "micro_step").get(0);
        assertThat(s.raw()).isEqualTo(1.0);
        assertThat(stats.list("tactic_success_rate", "reason_tag", "micro_step|tired")).hasSize(1);
    }

    // ------------------------------------------------------------------ T19·T20 기억

    @Test
    void t19_legacyMigrationAndBackfillAreIdempotent() {
        db.update("UPDATE memory SET content = ? WHERE id = 1", "아침형 인간. 커피를 좋아함. 허리가 약함.");
        memoryJobs.setAutoJobs(true);
        tx.exec(memoryJobs::ensureOneTimeJobs);
        llm.then(ScriptedLlm.text("""
                {"profile":{"life":"아침형","likes":"커피"},"facts":[{"category":"health","content":"허리가 약하다"}]}"""));
        runAgent();
        assertThat(memory.section("life").content()).isEqualTo("아침형");
        int items = count("SELECT COUNT(*) FROM memory_items");
        assertThat(items).isEqualTo(2); // 사실 1 + 보관한 원문 1
        assertThat(count("SELECT COUNT(*) FROM memory_items WHERE status = 'archived'")).isEqualTo(1);
        long[] ids = createBlock("공부", "2026-11-02T10:00", 30);
        int factsBefore = tx.run(facts::backfill);
        tx.exec(memoryJobs::ensureOneTimeJobs);
        tx.run(() -> memoryJobs.enqueue("memory_migrate", null));
        runAgent();
        tx.run(facts::backfill);
        assertThat(count("SELECT COUNT(*) FROM memory_items")).isEqualTo(items);
        assertThat(count("SELECT COUNT(*) FROM block_facts")).isEqualTo(factsBefore);
        assertThat(ids[1]).isPositive();
    }

    @Test
    void t20_memoryOffStopsExtractionAndReflectionButNotCards() {
        putSetting("memory.enabled", "false");
        memoryJobs.setAutoJobs(true);
        for (int i = 0; i < 7; i++) {
            tx.exec(memoryJobs::onUserTurn);
        }
        assertThat(count("SELECT COUNT(*) FROM turns WHERE trigger = 'memory_job'")).isZero();
        int llmBefore = llm.userContents.size();
        MemoryJobs.Usage u = memoryJobs.run(Json.parse("{\"job\":\"memory_reflection\"}"));
        assertThat(u.inputTokens()).isZero();
        assertThat(llm.userContents.size()).isEqualTo(llmBefore);
        cards.setEnabled(true);
        putSetting("card.min_blocks", "1");
        createBlock("공부", "2026-11-02T09:10", 20);
        runUntil("2026-11-02T09:46");
        assertThat(check()).isNotNull();
        tx.run(stats::compute);
    }

    @Test
    void memoryToolsWork() {
        assertThat(tool("user_message", "memory_save", "{\"kind\":\"fact\",\"category\":\"health\",\"content\":\"허리가 약하다\"}")
                .path("ok").asBoolean()).isTrue();
        assertThat(tool("user_message", "profile_edit", "{\"section\":\"now\",\"op\":\"insert\",\"text\":\"시험 준비 중\"}")
                .path("ok").asBoolean()).isTrue();
        assertThat(tool("user_message", "profile_edit", "{\"section\":\"now\",\"op\":\"rewrite\",\"text\":\""
                + "가".repeat(401) + "\"}").path("ok").asBoolean()).isFalse();
        JsonNode found = tool("user_message", "memory_search", "{\"query\":\"허리\"}").path("items");
        assertThat(found).hasSize(1);
        assertThat(tool("user_message", "memory_forget", "{\"item_id\":" + found.get(0).path("id").asLong() + "}")
                .path("ok").asBoolean()).isTrue();
        assertThat(memory.search("허리", null, null, null, "active", 5)).isEmpty();
        assertThat(tool("user_message", "save_memory", "{\"content\":\"커피 좋아함\"}").path("ok").asBoolean()).isTrue();
        assertThat(memory.section("now").content()).contains("시험 준비 중").contains("커피 좋아함");
        // 사용자가 쓴 섹션은 비서가 덧붙이기만
        tx.exec(() -> memory.edit("life", "rewrite", "올빼미형", null, "user"));
        assertThat(tool("user_message", "profile_edit", "{\"section\":\"life\",\"op\":\"rewrite\",\"text\":\"아침형\"}")
                .path("ok").asBoolean()).isFalse();
    }
}
