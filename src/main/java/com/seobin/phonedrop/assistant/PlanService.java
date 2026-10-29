package com.seobin.phonedrop.assistant;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.seobin.phonedrop.assistant.push.PushService;

import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** plan_version 과 plan.sync. 블록 생성·시각 변경·취소·설정 변경 때마다 bumpAndSync 한다. */
@Service
public class PlanService {

    private final AssistantDb db;
    private final AssistantClock clock;
    private final TimeUtil time;
    private final Store store;
    private final SettingsService settings;
    private final PushService push;

    public PlanService(AssistantDb db, AssistantClock clock, TimeUtil time, Store store,
                       SettingsService settings, PushService push) {
        this.db = db;
        this.clock = clock;
        this.time = time;
        this.store = store;
        this.settings = settings;
        this.push = push;
    }

    public int version() {
        return db.jdbc().sql("SELECT plan_version FROM plan_state WHERE id = 1").query(Integer.class).single();
    }

    public int bumpAndSync() {
        db.update("UPDATE plan_state SET plan_version = plan_version + 1 WHERE id = 1");
        push.pushAll(syncData());
        return version();
    }

    /** FCM plan.sync 의 data. GET /v1/sync 도 같은 값을 돌려준다. */
    public Map<String, String> syncData() {
        Instant now = clock.now();
        Instant until = now.plus(Duration.ofHours(24));

        ArrayNode quiet = Json.array();
        for (TimeUtil.Window w : time.quietWindows(now, until)) {
            ObjectNode o = quiet.addObject();
            o.put("from", time.iso(w.start()));
            o.put("to", time.iso(w.end()));
        }

        ArrayNode upcoming = Json.array();
        for (Store.Block b : store.plannedBlocksStartingBefore(now, until)) {
            ObjectNode o = upcoming.addObject();
            o.put("block_id", b.id());
            o.put("start", time.iso(b.start()));
            o.put("end", time.iso(b.end()));
            o.put("task_title", store.task(b.taskId()).map(Store.Task::title).orElse(""));
            o.put("screen_text", b.screenText());
            if (b.workProductId() != null) {
                o.put("work_product_id", b.workProductId());
            }
        }

        Map<String, String> data = new LinkedHashMap<>();
        data.put("type", "plan.sync");
        data.put("plan_version", String.valueOf(version()));
        data.put("quiet_windows", Json.write(quiet));
        data.put("upcoming", Json.write(upcoming));
        data.put("local_backup_grace_seconds", String.valueOf(settings.getInt("local_backup.grace_seconds")));
        data.put("reshow_minutes", String.valueOf(settings.getInt("fullscreen.reshow_minutes")));
        data.put("expire_grace_minutes", String.valueOf(settings.getInt("intervention.expire_grace_minutes")));
        data.put("negotiation_timeout_minutes", String.valueOf(settings.getInt("negotiation.timeout_minutes")));
        return data;
    }
}
