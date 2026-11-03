package com.seobin.phonedrop.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.seobin.phonedrop.assistant.api.CalendarController;

/** 블록 결과(완료·일부·놓침)를 사용자가 직접 바꾸기(2026-11-03). 시작 시각 2026-11-02(월) 09:00. */
class BlockResultTest extends AssistantTestBase {

    @Autowired
    CalendarController calendar;

    @Test
    void userMarksMissedBlockDoneAndAssistantIsTold() {
        long[] ids = createBlock("보고서", "2026-11-02T09:30", 60);
        runUntil("2026-11-02T10:46"); // 끝 + 유예 15분이 지나 놓침
        assertThat(block(ids[1]).status()).isEqualTo("missed");

        Map<String, Object> r = calendar.patchResult(ids[1], Json.parse("{\"status\":\"done\"}"));
        Store.Block b = block(ids[1]);
        assertThat(b.status()).isEqualTo("done");
        assertThat(b.actualEnd()).isNotNull();
        assertThat(db.jdbc().sql("SELECT trigger FROM turns WHERE id = ?").param(r.get("turn_id")).query(String.class).single())
                .isEqualTo("block_result_by_user");
        assertThat(db.jdbc().sql("SELECT content FROM block_logs WHERE block_id = ? AND actor = 'user' ORDER BY id DESC")
                .param(ids[1]).query(String.class).list().getFirst()).isEqualTo("사용자가 결과를 바꿈: 놓침 → 완료");
    }

    @Test
    void startedBlockMarkedPartialEndsItsJobs() {
        long[] ids = createBlock("보고서", "2026-11-02T09:30", 60);
        runUntil("2026-11-02T09:31");
        action("start", onlyIntervention().id(), ids[1], "k1");
        assertThat(block(ids[1]).status()).isEqualTo("started");

        calendar.patchResult(ids[1], Json.parse("{\"status\":\"partial\"}"));
        assertThat(block(ids[1]).status()).isEqualTo("partial");
        assertThat(db.jdbc().sql("SELECT COUNT(*) FROM jobs WHERE target_id = ? AND type = 'block_end' AND status = 'scheduled'")
                .param(ids[1]).query(Integer.class).single()).isZero();
    }

    @Test
    void futureBlockCannotGetResult() {
        long[] ids = createBlock("보고서", "2026-11-02T15:00", 60);
        assertThatThrownBy(() -> calendar.patchResult(ids[1], Json.parse("{\"status\":\"done\"}")))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> calendar.patchResult(ids[1], Json.parse("{\"status\":\"cancelled\"}")))
                .isInstanceOf(ApiException.class);
    }
}
