package com.seobin.phonedrop.assistant.agent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.seobin.phonedrop.assistant.AssistantClock;
import com.seobin.phonedrop.assistant.AssistantDb;
import com.seobin.phonedrop.assistant.GoalCoverage;
import com.seobin.phonedrop.assistant.Jobs;
import com.seobin.phonedrop.assistant.Store;
import com.seobin.phonedrop.assistant.Json;
import com.seobin.phonedrop.assistant.SettingsService;
import com.seobin.phonedrop.assistant.Tx;
import com.seobin.phonedrop.assistant.agent.llm.ConfiguredLlm;
import com.seobin.phonedrop.assistant.agent.llm.LlmClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * turn 실행 루프(빌드문서 v2 §8.2).
 * LLM 을 기다리는 동안에는 락을 잡지 않는다. 도구마다 별도 트랜잭션(Tx)이라 timing worker 의 개입이 밀리지 않는다.
 */
@Component
public class AgentRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentRunner.class);
    static final int MAX_LLM_RETRIES = 3;
    static final String OVER_LIMIT = "오늘 처리 한도를 넘었어요. 내일 아침에 이어서 볼게요.";
    static final String LLM_DOWN = "지금 처리가 안 돼요, 곧 다시 볼게요";

    private final Tx tx;
    private final AssistantDb db;
    private final AssistantClock clock;
    private final SettingsService settings;
    private final ContextBuilder context;
    private final Tools tools;
    private final ChatService chat;
    private final Jobs jobs;
    private final LlmClient llm;
    private final ProactiveService proactive;
    private final GoalCoverage coverage;
    private final Store store;
    private final String systemTemplate;

    public AgentRunner(Tx tx, AssistantDb db, AssistantClock clock, SettingsService settings, ContextBuilder context,
                       Tools tools, ChatService chat, Jobs jobs, LlmClient llm, ProactiveService proactive,
                       GoalCoverage coverage, Store store) {
        this.tx = tx;
        this.db = db;
        this.clock = clock;
        this.settings = settings;
        this.context = context;
        this.tools = tools;
        this.chat = chat;
        this.jobs = jobs;
        this.llm = llm;
        this.proactive = proactive;
        this.coverage = coverage;
        this.store = store;
        try (InputStream in = new ClassPathResource("assistant/prompts/system.md").getInputStream()) {
            this.systemTemplate = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("system.md 를 읽을 수 없습니다", e);
        }
    }

    /** agent worker 가 가져간 turn 작업 하나를 실행하고 작업 상태를 정한다. */
    public void runJob(Jobs.Job job) {
        long turnId = job.targetId();
        record Turn(String trigger, JsonNode ref) {
        }
        Turn turn = tx.run(() -> db.jdbc().sql("SELECT trigger, trigger_ref FROM turns WHERE id = ?").param(turnId)
                .query((rs, n) -> new Turn(rs.getString(1), Json.parse(rs.getString(2)))).optional().orElse(null));
        if (turn == null) {
            jobs.finish(job.id(), "skipped");
            return;
        }
        String outcome = runTurn(turnId, turn.trigger(), turn.ref(), job.attempts());
        switch (outcome) {
            case "retry" -> jobs.retryLater(job.id(), clock.now().plus(Duration.ofMinutes(1)), "LLM 호출 실패");
            case "failed" -> jobs.finish(job.id(), "failed");
            default -> jobs.finish(job.id(), "done");
        }
    }

    /** @return ok | partial | failed | retry | skipped */
    String runTurn(long turnId, String trigger, JsonNode ref, int attempts) {
        // 풀스크린 대화도 사용자가 직접 한 말이다(답이 있어야 하고, 한도 초과 안내도 한다).
        boolean fromUser = Permissions.fromUser(trigger);
        int hardLimit = settings.getInt("agent.daily_turn_hard");
        if (tx.run(chat::turnsToday) >= hardLimit) {
            tx.exec(() -> {
                if (fromUser) {
                    chat.sendAssistant(OVER_LIMIT, "reply", "normal", null, null);
                }
                endTurn(turnId, "skipped", 0, 0, 0, "daily_turn_hard");
            });
            return "skipped";
        }
        // v3 §2: pulse 를 실행하려는 순간 대기열에 다른 turn 이 있으면 pulse 는 건너뛴다.
        if ("pulse".equals(trigger) && tx.run(this::otherTurnsWaiting)) {
            tx.exec(() -> endTurn(turnId, "skipped", 0, 0, 0, "other turn queued"));
            return "skipped";
        }
        tx.exec(() -> db.update("UPDATE turns SET status = 'running', started_at = ? WHERE id = ?",
                clock.now(), turnId));

        String system = systemTemplate.replace("{persona}", settings.getString("persona"))
                .replace("{min_horizon_days}", String.valueOf(settings.getInt("goal.min_horizon_days")));
        String userContent = tx.run(() -> context.build(trigger, ref));
        // v3 §4.4: web.enabled=false 이거나 soft 상한을 넘으면 web_search 를 tools 에서 뺀다.
        boolean webSearch = settings.getBoolean("web.enabled") && !tx.run(proactive::overSoftLimit);
        Tools.Context ctx = new Tools.Context(turnId, trigger, false);
        if ("intervention_talk".equals(trigger) && ref.hasNonNull("intervention_id")) {
            ctx.interventionId = ref.get("intervention_id").asLong();
        }
        int maxCalls = settings.getInt("agent.max_tool_calls");
        int coverageRetries = settings.getInt("goal.coverage_retry");
        int coverageSent = 0;
        long inTokens = 0;
        long outTokens = 0;
        long[] cache = {0, 0}; // 캐시 읽기, 캐시 쓰기
        int toolCalls = 0;
        String lastText = "";
        String status = "partial";

        try {
            LlmClient.Conversation conv = llm.start(system, tools.definitions(), userContent, webSearch);
            for (int i = 0; i < maxCalls; i++) {
                LlmClient.LlmResponse resp = conv.next();
                inTokens += resp.inputTokens();
                outTokens += resp.outputTokens();
                cache[0] += resp.cacheReadTokens();
                cache[1] += resp.cacheWriteTokens();
                lastText = resp.text() == null ? "" : resp.text();
                if (resp.toolCalls().isEmpty()) {
                    // v3.1 §3.4-1: 이 turn 에서 손댄 목표에 블록이 없으면 turn 을 끝내지 않고 되돌려 보낸다.
                    String missing = coverageSent < coverageRetries ? tx.run(() -> missingMessage(ctx)) : null;
                    if (missing != null) {
                        coverageSent++;
                        conv.addUserText(missing);
                        continue;
                    }
                    status = "ok";
                    break;
                }
                List<LlmClient.ToolResult> results = new ArrayList<>();
                for (LlmClient.ToolCall call : resp.toolCalls()) {
                    results.add(runTool(ctx, call));
                    toolCalls++;
                }
                conv.addToolResults(results);
            }
        } catch (ConfiguredLlm.NotConfiguredException e) {
            log.warn("turn {} skipped: {}", turnId, e.getMessage());
            final int calls = toolCalls;
            tx.exec(() -> {
                if (fromUser) {
                    chat.sendAssistant("아직 AI 연결이 설정되지 않았어요(" + e.getMessage()
                            + "). 관리 화면에서 설정하면 바로 답할게요.", "reply", "normal", null, null);
                }
                endTurn(turnId, "failed", calls, 0, 0, e.getMessage());
            });
            return "failed";
        } catch (LlmClient.LlmException e) {
            log.warn("turn {} LLM failure (attempt {}): {}", turnId, attempts + 1, e.getMessage());
            final int calls = toolCalls;
            final long in = inTokens;
            final long out = outTokens;
            boolean retry = fromUser && attempts + 1 < MAX_LLM_RETRIES;
            tx.exec(() -> {
                if (fromUser && attempts == 0) {
                    chat.sendAssistant(LLM_DOWN, "reply", "normal", null, null);
                }
                endTurn(turnId, retry ? "queued" : "failed", calls, in, out, e.getMessage());
                recordCache(turnId, cache);
            });
            return retry ? "retry" : "failed";
        }

        final String finalText = lastText;
        final String finalStatus = status;
        final int calls = toolCalls;
        final long in = inTokens;
        final long out = outTokens;
        tx.exec(() -> {
            // 텍스트로만 답하고 send_message 를 안 했으면 그 텍스트를 reply 로 보낸다.
            // 풀스크린 대화(구 [미루기 요청] 포함)도 사용자가 말한 것이라 답이 있어야 한다.
            boolean replyExpected = fromUser || "snooze_request".equals(trigger);
            if (replyExpected && !ctx.sentReply && !finalText.isBlank()) {
                chat.sendAssistant(finalText.trim(), "reply", "normal", null, null, false, ctx.interventionId);
            }
            // 되돌려 보내기를 다 써도 누락이면 turn 은 끝내고 30분 뒤 goal_needs_blocks (§3.4-1).
            for (long goalId : ctx.touchedGoals) {
                store.goal(goalId).filter(g -> coverage.isMissing(g, clock.now()))
                        .ifPresent(g -> coverage.giveUpForNow(g.id(), turnId));
            }
            endTurn(turnId, finalStatus, calls, in, out, null);
            recordCache(turnId, cache);
        });
        return finalStatus;
    }

    /** 이 turn 에서 손댄 목표 중 누락 목표가 있으면 LLM 에게 돌려보낼 문장, 없으면 null. */
    private String missingMessage(Tools.Context ctx) {
        int days = settings.getInt("goal.min_horizon_days");
        StringBuilder sb = new StringBuilder();
        for (long goalId : ctx.touchedGoals) {
            Store.Goal g = store.goal(goalId).orElse(null);
            if (g != null && coverage.isMissing(g, clock.now())) {
                sb.append("목표 #").append(g.id()).append('(').append(g.label()).append(")에 ").append(days)
                        .append("일 안에 잡힌 블록이 없습니다. 블록을 배치하거나, 기다리는 중이면 waiting_until과 이유를 기록하세요.\n");
            }
        }
        return sb.isEmpty() ? null : "[시스템 검사] " + sb.toString().trim();
    }

    /** 도구마다 별도 트랜잭션. 예외는 그 도구 결과만 "내부 오류"로 돌려주고 루프는 계속한다. */
    private LlmClient.ToolResult runTool(Tools.Context ctx, LlmClient.ToolCall call) {
        try {
            ObjectNode result = tx.run(() -> tools.dispatch(ctx, call.name(), call.input()));
            boolean isError = !result.path("ok").asBoolean(false);
            return new LlmClient.ToolResult(call.id(), Json.write(result), isError);
        } catch (RuntimeException e) {
            log.error("tool {} failed in turn {}", call.name(), ctx.turnId, e);
            return new LlmClient.ToolResult(call.id(), Json.write(Tools.error("내부 오류")), true);
        }
    }

    private boolean otherTurnsWaiting() {
        return db.jdbc().sql("""
                        SELECT COUNT(*) FROM jobs WHERE type = 'turn' AND status = 'scheduled' AND fire_at <= ?""")
                .param(AssistantDb.ts(clock.now())).query(Integer.class).single() > 0;
    }

    private void recordCache(long turnId, long[] cache) {
        db.update("UPDATE turns SET cache_read_tokens = ?, cache_write_tokens = ? WHERE id = ?",
                (int) cache[0], (int) cache[1], turnId);
    }

    private void endTurn(long turnId, String status, int toolCalls, long in, long out, String error) {
        db.update("""
                        UPDATE turns SET status = ?, tool_calls = ?, input_tokens = ?, output_tokens = ?, ended_at = ?,
                        error = ? WHERE id = ?""",
                status, toolCalls, (int) in, (int) out, "queued".equals(status) ? null : clock.now(),
                error == null ? null : error.substring(0, Math.min(error.length(), 1900)), turnId);
    }
}
