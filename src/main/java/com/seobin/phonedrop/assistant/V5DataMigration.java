package com.seobin.phonedrop.assistant;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 빌드문서 v3.1 §2.3 데이터 이전. 앱 시작 때마다 실행하지만 다시 실행해도 결과가 같다.
 * 1) 활성·미래 events → 고정 블록(migrated_event_id 로 중복 판별) + 개입 예약
 * 2) 기존 계획 블록에 title·goal_id 채우기  3) 목표 label·color 채우기
 * (negotiating → talking 은 V5 SQL 이 한다.) 결과는 로그와 data_migrations 에 남긴다.
 */
@Component
public class V5DataMigration {

    private static final Logger log = LoggerFactory.getLogger(V5DataMigration.class);
    public static final String NAME = "v3_1_blocks_goals";

    private final AssistantDb db;
    private final AssistantClock clock;
    private final Tx tx;
    private final Store store;
    private final InterventionService interventions;
    private final GoalCoverage coverage;
    private final PlanService plan;

    public V5DataMigration(AssistantDb db, AssistantClock clock, Tx tx, Store store,
                           InterventionService interventions, GoalCoverage coverage, PlanService plan) {
        this.db = db;
        this.clock = clock;
        this.tx = tx;
        this.store = store;
        this.interventions = interventions;
        this.coverage = coverage;
        this.plan = plan;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStart() {
        tx.run(this::run);
    }

    public Map<String, Object> run() {
        Instant now = clock.now();
        record Event(long id, String title, Instant start, Instant end, int before, int after, String source,
                     String externalId, boolean sensitive) {
        }
        List<Event> events = db.jdbc().sql("SELECT * FROM events WHERE status = 'active' ORDER BY start_at, id")
                .query((rs, n) -> new Event(rs.getLong("id"), rs.getString("title"),
                        AssistantDb.instant(rs, "start_at"), AssistantDb.instant(rs, "end_at"),
                        rs.getInt("buffer_before_min"), rs.getInt("buffer_after_min"), rs.getString("source"),
                        rs.getString("external_id"), rs.getBoolean("sensitive")))
                .list();
        int copied = 0;
        int past = 0;
        int already = 0;
        for (Event e : events) {
            if (!e.end().isAfter(now)) {
                past++;
                continue;
            }
            int exists = db.jdbc().sql("SELECT COUNT(*) FROM blocks WHERE migrated_event_id = ?").param(e.id())
                    .query(Integer.class).single();
            if (exists > 0) {
                already++;
                continue;
            }
            // 일정의 버퍼는 비서가 정했던 값이라 prep_set_by=assistant(없으면 아직 안 정함).
            String prep = e.before() > 0 || e.after() > 0 ? "assistant" : null;
            long id = db.insert("""
                            INSERT INTO blocks(kind, title, start_at, end_at, status, screen_text, lead_minutes,
                            buffer_after_min, source, external_id, sensitive, migrated_event_id, prep_set_by)
                            VALUES ('fixed', ?, ?, ?, 'planned', ?, ?, ?, ?, ?, ?, ?, ?)""",
                    e.title(), e.start(), e.end(), InterventionService.fixedScreenText(e.title(), e.before()),
                    e.before(), e.after(), e.source(), e.externalId(), e.sensitive(), e.id(), prep);
            // 개입 시각(start − lead)이 과거면 시작 작업은 예약되지 않는다(끝 작업만).
            interventions.scheduleBlockJobs(store.block(id).orElseThrow());
            copied++;
        }

        int plannedFilled = db.update("""
                UPDATE blocks SET title = COALESCE((SELECT t.title FROM tasks t WHERE t.id = blocks.task_id), '')
                WHERE kind = 'planned' AND title = '' AND task_id IS NOT NULL""");
        db.update("""
                UPDATE blocks SET goal_id = (SELECT t.goal_id FROM tasks t WHERE t.id = blocks.task_id)
                WHERE kind = 'planned' AND goal_id IS NULL AND task_id IS NOT NULL""");

        int labeled = 0;
        int colored = 0;
        for (Store.Goal g : db.jdbc().sql("SELECT * FROM goals ORDER BY CASE WHEN status = 'active' THEN 0 ELSE 1 END, id")
                .query(Store::goal).list()) {
            if (g.label().isBlank()) {
                db.update("UPDATE goals SET label = ? WHERE id = ?", GoalCoverage.defaultLabel(g.title()), g.id());
                labeled++;
            }
            if (g.color().isBlank()) {
                db.update("UPDATE goals SET color = ? WHERE id = ?", coverage.assignColor(), g.id());
                colored++;
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("events_active", events.size());
        result.put("copied_to_fixed_blocks", copied);
        result.put("skipped_past", past);
        result.put("skipped_already_copied", already);
        result.put("planned_blocks_titled", plannedFilled);
        result.put("goals_labeled", labeled);
        result.put("goals_colored", colored);
        boolean changed = copied > 0 || plannedFilled > 0 || labeled > 0 || colored > 0;
        if (changed || db.jdbc().sql("SELECT COUNT(*) FROM data_migrations WHERE name = ?").param(NAME)
                .query(Integer.class).single() == 0) {
            db.update("MERGE INTO data_migrations(name, result, ran_at) KEY(name) VALUES (?, ?, ?)",
                    NAME, Json.write(Json.MAPPER.valueToTree(result)), now);
            log.info("v3.1 data migration: {}", result);
        }
        if (copied > 0) {
            plan.bumpAndSync();
        }
        return result;
    }

    /** 관리 화면용 마지막 이전 결과. */
    public Map<String, Object> lastResult() {
        return db.jdbc().sql("SELECT result, ran_at FROM data_migrations WHERE name = ?").param(NAME)
                .query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("result", Json.parse(rs.getString(1)));
                    m.put("ran_at", AssistantDb.instant(rs, "ran_at").toString());
                    return m;
                }).optional().orElse(Map.of());
    }
}
