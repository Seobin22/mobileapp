package com.seobin.phonedrop.assistant;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * 매일 1회 DB 자동 백업, 7일 보관(빌드문서 v3.1 §2.1-3). 서버 cron 대신 앱의 작업(jobs)으로 돈다.
 * 매일 04:00(사용자 시간대) H2 BACKUP TO → {data-dir}/backups/daily-*.zip. 마이그레이션 직전 백업(pre-V*)은 지우지 않는다.
 */
@Service
public class BackupService {

    private static final Logger log = LoggerFactory.getLogger(BackupService.class);
    public static final String JOB = "db_backup";
    static final LocalTime AT = LocalTime.of(4, 0);
    static final int KEEP_DAYS = 7;

    private final AssistantDb db;
    private final AssistantClock clock;
    private final Jobs jobs;
    private final Tx tx;
    private final TimeUtil time;
    private final boolean enabled;

    public BackupService(AssistantDb db, AssistantClock clock, Jobs jobs, Tx tx, TimeUtil time,
                         @Value("${assistant.worker.enabled:true}") boolean enabled) {
        this.db = db;
        this.clock = clock;
        this.jobs = jobs;
        this.tx = tx;
        this.time = time;
        this.enabled = enabled;
        jobs.register(JOB, this::onBackup);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (enabled) {
            tx.exec(this::ensureJob);
        }
    }

    public void ensureJob() {
        db.update("UPDATE jobs SET status = 'cancelled', updated_at = CURRENT_TIMESTAMP WHERE status = 'scheduled' AND type = ?",
                JOB);
        jobs.schedule(JOB, 0, nextRun(clock.now()), null);
    }

    Instant nextRun(Instant now) {
        LocalDate today = time.local(now).toLocalDate();
        Instant at = today.atTime(AT).atZone(time.zone()).toInstant();
        return at.isAfter(now) ? at : today.plusDays(1).atTime(AT).atZone(time.zone()).toInstant();
    }

    private boolean onBackup(Jobs.Job job) {
        jobs.schedule(JOB, 0, nextRun(clock.now().plusSeconds(60)), null);
        Path file = db.backup("daily");
        prune();
        return file != null;
    }

    /** daily-*.zip 중 7일보다 오래된 것을 지운다. */
    void prune() {
        Path dir = db.backupDir();
        if (!Files.isDirectory(dir)) {
            return;
        }
        Instant cutoff = Instant.now().minus(Duration.ofDays(KEEP_DAYS));
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : files.filter(f -> f.getFileName().toString().startsWith("daily-")).toList()) {
                if (Files.getLastModifiedTime(p).toInstant().isBefore(cutoff)) {
                    Files.deleteIfExists(p);
                }
            }
        } catch (IOException e) {
            log.warn("backup prune failed", e);
        }
    }

    /** 관리 화면용: 백업 파일 목록(최신순). */
    public List<String> list() {
        Path dir = db.backupDir();
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> sorted = new ArrayList<>(files.filter(f -> f.toString().endsWith(".zip")).toList());
            sorted.sort(Comparator.comparing((Path f) -> f.getFileName().toString()).reversed());
            return sorted.stream().map(f -> f.getFileName().toString()).limit(20).toList();
        } catch (IOException e) {
            return List.of();
        }
    }
}
