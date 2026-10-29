package com.seobin.phonedrop.assistant.agent;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.seobin.phonedrop.assistant.Store;
import com.seobin.phonedrop.assistant.TimeUtil;

/**
 * 시간표 검증기(빌드문서 v2 §4). LLM 이 낸 시간표를 코드가 검사만 한다. 배치는 하지 않는다.
 * 위반이 없으면 빈 목록.
 */
@Component
public class Validator {

    /** 새로 넣으려는 블록. taskId 가 null 이면 아직 만들지 않은 할 일(v1 수동 등록)이라 7번 검사를 건너뛴다. */
    public record NewBlock(Long taskId, Instant start, Instant end) {
    }

    private final Store store;
    private final TimeUtil time;

    public Validator(Store store, TimeUtil time) {
        this.store = store;
        this.time = time;
    }

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
            // 1. 새 블록끼리, 유지 블록과 겹침
            for (int j = 0; j < i; j++) {
                NewBlock o = newBlocks.get(j);
                if (overlaps(b.start(), b.end(), o.start(), o.end())) {
                    violations.add(label + "이 " + label(o) + "과 겹칩니다");
                }
            }
            for (Store.Block k : keepBlocks) {
                if (overlaps(b.start(), b.end(), k.start(), k.end())) {
                    violations.add(label + "이 다른 블록(" + range(k.start(), k.end()) + ", "
                            + taskTitle(k.taskId()) + ", 블록#" + k.id() + " " + k.status() + ")과 겹칩니다");
                }
            }
            // 2. 일정(버퍼 포함)
            for (Store.Event e : store.activeEventsOverlapping(b.start(), b.end())) {
                violations.add(label + "이 " + e.title() + "(버퍼 포함 " + range(e.blockedStart(), e.blockedEnd())
                        + ", 일정#" + e.id() + ")와 겹칩니다");
            }
            // 3. 수면·방해 금지
            for (TimeUtil.Window q : time.quietWindows(b.start(), b.end())) {
                violations.add(label + "이 수면·방해 금지 시간(" + range(q.start(), q.end()) + ")과 겹칩니다");
                break;
            }
            // 4. 식사
            for (TimeUtil.Window m : time.dailyWindows("life.meals", b.start(), b.end())) {
                violations.add(label + "이 식사 시간(" + range(m.start(), m.end()) + ")과 겹칩니다");
            }
        }
        return violations;
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
