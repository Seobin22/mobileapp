package com.seobin.phonedrop.assistant.api;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.seobin.phonedrop.assistant.ApiException;
import com.seobin.phonedrop.assistant.AssistantClock;
import com.seobin.phonedrop.assistant.InterventionService;
import com.seobin.phonedrop.assistant.PlanService;
import com.seobin.phonedrop.assistant.TimeUtil;
import com.seobin.phonedrop.assistant.Tx;
import com.seobin.phonedrop.assistant.agent.TimeWindowRunner;
import com.seobin.phonedrop.assistant.agent.TimeWindows;

import tools.jackson.databind.JsonNode;

/**
 * 방해 금지·적극 개입 시간대(빌드문서 v3.6 §1.2). 앱 설정 화면만 이 API 로 바꾼다. 비서 도구에는 쓰는 도구가 없다.
 */
@RestController
@RequestMapping("/api/assistant")
public class TimeWindowController {

    private final Tx tx;
    private final AssistantClock clock;
    private final TimeUtil time;
    private final TimeWindows windows;
    private final TimeWindowRunner runner;
    private final InterventionService interventions;
    private final PlanService plan;

    public TimeWindowController(Tx tx, AssistantClock clock, TimeUtil time, TimeWindows windows,
                                TimeWindowRunner runner, InterventionService interventions, PlanService plan) {
        this.tx = tx;
        this.clock = clock;
        this.time = time;
        this.windows = windows;
        this.runner = runner;
        this.interventions = interventions;
        this.plan = plan;
    }

    @GetMapping("/v1/settings/time-windows")
    public Map<String, Object> get() {
        return tx.run(this::state);
    }

    @PutMapping("/v1/settings/time-windows")
    public Map<String, Object> put(@RequestBody JsonNode body) {
        return tx.run(() -> {
            windows.replaceAll(body);
            interventions.onSettingsChanged(); // 조용한 구간이 바뀌었을 수 있다
            runner.reschedule();
            plan.bumpAndSync();
            return state();
        });
    }

    /** 적극 개입 알람에 반응(알림 [확인]·탭). 비서가 바로 먼저 말 건다(브리핑·첫 할 일). */
    @PostMapping("/v1/time-windows/ack")
    public Map<String, Object> ack() {
        return tx.run(() -> Map.of("ok", true, "acked", runner.ack(true)));
    }

    /** [10분만 조용히] */
    @PostMapping("/v1/time-windows/pause")
    public Map<String, Object> pause() {
        return tx.run(() -> Map.of("ok", true, "paused_until", time.iso(runner.pause())));
    }

    private Map<String, Object> state() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("windows", windows.list().stream().map(TimeWindows.Window::toMap).toList());
        r.put("today", windows.todayPreview());
        r.put("now", time.iso(clock.now()));
        r.put("paused_until", windows.pausedUntil().filter(clock.now()::isBefore).map(time::iso).orElse(null));
        return r;
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, String>> apiError(ApiException e) {
        return ResponseEntity.status(e.status()).body(Map.of("error", e.getMessage()));
    }
}
