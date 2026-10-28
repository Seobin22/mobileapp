package com.seobin.phonedrop.assistant;

import java.time.Duration;
import java.time.Instant;

/** 테스트용 시계. set/advance 로만 움직인다. */
public class FakeClock implements AssistantClock {

    private volatile Instant current;

    public FakeClock(Instant start) {
        this.current = start;
    }

    @Override
    public Instant now() {
        return current;
    }

    public void set(Instant t) {
        current = t;
    }

    public void advance(double minutes) {
        current = current.plus(Duration.ofMillis(Math.round(minutes * 60_000)));
    }
}
