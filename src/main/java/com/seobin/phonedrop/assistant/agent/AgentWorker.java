package com.seobin.phonedrop.assistant.agent;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.seobin.phonedrop.assistant.Jobs;

import jakarta.annotation.PreDestroy;

/**
 * agent worker(빌드문서 v2 §2.1). type=turn 작업만 한 번에 하나씩 처리한다(= turn 직렬화).
 * timing worker(@Scheduled)와 다른 스레드라 LLM 을 기다려도 개입 작업이 밀리지 않는다.
 */
@Component
public class AgentWorker {

    private static final Logger log = LoggerFactory.getLogger(AgentWorker.class);

    private final Jobs jobs;
    private final AgentRunner runner;
    private final boolean enabled;
    private final long pollMs;
    private volatile boolean running = true;
    private Thread thread;

    public AgentWorker(Jobs jobs, AgentRunner runner,
                       @Value("${assistant.worker.enabled:true}") boolean enabled,
                       @Value("${assistant.worker.poll-ms:1000}") long pollMs) {
        this.jobs = jobs;
        this.runner = runner;
        this.enabled = enabled;
        this.pollMs = pollMs;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!enabled) {
            return;
        }
        jobs.failInterruptedTurns();
        thread = new Thread(this::loop, "assistant-agent-worker");
        thread.setDaemon(true);
        thread.start();
    }

    private void loop() {
        while (running) {
            try {
                if (!runOnce()) {
                    Thread.sleep(pollMs);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                log.error("agent worker loop failed", e);
            }
        }
    }

    /** 시각이 된 turn 하나를 처리했으면 true. 테스트에서 직접 부른다. */
    public boolean runOnce() {
        Optional<Jobs.Job> job = jobs.claimTurn();
        if (job.isEmpty()) {
            return false;
        }
        try {
            runner.runJob(job.get());
        } catch (RuntimeException e) {
            log.error("turn job {} failed", job.get().id(), e);
            jobs.finish(job.get().id(), "failed");
        }
        return true;
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
    }
}
