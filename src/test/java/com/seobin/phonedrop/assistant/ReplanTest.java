package com.seobin.phonedrop.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.seobin.phonedrop.assistant.agent.Changes;
import com.seobin.phonedrop.assistant.agent.Replans;
import com.seobin.phonedrop.assistant.agent.Tools;
import com.seobin.phonedrop.assistant.insight.BlockFacts;

import tools.jackson.databind.node.ObjectNode;

/** 다시 계획: 같은 할 일 블록은 옮기고, 빠진 할 일은 알리고, 새 블록은 처음 계획 시각을 이어 받는다. 시작 11/02 09:00. */
class ReplanTest extends AssistantTestBase {

    @Autowired
    Tools agentTools;
    @Autowired
    BlockFacts facts;
    @Autowired
    Replans replans;
    @Autowired
    Changes changes;

    static String iso(String local) {
        return at(local).atZone(KST).toOffsetDateTime().toString();
    }

    long turn() {
        return db.insert("INSERT INTO turns(trigger, status, started_at) VALUES ('user_message', 'running', ?)", clock.now());
    }

    ObjectNode tool(String name, String json) {
        long turnId = turn();
        return tx.run(() -> agentTools.dispatch(new Tools.Context(turnId, "user_message", false), name, Json.parse(json)));
    }

    long task(String title, int minutes) {
        return tool("upsert_task", "{\"title\":\"" + title + "\",\"first_action\":\"열기\",\"est_minutes\":" + minutes + "}")
                .get("id").asLong();
    }

    String item(long taskId, String startLocal, int minutes) {
        String end = iso(LocalDateTime.parse(startLocal).plusMinutes(minutes).toString());
        return "{\"task_id\":" + taskId + ",\"start\":\"" + iso(startLocal) + "\",\"end\":\"" + end + "\"}";
    }

    ObjectNode schedule(String fromLocal, String toLocal, String... items) {
        ObjectNode r = tool("set_schedule", "{\"from\":\"" + iso(fromLocal) + "\",\"to\":\"" + iso(toLocal)
                + "\",\"blocks\":[" + String.join(",", items) + "]}");
        assertThat(r.path("ok").asBoolean()).as(r.toString()).isTrue();
        return r;
    }

    BlockFacts.Fact fact(long blockId) {
        tx.exec(() -> facts.refresh(blockId));
        return facts.get(blockId).orElseThrow();
    }

    @Test
    void sameTaskIsMovedNotRecreated() {
        long a = task("영어", 30);
        long b = task("수학", 30);
        ObjectNode r1 = schedule("2026-11-02T10:00", "2026-11-02T18:00",
                item(a, "2026-11-02T10:00", 30), item(b, "2026-11-02T11:00", 30));
        long blockA = r1.get("created").get(0).asLong();
        long blockB = r1.get("created").get(1).asLong();

        ObjectNode r2 = schedule("2026-11-02T10:00", "2026-11-02T18:00",
                item(a, "2026-11-02T14:00", 30), item(b, "2026-11-02T11:00", 30));
        assertThat(r2.get("created")).isEmpty();
        assertThat(r2.get("cancelled")).isEmpty();
        assertThat(r2.get("moved").get(0).asLong()).isEqualTo(blockA);
        assertThat(r2.get("moved")).hasSize(1); // 수학은 그대로
        assertThat(store.block(blockA).orElseThrow().start()).isEqualTo(at("2026-11-02T14:00"));
        assertThat(store.block(blockB).orElseThrow().status()).isEqualTo("planned");
        assertThat(r2.has("dropped")).isFalse();

        BlockFacts.Fact f = fact(blockA);
        assertThat(f.plannedStartFirst()).isEqualTo(at("2026-11-02T10:00"));
        assertThat(f.moveCount()).isEqualTo(1);
        assertThat(f.driftMin()).isEqualTo(240);
    }

    @Test
    void droppedTaskIsReportedAndNewBlockInheritsHistory() {
        long a = task("영어", 30);
        long b = task("수학", 30);
        ObjectNode r1 = schedule("2026-11-02T10:00", "2026-11-02T18:00",
                item(a, "2026-11-02T10:00", 30), item(b, "2026-11-02T11:00", 30));
        long blockA = r1.get("created").get(0).asLong();

        // 수학만 남기고 영어를 빠뜨림
        ObjectNode r2 = schedule("2026-11-02T10:00", "2026-11-02T18:00", item(b, "2026-11-02T11:00", 30));
        assertThat(r2.get("cancelled").get(0).asLong()).isEqualTo(blockA);
        assertThat(r2.get("dropped")).hasSize(1);
        assertThat(r2.get("dropped").get(0).get("task_id").asLong()).isEqualTo(a);
        assertThat(tx.run(() -> replans.unresolved())).extracting(Replans.Dropped::taskId).containsExactly(a);

        // 나중에 다른 날로 다시 넣으면 새 블록이 처음 계획 시각과 옮긴 횟수를 이어 받는다
        ObjectNode r3 = schedule("2026-11-03T10:00", "2026-11-03T18:00", item(a, "2026-11-03T15:00", 30));
        long newA = r3.get("created").get(0).asLong();
        BlockFacts.Fact f = fact(newA);
        assertThat(f.plannedStartFirst()).isEqualTo(at("2026-11-02T10:00"));
        assertThat(f.moveCount()).isEqualTo(1);
        assertThat(tx.run(() -> replans.unresolved())).isEmpty();

        // 한 번 이어 받은 블록은 다시 이어 받지 않는다
        ObjectNode r4 = schedule("2026-11-03T10:00", "2026-11-03T18:00", item(a, "2026-11-03T16:00", 30));
        assertThat(r4.get("moved").get(0).asLong()).isEqualTo(newA);
        assertThat(fact(newA).moveCount()).isEqualTo(2);
        assertThat(fact(newA).plannedStartFirst()).isEqualTo(at("2026-11-02T10:00"));
    }

    @Test
    void finishedOrCancelledTaskIsNotReportedAsDropped() {
        long a = task("영어", 30);
        schedule("2026-11-02T10:00", "2026-11-02T18:00", item(a, "2026-11-02T10:00", 30), item(a, "2026-11-02T15:00", 30));
        // 같은 할 일에 블록이 하나 남아 있으면 빠진 게 아니다
        ObjectNode r = schedule("2026-11-02T10:00", "2026-11-02T12:00");
        assertThat(r.get("cancelled")).hasSize(1);
        assertThat(r.has("dropped")).isFalse();

        long b = task("수학", 30);
        schedule("2026-11-02T12:00", "2026-11-02T13:00", item(b, "2026-11-02T12:00", 30));
        tool("upsert_task", "{\"id\":" + b + ",\"status\":\"done\"}");
        assertThat(tx.run(() -> replans.unresolved())).isEmpty();
    }

    @Test
    void undoRestoresMovedBlockTime() {
        long a = task("영어", 30);
        long blockA = schedule("2026-11-02T10:00", "2026-11-02T18:00", item(a, "2026-11-02T10:00", 30))
                .get("created").get(0).asLong();
        schedule("2026-11-02T10:00", "2026-11-02T18:00", item(a, "2026-11-02T14:00", 30));
        long now = turn();
        Changes.UndoResult u = tx.run(() -> changes.undoLast(now));
        assertThat(u.restored()).isPositive();
        Store.Block b = store.block(blockA).orElseThrow();
        assertThat(b.start()).isEqualTo(at("2026-11-02T10:00"));
        assertThat(b.status()).isEqualTo("planned");
    }

    @Test
    void startNowDisplacingOtherTaskReportsDropped() {
        long a = task("영어", 30);
        long b = task("수학", 60);
        long blockA = schedule("2026-11-02T09:30", "2026-11-02T10:30", item(a, "2026-11-02T09:30", 30))
                .get("created").get(0).asLong();
        ObjectNode r = tool("start_now", "{\"task_id\":" + b + "}");
        assertThat(r.get("displaced").get(0).asLong()).isEqualTo(blockA);
        assertThat(r.get("dropped").get(0).get("task_id").asLong()).isEqualTo(a);
    }
}
