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

    private final Store store;

    public Permissions(Store store) {
        this.store = store;
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
                    boolean timeChange = args.hasNonNull("start") || args.hasNonNull("end")
                            || args.hasNonNull("lead_minutes") || args.hasNonNull("buffer_after_min");
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
            default -> {
                return Decision.execute();
            }
        }
    }
}
