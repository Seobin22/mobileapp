package com.seobin.phonedrop.assistant;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;

/**
 * agent worker 가 실행할 turn 을 예약한다. timing worker 쪽(개입·wakeup·pulse)도 turn 을 만들어야 해서
 * 채팅 서비스와 분리해 둔다.
 */
@Component
public class TurnQueue {

    public static final String TURN_JOB = "turn";
    /** user_message·snooze_request (빌드문서 v3 §2) */
    public static final int PRIORITY_USER = 10;
    public static final int PRIORITY_DEFAULT = 5;
    public static final int PRIORITY_PULSE = 1;

    private final AssistantDb db;
    private final AssistantClock clock;
    private final Jobs jobs;

    public TurnQueue(AssistantDb db, AssistantClock clock, Jobs jobs) {
        this.db = db;
        this.clock = clock;
        this.jobs = jobs;
    }

    /** turns 행(queued)을 만들고 turn 작업을 예약한다. */
    public long enqueue(String trigger, JsonNode ref, int priority) {
        long turnId = db.insert("INSERT INTO turns(trigger, trigger_ref, status, started_at) VALUES (?, ?, 'queued', ?)",
                trigger, Json.write(ref), clock.now());
        jobs.schedule(TURN_JOB, turnId, clock.now(), null, Json.object(), priority);
        return turnId;
    }
}
