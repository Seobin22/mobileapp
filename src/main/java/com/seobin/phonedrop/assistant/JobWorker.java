package com.seobin.phonedrop.assistant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 예약 작업 폴링 루프. 앱 프로세스 안의 스레드 하나라 worker 는 항상 1개다
 * (H2 파일 DB 는 한 프로세스만 열 수 있어서 두 번째 인스턴스는 시작하지 못한다).
 */
@Component
public class JobWorker {

    private static final Logger log = LoggerFactory.getLogger(JobWorker.class);

    private final Jobs jobs;
    private final boolean enabled;

    public JobWorker(Jobs jobs, @Value("${assistant.worker.enabled:true}") boolean enabled) {
        this.jobs = jobs;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${assistant.worker.poll-ms:1000}", initialDelay = 3000)
    public void poll() {
        if (!enabled) {
            return;
        }
        try {
            jobs.runDue();
        } catch (RuntimeException e) {
            log.error("job worker poll failed", e);
        }
    }
}
