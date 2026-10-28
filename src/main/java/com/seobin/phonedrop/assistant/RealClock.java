package com.seobin.phonedrop.assistant;

import java.time.Instant;

public class RealClock implements AssistantClock {

    @Override
    public Instant now() {
        return Instant.now();
    }
}
