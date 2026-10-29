package com.seobin.phonedrop.assistant;

import java.time.Instant;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;

/**
 * agent worker 가 실행할 turn 을 예약한다. timing worker 쪽(개입·wakeup·pulse)도 turn 을 만들어야 해서
 * 채팅 서비스와 분리해 둔다.
 */
@Component
public class TurnQueue {

    public static final String TURN_JOB = "turn";
    /** user_message·intervention_talk·goal_changed_by_user (빌드문서 v3 §2, v3.1 §3.6·§3.7) */
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
        return enqueueAt(trigger, ref, priority, clock.now());
    }

    /** at 에 실행할 turn. */
    public long enqueueAt(String trigger, JsonNode ref, int priority, Instant at) {
        long turnId = db.insert("INSERT INTO turns(trigger, trigger_ref, status, started_at) VALUES (?, ?, 'queued', ?)",
                trigger, Json.write(ref), clock.now());
        jobs.schedule(TURN_JOB, turnId, at, null, Json.object(), priority);
        return turnId;
    }

    /** 같은 trigger·ref 의 turn 이 아직 대기 중이면 새로 만들지 않는다. 만들었으면 true. */
    public boolean enqueueIfAbsent(String trigger, JsonNode ref, int priority, Instant at) {
        int queued = db.jdbc().sql("SELECT COUNT(*) FROM turns WHERE trigger = ? AND trigger_ref = ? AND status = 'queued'")
                .param(trigger).param(Json.write(ref)).query(Integer.class).single();
        if (queued > 0) {
            return false;
        }
        enqueueAt(trigger, ref, priority, at);
        return true;
    }
}
