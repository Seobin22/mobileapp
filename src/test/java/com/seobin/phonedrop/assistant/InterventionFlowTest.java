package com.seobin.phonedrop.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.node.ObjectNode;

/** 빌드문서 v1 §10 테스트 T1~T15. */
class InterventionFlowTest extends AssistantTestBase {

    @Test
    void t1_interventionAtBlockStart() {
        long[] ids = createBlock("보고서 초안", "2026-11-02T10:00", 60);
        runUntil("2026-11-02T10:00");

        assertThat(push.ofType("intervention.level")).hasSize(1);
        Map<String, String> level = push.ofType("intervention.level").getFirst();
        assertThat(level.get("block_id")).isEqualTo(String.valueOf(ids[1]));
        assertThat(level.get("level")).isEqualTo("3");
        assertThat(level.get("screen_text")).isEqualTo("지금 할 건 하나예요. 보고서 초안 파일 열기");
        assertThat(level.get("expire_at")).isEqualTo("2026-11-02T11:15:00+09:00");
        assertThat(onlyIntervention().status()).isEqualTo("running");
        assertThat(block(ids[1]).status()).isEqualTo("nudging");
    }

    @Test
    void t2_startAcksAndStopsReshow() {
        long[] ids = createBlock("보고서 초안", "2026-11-02T10:00", 60);
        runUntil("2026-11-02T10:01");
        long interventionId = onlyIntervention().id();

        Map<String, Object> result = action("start", interventionId, ids[1], "k-start");

        assertThat(result.get("status")).isEqualTo("ok");
        assertThat(onlyIntervention().status()).isEqualTo("acked");
        assertThat(block(ids[1]).status()).isEqualTo("started");
        assertThat(push.ofType("intervention.end")).singleElement()
                .satisfies(d -> assertThat(d.get("reason")).isEqualTo("acked"));

        runUntil("2026-11-02T10:40");
        assertThat(push.ofType("intervention.level")).hasSize(1);
    }

    @Test
    void t3_reshowEveryThreeMinutesThenExpire() {
        long[] ids = createBlock("보고서 초안", "2026-11-02T10:00", 60);
        runUntil("2026-11-02T10:07");
        assertThat(push.ofType("intervention.level")).hasSize(3); // 10:00, 10:03, 10:06

        runUntil("2026-11-02T11:14");
        assertThat(onlyIntervention().status()).isEqualTo("running");
        assertThat(push.ofType("intervention.level")).hasSize(25); // 10:00 ~ 11:12

        runUntil("2026-11-02T11:15");
        Store.Intervention i = onlyIntervention();
        assertThat(i.status()).isEqualTo("expired");
        assertThat(i.endReason()).isEqualTo("no_response");
        assertThat(i.endedAt()).isEqualTo(at("2026-11-02T11:15"));
        assertThat(block(ids[1]).status()).isEqualTo("missed");
        assertThat(push.ofType("intervention.end")).singleElement()
                .satisfies(d -> assertThat(d.get("reason")).isEqualTo("expired"));

        runUntil("2026-11-02T11:20");
        assertThat(push.ofType("intervention.level")).hasSize(25);
    }

    @Test
    void t4_dndAddedDuringInterventionSuspends() {
        long[] ids = createBlock("보고서 초안", "2026-11-02T10:00", 60);
        runUntil("2026-11-02T10:05");
        putSetting("dnd.windows", "[{\"days\":[1,2,3,4,5],\"start\":\"10:30\",\"end\":\"11:30\"}]");

        runUntil("2026-11-02T10:29");
        assertThat(onlyIntervention().status()).isEqualTo("running");
        runUntil("2026-11-02T10:30");

        Store.Intervention i = onlyIntervention();
        assertThat(i.status()).isEqualTo("suspended");
        assertThat(i.endReason()).isEqualTo("quiet");
        assertThat(block(ids[1]).status()).isEqualTo("missed");
        assertThat(push.ofType("intervention.end")).singleElement()
                .satisfies(d -> assertThat(d.get("reason")).isEqualTo("suspended"));
    }

    @Test
    void t4b_dndSetBeforeStartSuspendsAtWindowStart() {
        putSetting("dnd.windows", "[{\"days\":[1],\"start\":\"10:30\",\"end\":\"11:30\"}]");
        long[] ids = createBlock("보고서 초안", "2026-11-02T10:00", 30);
        // 10:30 에 끝나는 블록이라 방해 금지와 겹치지 않는다. 유예(15분) 안에 방해 금지가 시작된다.
        runUntil("2026-11-02T10:30");
        assertThat(onlyIntervention().status()).isEqualTo("suspended");
        assertThat(block(ids[1]).status()).isEqualTo("missed");
    }

    @Test
    void t5_patchMovesBlockAndOldJobIsNotRun() {
        long[] ids = createBlock("보고서 초안", "2026-11-02T10:00", 60);
        ObjectNode patch = Json.object();
        patch.put("start", "2026-11-02T11:00:00+09:00");
        tx.run(() -> tasks.patch(ids[0], patch));
        assertThat(block(ids[1]).version()).isEqualTo(2);

        runUntil("2026-11-02T10:59");
        assertThat(interventionCount()).isZero();
        List<String> oldJobs = db.jdbc().sql("""
                        SELECT status FROM jobs WHERE type = 'start_intervention' AND expected_version = 1""")
                .query(String.class).list();
        assertThat(oldJobs).allMatch(s -> s.equals("cancelled") || s.equals("skipped"));

        runUntil("2026-11-02T11:00");
        assertThat(onlyIntervention().status()).isEqualTo("running");
        runUntil("2026-11-02T12:00");
        assertThat(interventionCount()).isEqualTo(1);
    }

    @Test
    void t6_duplicateStartIsIdempotent() {
        long[] ids = createBlock("보고서 초안", "2026-11-02T10:00", 60);
        runUntil("2026-11-02T10:01");
        long interventionId = onlyIntervention().id();

        Map<String, Object> first = action("start", interventionId, ids[1], "same-key");
        Map<String, Object> second = action("start", interventionId, ids[1], "same-key");

        assertThat(first.get("status")).isEqualTo("ok");
        assertThat(second.get("status")).isEqualTo("duplicate");
        assertThat(push.ofType("intervention.end")).hasSize(1);
        assertThat(onlyIntervention().version()).isEqualTo(2);
    }

    @Test
    void t7_startAfterExpireIsAcceptedAsStartNow() {
        long[] ids = createBlock("보고서 초안", "2026-11-02T10:00", 60);
        runUntil("2026-11-02T11:30");
        assertThat(block(ids[1]).status()).isEqualTo("missed");

        Map<String, Object> result = action("start", onlyIntervention().id(), ids[1], "late-start");

        assertThat(result.get("status")).isEqualTo("accepted_as_start_now");
        Store.Block b = block(ids[1]);
        assertThat(b.status()).isEqualTo("started");
        assertThat(b.start()).isEqualTo(at("2026-11-02T11:30"));
        assertThat(b.end()).isEqualTo(at("2026-11-02T12:30"));

        // v3 §3.5(변경): 끝 시각에 자동 done 대신 started 유지 + block_ended turn
        runUntil("2026-11-02T12:30");
        assertThat(block(ids[1]).status()).isEqualTo("started");
        assertThat(db.jdbc().sql("SELECT COUNT(*) FROM turns WHERE trigger = 'block_ended'").query(Integer.class).single())
                .isEqualTo(1);
    }

    @Test
    void t8_overlappingRegistrationRejected() {
        createBlock("보고서 초안", "2026-11-02T10:00", 60);
        assertThatThrownBy(() -> createBlock("다른 일", "2026-11-02T10:30", 60))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("겹쳐요")
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(422));
    }

    @Test
    void t9_afterMidnightAllowedWhenSleepIsLate() {
        putSetting("life.sleep", "\"01:00\"");
        long[] ids = createBlock("야간 정리", "2026-11-03T00:30", 20);
        assertThat(block(ids[1]).status()).isEqualTo("planned");
    }

    @Test
    void t10_sleepTimeRejected() {
        assertThatThrownBy(() -> createBlock("새벽 일", "2026-11-03T02:00", 30))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("수면");
    }

    @Test
    void t11_workerPausedThenResumedCreatesOneIntervention() {
        createBlock("보고서 초안", "2026-11-02T10:00", 60);
        runUntil("2026-11-02T09:59");
        clock.set(at("2026-11-02T10:10")); // 10:00~10:10 worker 정지
        jobs.runDue();

        assertThat(interventionCount()).isEqualTo(1);
        assertThat(push.ofType("intervention.level")).hasSize(1);
    }

    @Test
    void t12_localFiredBeforeDelayedServerJob() {
        long[] ids = createBlock("보고서 초안", "2026-11-02T10:00", 60);
        runUntil("2026-11-02T09:59");
        clock.set(at("2026-11-02T10:01:30")); // 서버 작업이 아직 안 돈 상태
        action("local_fired", "local:" + ids[1], ids[1], "local-1");
        assertThat(interventionCount()).isEqualTo(1);

        clock.set(at("2026-11-02T10:05")); // 서버 작업 지연 실행
        jobs.runDue();

        assertThat(interventionCount()).isEqualTo(1);
        String startJob = db.jdbc().sql("SELECT status FROM jobs WHERE type = 'start_intervention' AND target_id = ?")
                .param(ids[1]).query(String.class).single();
        assertThat(startJob).isEqualTo("skipped");
        long localLogs = db.jdbc().sql("SELECT COUNT(*) FROM intervention_logs WHERE reaction = 'local_fired'")
                .query(Long.class).single();
        assertThat(localLogs).isEqualTo(1);
    }

    @Test
    void t13_nextBlockSupersedesUnansweredIntervention() {
        long[] first = createBlock("첫째", "2026-11-02T10:00", 60);
        long[] second = createBlock("둘째", "2026-11-02T11:00", 60);
        runUntil("2026-11-02T11:00");

        List<Store.Intervention> all = db.jdbc().sql("SELECT * FROM interventions ORDER BY id")
                .query(Store::intervention).list();
        assertThat(all).hasSize(2);
        assertThat(all.get(0).status()).isEqualTo("expired");
        assertThat(all.get(0).endReason()).isEqualTo("superseded");
        assertThat(all.get(1).status()).isEqualTo("running");
        assertThat(all.get(1).blockId()).isEqualTo(second[1]);
        assertThat(block(first[1]).status()).isEqualTo("missed");
        assertThat(push.ofType("intervention.end")).singleElement()
                .satisfies(d -> assertThat(d.get("reason")).isEqualTo("superseded"));

        runUntil("2026-11-02T11:20");
        assertThat(all.get(0).id()).isEqualTo(store.intervention(all.get(0).id()).orElseThrow().id());
        assertThat(store.intervention(all.get(0).id()).orElseThrow().status()).isEqualTo("expired");
    }

    @Test
    void t14_startedBlockStaysStartedAtEnd() {
        // v3 §3.5(변경): v1 의 "끝 시각에 자동 done"을 없앴다. 정리는 block_ended turn 에서 비서가 한다.
        long[] ids = createBlock("보고서 초안", "2026-11-02T10:00", 60);
        runUntil("2026-11-02T10:02");
        action("start", onlyIntervention().id(), ids[1], "k");
        runUntil("2026-11-02T10:59");
        assertThat(block(ids[1]).status()).isEqualTo("started");
        runUntil("2026-11-02T11:00");
        Store.Block b = block(ids[1]);
        assertThat(b.status()).isEqualTo("started");
        assertThat(b.actualEnd()).isNull();
    }

    @Test
    void t15_blockChangeBumpsPlanVersionAndSyncs() {
        int before = plan.version();
        long[] ids = createBlock("보고서 초안", "2026-11-02T10:00", 60);
        assertThat(plan.version()).isEqualTo(before + 1);
        Map<String, String> sync = push.ofType("plan.sync").getLast();
        assertThat(sync.get("plan_version")).isEqualTo(String.valueOf(before + 1));
        assertThat(sync.get("upcoming")).contains("\"block_id\":" + ids[1]);
        assertThat(sync.get("expire_grace_minutes")).isEqualTo("15");

        ObjectNode patch = Json.object();
        patch.put("duration_min", 30);
        tx.run(() -> tasks.patch(ids[0], patch));
        assertThat(plan.version()).isEqualTo(before + 2);

        tx.run(() -> tasks.delete(ids[0]));
        assertThat(plan.version()).isEqualTo(before + 3);
        assertThat(push.ofType("plan.sync")).hasSize(3);
        assertThat(push.ofType("plan.sync").getLast().get("upcoming")).isEqualTo("[]");
    }

    @Test
    void deleteDuringInterventionCancelsIt() {
        long[] ids = createBlock("보고서 초안", "2026-11-02T10:00", 60);
        runUntil("2026-11-02T10:01");
        tx.run(() -> tasks.delete(ids[0]));
        assertThat(onlyIntervention().status()).isEqualTo("cancelled");
        assertThat(block(ids[1]).status()).isEqualTo("cancelled");
        assertThat(push.ofType("intervention.end")).singleElement()
                .satisfies(d -> assertThat(d.get("reason")).isEqualTo("cancelled"));
        runUntil("2026-11-02T10:10");
        assertThat(push.ofType("intervention.level")).hasSize(1);
    }

    @Test
    void dismissOnlyLogsAndReshowContinues() {
        long[] ids = createBlock("보고서 초안", "2026-11-02T10:00", 60);
        runUntil("2026-11-02T10:01");
        action("dismiss", onlyIntervention().id(), ids[1], "d1");
        runUntil("2026-11-02T10:04");
        assertThat(onlyIntervention().status()).isEqualTo("running");
        assertThat(push.ofType("intervention.level")).hasSize(2);
    }

    @Test
    void invalidTokenDeviceIsRemoved() {
        ObjectNode device = Json.object();
        device.put("fcm_token", "invalid-token");
        tx.run(() -> devices.register(device));
        createBlock("보고서 초안", "2026-11-02T10:00", 60);
        assertThat(db.jdbc().sql("SELECT COUNT(*) FROM devices").query(Integer.class).single()).isEqualTo(1);
    }
}
