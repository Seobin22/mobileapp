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

    public ContextBuilder(AssistantDb db, AssistantClock clock, Store store, TimeUtil time, SettingsService settings,
                          Timeline timeline, Facts facts, ChatService chat, GoalCoverage coverage) {
        this.db = db;
        this.clock = clock;
        this.store = store;
        this.time = time;
        this.settings = settings;
        this.timeline = timeline;
        this.facts = facts;
        this.chat = chat;
        this.coverage = coverage;
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
                .append(" / 식사 ").append(windows(settings.get("life.meals")))
                .append(" / 집중 잘 되는 시간: ").append(windows(settings.get("life.focus_windows")))
                .append(" / 방해 금지: ").append(windows(settings.get("dnd.windows"))).append("\n\n");

        sb.append("# 이번 실행 이유\n").append(reason(trigger, ref)).append("\n\n");

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

        sb.append("# memory\n");
        String memory = db.jdbc().sql("SELECT content FROM memory WHERE id = 1").query(String.class).optional().orElse("");
        sb.append(memory.isBlank() ? "(비어 있음)" : memory).append("\n\n");

        int n = settings.getInt("agent.context_messages");
        sb.append("# 최근 대화 (최근 ").append(n).append("개)\n");
        List<String> recent = db.jdbc().sql("""
                        SELECT * FROM (SELECT id, role, kind, content, created_at FROM messages ORDER BY id DESC LIMIT ?)
                        ORDER BY id""")
                .param(n)
                .query((rs, i) -> "[" + mdHm(AssistantDb.instant(rs, "created_at")) + " "
                        + ("user".equals(rs.getString("role")) ? "사용자" : "비서")
                        + ("confirmation".equals(rs.getString("kind")) ? " 확인카드" : "")
                        + ("question".equals(rs.getString("kind")) ? " 질문" : "")
                        + "] " + rs.getString("content")).list();
        sb.append(recent.isEmpty() ? "없음" : String.join("\n", recent)).append('\n');
        return sb.toString();
    }

    private String reason(String trigger, JsonNode ref) {
        return switch (trigger) {
            case "user_message" -> {
                String text = db.jdbc().sql("SELECT content FROM messages WHERE id = ?")
                        .param(ref.path("message_id").asLong()).query(String.class).optional().orElse("");
                yield "user_message: \"" + text + "\"";
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
            case "blocks_displaced" -> "blocks_displaced: 사용자가 앱에서 고정 블록#" + ref.path("block_id").asLong()
                    + "을 넣어 계획 블록 " + ref.path("displaced") + "이 취소됐다. 재배치하고 알린다";
            case "block_missed" -> "block_missed: 블록#" + ref.path("block_id").asLong() + "이 놓침(missed) 상태가 됨, 이유 "
                    + switch (ref.path("reason").asString("")) {
                        case "no_response" -> "no_response(화면에 무응답)";
                        case "quiet" -> "quiet(방해 금지·수면으로 중단)";
                        case "never_started" -> "never_started(시작되지 않고 끝 시각이 지남)";
                        default -> ref.path("reason").asString("");
                    };
            case "block_started" -> "block_started: 사용자가 [시작]을 눌러 블록#" + ref.path("block_id").asLong()
                    + " 작업을 시작함(체크인 예약 등 동행)";
            case "block_ended" -> "fixed_block".equals(ref.path("reason").asString(""))
                    ? "block_ended: 진행 중이던 블록#" + ref.path("block_id").asLong()
                    + "이 새 고정 블록의 준비 시간이 되어 부분 완료(partial)로 끝남"
                    : "block_ended: 진행 중이던 블록#" + ref.path("block_id").asLong() + "의 끝 시각이 됨";
            case "wakeup" -> "wakeup: 예약해 둔 wakeup#" + ref.path("wakeup_id").asLong()
                    + (ref.hasNonNull("task_id") ? " 할일#" + ref.path("task_id").asLong() : "")
                    + " — " + ref.path("reason").asString("");
            case "pulse" -> "pulse: 정기 점검(먼저 말 걸 가치가 있을 때만 send_message)";
            case "morning" -> "morning: 아침 브리핑(" + ref.path("logical_day").asString("") + ")";
            case "evening" -> "evening: 저녁 회고(" + ref.path("logical_day").asString("") + ")";
            default -> trigger + ": " + ref;
        };
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
                        sb.append("- ").append(range).append(" [고정] ").append(nz(item.get("title")))
                                .append(" 블록#").append(item.get("id"))
                                .append(lead > 0 ? " (출발 " + hm(java.time.Instant.parse(isoUtc(item.get("start")))
                                        .minusSeconds(lead * 60L)) + ")" : "")
                                .append(after > 0 ? " (끝난 뒤 " + after + "분 이동)" : "")
                                .append(goalTag(item))
                                .append(item.get("task_id") == null ? "" : " (할일#" + item.get("task_id") + ")")
                                .append(item.get("location") == null ? "" : " @" + item.get("location"))
                                .append("calendar".equals(item.get("source")) ? " (캘린더)" : "")
                                .append(" ").append(item.get("status")).append('\n');
                    }
                    case "planned_block" -> sb.append("- ").append(range)
                            .append(String.valueOf(item.get("goal_label")).isEmpty() ? "" : " [" + item.get("goal_label") + "]")
                            .append(" 블록#").append(item.get("id")).append(' ').append(nz(item.get("title")))
                            .append(" (할일#").append(item.get("task_id")).append(") ").append(item.get("status"))
                            .append(item.get("work_product_id") == null ? "" : " (작업물#" + item.get("work_product_id") + " 첨부)")
                            .append('\n');
                    case "quiet" -> sb.append("- ").append(range).append(" 방해금지\n");
                    case "meal" -> sb.append("- ").append(range).append(" 식사\n");
                    default -> {
                    }
                }
            }
            sb.append("- 빈 시간: ").append(free.isEmpty() ? "없음" : free).append('\n');
        }
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
