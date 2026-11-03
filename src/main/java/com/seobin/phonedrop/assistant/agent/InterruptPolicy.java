package com.seobin.phonedrop.assistant.agent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.seobin.phonedrop.assistant.AssistantClock;
import com.seobin.phonedrop.assistant.AssistantDb;
import com.seobin.phonedrop.assistant.Jobs;
import com.seobin.phonedrop.assistant.Store;
import com.seobin.phonedrop.assistant.TimeUtil;

/**
 * 고정 일정의 방해 허용 정도(빌드문서 v3.3 §1.2). 판단(단계 정하기)은 LLM, 강제는 코드.
 * 진행 중 = 그 고정 블록의 금지 구간 [start − lead, end + buffer_after].
 * - dnd: 비서가 먼저 보내는 메시지를 보내지 않고 모아 두었다가 일정이 끝나면 묶음 메시지 1개로 보낸다.
 * - quiet: 무음(채팅에만 쌓임, v3 동작).
 * - allow: 일반 알림(메시지 예산 규칙은 그대로).
 * 사용자 말(user_message·풀스크린 대화)에 대한 답은 어느 단계에서든 보낸다.
 */
@Component
public class InterruptPolicy {

    public static final String RELEASE_JOB = "dnd_release";
    public static final List<String> LEVELS = List.of("dnd", "quiet", "allow");

    public enum Mode { SEND, SILENT, HOLD }

    /** HOLD 면 heldBlockId 에 모아 둔다. */
    public record Decision(Mode mode, Long heldBlockId) {

        public boolean silent() {
            return mode == Mode.SILENT;
        }
    }

    private final AssistantDb db;
    private final AssistantClock clock;
    private final Store store;
    private final TimeUtil time;
    private final Jobs jobs;
    private final ChatService chat;

    public InterruptPolicy(AssistantDb db, AssistantClock clock, Store store, TimeUtil time, Jobs jobs,
                           ChatService chat) {
        this.db = db;
        this.clock = clock;
        this.store = store;
        this.time = time;
        this.jobs = jobs;
        this.chat = chat;
        jobs.register(RELEASE_JOB, this::onRelease);
    }

    /** 결정대로 비서 메시지를 보낸다(HOLD 면 저장만 하고 알리지 않는다). */
    public long send(Decision d, String text, String kind, String importance, Long questionId, Long confirmationId) {
        if (d.mode() == Mode.HOLD) {
            return chat.holdAssistant(text, kind, importance, questionId, confirmationId, d.heldBlockId());
        }
        return chat.sendAssistant(text, kind, importance, questionId, confirmationId, d.silent());
    }

    /** 사용자에게 한 답이 아닌 비서 메시지(먼저 말 걸기·질문·확인 카드)가 지금 어떻게 나가야 하나. */
    public Decision proactive(String trigger) {
        Instant now = clock.now();
        if (trigger != null && Permissions.fromUser(trigger)) {
            return new Decision(Mode.SEND, null);
        }
        Optional<Store.Block> dnd = store.dndInProgress(now, null);
        if (dnd.isPresent()) {
            scheduleRelease(dnd.get());
            return new Decision(Mode.HOLD, dnd.get().id());
        }
        if (time.isQuiet(now) || quietFixedInProgress(now)) {
            return new Decision(Mode.SILENT, null);
        }
        return new Decision(Mode.SEND, null);
    }

    /**
     * 조용히(quiet) 단계의 고정 일정이 진행 중인가. [시작]을 눌러 started 가 된 고정 블록은 작업 중으로 보고 빼서
     * 체크인이 울리게 한다(v3.1 동작 유지). 알림 허용(allow) 일정은 무음으로 만들지 않는다.
     */
    public boolean quietFixedInProgress(Instant now) {
        return store.fixedInProgress(now).stream()
                .anyMatch(b -> "quiet".equals(b.level()) && !"started".equals(b.status()));
    }

    /** 묶음 메시지 예약: 일정의 금지 구간이 끝나는 시각. 이미 있으면 다시 잡는다(연장 반영). */
    public void scheduleRelease(Store.Block b) {
        jobs.cancelForTarget(RELEASE_JOB, b.id());
        Instant at = b.blockedEnd().isAfter(clock.now()) ? b.blockedEnd() : clock.now();
        jobs.schedule(RELEASE_JOB, b.id(), at, null);
    }

    /** 이 블록에 모아 둔 메시지가 있나. */
    public boolean hasHeld(long blockId) {
        return db.jdbc().sql("SELECT COUNT(*) FROM messages WHERE held_block_id = ?").param(blockId)
                .query(Integer.class).single() > 0;
    }

    /** 모아 둔 메시지(plain: 버튼 없는 글, interactive: 질문·확인 카드·목표 질문). */
    record Held(long id, String content, String kind, String importance, Long questionId, Long confirmationId,
                Long goalId) {

        boolean interactive() {
            return questionId != null || confirmationId != null || goalId != null;
        }
    }

    List<Held> held(long blockId) {
        return new ArrayList<>(db.jdbc().sql("""
                        SELECT id, content, kind, importance, question_id, confirmation_id, goal_id FROM messages
                        WHERE held_block_id = ? ORDER BY id""")
                .param(blockId)
                .query((rs, n) -> new Held(rs.getLong("id"), rs.getString("content"), rs.getString("kind"),
                        rs.getString("importance"), AssistantDb.nullableLong(rs, "question_id"),
                        AssistantDb.nullableLong(rs, "confirmation_id"), AssistantDb.nullableLong(rs, "goal_id")))
                .list());
    }

    private boolean onRelease(Jobs.Job job) {
        Store.Block b = store.block(job.targetId()).orElse(null);
        if (b != null && stillHolding(b)) {
            scheduleRelease(b);
            return false;
        }
        return release(job.targetId());
    }

    private boolean stillHolding(Store.Block b) {
        return "dnd".equals(b.level()) && List.of("planned", "nudging", "started").contains(b.status())
                && clock.now().isBefore(b.blockedEnd());
    }

    /** 단계가 바뀌었거나 일정이 끝났으면 모아 둔 메시지를 지금 보낸다(§2: 방해 허용 정도 변경·지금 끝내기). */
    public void releaseIfDone(long blockId) {
        Store.Block b = store.block(blockId).orElse(null);
        if (b == null || !stillHolding(b)) {
            jobs.cancelForTarget(RELEASE_JOB, blockId);
            release(blockId);
        }
    }

    /**
     * 모아 둔 메시지를 "○○ 동안 온 소식" 한 메시지로 보낸다(예산 1개). 버튼이 있는 질문·확인 카드는 채팅 끝에
     * 무음으로 다시 붙인다(앱이 새 id 만 받아 가므로 새 행으로 옮긴다).
     */
    public boolean release(long blockId) {
        List<Held> list = held(blockId);
        if (list.isEmpty()) {
            return false;
        }
        String title = store.block(blockId).map(store::blockTitle).orElse("일정");
        StringBuilder sb = new StringBuilder("'" + title + "' 동안 온 소식이에요");
        int interactive = 0;
        for (Held h : list) {
            if (h.interactive()) {
                interactive++;
            } else {
                sb.append("\n- ").append(h.content());
            }
        }
        if (interactive > 0) {
            sb.append("\n(답이 필요한 질문 ").append(interactive).append("개는 아래에 있어요)");
        }
        chat.sendAssistant(sb.toString(), "proactive", "normal", null, null, false);
        for (Held h : list) {
            if (h.interactive()) {
                long id = chat.sendAssistant(h.content(), h.kind(), h.importance(), h.questionId(), h.confirmationId(),
                        true);
                if (h.goalId() != null) {
                    db.update("UPDATE messages SET goal_id = ? WHERE id = ?", h.goalId(), id);
                }
            }
            db.update("DELETE FROM messages WHERE id = ?", h.id());
        }
        return true;
    }
}
