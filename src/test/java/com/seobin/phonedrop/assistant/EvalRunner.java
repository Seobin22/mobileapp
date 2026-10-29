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
 * 평가 세트 실행기(빌드문서 v2 §12.3). 실제 Claude API 를 부르므로 돈이 든다. 평소 테스트에서는 건너뛴다.
 *   ANTHROPIC_API_KEY=... make eval     (= ./mvnw test -Deval=true -Dtest=EvalRunner)
 * evals/v2/*.yaml 마다 메모리 DB 를 비우고 setup 을 넣은 뒤 input 을 사용자 메시지로 보내고,
 * 결과(목표·할 일·블록·일정·메모·대화)를 다른 Claude 호출로 criteria 대로 채점해 evals/report.md 를 만든다.
 * 점수 기준은 없다. 사용자가 읽고 판단한다.
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

    @Test
    @SuppressWarnings("unchecked")
    void runEvals() throws IOException {
        String key = System.getenv().getOrDefault("ANTHROPIC_API_KEY", "");
        if (key.isBlank()) {
            throw new IllegalStateException("ANTHROPIC_API_KEY 환경변수가 필요합니다");
        }
        AnthropicLlm judge = new AnthropicLlm(key, llm.model());
        List<Path> cases;
        try (Stream<Path> files = Files.list(Path.of("evals", "v2"))) {
            cases = files.filter(p -> p.toString().endsWith(".yaml")).sorted().toList();
        }

        StringBuilder report = new StringBuilder("# 평가 결과 (v2)\n\n모델: `" + llm.model() + "` · 채점도 같은 모델\n\n");
        for (Path file : cases) {
            Map<String, Object> spec = new Yaml().load(Files.readString(file, StandardCharsets.UTF_8));
            reset((String) spec.getOrDefault("now", "2026-11-02T09:00"));
            applySetup((Map<String, Object>) spec.get("setup"));

            ObjectNode req = Json.object();
            req.put("client_msg_id", java.util.UUID.randomUUID().toString());
            req.put("text", (String) spec.get("input"));
            tx.run(() -> chat.receive(req));
            while (agent.runOnce()) {
                // 대기열이 빌 때까지(확인 카드 등으로 이어지는 turn 포함)
            }

            String state = tx.run(this::stateSummary);
            List<String> criteria = (List<String>) spec.get("criteria");
            String verdict = judge.start(
                            "너는 AI 비서의 판단을 채점하는 평가자다. 결과를 보고 각 조건을 통과(PASS)/실패(FAIL)로 판정하고 한 줄 이유를 쓴다. "
                                    + "형식: 조건마다 한 줄 '- PASS|FAIL: 조건 — 이유'. 마지막 줄에 '총평: ...'.",
                            Json.array(),
                            "## 사용자 입력\n" + spec.get("input") + "\n\n## 결과\n" + state + "\n\n## 조건\n"
                                    + String.join("\n", criteria.stream().map(c -> "- " + c).toList()))
                    .next().text();

            report.append("## ").append(spec.get("name")).append(" (`").append(file.getFileName()).append("`)\n\n")
                    .append("**입력:** ").append(spec.get("input")).append("\n\n")
                    .append("### 채점\n").append(verdict.trim()).append("\n\n")
                    .append("<details><summary>결과 상태</summary>\n\n```\n").append(state).append("```\n</details>\n\n");
        }
        Files.writeString(Path.of("evals", "report.md"), report.toString(), StandardCharsets.UTF_8);
        System.out.println("evals/report.md 작성됨");
    }

    private void reset(String now) {
        tx.exec(() -> {
            for (String table : new String[]{"changes", "turns", "messages", "confirmations", "questions",
                    "intervention_logs", "interventions", "jobs", "blocks", "tasks", "goals", "events", "notes",
                    "device_events", "devices", "processed_actions", "settings"}) {
                db.update("DELETE FROM " + table);
            }
            db.update("UPDATE memory SET content = '' WHERE id = 1");
            settings.seedDefaults();
        });
        clock.set(LocalDateTime.parse(now).atZone(KST).toInstant());
    }

    @SuppressWarnings("unchecked")
    private void applySetup(Map<String, Object> setup) {
        if (setup == null) {
            return;
        }
        tx.exec(() -> {
            List<Long> goalIds = new ArrayList<>();
            for (Map<String, Object> g : (List<Map<String, Object>>) setup.getOrDefault("goals", List.of())) {
                goalIds.add(db.insert("INSERT INTO goals(title, deadline) VALUES (?, ?)", g.get("title"), time(g.get("deadline"))));
            }
            List<Long> taskIds = new ArrayList<>();
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
            for (Map<String, Object> b : (List<Map<String, Object>>) setup.getOrDefault("blocks", List.of())) {
                db.insert("INSERT INTO blocks(task_id, start_at, end_at, screen_text) VALUES (?, ?, ?, '지금 할 건 하나예요.')",
                        taskIds.get(((Number) b.get("task")).intValue()), time(b.get("start")), time(b.get("end")));
            }
        });
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
        sb.append("[대화]\n");
        db.jdbc().sql("SELECT role, kind, content FROM messages ORDER BY id").query((rs, n) -> {
            sb.append(rs.getString(1)).append('(').append(rs.getString(2)).append("): ").append(rs.getString(3)).append('\n');
            return null;
        }).list();
        db.jdbc().sql("SELECT q.text, q.options FROM questions q ORDER BY id").query((rs, n) -> {
            sb.append("질문 선택지: ").append(rs.getString(2)).append(" (").append(rs.getString(1)).append(")\n");
            return null;
        }).list();
        return sb.toString();
    }
}
