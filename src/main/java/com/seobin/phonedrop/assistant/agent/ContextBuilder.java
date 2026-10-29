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

    public ContextBuilder(AssistantDb db, AssistantClock clock, Store store, TimeUtil time, SettingsService settings,
                          Timeline timeline) {
        this.db = db;
        this.clock = clock;
        this.store = store;
        this.time = time;
        this.settings = settings;
        this.timeline = timeline;
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
            sb.append("- 목표#").append(g.id()).append(' ').append(timeline.goalTitle(g))
                    .append(" (마감 ").append(g.deadline() == null ? "없음" : md(g.deadline())).append(')');
            if (!g.reason().isBlank()) {
                sb.append(" 이유: ").append(g.reason());
            }
            sb.append('\n');
            if (!g.strategy().isBlank()) {
                sb.append("  전략: ").append(g.strategy().replace("\n", "\n  ")).append('\n');
            }
            tasks.stream().filter(t -> Long.valueOf(g.id()).equals(t.goalId()))
                    .forEach(t -> sb.append("  할 일: ").append(taskLine(t)).append('\n'));
        }
        sb.append('\n');

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
            String title = store.task(b.taskId()).map(timeline::taskTitle).orElse("");
            sb.append("- 개입#").append(i.id()).append(" 블록#").append(b.id()).append(' ').append(title)
                    .append(" (").append(time.hm(i.startedAt())).append("부터 화면 표시 중)\n");
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
            default -> trigger + ": " + ref;
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
                    case "event" -> sb.append("- ").append(range).append(" 일정#").append(item.get("id")).append(' ')
                            .append(item.get("title")).append(" (버퍼 앞").append(item.get("buffer_before_min"))
                            .append(" 뒤").append(item.get("buffer_after_min"))
                            .append("calendar".equals(item.get("source")) ? ", 캘린더" : "").append(")\n");
                    case "block" -> sb.append("- ").append(range).append(" 블록#").append(item.get("id"))
                            .append(" [").append(item.get("status")).append("] 할일#").append(item.get("task_id"))
                            .append(' ').append(nz(item.get("title"))).append('\n');
                    case "quiet" -> sb.append("- ").append(range).append(" 방해금지\n");
                    case "meal" -> sb.append("- ").append(range).append(" 식사\n");
                    default -> {
                    }
                }
            }
            sb.append("- 빈 시간: ").append(free.isEmpty() ? "없음" : free).append('\n');
        }
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
