package com.seobin.phonedrop.assistant.agent;

import org.springframework.stereotype.Component;

import com.seobin.phonedrop.assistant.Store;

import tools.jackson.databind.JsonNode;

/**
 * 권한표(빌드문서 v2 §6, v3.1 §3.3). 판단은 LLM, 강제는 코드.
 * 사용자가 직접 한 말(user_message, 풀스크린 대화 intervention_talk)이 아닌 trigger 에서 고정 블록·목표를 바꾸려 하면
 * 실행하지 않고 확인 카드로 바꾼다. 캘린더 출처 고정 블록은 어떤 trigger 에서도 바꾸지 못한다.
 */
@Component
public class Permissions {

    public enum Kind { EXECUTE, CONFIRM, REJECT }

    /** CONFIRM 이면 confirmationKind(fixed_change 등), REJECT 면 message. */
    public record Decision(Kind kind, String confirmationKind, String message) {

        static Decision execute() {
            return new Decision(Kind.EXECUTE, null, null);
        }

        static Decision confirm(String confirmationKind) {
            return new Decision(Kind.CONFIRM, confirmationKind, null);
        }

        static Decision reject(String message) {
            return new Decision(Kind.REJECT, null, message);
        }
    }

    public static final String CALENDAR_REJECT = "캘린더에서 바꿔주세요";
    public static final String MEMO_USER_ONLY =
            "고정 일정 메모는 사용자 것이다. 사용자가 채팅으로 직접 요청했을 때만 append_fixed_memo 로 덧붙일 수 있다. 네 판단은 add_block_log 에 남긴다";
    public static final String WORK_PRODUCT_ASK_FIRST =
            "작업물은 사용자 허락을 받은 뒤에 만든다. ask_user로 만들지 묻고, 사용자가 원한다고 답하면 그때 만든다";

    private final Store store;
    private final com.seobin.phonedrop.assistant.AssistantClock clock;

    public Permissions(Store store, com.seobin.phonedrop.assistant.AssistantClock clock) {
        this.store = store;
        this.clock = clock;
    }

    /** v3.3 §2: 시작 시각이 지났거나 [시작]을 누른 고정 블록. 비서는 확인 카드로만 바꾼다. */
    private boolean fixedInProgress(Store.Block b) {
        return b != null && b.fixed() && java.util.List.of("planned", "nudging", "started").contains(b.status())
                && ("started".equals(b.status()) || !b.start().isAfter(clock.now()));
    }

    /**
     * 2026-11-06: 풀스크린 대화에서 이동 블록이 있는(장소 있는) 약속의 시각을 바꾸려 하면 확인 카드.
     * 늦게 나가겠다는 말은 adjust_travel(출발 미루기)로 처리한다. 장소 없는 일정(공부 등)의 미루기는 그대로.
     */
    private boolean travelAppointment(String trigger, Store.Block b) {
        return "intervention_talk".equals(trigger) && b != null && b.fixed() && store.activeArriveTravel(b.id()).isPresent();
    }

    /** 방해 허용 정도만 바꾸는 호출(§1.3: 확인 카드 없이). */
    private static boolean onlyInterruptLevel(JsonNode args) {
        java.util.Set<String> keys = new java.util.HashSet<>();
        args.propertyNames().forEach(keys::add);
        keys.removeAll(java.util.Set.of("id", "interrupt_level", "interrupt_from_user", "confirm_text"));
        return keys.isEmpty();
    }

    /** 사용자가 직접 한 말로 시작된 turn(§3.6-2: 풀스크린 대화도 user_message 와 같은 권한). */
    public static boolean fromUser(String trigger) {
        return "user_message".equals(trigger) || "intervention_talk".equals(trigger);
    }

    public Decision decide(String trigger, String tool, JsonNode args) {
        boolean fromUser = fromUser(trigger);
        switch (tool) {
            case "upsert_fixed_block" -> {
                if (args.hasNonNull("id")) {
                    Store.Block b = store.block(args.get("id").asLong()).orElse(null);
                    if (b != null && "calendar".equals(b.source())) {
                        return Decision.reject(CALENDAR_REJECT);
                    }
                    if (fixedInProgress(b) && !fromUser && !onlyInterruptLevel(args)
                            && ("started".equals(b.status()) || !FixedBlockService.startChange(args, b))) {
                        return Decision.confirm("fixed_change");
                    }
                    // v3.1 §3.2.1: 준비 시간(lead·buffer)만 바꾸는 것은 확인 카드 없이(사용자가 정한 값은 도구가 거절한다).
                    boolean timeChange = args.hasNonNull("start") || args.hasNonNull("end");
                    if (timeChange && travelAppointment(trigger, b)) {
                        return Decision.confirm("fixed_change");
                    }
                    if (timeChange && !fromUser) {
                        return Decision.confirm("fixed_change");
                    }
                    return Decision.execute();
                }
                // 생성
                return fromUser ? Decision.execute() : Decision.confirm("fixed_change");
            }
            case "move_block" -> {
                Store.Block b = store.block(args.path("block_id").asLong(-1)).orElse(null);
                if (b != null && b.fixed()) {
                    if ("calendar".equals(b.source())) {
                        return Decision.reject(CALENDAR_REJECT);
                    }
                    if (travelAppointment(trigger, b)) {
                        return Decision.confirm("fixed_change");
                    }
                    return fromUser ? Decision.execute() : Decision.confirm("fixed_change");
                }
                return Decision.execute();
            }
            case "cancel" -> {
                String entity = args.path("entity").asString("");
                if ("fixed_block".equals(entity)) {
                    Store.Block b = store.block(args.path("id").asLong()).orElse(null);
                    if (b != null && "calendar".equals(b.source())) {
                        return Decision.reject(CALENDAR_REJECT);
                    }
                    return fromUser ? Decision.execute() : Decision.confirm("fixed_cancel");
                }
                if ("goal".equals(entity)) {
                    return fromUser ? Decision.execute() : Decision.confirm("goal_cancel");
                }
                if ("fixed_series".equals(entity)) {
                    return fromUser ? Decision.execute() : Decision.confirm("fixed_cancel");
                }
                return Decision.execute();
            }
            case "upsert_goal" -> {
                boolean existing = args.hasNonNull("id");
                boolean sensitiveChange = existing && (args.has("deadline") || args.hasNonNull("status"));
                if (sensitiveChange && !fromUser) {
                    return Decision.confirm("goal_change");
                }
                return Decision.execute();
            }
            case "upsert_fixed_series" -> {
                // v3.2 §2.3: 반복 일정은 고정 블록과 같은 권한(사용자 말이면 반영, 그 외 확인 카드).
                return fromUser ? Decision.execute() : Decision.confirm("fixed_change");
            }
            case "append_fixed_memo" -> {
                // v3.2 §3.1: 메모는 사용자 것. 채팅으로 직접 요청한 turn 에서만 덧붙일 수 있다.
                return fromUser ? Decision.execute() : Decision.reject(MEMO_USER_ONLY);
            }
            case "adjust_travel" -> {
                // 출발 미루기·이동 시간 고치기는 사용자가 직접 말했을 때만.
                return fromUser ? Decision.execute() : Decision.reject("이동 블록은 사용자가 직접 말했을 때만 고칠 수 있다. 필요하면 ask_user 로 묻는다");
            }
            case "extend_block", "end_block" -> {
                // v3.3 §2: 진행 중 고정 일정은 비서가 직접 늘리거나 끝내지 않는다(사용자 말이면 반영).
                Store.Block b = store.block(args.path("block_id").asLong(-1)).orElse(null);
                if (b != null && b.fixed()) {
                    if ("calendar".equals(b.source())) {
                        return Decision.reject(CALENDAR_REJECT);
                    }
                    return fromUser ? Decision.execute() : Decision.confirm("fixed_change");
                }
                return Decision.execute();
            }
            case "create_work_product" -> {
                // 사용자 요청(2026-11-02): 작업물은 허락받은 뒤에만 만든다. 허락은 사용자의 답(user_message)으로 온다.
                return fromUser ? Decision.execute() : Decision.reject(WORK_PRODUCT_ASK_FIRST);
            }
            default -> {
                return Decision.execute();
            }
        }
    }
}
