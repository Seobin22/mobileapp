package com.seobin.phonedrop.assistant;

import java.time.Instant;

/**
 * 비즈니스 로직의 현재 시각은 반드시 여기서 읽는다(Instant 는 항상 UTC).
 * 운영은 RealClock, 테스트는 FakeClock.
 */
public interface AssistantClock {

    Instant now();
}
