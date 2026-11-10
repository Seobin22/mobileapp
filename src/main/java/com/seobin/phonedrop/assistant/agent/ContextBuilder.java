package com.seobin.phonedrop.assistant.agent;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.seobin.phonedrop.assistant.AssistantClock;
import com.seobin.phonedrop.assistant.AssistantDb;
import com.seobin.phonedrop.assistant.BlockLogs;
import com.seobin.phonedrop.assistant.GoalCoverage;
import com.seobin.phonedrop.assistant.SettingsService;
import com.seobin.phonedrop.assistant.Store;
import com.seobin.phonedrop.assistant.TimeUtil;

import tools.jackson.databind.JsonNode;

/** turn 의 맥락 텍스트(빌드문서 v2 §8.3). 항목마다 id 를 붙인다. sensitive 는 제목을 가린다. */
@Component
public class ContextBuilder {

    private static final DateTimeFormatter MD = DateTimeFormatter.ofPattern("MM/dd");
    private static final DateTimeFormatter MD_HM = DateTimeFormatter.ofPattern("MM/dd HH:mm");

    private final AssistantDb db;
    private final AssistantClock clock;
    private final Store store;
    private final TimeUtil time;
    private final SettingsService settings;
    private final Timeline timeline;
    private final Facts facts;
    private final ChatService chat;
    private final GoalCoverage coverage;
    private final UserState userState;
    private final Deadlines deadlines;
    private final MemoService memos;
    private final BlockLogs logs;
    private final GoalPrompts goalPrompts;
    private final TravelService travel;
    private final DocumentService documents;
    private final com.seobin.phonedrop.assistant.insight.MemoryContext memoryContext;

    private final Replans replans;
    private TimeWindows timeWindows;
    private org.springframework.beans.factory.ObjectProvider<com.seobin.phonedrop.assistant.distract.DistractionService> distractions;

    @org.springframework.beans.factory.annotation.Autowired
    void setDistractions(org.springframework.beans.factory.ObjectProvider<com.seobin.phonedrop.assistant.distract.DistractionService> distractions) {
        this.distractions = distractions;
    }

    /** 딴짓 기록을 맥락에 넣는 turn. */
    static final class AgentRunnerTriggers {
        static final java.util.Set<String> DISTRACTION_CONTEXT =
                java.util.Set.of("distraction", "intervention_talk", "snooze_request", "block_missed");
    }

    @org.springframework.beans.factory.annotation.Autowired
    void setTimeWindows(TimeWindows timeWindows) {
        this.timeWindows = timeWindows;
    }

    public ContextBuilder(AssistantDb db, AssistantClock clock, Store store, TimeUtil time, SettingsService settings,
                          Timeline timeline, Facts facts, ChatService chat, GoalCoverage coverage, UserState userState,
                          Deadlines deadlines, MemoService memos, BlockLogs logs, GoalPrompts goalPrompts,
                          TravelService travel, DocumentService documents,
                          com.seobin.phonedrop.assistant.insight.MemoryContext memoryContext, Replans replans) {
        this.replans = replans;
        this.memoryContext = memoryContext;
        this.db = db;
        this.clock = clock;
        this.store = store;
        this.time = time;
        this.settings = settings;
        this.timeline = timeline;
        this.facts = facts;
        this.chat = chat;
        this.coverage = coverage;
        this.userState = userState;
        this.deadlines = deadlines;
        this.memos = memos;
        this.logs = logs;
        this.goalPrompts = goalPrompts;
        this.travel = travel;
        this.documents = documents;
    }

    public String build(String trigger, JsonNode ref) {
        Instant now = clock.now();
        StringBuilder sb = new StringBuilder();
        ZonedDateTime local = time.local(now);

        sb.append("# 지금\n")
                .append(local.toLocalDate()).append('(').append(dow(local)).append(") ")
                .append(local.format(DateTimeFormatter.ofPattern("HH:mm")))
                .append(", 시간대 ").append(time.zone().getId())
                .append(", 기상 ").append(settings.getString("life.wake"))
                .append(" 취침 ").append(settings.getString("life.sleep")).append("\n\n");

        sb.append("# 생활 패턴\n")
                .append("기상 ").append(settings.getString("life.wake"))
                .append(" / 취침 ").append(settings.getString("life.sleep"))

                .append(" / 집중 잘 되는 시간: ").append(windows(settings.get("life.focus_windows")))
                .append(" / 방해 금지: ").append(windows(settings.get("dnd.windows"))).append("\n\n");
        sb.append(timeWindowSection(now));

        sb.append("# 이번 실행 이유\n").append(reason(trigger, ref)).append("\n\n");

        // v3.2 P2.2·P1.2
        sb.append("# 지금 상태\n").append(userState.contextSection()).append('\n');
        sb.append("# 다가오는 마감(").append(Deadlines.CONTEXT_DAYS).append("일 안, 지난 미완료 포함)\n")
                .append(deadlines.contextSection()).append('\n');
        sb.append("# 답을 기다리는 목표 질문(코드가 버튼으로 물음)\n").append(goalPrompts.contextSection(trigger)).append('\n');
        appendMemos(sb, trigger, ref, now);
        // v4 §6: 미루기·block_missed·distraction turn 에는 이 블록의 오늘 딴짓(앱·분)을 넣는다(제목은 넣지 않는다).
        if (AgentRunnerTriggers.DISTRACTION_CONTEXT.contains(trigger)) {
            Long blockId = ref.hasNonNull("block_id") ? Long.valueOf(ref.get("block_id").asLong())
                    : ref.hasNonNull("intervention_id")
                    ? store.intervention(ref.get("intervention_id").asLong()).map(Store.Intervention::blockId).orElse(null) : null;
            com.seobin.phonedrop.assistant.distract.DistractionService ds = distractions == null ? null : distractions.getIfAvailable();
            String summary = blockId == null || ds == null ? "" : ds.blockSummary(blockId);
            if (!summary.isEmpty()) {
                sb.append("# 이 블록 딴짓 기록(사실, 블록#").append(blockId).append(")\n").append(summary).append('\n');
            }
        }
        // v3.3 §4.5: 자료는 요약·제목만(전문은 read_document)
        sb.append("# 사용자 자료\n")
                .append(documents.contextSection(trigger, goalOf(trigger, ref),
                        "document_ready".equals(trigger) ? ref.path("document_id").asLong() : null))
                .append('\n');
        appendBlockLogs(sb, ref);

        // v3.1 §3.6-2: 풀스크린 대화면 이 개입에서 오간 말
        if ("intervention_talk".equals(trigger)) {
            long interventionId = ref.path("intervention_id").asLong(-1);
            sb.append("# 이 시작 화면에서 오간 대화(개입#").append(interventionId).append(")\n");
            List<String> talk = db.jdbc().sql("""
                            SELECT role, content, created_at FROM messages WHERE intervention_id = ? ORDER BY id""")
                    .param(interventionId)
                    .query((rs, n) -> "[" + mdHm(AssistantDb.instant(rs, "created_at")) + " "
                            + ("user".equals(rs.getString("role")) ? "사용자" : "비서") + "] " + rs.getString("content"))
                    .list();
            sb.append(talk.isEmpty() ? "없음" : String.join("\n", talk)).append("\n\n");
        }

        // v3 §6.1: 이 할 일의 사실(get_facts)을 미리 넣는다.
        Long factTask = factTaskId(trigger, ref);
        if (factTask != null) {
            Map<String, Object> f = facts.of(factTask);
            if (f != null) {
                sb.append("# 사실(get_facts, 할일#").append(factTask).append(")\n")
                        .append(com.seobin.phonedrop.assistant.Json.write(
                                com.seobin.phonedrop.assistant.Json.MAPPER.valueToTree(f)))
                        .append("\n\n");
            }
        }

        int[] sent = chat.proactiveToday();
        sb.append("# 메시지 예산(오늘)\n")
                .append("먼저 보낸 메시지 ").append(sent[0]).append(" / ").append(settings.getInt("messages.daily_budget"))
                .append(", high 알림 ").append(sent[1]).append(" / ").append(settings.getInt("messages.high_per_day"))
                .append("\n\n");

        sb.append("# 예약된 wakeup(향후 24시간)\n");
        List<String> wakeups = db.jdbc().sql("""
                        SELECT id, fire_at, reason, task_id FROM wakeups WHERE status = 'scheduled' AND fire_at < ?
                        ORDER BY fire_at, id""")
                .param(AssistantDb.ts(now.plus(Duration.ofHours(24))))
                .query((rs, n) -> "- wakeup#" + rs.getLong("id") + " " + mdHm(AssistantDb.instant(rs, "fire_at"))
                        + (rs.getObject("task_id") == null ? "" : " 할일#" + rs.getLong("task_id"))
                        + " " + rs.getString("reason")).list();
        sb.append(wakeups.isEmpty() ? "없음" : String.join("\n", wakeups)).append("\n\n");

        sb.append("# 향후 7일 시간표\n");
        appendTimeline(sb, now, now.plus(Duration.ofDays(7)));
        sb.append('\n');

        // v3.3 §3.5: 이동 불가능 경고. 사용자 메시지 turn·아침 브리핑에서만, 같은 경고는 한 번만 알린다.
        if ("user_message".equals(trigger) || "morning".equals(trigger)) {
            List<TravelService.Warning> warnings = travel.unnoticedWarnings(now, now.plus(Duration.ofDays(7)));
            if (!warnings.isEmpty()) {
                sb.append("# 경고(사용자에게 알린다)\n");
                warnings.forEach(w -> sb.append("- 블록#").append(w.blockId()).append(' ').append(w.text()).append('\n'));
                sb.append('\n');
                travel.markNoticed(warnings);
            }
        }

        List<Store.Task> tasks = store.activeTasks();
        sb.append("# 목표\n");
        List<Store.Goal> goals = store.activeGoals();
        if (goals.isEmpty()) {
            sb.append("없음\n");
        }
        for (Store.Goal g : goals) {
            sb.append("- 목표#").append(g.id()).append(" [").append(g.label()).append("] ").append(timeline.goalTitle(g))
                    .append(" (마감 ").append(g.deadline() == null ? "없음" : md(g.deadline()))
                    .append(", 우선 ").append(g.priority()).append(')');
            if (!g.reason().isBlank()) {
                sb.append(" 이유: ").append(g.reason());
            }
            if (g.progress() != null) {
                sb.append(" 진행: ").append(g.progress());
            }
            if (g.waitingUntil() != null) {
                sb.append(" 대기 중(").append(mdHm(g.waitingUntil())).append(" 확인")
                        .append(g.waitingReason() == null ? "" : ", " + g.waitingReason()).append(')');
            }
            sb.append('\n');
            sb.append("  전략 요약: ").append(g.strategySummary().isBlank() ? "(없음 — strategy_summary 를 채우세요)"
                    : g.strategySummary()).append('\n');
            if (!g.strategy().isBlank()) {
                sb.append("  전략: ").append(g.strategy().replace("\n", "\n  ")).append('\n');
            }
            tasks.stream().filter(t -> Long.valueOf(g.id()).equals(t.goalId()))
                    .forEach(t -> sb.append("  할 일: ").append(taskLine(t)).append('\n'));
        }
        sb.append('\n');

        // v3.1 §3.4·§3.8: 블록이 없는 목표(누락). 판단 없이 "블록이 있는가"만 본 결과.
        sb.append("# 블록이 없는 목표(").append(settings.getInt("goal.min_horizon_days")).append("일 안)\n");
        List<Store.Goal> missing = coverage.missing();
        sb.append(missing.isEmpty() ? "없음" : String.join("\n", missing.stream()
                .map(g -> "- 목표#" + g.id() + " [" + g.label() + "] " + timeline.goalTitle(g)).toList())).append("\n\n");

        sb.append("# 목표 없는 할 일\n");
        List<Store.Task> loose = tasks.stream().filter(t -> t.goalId() == null).toList();
        if (loose.isEmpty()) {
            sb.append("없음\n");
        }
        loose.forEach(t -> sb.append("- ").append(taskLine(t)).append('\n'));
        sb.append('\n');

        // 다시 계획하다 빠진 할 일(블록이 취소됐고 앞으로 블록이 없음). 있을 때만 보인다.
        List<Replans.Dropped> dropped = replans.unresolved();
        if (!dropped.isEmpty()) {
            sb.append("# 계획에서 빠진 할 일(다시 배치하거나 사용자에게 묻는다. 사용자가 빼라고 했으면 그대로 둔다)\n");
            dropped.forEach(d -> sb.append("- 할일#").append(d.taskId()).append(' ').append(d.title()).append(" (원래 블록#")
                    .append(d.blockId()).append(' ').append(md(d.start())).append(' ').append(time.hm(d.start())).append(")\n"));
            sb.append('\n');
        }

        sb.append("# 메모(open)\n");
        List<String> notes = db.jdbc().sql("SELECT id, content FROM notes WHERE status = 'open' ORDER BY id")
                .query((rs, n) -> "- 메모#" + rs.getLong(1) + " " + rs.getString(2)).list();
        sb.append(notes.isEmpty() ? "없음" : String.join("\n", notes)).append("\n\n");

        sb.append("# 열린 질문 / 대기 중 확인 카드\n");
        List<String> open = new java.util.ArrayList<>(db.jdbc().sql(
                        "SELECT id, text, asked_at FROM questions WHERE status = 'open' ORDER BY id")
                .query((rs, n) -> "- 질문#" + rs.getLong(1) + " (" + mdHm(AssistantDb.instant(rs, "asked_at")) + ") \""
                        + rs.getString(2) + "\"").list());
        open.addAll(db.jdbc().sql("SELECT id, text FROM confirmations WHERE status = 'pending' ORDER BY id")
                .query((rs, n) -> "- 확인카드#" + rs.getLong(1) + " [pending] " + rs.getString(2)).list());
        sb.append(open.isEmpty() ? "없음" : String.join("\n", open)).append("\n\n");

        sb.append("# 부하 요약\n");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> groups = (List<Map<String, Object>>) timeline.loadSummary().get("groups");
        if (groups.isEmpty()) {
            sb.append("없음\n");
        } else {
            sb.append("| 그룹 | 마감 | 남은 분 | 마감까지 빈 분 | 상태 | 7일 진행 분 | 다음 블록 |\n|---|---|---|---|---|---|---|\n");
            for (Map<String, Object> g : groups) {
                sb.append("| ").append(g.get("goal_id") == null ? "" : "목표#" + g.get("goal_id") + " ")
                        .append(g.get("title")).append(" | ").append(nz(g.get("deadline")))
                        .append(" | ").append(g.get("remaining_minutes"))
                        .append(" | ").append(nz(g.get("free_minutes_until_deadline")))
                        .append(" | ").append(g.get("status"))
                        .append(" | ").append(g.get("progress_minutes_7d"))
                        .append(" | ").append(nz(g.get("next_block_start"))).append(" |\n");
            }
        }
        sb.append('\n');

        sb.append("# 진행 중 개입\n");
        List<Store.Intervention> running = store.runningInterventions();
        if (running.isEmpty()) {
            sb.append("없음\n");
        }
        for (Store.Intervention i : running) {
            Store.Block b = store.block(i.blockId()).orElseThrow();
            sb.append("- 개입#").append(i.id()).append(" 블록#").append(b.id())
                    .append(b.fixed() ? " [고정]" : "").append(b.taskId() == null ? "" : " 할일#" + b.taskId())
                    .append(' ').append(timeline.blockTitle(b))
                    .append(" (").append(time.hm(i.startedAt())).append("부터 화면 표시 중)");
            if ("talking".equals(i.status()) && i.talkUntil() != null) {
                long left = Math.max(0, Duration.between(now, i.talkUntil()).toMinutes());
                sb.append(" 시작 화면에서 대화 중, ").append(left).append("분 안에 답이 없으면 화면이 다시 뜸");
            }
            sb.append('\n');
        }
        sb.append('\n');

        // v3.5 §9: 프로필·대하는 법은 시스템 프롬프트(캐시). 여기에는 이 turn 에 맞는 패턴·기억만.
        sb.append(memoryContext.section(trigger, ref));

        int n = settings.getInt("agent.context_messages");
        sb.append("# 최근 대화 (최근 ").append(n).append("개)\n");
        List<String> recent = db.jdbc().sql("""
                        SELECT * FROM (SELECT id, role, kind, content, created_at, channel, interrupted, confirmation_id FROM messages WHERE held_block_id IS NULL AND held_window_id IS NULL
                        ORDER BY id DESC LIMIT ?)
                        ORDER BY id""")
                .param(n)
                .query((rs, i) -> "[" + mdHm(AssistantDb.instant(rs, "created_at")) + " "
                        + ("user".equals(rs.getString("role")) ? "사용자" : "비서")
                        + ("confirmation".equals(rs.getString("kind")) ? " 확인카드#" + rs.getLong("confirmation_id") : "")
                        + ("voice".equals(rs.getString("channel")) ? " 음성" : "")
                        + (rs.getBoolean("interrupted") ? " 끊김" : "")
                        + ("question".equals(rs.getString("kind")) ? " 질문" : "")
                        + "] " + rs.getString("content")).list();
        sb.append(recent.isEmpty() ? "없음" : String.join("\n", recent)).append('\n');
        return sb.toString();
    }

    /**
     * v3.6 §1.2·§2.2: 사용자가 정한 시간대(오늘·내일). 비서는 읽기만 한다.
     */
    private String timeWindowSection(Instant now) {
        java.time.LocalDate today = time.local(now).toLocalDate();
        Instant from = today.atStartOfDay(time.zone()).toInstant();
        Instant to = today.plusDays(2).atStartOfDay(time.zone()).toInstant();
        List<TimeWindows.Occ> occs = timeWindows.occurrences(from, to);
        if (occs.isEmpty() && timeWindows.list().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("# 시간대(사용자 설정. 비서는 바꿀 수 없다. 바꾸는 게 좋겠으면 제안만 하고 send_message 에 action=\"open_settings\" 를 붙여 [설정 열기] 버튼을 준다)\n");
        for (TimeWindows.Occ o : occs) {
            sb.append(timeWindows.contextLine(o)).append('\n');
        }
        if (timeWindows.dndAt(now).isPresent()) {
            sb.append("지금: 방해금지 시간대 — 먼저 보내는 말은 끝날 때 묶어서 간다. 계획 블록을 이 시간에 두지 않는다.\n");
        } else if (timeWindows.activeAt(now).isPresent()) {
            sb.append("지금: 적극 개입 시간대 — 보내는 말은 모두 소리로 읽힌다. 사람 비서처럼 지금 필요한 것(곧 시작할 일정·준비·출발·마감·")
                    .append("바뀐 계획 등)을 그때그때 짧게 말해도 된다(메시지 예산 없음). 단 지금 맥락에 맞는 말만: 이미 지난 일·지금 못 하는 일·")
                    .append("방금 한 말은 말하지 않는다. 글 목록·기호 없이 말하듯 1~2문장.\n");
        } else if (timeWindows.paused(now)) {
            sb.append("지금: 적극 개입 시간대지만 사용자가 [10분만 조용히]를 눌러 ")
                    .append(timeWindows.pausedUntil().map(time::hm).orElse("")).append("까지 기본 동작.\n");
        }
        return sb.append('\n').toString();
    }

    /** 다음(또는 진행 중) 고정 일정과 준비 시작 시각. 먼저 말 걸기가 맥락을 보게. */
    private String nextCommitment() {
        Instant now = clock.now();
        return store.activeFixedBlocksOverlapping(now, now.plus(java.time.Duration.ofHours(12))).stream()
                .filter(b -> !b.travel())
                .findFirst()
                .map(b -> "다음 고정 일정 '" + store.blockTitle(b) + "' " + time.hm(b.start()) + " 시작(준비 " + time.hm(b.blockedStart())
                        + "부터" + (now.isBefore(b.blockedStart()) ? ", " + java.time.Duration.between(now, b.blockedStart()).toMinutes() + "분 남음" : ", 이미 준비 시간") + ")")
                .orElse("오늘 남은 고정 일정 없음");
    }

    private String hmOf(String iso) {
        try {
            return time.hm(TimeUtil.parse(iso));
        } catch (RuntimeException e) {
            return iso;
        }
    }

    private String reason(String trigger, JsonNode ref) {
        return switch (trigger) {
            case "user_message" -> {
                String text = db.jdbc().sql("SELECT content FROM messages WHERE id = ?")
                        .param(ref.path("message_id").asLong()).query(String.class).optional().orElse("");
                yield "user_message: \"" + text + "\"";
            }
            case "voice" -> {
                String text = db.jdbc().sql("SELECT content FROM messages WHERE id = ?")
                        .param(ref.path("message_id").asLong()).query(String.class).optional().orElse("");
                yield "voice: 음성 대화에서 사용자가 말함(받아쓴 글이라 오타가 있을 수 있다) \"" + text + "\"";
            }
            case "confirmation_resolved" -> {
                long id = ref.path("confirmation_id").asLong();
                String text = db.jdbc().sql("SELECT text FROM confirmations WHERE id = ?").param(id)
                        .query(String.class).optional().orElse("");
                yield "confirmation_resolved: 확인카드#" + id + (ref.path("accepted").asBoolean() ? " 수락" : " 거절")
                        + " (" + text + ")";
            }
            case "snooze_request" -> "snooze_request: 사용자가 개입 화면에서 [미루기 요청]을 눌렀다 (개입#"
                    + ref.path("intervention_id").asLong() + ", 블록#" + ref.path("block_id").asLong() + ")";
            case "intervention_talk" -> {
                String text = db.jdbc().sql("SELECT content FROM messages WHERE id = ?")
                        .param(ref.path("message_id").asLong()).query(String.class).optional().orElse("");
                yield "intervention_talk: 사용자가 시작 화면(개입#" + ref.path("intervention_id").asLong() + ", 블록#"
                        + ref.path("block_id").asLong() + ")에서 말함: \"" + text + "\" (답은 TTS 로 읽힌다)";
            }
            case "goal_needs_blocks" -> "goal_needs_blocks: 목표#" + ref.path("goal_id").asLong()
                    + "에 앞으로 " + settings.getInt("goal.min_horizon_days") + "일 안에 블록이 없다. 이어서 배치하거나 waiting_until 을 기록";
            case "goal_waiting_check" -> "goal_waiting_check: 목표#" + ref.path("goal_id").asLong()
                    + "의 대기(waiting_until)가 끝났다. 사용자에게 결과를 묻고 계획을 이어간다";
            case "goal_changed_by_user" -> "goal_changed_by_user: 사용자가 목표 탭에서 목표#" + ref.path("goal_id").asLong()
                    + "을 직접 바꿈(" + ref.path("action").asString("") + (ref.hasNonNull("fields") ? " " + ref.get("fields").asString() : "")
                    + "). 시간표를 다시 정리하고 짧게 알린다";
            case "fixed_block_added" -> "fixed_block_added: 사용자가 앱에서 고정 블록#" + ref.path("block_id").asLong()
                    + "을 준비 시간 없이 넣었다. 장소·거리·준비물·일의 성격을 보고 lead_minutes·buffer_after_min 을 정하거나"
                    + " 앞에 준비용 계획 블록을 만든다(upsert_fixed_block 에 id 와 두 값만)."
                    + " 방해 허용 정도가 미정이면 set_interrupt_level 로 정한다";
            case "fixed_memo_changed" -> "fixed_memo_changed: 사용자가 곧 시작하는 고정 블록#" + ref.path("block_id").asLong()
                    + "의 메모를 바꿨다. 메모를 읽고 알릴 것·준비할 것이 있으면 처리한다(없으면 말하지 않는다)";
            case "fixed_changed" -> "fixed_changed: 고정 블록#" + ref.path("block_id").asLong()
                    + ("cancelled".equals(ref.path("change").asString("")) ? "이 삭제되어 연결 블록 " + ref.path("linked")
                    + "과 그 할 일을 코드가 취소했다. 사용자에게 짧게 알린다"
                    : "의 시각이 바뀌었다. 연결 블록 " + ref.path("linked") + "을 다시 배치하거나 알린다");
            case GoalPrompts.AFTER_DEADLINE_TRIGGER -> "goal_blocks_after_deadline: 목표#" + ref.path("goal_id").asLong()
                    + "의 계획 블록 " + ref.path("blocks") + "이 목표 마감 뒤에 끝난다. 마감 안으로 다시 배치한다(set_schedule)."
                    + " 마감 안에 넣을 수 없으면 사용자에게 알리기만 한다. 목표를 지우거나 마감을 바꾸지 않는다";
            case "block_result_by_user" -> "block_result_by_user: 사용자가 블록#" + ref.path("block_id").asLong()
                    + "의 결과를 직접 '" + ref.path("status").asString("") + "'(으)로 바꿨다(전: " + ref.path("before").asString("")
                    + "). 이미 반영된 사실이다. 할 일의 남은 시간·다음 블록을 맞추고, 필요할 때만 짧게 알린다";
            case "task_changed_by_user" -> "task_changed_by_user: 사용자가 앱에서 할일#" + ref.path("task_id").asLong()
                    + "을 직접 바꿈(" + ref.path("fields").asString("") + "). 이미 반영된 결정이니 시간표를 맞추고 짧게 알린다";
            case "blocks_displaced" -> "late_start".equals(ref.path("reason").asString(""))
                    ? "blocks_displaced: 블록#" + ref.path("block_id").asLong() + "을 늦게 시작해 겹친 계획 블록 "
                    + ref.path("displaced") + "이 취소됐다. 그 할 일을 다시 배치하고 짧게 알린다"
                    : "blocks_displaced: 사용자가 앱에서 고정 블록#" + ref.path("block_id").asLong()
                    + "을 넣어 계획 블록 " + ref.path("displaced") + "이 취소됐다. 재배치하고 알린다";
            case "distraction" -> "distraction: 블록#" + ref.path("block_id").asLong() + " 시간에 " + ref.path("app").asString("")
                    + " " + ref.path("minutes").asInt() + "분째 딴짓 중(코드가 감지, 개입 단계는 코드가 정함). 이 블록으로 돌아가라고 짧고 단호하게 한마디."
                    + " 비난·꼬리표 없이. send_message kind=proactive 1개";
            case "block_missed" -> "block_missed: 블록#" + ref.path("block_id").asLong() + "이 놓침(missed) 상태가 됨, 이유 "
                    + switch (ref.path("reason").asString("")) {
                        case "no_response" -> "no_response(화면에 무응답)";
                        case "quiet" -> "quiet(방해 금지·수면으로 중단)";
                        case "never_started" -> "never_started(시작되지 않고 끝 시각이 지남)";
                        case "fixed_block" -> "fixed_block(다른 고정 일정의 준비·이동이 시작되어 개입을 멈춤 — 사용자 잘못이 아니다. 고정 일정 뒤 빈 시간에 다시 잡는다)";
                        case "unreachable" -> "unreachable(기기 연결 끊김 — 무시한 것이 아니다)";
                        case "emergency" -> "emergency(사용자가 비상 해제를 눌렀다 — 급한 일이 있었을 수 있다. 다그치지 말고 다시 잡을지 묻는다)";
                        default -> ref.path("reason").asString("");
                    };
            case "block_started" -> ref.path("already").asBoolean(false)
                    ? "block_started: 사용자가 [이미 하고 있음]을 눌러 블록#" + ref.path("block_id").asLong()
                    + " 작업을 알림 전에 이미 시작했다고 알림(시작하라고 다시 권하지 말고, 체크인 예약 등 동행)"
                    : "block_started: 사용자가 [시작]을 눌러 블록#" + ref.path("block_id").asLong()
                    + " 작업을 시작함(체크인 예약 등 동행)";
            case "block_ended" -> "already_done".equals(ref.path("reason").asString(""))
                    ? "block_ended: 사용자가 알림 화면에서 [이미 완료함]을 눌렀다 — 블록#" + ref.path("block_id").asLong()
                    + "을 알림 전에 이미 끝냈다(done). 다시 시키지 말고, 할 일이 다 끝났는지 애매하면 짧게 묻고 다음 일을 챙긴다"
                    : "fixed_block".equals(ref.path("reason").asString(""))
                    ? "block_ended: 진행 중이던 블록#" + ref.path("block_id").asLong()
                    + "이 새 고정 블록의 준비 시간이 되어 부분 완료(partial)로 끝남"
                    : "block_ended: 진행 중이던 블록#" + ref.path("block_id").asLong() + "의 끝 시각이 됨";
            case "wakeup" -> "wakeup: 예약해 둔 wakeup#" + ref.path("wakeup_id").asLong()
                    + (ref.hasNonNull("task_id") ? " 할일#" + ref.path("task_id").asLong() : "")
                    + " — " + ref.path("reason").asString("");
            case "document_ready" -> {
                DocumentService.Document d = documents.find(ref.path("document_id").asLong()).orElse(null);
                yield "document_ready: 사용자가 올린 자료#" + ref.path("document_id").asLong()
                        + (d == null ? "" : " '" + d.filename() + "'" + (d.message() == null ? "" : ", 함께 남긴 말: \"" + d.message() + "\""))
                        + "를 읽었다(요약·추출은 '# 사용자 자료'). 목표가 없으면 어느 목표 자료인지 판단해 link_document(애매하면 ask_user)."
                        + " 추출된 마감·할 일은 자동 등록하지 말고 ask_user 로 등록할지 묻는다. 필요하면 목표 전략을 자료에 맞게 고치고 짧게 알린다."
                        + " 작업물은 만들지 않는다";
            }
            case "pulse" -> "pulse: 정기 점검(먼저 말 걸 가치가 있을 때만 send_message)";
            case "active_window" -> ("ack".equals(ref.path("reason").asString(""))
                    ? "active_window: 사용자가 '" + ref.path("name").asString("") + "' 적극 개입 시간대 알람을 껐다(~"
                    + hmOf(ref.path("until").asString("")) + "). 지금 상황에 맞는 것 한두 개만 짧게 브리핑한다."
                    : "active_window: '" + ref.path("name").asString("") + "' 적극 개입 시간대(~"
                    + hmOf(ref.path("until").asString("")) + ", 이번 회차 " + ref.path("count").asInt() + "번째 먼저 말 걸기). 아직 시작한 블록이 없다.")
                    + " 지금 시각·" + nextCommitment() + "·놓친 블록을 보고 '지금 실제로 할 수 있는 것'을 정한다:"
                    + " 곧(준비 포함) 고정 일정이 있으면 그 준비를 챙기고, 놓친 할 일은 그 앞 빈 시간이 충분할 때만 권하거나 그 뒤로 다시 잡는다."
                    + " 이미 시각이 지난 일·지금 못 하는 일을 하라고 하지 않는다. 늦게 일어났으면 그걸 감안한다."
                    + " 말할 가치가 없으면 send_message 없이 끝낸다. 말한다면 send_message kind=proactive 1개, 소리로 읽힐 수 있으니 1~2문장(앞서 한 말 반복 금지)";
            case "morning" -> "morning: 아침 브리핑(" + ref.path("logical_day").asString("") + ")";
            case "evening" -> "evening: 저녁 회고(" + ref.path("logical_day").asString("") + ")";
            default -> trigger + ": " + ref;
        };
    }

    /**
     * v3.2 §3.2: morning 은 앞으로 lookahead.days 의 고정 일정 전부(메모·고지), 고정 블록 관련 turn 은 그 블록 메모 전체,
     * 그 밖에는 48시간 안 메모가 있는 일정만.
     */
    private void appendMemos(StringBuilder sb, String trigger, JsonNode ref, Instant now) {
        if ("morning".equals(trigger)) {
            int days = settings.getInt("lookahead.days");
            sb.append("# 앞으로 ").append(days).append("일 고정 일정(메모·고지)\n");
            List<MemoService.Entry> list = memos.entries(now, now.plus(Duration.ofDays(days)), true);
            sb.append(list.isEmpty() ? "없음" : String.join("\n", list.stream().map(memos::line).toList())).append("\n\n");
            return;
        }
        Store.Block fixed = ref.hasNonNull("block_id") ? store.block(ref.get("block_id").asLong()).filter(Store.Block::fixed)
                .orElse(null) : null;
        if (fixed != null) {
            sb.append("# 이 고정 일정의 메모(블록#").append(fixed.id()).append(")\n")
                    .append(memos.line(new MemoService.Entry(fixed.id(), fixed.seriesId(), fixed.start(),
                            timeline.blockTitle(fixed), memos.fullMemo(fixed), logs.notices(fixed.id()))))
                    .append("\n\n");
            return;
        }
        sb.append("# 고정 일정 메모(48시간 안)\n");
        List<MemoService.Entry> list = memos.entries(now, now.plus(Duration.ofHours(48)), false);
        sb.append(list.isEmpty() ? "없음" : String.join("\n", list.stream().map(memos::line).toList())).append("\n\n");
    }

    /** v3.2 §4.3: 진행 중 개입 블록의 기록, 이번 turn 블록과 같은 목표의 직전 블록 3개 기록(블록당 최근 5줄). */
    /** 이번 turn 이 어느 목표에 관한 것인지(자료 요약을 넣을 목표). */
    private Long goalOf(String trigger, JsonNode ref) {
        if (ref.hasNonNull("goal_id")) {
            return ref.get("goal_id").asLong();
        }
        if ("document_ready".equals(trigger)) {
            return documents.find(ref.path("document_id").asLong()).map(DocumentService.Document::goalId).orElse(null);
        }
        if (ref.hasNonNull("block_id")) {
            return store.block(ref.get("block_id").asLong()).map(timeline::goalOf).map(Store.Goal::id).orElse(null);
        }
        if (ref.hasNonNull("task_id")) {
            return store.task(ref.get("task_id").asLong()).map(Store.Task::goalId).orElse(null);
        }
        return null;
    }

    private void appendBlockLogs(StringBuilder sb, JsonNode ref) {
        List<String> lines = new java.util.ArrayList<>();
        for (Store.Intervention i : store.runningInterventions()) {
            lines.add("블록#" + i.blockId() + "(진행 중 개입):");
            logs.ofBlock(i.blockId(), 10).forEach(l -> lines.add("  " + logLine(l)));
        }
        Store.Block b = ref.hasNonNull("block_id") ? store.block(ref.get("block_id").asLong()).orElse(null) : null;
        Store.Goal g = b == null ? null : timeline.goalOf(b);
        if (g != null) {
            List<Store.Block> prev = db.jdbc().sql("""
                            SELECT b.* FROM blocks b LEFT JOIN tasks t ON t.id = b.task_id
                            WHERE (b.goal_id = ? OR t.goal_id = ?) AND b.id <> ? AND b.start_at < ? AND b.status <> 'cancelled'
                            ORDER BY b.start_at DESC LIMIT 3""")
                    .param(g.id()).param(g.id()).param(b.id()).param(AssistantDb.ts(b.start())).query(Store::block).list();
            for (Store.Block p : prev) {
                lines.add("블록#" + p.id() + " " + time.dayText(p.start()) + " " + timeline.blockTitle(p) + " (" + p.status() + "):");
                logs.ofBlock(p.id(), 5).forEach(l -> lines.add("  " + logLine(l)));
            }
        }
        if (!lines.isEmpty()) {
            sb.append("# 블록 기록\n").append(String.join("\n", lines)).append("\n\n");
        }
    }

    private String logLine(BlockLogs.Log l) {
        return mdHm(l.at()) + " [" + l.type() + "/" + l.actor() + "] " + l.content();
    }

    /** snooze_request·block_missed·block_ended·wakeup(task_id 있음)이면 그 할 일. */
    private Long factTaskId(String trigger, JsonNode ref) {
        return switch (trigger) {
            case "snooze_request", "block_missed", "block_ended", "block_started", "intervention_talk" ->
                    store.block(ref.path("block_id").asLong(-1)).map(Store.Block::taskId).orElse(null);
            case "wakeup" -> ref.hasNonNull("task_id") ? ref.get("task_id").asLong() : null;
            default -> null;
        };
    }

    private void appendTimeline(StringBuilder sb, Instant from, Instant to) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> days = (List<Map<String, Object>>) timeline.timeline(from, to, true).get("days");
        for (Map<String, Object> day : days) {
            java.time.LocalDate date = java.time.LocalDate.parse((String) day.get("date"));
            sb.append(date.format(MD)).append('(').append(date.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.KOREAN))
                    .append(")\n");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) day.get("items");
            StringBuilder free = new StringBuilder();
            for (Map<String, Object> item : items) {
                String range = hm(item.get("start")) + "–" + hm(item.get("end"));
                switch ((String) item.get("kind")) {
                    case "free" -> free.append(free.isEmpty() ? "" : ", ").append(range);
                    case "fixed_block" -> {
                        int lead = ((Number) item.get("lead_minutes")).intValue();
                        int after = ((Number) item.get("buffer_after_min")).intValue();
                        sb.append("- ").append(range).append(' ').append(fixedTag(item)).append(' ')
                                .append(nz(item.get("title")))
                                .append(item.get("place_name") == null ? "" : "(" + item.get("place_name") + ")")
                                .append(" 블록#").append(item.get("id"))
                                .append(lead > 0 ? " · 준비 " + lead + "분" : "")
                                .append(after > 0 ? " (끝난 뒤 " + after + "분 이동)" : "")
                                .append(goalTag(item))
                                .append(item.get("task_id") == null ? "" : " (할일#" + item.get("task_id") + ")")
                                .append(item.get("location") == null ? "" : " @" + item.get("location"))
                                .append(item.get("prep_set_by") == null ? " (준비 시간 미정)"
                                        : "user".equals(item.get("prep_set_by")) ? " (준비 시간 사용자가 정함)" : "")
                                .append("calendar".equals(item.get("source")) ? " (캘린더)" : "")
                                .append(item.get("series_id") == null ? "" : " (반복 일정#" + item.get("series_id") + ")")
                                .append(" ").append(item.get("status"))
                                .append(store.block(((Number) item.get("id")).longValue()).map(travel::contextText).orElse(""))
                                .append('\n');
                    }
                    case "planned_block" -> {
                        if ("travel".equals(item.get("link_role"))) {
                            // v3.4 §7.1: 시스템이 관리하는 이동 블록
                            sb.append("- ").append(range).append("home".equals(item.get("travel_direction")) ? " [귀가] " : " [이동] ")
                                    .append(String.valueOf(item.get("title")).replaceFirst("^(이동|귀가): ", ""))
                                    .append(item.get("travel_tight_min") == null ? "" : " · " + item.get("travel_tight_min") + "분 부족")
                                    .append(item.get("travel_late_min") == null ? "" : " · 지각 예상 " + item.get("travel_late_min") + "분(출발 미룸)")
                                    .append(" 블록#").append(item.get("id"))
                                    .append(" (시스템 관리)\n");
                            continue;
                        }
                        sb.append("- ").append(range)
                            .append(String.valueOf(item.get("goal_label")).isEmpty() ? "" : " [" + item.get("goal_label") + "]")
                            .append(" 블록#").append(item.get("id")).append(' ').append(nz(item.get("title")))
                            .append(" (할일#").append(item.get("task_id")).append(") ").append(item.get("status"))
                            .append(item.get("work_product_id") == null ? "" : " (작업물#" + item.get("work_product_id") + " 첨부)")
                            .append(item.get("linked_fixed_block_id") == null ? ""
                                    : " (→ 고정 블록#" + item.get("linked_fixed_block_id") + " 준비)")
                            .append('\n');
                    }
                    case "quiet" -> sb.append("- ").append(range).append(" 방해금지\n");

                    default -> {
                    }
                }
            }
            sb.append("- 빈 시간: ").append(free.isEmpty() ? "없음" : free).append('\n');
        }
    }

    /** v3.3 §1.4: [고정·방해금지] / [고정·조용히] / [고정·알림허용], 아직 안 정했으면 [고정](조용히로 동작). */
    private static String fixedTag(Map<String, Object> item) {
        Object level = item.get("interrupt_level");
        if (level == null) {
            return "[고정]";
        }
        String tag = switch (String.valueOf(level)) {
            case "dnd" -> "방해금지";
            case "allow" -> "알림허용";
            default -> "조용히";
        };
        return "[고정·" + tag + ("user".equals(item.get("interrupt_set_by")) ? "(사용자)" : "") + "]";
    }

    private static String goalTag(Map<String, Object> item) {
        Object label = item.get("goal_label");
        return label == null || String.valueOf(label).isEmpty() ? "" : " [" + label + "]";
    }

    /** timeline 의 ISO(+09:00 등) → Instant.parse 가 읽는 형태. */
    private static String isoUtc(Object iso) {
        return java.time.OffsetDateTime.parse(String.valueOf(iso)).toInstant().toString();
    }

    private String hm(java.time.Instant t) {
        return time.hm(t);
    }

    private String taskLine(Store.Task t) {
        return "할일#" + t.id() + " " + timeline.taskTitle(t) + " (남은 " + t.remainingMinutes() + "분, 마감 "
                + (t.deadline() == null ? "없음" : mdHm(t.deadline())) + ", 우선 " + t.priority()
                + ", 첫 행동: " + t.firstAction() + ")" + (t.notes().isBlank() ? "" : " 메모: " + t.notes());
    }

    private static String windows(JsonNode list) {
        if (!list.isArray() || list.isEmpty()) {
            return "없음";
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode w : list) {
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            if (w.has("days")) {
                sb.append(days(w.path("days"))).append(' ');
            }
            sb.append(w.path("start").asString()).append('–').append(w.path("end").asString());
        }
        return sb.toString();
    }

    private static String days(JsonNode days) {
        String names = "월화수목금토일";
        StringBuilder sb = new StringBuilder();
        for (JsonNode d : days) {
            int i = d.asInt();
            if (i >= 1 && i <= 7) {
                sb.append(names.charAt(i - 1));
            }
        }
        return "12345".equals(daysKey(days)) ? "평일" : sb.toString();
    }

    private static String daysKey(JsonNode days) {
        StringBuilder sb = new StringBuilder();
        days.forEach(d -> sb.append(d.asInt()));
        return sb.toString();
    }

    private static String dow(ZonedDateTime t) {
        return t.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.KOREAN);
    }

    private String md(Instant t) {
        return time.local(t).format(MD);
    }

    private String mdHm(Instant t) {
        return t == null ? "" : time.local(t).format(MD_HM);
    }

    private static String hm(Object iso) {
        String s = String.valueOf(iso);
        return s.length() >= 16 ? s.substring(11, 16) : s;
    }

    private static Object nz(Object v) {
        return v == null ? "-" : v;
    }
}
