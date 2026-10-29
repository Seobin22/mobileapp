package com.seobin.phonedrop.assistant.agent;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.seobin.phonedrop.assistant.Store;
import com.seobin.phonedrop.assistant.TimeUtil;

/**
 * 시간표 검증기(빌드문서 v2 §4, v3.1 §3.1). LLM 이 낸 시간표를 코드가 검사만 한다. 배치는 하지 않는다.
 * 블록의 금지 구간 = [start − lead_minutes, end + buffer_after_min]. 위반이 없으면 빈 목록.
 */
@Component
public class Validator {

    /** 새로 넣으려는 계획 블록. taskId 가 null 이면 할 일 검사(7번)를 건너뛴다. */
    public record NewBlock(Long taskId, Instant start, Instant end) {
    }

    /**
     * 고정 블록 검사 결과(§3.1). errors 가 있으면 거절. displaced 는 겹쳐서 밀려날 계획 블록(planned·nudging),
     * cutoff 는 금지 구간 시작에 partial 로 끝낼 진행 중 블록.
     */
    public record FixedCheck(List<String> errors, List<Store.Block> displaced, List<Store.Block> cutoff) {
    }

    private final Store store;
    private final TimeUtil time;

    public Validator(Store store, TimeUtil time) {
        this.store = store;
        this.time = time;
    }

    /** 계획 블록 검사. 계획 블록은 다른 모든 블록(keepBlocks)의 금지 구간과 겹치면 안 된다. */
    public List<String> validateBlocks(List<NewBlock> newBlocks, List<Store.Block> keepBlocks, Instant now) {
        List<String> violations = new ArrayList<>();
        for (int i = 0; i < newBlocks.size(); i++) {
            NewBlock b = newBlocks.get(i);
            String label = label(b);

            // 6. 5분 미만 (길이가 0 이하이면 다른 검사가 엉뚱해지므로 먼저 본다)
            if (Duration.between(b.start(), b.end()).toMinutes() < 5) {
                violations.add(label + "의 길이가 5분 미만입니다");
                continue;
            }
            // 5. 과거
            if (b.start().isBefore(now)) {
                violations.add(label + "의 시작 시각이 이미 지났습니다");
            }
            // 7. 할 일 없음·비활성
            if (b.taskId() != null) {
                Store.Task t = store.task(b.taskId()).orElse(null);
                if (t == null) {
                    violations.add(label + ": 할일#" + b.taskId() + "이 없습니다");
                } else if (!"active".equals(t.status())) {
                    violations.add(label + ": 할일#" + b.taskId() + "은 " + t.status() + " 상태라 배치할 수 없습니다");
                }
            }
            // 1. 새 블록끼리 겹침
            for (int j = 0; j < i; j++) {
                NewBlock o = newBlocks.get(j);
                if (overlaps(b.start(), b.end(), o.start(), o.end())) {
                    violations.add(label + "이 " + label(o) + "과 겹칩니다");
                }
            }
            checkConflicts(label, b, keepBlocks, violations);
        }
        return violations;
    }

    /**
     * 시간 구간만 검사한다(유지 블록·수면·방해 금지). 과거·길이·할 일 검사는 하지 않는다.
     * extend_block 의 늘어나는 구간(v3 §4.2)용.
     */
    public List<String> validateRange(Long taskId, Instant start, Instant end, List<Store.Block> keepBlocks) {
        List<String> violations = new ArrayList<>();
        NewBlock b = new NewBlock(taskId, start, end);
        checkConflicts(label(b), b, keepBlocks, violations);
        return violations;
    }

    /**
     * 고정 블록 검사(§3.1). 사용자가 정한 시각이므로 수면·방해 금지는 보지 않는다.
     * 고정 블록끼리 금지 구간이 겹치면 거절, 계획 블록과 겹치면 고정 블록이 우선(displaced),
     * 진행 중 블록과 겹치면 금지 구간 시작에 partial(cutoff).
     */
    public FixedCheck validateFixed(Long excludeId, Instant start, Instant end, int leadMinutes, int bufferAfterMin,
                                    Instant now) {
        List<String> errors = new ArrayList<>();
        List<Store.Block> displaced = new ArrayList<>();
        List<Store.Block> cutoff = new ArrayList<>();
        if (Duration.between(start, end).toMinutes() < 5) {
            errors.add("길이가 5분 미만이에요. 시간을 바꿔 주세요.");
            return new FixedCheck(errors, displaced, cutoff);
        }
        if (!end.isAfter(now)) {
            errors.add("이미 지난 시간이에요. 시간을 바꿔 주세요.");
            return new FixedCheck(errors, displaced, cutoff);
        }
        Instant blockedStart = start.minus(Duration.ofMinutes(Math.max(0, leadMinutes)));
        Instant blockedEnd = end.plus(Duration.ofMinutes(Math.max(0, bufferAfterMin)));
        for (Store.Block k : store.activeBlocks()) {
            if (excludeId != null && k.id() == excludeId) {
                continue;
            }
            if (!overlaps(blockedStart, blockedEnd, k.blockedStart(), k.blockedEnd())) {
                continue;
            }
            if (k.fixed()) {
                errors.add(store.blockTitle(k) + "(" + range(k.start(), k.end()) + ", 블록#" + k.id()
                        + ")와 겹쳐요. 시간을 바꿔 주세요.");
            } else if ("started".equals(k.status())) {
                cutoff.add(k);
            } else {
                displaced.add(k);
            }
        }
        return new FixedCheck(errors, displaced, cutoff);
    }

    private void checkConflicts(String label, NewBlock b, List<Store.Block> keepBlocks, List<String> violations) {
        // 1·2. 유지 블록(고정 블록은 lead·buffer 포함 금지 구간)과 겹침
        for (Store.Block k : keepBlocks) {
            if (overlaps(b.start(), b.end(), k.blockedStart(), k.blockedEnd())) {
                String kind = k.fixed() ? "고정 블록" : "다른 블록";
                String zone = k.fixed() && (k.leadMinutes() > 0 || k.bufferAfterMin() > 0)
                        ? ", 준비·이동 포함 " + range(k.blockedStart(), k.blockedEnd()) : "";
                violations.add(label + "이 " + kind + "(" + range(k.start(), k.end()) + ", " + store.blockTitle(k)
                        + ", 블록#" + k.id() + " " + k.status() + zone + ")과 겹칩니다");
            }
        }
        // 3. 수면·방해 금지
        for (TimeUtil.Window q : time.quietWindows(b.start(), b.end())) {
            violations.add(label + "이 수면·방해 금지 시간(" + range(q.start(), q.end()) + ")과 겹칩니다");
            break;
        }
        // (v3.1: 기본 식사 시간과 '식사 시간 겹침' 검사는 없앴다.)
    }

    private String label(NewBlock b) {
        String title = b.taskId() == null ? "새 할 일" : taskTitle(b.taskId());
        return "블록(" + range(b.start(), b.end()) + ", " + title + ")";
    }

    private String taskTitle(long taskId) {
        return store.task(taskId).map(Store.Task::title).orElse("할일#" + taskId);
    }

    private String range(Instant s, Instant e) {
        return time.shortText(s) + "–" + time.hm(e);
    }

    private static boolean overlaps(Instant s1, Instant e1, Instant s2, Instant e2) {
        return s1.isBefore(e2) && s2.isBefore(e1);
    }
}
