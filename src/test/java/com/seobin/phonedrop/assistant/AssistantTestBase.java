package com.seobin.phonedrop.assistant;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import com.seobin.phonedrop.assistant.agent.AgentWorker;
import com.seobin.phonedrop.assistant.agent.llm.ScriptedLlm;
import com.seobin.phonedrop.assistant.push.FakePush;
import com.seobin.phonedrop.assistant.push.Push;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** FakeClock + FakePush + 메모리 H2. 테스트마다 테이블을 비우고 2026-11-02(월) 09:00 KST 에서 시작한다. */
@SpringBootTest
@Import(AssistantTestBase.TestClockConfig.class)
abstract class AssistantTestBase {

    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    static final Instant START = at("2026-11-02T09:00");

    @TestConfiguration
    static class TestClockConfig {
        @Bean
        @Primary
        FakeClock fakeClock() {
            return new FakeClock(START);
        }

        @Bean
        @Primary
        ScriptedLlm scriptedLlm() {
            return new ScriptedLlm();
        }
    }

    @Autowired
    ScriptedLlm llm;
    @Autowired
    AgentWorker agentWorker;

    @Autowired
    FakeClock clock;
    @Autowired
    Push pushBean;
    @Autowired
    AssistantDb db;
    @Autowired
    Tx tx;
    @Autowired
    Jobs jobs;
    @Autowired
    SettingsService settings;
    @Autowired
    TaskAdminService tasks;
    @Autowired
    InterventionService interventions;
    @Autowired
    PlanService plan;
    @Autowired
    Store store;
    @Autowired
    DeviceService devices;

    FakePush push;

    static Instant at(String localDateTime) {
        return LocalDateTime.parse(localDateTime).atZone(KST).toInstant();
    }

    @BeforeEach
    void resetState() {
        push = (FakePush) pushBean;
        for (String table : new String[]{"changes", "turns", "messages", "confirmations", "questions",
                "intervention_logs", "interventions", "jobs", "blocks", "work_products", "wakeups", "daily_flags", "tasks",
                "goals", "events", "notes",
                "device_events", "devices", "processed_actions", "settings"}) {
            db.update("DELETE FROM " + table);
        }
        db.update("UPDATE memory SET content = '' WHERE id = 1");
        llm.reset();
        db.update("UPDATE plan_state SET plan_version = 0");
        settings.seedDefaults();
        clock.set(START);
        ObjectNode device = Json.object();
        device.put("fcm_token", "tok-1");
        device.put("app_version", "test");
        tx.run(() -> devices.register(device));
        push.clear();
    }

    /** FakeClock 을 1분씩 전진시키며 매번 run_due. */
    void advanceAndRun(int minutes) {
        for (int i = 0; i < minutes; i++) {
            clock.advance(1);
            jobs.runDue();
        }
    }

    void runUntil(String localDateTime) {
        Instant target = at(localDateTime);
        while (clock.now().isBefore(target)) {
            advanceAndRun(1);
        }
    }

    long[] createBlock(String title, String startLocal, int durationMin) {
        ObjectNode req = Json.object();
        req.put("title", title);
        req.put("first_action", title + " 파일 열기");
        req.put("start", at(startLocal).atZone(KST).toOffsetDateTime().toString());
        req.put("duration_min", durationMin);
        Map<String, Object> result = tx.run(() -> tasks.create(req));
        return new long[]{(Long) result.get("task_id"), (Long) result.get("block_id")};
    }

    Map<String, Object> action(String type, Object interventionId, Long blockId, String key) {
        ObjectNode req = Json.object();
        req.put("idempotency_key", key);
        req.put("type", type);
        if (interventionId != null) {
            req.put("intervention_id", interventionId.toString());
        }
        if (blockId != null) {
            req.put("block_id", blockId);
        }
        return tx.run(() -> interventions.action(req));
    }

    void putSetting(String key, String jsonValue) {
        JsonNode value = Json.parse(jsonValue);
        tx.exec(() -> {
            settings.put(key, value);
            interventions.onSettingsChanged();
            plan.bumpAndSync();
        });
    }

    Store.Block block(long id) {
        return store.block(id).orElseThrow();
    }

    int interventionCount() {
        return db.jdbc().sql("SELECT COUNT(*) FROM interventions").query(Integer.class).single();
    }

    Store.Intervention onlyIntervention() {
        return db.jdbc().sql("SELECT * FROM interventions ORDER BY id").query(Store::intervention).single();
    }
}
