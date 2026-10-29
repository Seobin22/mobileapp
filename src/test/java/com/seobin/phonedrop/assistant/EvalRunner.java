package com.seobin.phonedrop.assistant;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.yaml.snakeyaml.Yaml;

import com.seobin.phonedrop.assistant.agent.AgentWorker;
import com.seobin.phonedrop.assistant.agent.ChatService;
import com.seobin.phonedrop.assistant.agent.llm.AnthropicLlm;
import com.seobin.phonedrop.assistant.agent.llm.ConfiguredLlm;

import tools.jackson.databind.node.ObjectNode;

/**
 * 평가 세트 실행기(빌드문서 v2 §12.3, v3 §9.2). 실제 Claude API 를 부르므로 돈이 든다. 평소 테스트에서는 건너뛴다.
 *   ANTHROPIC_API_KEY=... make eval     (= ./mvnw test -Deval=true -Dtest=EvalRunner)
 *   폴더 고르기: -Deval.dirs=v3 (기본 v2,v3)
 * evals/{dir}/*.yaml 마다 메모리 DB 를 비우고 setup 을 넣은 뒤 trigger(기본: input 을 사용자 메시지로)를 실행하고,
 * inputs 가 있으면 이어서 사용자 메시지로 보낸다. 결과(목표·할 일·블록·일정·메모·wakeup·작업물·대화)를
 * 다른 Claude 호출로 criteria 대로 채점해 evals/report.md 를 만든다. 점수 기준은 없다. 사용자가 읽고 판단한다.
 *
 * v3 yaml 확장:
 *   trigger: {type: snooze_request|pulse|wakeup|block_missed|block_ended|morning|evening, ...ref 필드}
 *            snooze_request 는 block(setup.blocks 순번)의 진행 중 개입에 [미루기 요청] 액션을 보낸다.
 *            그 외는 ref(task·block 은 setup 순번 → 실제 id)로 turn 을 바로 만든다.
 *   setup.blocks[].status, setup.blocks[].intervention(true 면 running 개입), setup.sql(그대로 실행할 SQL)
 */
@EnabledIfSystemProperty(named = "eval", matches = "true")
@SpringBootTest(properties = "assistant.worker.enabled=false")
@Import(EvalRunner.EvalClock.class)
class EvalRunner {

    static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @TestConfiguration
    static class EvalClock {
        @Bean
        @Primary
        FakeClock evalFakeClock() {
            return new FakeClock(LocalDateTime.parse("2026-11-02T09:00").atZone(KST).toInstant());
        }
    }

    @Autowired
    FakeClock clock;
    @Autowired
    AssistantDb db;
    @Autowired
    Tx tx;
    @Autowired
    SettingsService settings;
    @Autowired
    ChatService chat;
    @Autowired
    AgentWorker agent;
    @Autowired
    ConfiguredLlm llm;
    @Autowired
    InterventionService interventions;

    @Test
    @SuppressWarnings("unchecked")
    void runEvals() throws IOException {
        String key = System.getenv().getOrDefault("ANTHROPIC_API_KEY", "");
        if (key.isBlank()) {
            throw new IllegalStateException("ANTHROPIC_API_KEY 환경변수가 필요합니다");
        }
        AnthropicLlm judge = new AnthropicLlm(key, llm.model());
        String dirs = System.getProperty("eval.dirs", "v2,v3");
        List<Path> cases = new ArrayList<>();
        for (String dir : dirs.split(",")) {
            try (Stream<Path> files = Files.list(Path.of("evals", dir.trim()))) {
                cases.addAll(files.filter(p -> p.toString().endsWith(".yaml")).sorted().toList());
            }
        }

        StringBuilder report = new StringBuilder("# 평가 결과 (" + dirs + ")\n\n모델: `" + llm.model() + "` · 채점도 같은 모델\n\n");
        StringBuilder usage = new StringBuilder("""
                ## 토큰 사용량
                토큰 = 입력(캐시 안 됨) / 출력 / 캐시 읽기 / 캐시 쓰기. 비용은 Sonnet 5.5 표준 단가 추정
                (입력 $2, 출력 $10, 캐시 읽기 $0.20, 캐시 쓰기 $2.50 — 백만 토큰당).

                | 케이스 | turn | 도구 호출 | 비서 토큰 | 채점 토큰 | 추정 비용 |
                |---|---|---|---|---|---|
                """);
        long[] total = new long[4];
        long[] totalJudge = new long[4];
        for (Path file : cases) {
            Map<String, Object> spec = new Yaml().load(Files.readString(file, StandardCharsets.UTF_8));
            reset((String) spec.getOrDefault("now", "2026-11-02T09:00"));
            Ids ids = applySetup((Map<String, Object>) spec.get("setup"));

            Map<String, Object> trigger = (Map<String, Object>) spec.get("trigger");
            if (trigger == null) {
                say((String) spec.get("input"));
            } else {
                fire(trigger, ids);
            }
            runAgent();
            for (String text : (List<String>) spec.getOrDefault("inputs", List.of())) {
                say(text);
                runAgent();
            }

            String state = tx.run(this::stateSummary);
            long[] agentTokens = tx.run(() -> db.jdbc().sql("""
                            SELECT COALESCE(SUM(input_tokens),0), COALESCE(SUM(output_tokens),0),
                            COALESCE(SUM(cache_read_tokens),0), COALESCE(SUM(cache_write_tokens),0), COUNT(*),
                            COALESCE(SUM(tool_calls),0) FROM turns WHERE status <> 'queued'""")
                    .query((rs, n) -> new long[]{rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4),
                            rs.getLong(5), rs.getLong(6)}).single());
            List<String> criteria = (List<String>) spec.get("criteria");
            var judgeResponse = judge.start(
                            "너는 AI 비서의 판단을 채점하는 평가자다. 결과를 보고 각 조건을 통과(PASS)/실패(FAIL)로 판정하고 한 줄 이유를 쓴다. "
                                    + "형식: 조건마다 한 줄 '- PASS|FAIL: 조건 — 이유'. 마지막 줄에 '총평: ...'.",
                            Json.array(),
                            "## 상황\n" + describe(spec) + "\n\n## 결과\n" + state + "\n\n## 조건\n"
                                    + String.join("\n", criteria.stream().map(c -> "- " + c).toList()))
                    .next();
            String verdict = judgeResponse.text();
            long[] judgeTokens = {judgeResponse.inputTokens(), judgeResponse.outputTokens(),
                    judgeResponse.cacheReadTokens(), judgeResponse.cacheWriteTokens()};
            for (int i = 0; i < 4; i++) {
                total[i] += agentTokens[i];
                totalJudge[i] += judgeTokens[i];
            }
            usage.append("| ").append(spec.get("name")).append(" | ").append(agentTokens[4]).append(" | ")
                    .append(agentTokens[5]).append(" | ").append(tokens(agentTokens)).append(" | ")
                    .append(tokens(judgeTokens)).append(" | $").append(String.format("%.4f", cost(agentTokens) + cost(judgeTokens)))
                    .append(" |\n");

            report.append("## ").append(spec.get("name")).append(" (`").append(file.getFileName()).append("`)\n\n")
                    .append("**상황:** ").append(describe(spec).replace("\n", " / ")).append("\n\n")
                    .append("### 채점\n").append(verdict.trim()).append("\n\n")
                    .append("<details><summary>결과 상태</summary>\n\n```\n").append(state).append("```\n</details>\n\n");
        }
        usage.append("| **합계** | | | ").append(tokens(total)).append(" | ").append(tokens(totalJudge))
                .append(" | **$").append(String.format("%.4f", cost(total) + cost(totalJudge))).append("** |\n\n");
        report.insert(report.indexOf("\n\n") + 2, usage);
        Files.writeString(Path.of("evals", "report.md"), report.toString(), StandardCharsets.UTF_8);
        System.out.println("EVAL_TOKENS agent=" + tokens(total) + " judge=" + tokens(totalJudge)
                + " cost=$" + String.format("%.4f", cost(total) + cost(totalJudge)));
        System.out.println("evals/report.md 작성됨");
    }

    @SuppressWarnings("unchecked")
    private static String describe(Map<String, Object> spec) {
        StringBuilder sb = new StringBuilder();
        if (spec.get("situation") != null) {
            sb.append(spec.get("situation")).append('\n');
        }
        if (spec.get("trigger") != null) {
            sb.append("trigger: ").append(spec.get("trigger")).append('\n');
        }
        if (spec.get("input") != null) {
            sb.append("사용자: ").append(spec.get("input")).append('\n');
        }
        for (String text : (List<String>) spec.getOrDefault("inputs", List.of())) {
            sb.append("이어서 사용자: ").append(text).append('\n');
        }
        return sb.toString().trim();
    }

    private void say(String text) {
        ObjectNode req = Json.object();
        req.put("client_msg_id", java.util.UUID.randomUUID().toString());
        req.put("text", text);
        tx.run(() -> chat.receive(req));
    }

    private void runAgent() {
        while (agent.runOnce()) {
            // 대기열이 빌 때까지(확인 카드 등으로 이어지는 turn 포함)
        }
    }

    /** setup 순번 → 실제 id */
    record Ids(List<Long> goals, List<Long> tasks, List<Long> blocks, Map<Integer, Long> interventions) {
    }

    private void fire(Map<String, Object> trigger, Ids ids) {
        String type = (String) trigger.get("type");
        if ("snooze_request".equals(type)) {
            int block = ((Number) trigger.getOrDefault("block", 0)).intValue();
            ObjectNode req = Json.object();
            req.put("idempotency_key", java.util.UUID.randomUUID().toString());
            req.put("type", "snooze_request");
            req.put("intervention_id", ids.interventions().get(block));
            req.put("block_id", ids.blocks().get(block));
            tx.run(() -> interventions.action(req));
            return;
        }
        ObjectNode ref = Json.object();
        trigger.forEach((k, v) -> {
            switch (k) {
                case "type" -> {
                }
                case "task" -> ref.put("task_id", ids.tasks().get(((Number) v).intValue()));
                case "block" -> ref.put("block_id", ids.blocks().get(((Number) v).intValue()));
                default -> ref.put(k, String.valueOf(v));
            }
        });
        tx.run(() -> chat.enqueueTurn(type, ref, 5));
    }

    private static String tokens(long[] t) {
        return t[0] + " / " + t[1] + " / " + t[2] + " / " + t[3];
    }

    /** Sonnet 5.5 표준 단가(백만 토큰당): 입력 $2, 출력 $10, 캐시 읽기 $0.20, 캐시 쓰기(5분) $2.50. */
    private static double cost(long[] t) {
        return (t[0] * 2.0 + t[1] * 10.0 + t[2] * 0.20 + t[3] * 2.50) / 1_000_000;
    }

    private void reset(String now) {
        tx.exec(() -> {
            for (String table : new String[]{"changes", "turns", "messages", "confirmations", "questions",
                    "intervention_logs", "interventions", "jobs", "blocks", "work_products", "wakeups", "daily_flags",
                    "tasks", "goals", "events", "notes", "device_events", "devices", "processed_actions", "settings"}) {
                db.update("DELETE FROM " + table);
            }
            db.update("UPDATE memory SET content = '' WHERE id = 1");
            settings.seedDefaults();
        });
        clock.set(LocalDateTime.parse(now).atZone(KST).toInstant());
    }

    @SuppressWarnings("unchecked")
    private Ids applySetup(Map<String, Object> setup) {
        Ids ids = new Ids(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new java.util.HashMap<>());
        if (setup == null) {
            return ids;
        }
        tx.exec(() -> {
            List<Long> goalIds = ids.goals();
            for (Map<String, Object> g : (List<Map<String, Object>>) setup.getOrDefault("goals", List.of())) {
                goalIds.add(db.insert("INSERT INTO goals(title, deadline, reason, strategy) VALUES (?, ?, ?, ?)",
                        g.get("title"), time(g.get("deadline")), g.getOrDefault("reason", ""), g.getOrDefault("strategy", "")));
            }
            List<Long> taskIds = ids.tasks();
            for (Map<String, Object> t : (List<Map<String, Object>>) setup.getOrDefault("tasks", List.of())) {
                int est = ((Number) t.get("est_minutes")).intValue();
                Object goal = t.get("goal");
                taskIds.add(db.insert("""
                                INSERT INTO tasks(title, first_action, est_minutes, remaining_minutes, deadline, goal_id)
                                VALUES (?, ?, ?, ?, ?, ?)""",
                        t.get("title"), t.get("first_action"), est, est, time(t.get("deadline")),
                        goal == null ? null : goalIds.get(((Number) goal).intValue())));
            }
            for (Map<String, Object> e : (List<Map<String, Object>>) setup.getOrDefault("events", List.of())) {
                db.insert("INSERT INTO events(title, start_at, end_at) VALUES (?, ?, ?)",
                        e.get("title"), time(e.get("start")), time(e.get("end")));
            }
            List<Map<String, Object>> blocks = (List<Map<String, Object>>) setup.getOrDefault("blocks", List.of());
            for (int i = 0; i < blocks.size(); i++) {
                Map<String, Object> b = blocks.get(i);
                String status = (String) b.getOrDefault("status", "planned");
                long blockId = db.insert("""
                                INSERT INTO blocks(task_id, start_at, end_at, status, screen_text, actual_start)
                                VALUES (?, ?, ?, ?, '지금 할 건 하나예요.', ?)""",
                        taskIds.get(((Number) b.get("task")).intValue()), time(b.get("start")), time(b.get("end")),
                        status, "started".equals(status) ? time(b.get("start")) : null);
                ids.blocks().add(blockId);
                if (Boolean.TRUE.equals(b.get("intervention"))) {
                    db.update("UPDATE blocks SET status = 'nudging' WHERE id = ?", blockId);
                    ids.interventions().put(i, db.insert("""
                                    INSERT INTO interventions(block_id, status, level, block_version, started_at)
                                    VALUES (?, 'running', 3, 1, ?)""",
                            blockId, time(b.get("start"))));
                }
            }
            if (setup.get("memory") != null) {
                db.update("UPDATE memory SET content = ? WHERE id = 1", setup.get("memory"));
            }
            for (String sql : (List<String>) setup.getOrDefault("sql", List.of())) {
                db.update(sql);
            }
        });
        return ids;
    }

    private static java.time.Instant time(Object local) {
        return local == null ? null : LocalDateTime.parse(local.toString()).atZone(KST).toInstant();
    }

    private String stateSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("[목표]\n");
        db.jdbc().sql("SELECT id, title, deadline, status, reason, strategy FROM goals ORDER BY id").query((rs, n) -> {
            sb.append("목표#").append(rs.getLong(1)).append(' ').append(rs.getString(2)).append(" 마감=")
                    .append(rs.getObject(3)).append(" 상태=").append(rs.getString(4)).append(" 이유=").append(rs.getString(5))
                    .append("\n  전략: ").append(rs.getString(6)).append('\n');
            return null;
        }).list();
        sb.append("[할 일]\n");
        db.jdbc().sql("SELECT id, title, est_minutes, remaining_minutes, deadline, goal_id, status, first_action FROM tasks ORDER BY id")
                .query((rs, n) -> {
                    sb.append("할일#").append(rs.getLong(1)).append(' ').append(rs.getString(2)).append(" 예상=")
                            .append(rs.getInt(3)).append("분 남은=").append(rs.getInt(4)).append("분 마감=").append(rs.getObject(5))
                            .append(" 목표=").append(rs.getObject(6)).append(" 상태=").append(rs.getString(7))
                            .append(" 첫행동=").append(rs.getString(8)).append('\n');
                    return null;
                }).list();
        sb.append("[블록]\n");
        db.jdbc().sql("SELECT id, task_id, start_at, end_at, status, screen_text FROM blocks ORDER BY start_at").query((rs, n) -> {
            sb.append("블록#").append(rs.getLong(1)).append(" 할일#").append(rs.getLong(2)).append(' ')
                    .append(rs.getObject(3)).append(" ~ ").append(rs.getObject(4)).append(' ').append(rs.getString(5))
                    .append(" \"").append(rs.getString(6)).append("\"\n");
            return null;
        }).list();
        sb.append("[일정]\n");
        db.jdbc().sql("SELECT id, title, start_at, end_at, buffer_before_min, buffer_after_min, status FROM events ORDER BY id")
                .query((rs, n) -> {
                    sb.append("일정#").append(rs.getLong(1)).append(' ').append(rs.getString(2)).append(' ').append(rs.getObject(3))
                            .append(" ~ ").append(rs.getObject(4)).append(" 버퍼 앞").append(rs.getInt(5)).append(" 뒤")
                            .append(rs.getInt(6)).append(' ').append(rs.getString(7)).append('\n');
                    return null;
                }).list();
        sb.append("[메모]\n");
        db.jdbc().sql("SELECT id, content, status FROM notes ORDER BY id").query((rs, n) -> {
            sb.append("메모#").append(rs.getLong(1)).append(' ').append(rs.getString(2)).append(' ').append(rs.getString(3)).append('\n');
            return null;
        }).list();
        sb.append("[wakeup]\n");
        db.jdbc().sql("SELECT id, fire_at, reason, task_id, status FROM wakeups ORDER BY id").query((rs, n) -> {
            sb.append("wakeup#").append(rs.getLong(1)).append(' ').append(rs.getObject(2)).append(" 할일#")
                    .append(rs.getObject(4)).append(' ').append(rs.getString(5)).append(" \"").append(rs.getString(3)).append("\"\n");
            return null;
        }).list();
        sb.append("[작업물]\n");
        db.jdbc().sql("SELECT id, task_id, title, kind, content_md, sources FROM work_products ORDER BY id").query((rs, n) -> {
            sb.append("작업물#").append(rs.getLong(1)).append(" 할일#").append(rs.getLong(2)).append(' ').append(rs.getString(3))
                    .append(" (").append(rs.getString(4)).append(")\n  출처: ").append(rs.getString(6))
                    .append("\n  본문:\n").append(rs.getString(5)).append('\n');
            return null;
        }).list();
        db.jdbc().sql("SELECT id, work_product_id FROM blocks WHERE work_product_id IS NOT NULL").query((rs, n) -> {
            sb.append("블록#").append(rs.getLong(1)).append("에 작업물#").append(rs.getLong(2)).append(" 첨부\n");
            return null;
        }).list();
        sb.append("[개입]\n");
        db.jdbc().sql("SELECT id, block_id, status, end_reason FROM interventions ORDER BY id").query((rs, n) -> {
            sb.append("개입#").append(rs.getLong(1)).append(" 블록#").append(rs.getLong(2)).append(' ')
                    .append(rs.getString(3)).append(' ').append(rs.getString(4)).append('\n');
            return null;
        }).list();
        sb.append("[memory]\n").append(db.jdbc().sql("SELECT content FROM memory WHERE id = 1").query(String.class).single())
                .append('\n');
        sb.append("[대화]\n");
        db.jdbc().sql("SELECT role, kind, silent, content FROM messages ORDER BY id").query((rs, n) -> {
            sb.append(rs.getString(1)).append('(').append(rs.getString(2)).append(rs.getBoolean(3) ? ", 알림 없음" : "")
                    .append("): ").append(rs.getString(4)).append('\n');
            return null;
        }).list();
        db.jdbc().sql("SELECT q.text, q.options FROM questions q ORDER BY id").query((rs, n) -> {
            sb.append("질문 선택지: ").append(rs.getString(2)).append(" (").append(rs.getString(1)).append(")\n");
            return null;
        }).list();
        return sb.toString();
    }
}
