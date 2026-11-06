package com.seobin.phonedrop.assistant.agent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

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
import com.seobin.phonedrop.assistant.TurnQueue;
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
    /** 사용자에게 말하는 도구. 이것만 단독으로 부르면 turn 을 끝낸다. */
    static final Set<String> MESSAGE_TOOLS = Set.of("send_message", "ask_user");
    static final String STAGE_THINKING = "생각하는 중…";

    private final Tx tx;
    private final AssistantDb db;
    private final AssistantClock clock;
    private final SettingsService settings;
    private final ContextBuilder context;
    private final Tools tools;
    private final ChatService chat;
    private final Jobs jobs;
    private final LlmClient llm;
    /** v3.6 §3.5 음성 turn 에만 붙는 말투. */
    private final String voicePrompt;
    /** v4 §5.6 딴짓 기록(distraction·미루기·block_missed turn). */
    private final String distractionPrompt;
    static final java.util.Set<String> DISTRACTION_PROMPT_TRIGGERS =
            java.util.Set.of("distraction", "intervention_talk", "snooze_request", "block_missed");
    private final ProactiveService proactive;
    private final GoalCoverage coverage;
    private final Store store;
    private final GoalPrompts goalPrompts;
    private final DocumentService documents;
    private final String systemTemplate;
    private final com.seobin.phonedrop.assistant.insight.MemoryJobs memoryJobs;
    private final com.seobin.phonedrop.assistant.insight.MemoryContext memoryContext;
    private final com.seobin.phonedrop.assistant.insight.MemoryStore memoryStore;
    private final org.springframework.beans.factory.ObjectProvider<com.seobin.phonedrop.assistant.insight.Cards> cards;
    private final com.seobin.phonedrop.assistant.TimeUtil time;

    public AgentRunner(Tx tx, AssistantDb db, AssistantClock clock, SettingsService settings, ContextBuilder context,
                       Tools tools, ChatService chat, Jobs jobs, LlmClient llm, ProactiveService proactive,
                       GoalCoverage coverage, Store store, GoalPrompts goalPrompts, DocumentService documents,
                       com.seobin.phonedrop.assistant.insight.MemoryJobs memoryJobs,
                       com.seobin.phonedrop.assistant.insight.MemoryContext memoryContext,
                       com.seobin.phonedrop.assistant.insight.MemoryStore memoryStore,
                       org.springframework.beans.factory.ObjectProvider<com.seobin.phonedrop.assistant.insight.Cards> cards,
                       com.seobin.phonedrop.assistant.TimeUtil time) {
        this.memoryJobs = memoryJobs;
        this.memoryContext = memoryContext;
        this.memoryStore = memoryStore;
        this.cards = cards;
        this.time = time;
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
        this.goalPrompts = goalPrompts;
        this.documents = documents;
        try (InputStream in = new ClassPathResource("assistant/prompts/distraction.md").getInputStream()) {
            distractionPrompt = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("distraction.md 를 읽을 수 없습니다", e);
        }
        try (InputStream in = new ClassPathResource("assistant/prompts/voice.md").getInputStream()) {
            voicePrompt = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("voice.md 를 읽을 수 없습니다", e);
        }
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

    /** turn 하나의 집계(P0.2 turn 기록: 토큰·도구 이름·소요 시간). */
    private static final class Stats {
        final long started = System.nanoTime();
        long llmNanos;
        int rounds;
        long inTokens;
        long outTokens;
        final long[] cache = {0, 0}; // 캐시 읽기, 캐시 쓰기
        final List<String> toolNames = new ArrayList<>();

        int toolCalls() {
            return toolNames.size();
        }

        long elapsedMs() {
            return (System.nanoTime() - started) / 1_000_000;
        }
    }

    /** @return ok | partial | failed | retry | skipped */
    String runTurn(long turnId, String trigger, JsonNode ref, int attempts) {
        // v3.3 §4.3: 자료 처리는 비서 turn 이 아니라 코드 작업이다(LLM 은 읽기·요약에만, 하루 turn 수에 넣지 않는다).
        if (DocumentService.PROCESS_TRIGGER.equals(trigger)) {
            tx.exec(() -> db.update("UPDATE turns SET status = 'running', started_at = ? WHERE id = ?", clock.now(), turnId));
            setStage(turnId, "working", "자료 읽는 중…");
            documents.process(ref.path("document_id").asLong());
            tx.exec(() -> endTurn(turnId, "ok", new Stats(), null));
            return "ok";
        }
        // v3.5 §0-5: 백그라운드 작업(서술 추출·사실 뽑기·밤 정리·분류 추정)은 대화 turn 이 아니다. 하루 turn 수에 넣지 않는다.
        if (com.seobin.phonedrop.assistant.insight.MemoryJobs.TRIGGER.equals(trigger)) {
            tx.exec(() -> db.update("UPDATE turns SET status = 'running', started_at = ? WHERE id = ?", clock.now(), turnId));
            Stats s = new Stats();
            try {
                com.seobin.phonedrop.assistant.insight.MemoryJobs.Usage u = memoryJobs.run(ref);
                s.inTokens = u.inputTokens();
                s.outTokens = u.outputTokens();
                tx.exec(() -> endTurn(turnId, "ok", s, null));
                return "ok";
            } catch (RuntimeException e) {
                log.warn("memory job turn {} failed: {}", turnId, e.toString());
                tx.exec(() -> endTurn(turnId, "failed", s, e.toString()));
                return "failed";
            }
        }
        // 풀스크린 대화도 사용자가 직접 한 말이다(답이 있어야 하고, 한도 초과 안내도 한다).
        boolean fromUser = Permissions.fromUser(trigger);
        int hardLimit = settings.getInt("agent.daily_turn_hard");
        // v3.6 §3.7: 음성 turn 은 voice.daily_turn_cap 으로 따로 센다(VoiceService 가 받기 전에 막는다).
        if (!"voice".equals(trigger) && tx.run(chat::turnsToday) >= hardLimit) {
            tx.exec(() -> {
                if (fromUser) {
                    chat.sendAssistant(OVER_LIMIT, "reply", "normal", null, null);
                }
                endTurn(turnId, "skipped", new Stats(), "daily_turn_hard");
            });
            return "skipped";
        }
        // v3 §2: pulse 를 실행하려는 순간 대기열에 다른 turn 이 있으면 pulse 는 건너뛴다.
        if ("pulse".equals(trigger) && tx.run(this::otherTurnsWaiting)) {
            tx.exec(() -> endTurn(turnId, "skipped", new Stats(), "other turn queued"));
            return "skipped";
        }
        tx.exec(() -> db.update("UPDATE turns SET status = 'running', started_at = ? WHERE id = ?",
                clock.now(), turnId));
        setStage(turnId, "thinking", STAGE_THINKING);

        String system = systemTemplate.replace("{persona}", settings.getString("persona"))
                .replace("{min_horizon_days}", String.valueOf(settings.getInt("goal.min_horizon_days")))
                .replace("{profile}", tx.run(memoryContext::systemSection))
                + ("voice".equals(trigger) ? voicePrompt : "")
                + (DISTRACTION_PROMPT_TRIGGERS.contains(trigger) ? distractionPrompt : "");
        String userContent = tx.run(() -> context.build(trigger, ref));
        // v3 §4.4: web.enabled=false 이거나 soft 상한을 넘으면 web_search 를 tools 에서 뺀다.
        boolean webSearch = settings.getBoolean("web.enabled") && !tx.run(proactive::overSoftLimit);
        Tools.Context ctx = new Tools.Context(turnId, trigger, false);
        if ("intervention_talk".equals(trigger) && ref.hasNonNull("intervention_id")) {
            ctx.interventionId = ref.get("intervention_id").asLong();
        }
        if ("voice".equals(trigger)) {
            ctx.voiceSessionId = ref.path("session_id").asLong();
            ctx.userMessageId = ref.path("message_id").asLong();
        }
        int maxCalls = settings.getInt("agent.max_tool_calls");
        int coverageRetries = settings.getInt("goal.coverage_retry");
        int coverageSent = 0;
        Stats stats = new Stats();
        String lastText = "";
        String status = "partial";

        try {
            LlmClient.Conversation conv = "voice".equals(trigger) && llm instanceof ConfiguredLlm configured
                    ? configured.start(system, tools.definitions(), userContent, webSearch, voiceModel(configured))
                    : llm.start(system, tools.definitions(), userContent, webSearch);
            for (int i = 0; i < maxCalls; i++) {
                if (i > 0) {
                    setStage(turnId, "thinking", STAGE_THINKING);
                }
                long callStarted = System.nanoTime();
                LlmClient.LlmResponse resp = conv.next();
                stats.llmNanos += System.nanoTime() - callStarted;
                stats.rounds++;
                stats.inTokens += resp.inputTokens();
                stats.outTokens += resp.outputTokens();
                stats.cache[0] += resp.cacheReadTokens();
                stats.cache[1] += resp.cacheWriteTokens();
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
                setStage(turnId, "working", workingText(resp.toolCalls()));
                List<LlmClient.ToolResult> results = new ArrayList<>();
                boolean messaged = false;
                boolean onlyActions = true;
                boolean allOk = true;
                for (LlmClient.ToolCall call : resp.toolCalls()) {
                    LlmClient.ToolResult result = runTool(ctx, call);
                    results.add(result);
                    stats.toolNames.add(call.name());
                    messaged |= MESSAGE_TOOLS.contains(call.name());
                    onlyActions &= !call.name().startsWith("get_");
                    allOk &= !result.isError();
                }
                // v3.2 P0.1: 답장(send_message·ask_user)을 보낸 응답이고 모두 성공했으면 여기서 끝낸다.
                // 같은 응답의 기록·변경 도구도 이미 실행됐다. 조회 도구가 섞였으면 결과를 보고 이어가야 하므로 계속한다.
                if (messaged && onlyActions && allOk) {
                    String missing = coverageSent < coverageRetries ? tx.run(() -> missingMessage(ctx)) : null;
                    if (missing == null) {
                        status = "ok";
                        break;
                    }
                    // v3.1 목표 블록 검사: 누락이면 turn 을 끝내지 않고 되돌려 보낸다(P0.2).
                    coverageSent++;
                    conv.addToolResults(results);
                    conv.addUserText(missing);
                    continue;
                }
                conv.addToolResults(results);
            }
        } catch (ConfiguredLlm.NotConfiguredException e) {
            log.warn("turn {} skipped: {}", turnId, e.getMessage());
            tx.exec(() -> {
                if (fromUser) {
                    chat.sendAssistant("아직 AI 연결이 설정되지 않았어요(" + e.getMessage()
                            + "). 관리 화면에서 설정하면 바로 답할게요.", "reply", "normal", null, null);
                }
                endTurn(turnId, "failed", stats, e.getMessage());
            });
            return "failed";
        } catch (LlmClient.LlmException e) {
            log.warn("turn {} LLM failure (attempt {}): {}", turnId, attempts + 1, e.getMessage());
            boolean retry = fromUser && attempts + 1 < MAX_LLM_RETRIES;
            tx.exec(() -> {
                if (fromUser && attempts == 0) {
                    chat.sendAssistant(LLM_DOWN, "reply", "normal", null, null);
                }
                endTurn(turnId, retry ? "queued" : "failed", stats, e.getMessage());
            });
            return retry ? "retry" : "failed";
        }

        final String finalText = lastText;
        final String finalStatus = status;
        tx.exec(() -> finish(turnId, trigger, ctx, finalStatus, finalText, stats));
        return finalStatus;
    }

    /**
     * 정상 종료 공통 마무리(v3.2 P0.2). 답장으로 일찍 끝난 turn 도, 텍스트로 끝난 turn 도 모두 여기를 지난다.
     * 텍스트 답 보내기 → 목표 누락 정리 → '저장됨' 한 줄 → turn 기록(토큰·도구·시간) → 진행 상태 done.
     * (목표 누락 되돌려 보내기는 turn 을 끝내지 않는 경로라 루프 안에서 한다.)
     */
    private void finish(long turnId, String trigger, Tools.Context ctx, String status, String text, Stats stats) {
        boolean fromUser = Permissions.fromUser(trigger);
        // 텍스트로만 답하고 send_message 를 안 했으면 그 텍스트를 reply 로 보낸다.
        // 풀스크린 대화(구 [미루기 요청] 포함)도 사용자가 말한 것이라 답이 있어야 한다.
        boolean replyExpected = fromUser || "snooze_request".equals(trigger);
        boolean voice = ctx.voiceSessionId != null;
        if (replyExpected && !ctx.sentReply && !text.isBlank()) {
            long id = chat.sendAssistant(text.trim(), "reply", "normal", null, null, voice, ctx.interventionId);
            if (voice) {
                db.update("UPDATE messages SET channel = 'voice' WHERE id = ?", id);
            }
        }
        // 되돌려 보내기를 다 써도 누락이면 turn 은 끝내고 30분 뒤 goal_needs_blocks (§3.4-1).
        // 마감 지남·시간 없음·할 일 모두 끝남은 누락이 아니다: 코드가 한 번만 묻는다(GoalPrompts).
        for (long goalId : ctx.touchedGoals) {
            store.goal(goalId).ifPresent(g -> {
                if (coverage.isMissing(g, clock.now())) {
                    coverage.giveUpForNow(g.id(), turnId);
                }
                goalPrompts.sweep(g);
            });
        }
        if ("morning".equals(trigger)) {
            goalPrompts.markReminded();
            // v3.5 §4·§8-7: 아침 카드(잠·컨디션)를 브리핑 뒤에 붙이고, '확인 필요'는 한 번 물은 것으로 둔다.
            cards.getObject().sendDayCard(time.logicalDay(clock.now()));
            memoryStore.markChecksAsked(3);
        }
        if ("evening".equals(trigger) && memoryStore.enabled()) {
            memoryJobs.enqueue("memory_reflection", null); // §8 밤 정리(회고 turn 이 끝난 뒤 1번)
        }
        if (fromUser) {
            memoryJobs.onUserTurn(); // §7.3 사실 뽑기 주기
        }
        // v3.2 P1.2: 사용자 말로 시작된 turn 에서 마감·고정 일정이 바뀌었으면 코드가 '저장됨' 한 줄을 덧붙인다.
        if (fromUser && !ctx.savedLines.isEmpty()) {
            long id = chat.sendAssistant(String.join("\n", ctx.savedLines), "system", "normal", null, null, true,
                    ctx.interventionId, ctx.voiceUndoCard != null ? "undo_card:" + ctx.voiceUndoCard : null);
            if (voice) {
                db.update("UPDATE messages SET channel = 'voice' WHERE id = ?", id);
            }
        }
        endTurn(turnId, status, stats, null);
    }

    /** voice.model, 비면 Anthropic 이면 Haiku 계열, 다른 제공자면 지금 모델(그 게이트웨이에 Haiku 가 없을 수 있다). */
    private String voiceModel(ConfiguredLlm configured) {
        String m = settings.getString("voice.model");
        if (m != null && !m.isBlank()) {
            return m.strip();
        }
        return ConfiguredLlm.ANTHROPIC.equals(configured.provider())
                ? com.seobin.phonedrop.assistant.insight.MemoryJobs.DEFAULT_MODEL : null;
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

    /** 진행 상태(v3.2 P3.2). 문구는 코드가 정한다. 별도 트랜잭션이라 앱이 바로 볼 수 있다. */
    private void setStage(long turnId, String stage, String text) {
        tx.exec(() -> db.update("UPDATE turns SET stage = ?, stage_text = ?, updated_at = ? WHERE id = ?",
                stage, text, clock.now(), turnId));
    }

    /** 실행 중인 도구의 대표 문구. 여러 개면 일정 관련을 먼저 고른다. */
    public static String workingText(List<LlmClient.ToolCall> calls) {
        List<String> names = calls.stream().map(LlmClient.ToolCall::name).toList();
        if (names.stream().anyMatch(n -> n.equals("set_schedule") || n.equals("move_block"))) {
            return "일정 짜는 중…";
        }
        if (names.stream().anyMatch(n -> n.startsWith("upsert_fixed"))) {
            return "고정 일정 저장하는 중…";
        }
        if (names.contains("get_timeline")) {
            return "일정 확인하는 중…";
        }
        if (names.stream().anyMatch(n -> n.equals("upsert_task") || n.equals("upsert_goal"))) {
            return "할 일 정리하는 중…";
        }
        if (names.contains("add_block_log")) {
            return "기록하는 중…";
        }
        if (names.stream().allMatch(MESSAGE_TOOLS::contains)) {
            return "답장 쓰는 중…";
        }
        return "처리하는 중…";
    }

    private void endTurn(long turnId, String status, Stats stats, String error) {
        boolean queued = "queued".equals(status);
        String stage = queued ? "queued" : "failed".equals(status) ? "failed" : "done";
        String names = String.join(",", stats.toolNames);
        log.info("turn {} {}: rounds={} tools={} llm_ms={} total_ms={}", turnId, status, stats.rounds, names,
                stats.llmNanos / 1_000_000, stats.elapsedMs());
        db.update("""
                        UPDATE turns SET status = ?, tool_calls = ?, input_tokens = ?, output_tokens = ?, ended_at = ?,
                        error = ?, cache_read_tokens = ?, cache_write_tokens = ?, tool_names = ?, duration_ms = ?,
                        stage = ?, stage_text = ?, updated_at = ? WHERE id = ?""",
                status, stats.toolCalls(), (int) stats.inTokens, (int) stats.outTokens, queued ? null : clock.now(),
                error == null ? null : error.substring(0, Math.min(error.length(), 1900)),
                (int) stats.cache[0], (int) stats.cache[1],
                names.length() > 1900 ? names.substring(0, 1900) : names, (int) stats.elapsedMs(),
                stage, queued ? TurnQueue.STAGE_QUEUED_TEXT : null, clock.now(), turnId);
    }
}
