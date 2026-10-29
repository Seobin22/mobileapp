package com.seobin.phonedrop.assistant.api;

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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.seobin.phonedrop.assistant.ApiException;
import com.seobin.phonedrop.assistant.AssistantClock;
import com.seobin.phonedrop.assistant.DeviceService;
import com.seobin.phonedrop.assistant.InterventionService;
import com.seobin.phonedrop.assistant.Jobs;
import com.seobin.phonedrop.assistant.PlanService;
import com.seobin.phonedrop.assistant.SettingsService;
import com.seobin.phonedrop.assistant.Store;
import com.seobin.phonedrop.assistant.TaskAdminService;
import com.seobin.phonedrop.assistant.TimeUtil;
import com.seobin.phonedrop.assistant.Tx;
import com.seobin.phonedrop.assistant.agent.ProactiveService;
import com.seobin.phonedrop.assistant.push.PushService;

import tools.jackson.databind.JsonNode;

/**
 * 실행 비서 API(빌드문서 v1 §7.1). 기본 주소는 https://test.seobin.page/api/assistant
 * 상태를 바꾸는 요청은 모두 Tx(락 + 트랜잭션) 안에서 처리한다.
 */
@RestController
@RequestMapping("/api/assistant")
public class AssistantController {

    private final Tx tx;
    private final AssistantClock clock;
    private final TimeUtil time;
    private final DeviceService devices;
    private final InterventionService interventions;
    private final TaskAdminService tasks;
    private final SettingsService settings;
    private final PlanService plan;
    private final Store store;
    private final Jobs jobs;
    private final PushService push;
    private final ProactiveService proactive;

    public AssistantController(Tx tx, AssistantClock clock, TimeUtil time, DeviceService devices,
                               InterventionService interventions, TaskAdminService tasks, SettingsService settings,
                               PlanService plan, Store store, Jobs jobs, PushService push, ProactiveService proactive) {
        this.tx = tx;
        this.clock = clock;
        this.time = time;
        this.devices = devices;
        this.interventions = interventions;
        this.tasks = tasks;
        this.settings = settings;
        this.plan = plan;
        this.store = store;
        this.jobs = jobs;
        this.push = push;
        this.proactive = proactive;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("ok", true);
    }

    @PostMapping("/v1/devices/register")
    public Map<String, Object> register(@RequestBody JsonNode body) {
        return Map.of("device_id", tx.run(() -> devices.register(body)));
    }

    @PostMapping("/v1/devices/heartbeat")
    public Map<String, Object> heartbeat(@RequestBody JsonNode body) {
        tx.exec(() -> devices.heartbeat(body));
        return Map.of("ok", true);
    }

    @GetMapping("/v1/sync")
    public Map<String, String> sync() {
        return tx.run(plan::syncData);
    }

    @PostMapping("/v1/actions")
    public Map<String, Object> action(@RequestBody JsonNode body) {
        return tx.run(() -> interventions.action(body));
    }

    @PostMapping("/v1/admin/tasks")
    public Map<String, Object> createTask(@RequestBody JsonNode body) {
        return tx.run(() -> tasks.create(body));
    }

    @GetMapping("/v1/admin/tasks")
    public Map<String, Object> listTasks() {
        return tx.run(tasks::list);
    }

    @PatchMapping("/v1/admin/tasks/{id}")
    public Map<String, Object> patchTask(@PathVariable long id, @RequestBody JsonNode body) {
        return tx.run(() -> tasks.patch(id, body));
    }

    @DeleteMapping("/v1/admin/tasks/{id}")
    public Map<String, Object> deleteTask(@PathVariable long id) {
        return tx.run(() -> tasks.delete(id));
    }

    @GetMapping("/v1/settings")
    public Map<String, Object> getSettings() {
        return Map.of("settings", settings.all());
    }

    @PutMapping("/v1/settings")
    public Map<String, Object> putSetting(@RequestBody JsonNode body) {
        String key = body.path("key").asString("");
        if (!body.has("value")) {
            throw ApiException.badRequest("value 가 필요합니다");
        }
        int version = tx.run(() -> {
            settings.put(key, body.get("value"));
            interventions.onSettingsChanged();
            proactive.ensureJobs(); // 기상·취침·pulse 간격이 바뀌었을 수 있다
            return plan.bumpAndSync();
        });
        return Map.of("ok", true, "plan_version", version);
    }

    /** 관리 화면(test.seobin.page)용 현재 상태. */
    @GetMapping("/v1/admin/status")
    public Map<String, Object> status() {
        return tx.run(() -> {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("now", time.iso(clock.now()));
            result.put("quiet_now", time.isQuiet(clock.now()));
            result.put("plan_version", plan.version());
            result.put("push_mode", push.mode());
            List<Map<String, Object>> running = store.runningInterventions().stream().map(i -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", i.id());
                m.put("block_id", i.blockId());
                m.put("status", i.status());
                m.put("negotiate_until", time.iso(i.negotiateUntil()));
                m.put("level", i.level());
                m.put("started_at", time.iso(i.startedAt()));
                return m;
            }).toList();
            result.put("running_interventions", running);
            result.put("devices", devices.list());
            result.put("scheduled_jobs", jobs.scheduled(30).stream().map(j -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", j.id());
                m.put("type", j.type());
                m.put("target_id", j.targetId());
                m.put("fire_at", time.iso(j.fireAt()));
                return m;
            }).toList());
            result.put("recent_pushes", push.recent());
            return result;
        });
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, String>> apiError(ApiException e) {
        return ResponseEntity.status(e.status()).body(Map.of("error", e.getMessage()));
    }
}
