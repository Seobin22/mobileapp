package com.seobin.phonedrop.assistant.agent;

import org.springframework.stereotype.Component;

import com.seobin.phonedrop.assistant.Store;

import tools.jackson.databind.JsonNode;

/**
 * 권한표(빌드문서 v2 §6). 판단은 LLM, 강제는 코드.
 * user_message 가 아닌 trigger 에서 고정 일정·목표를 바꾸려 하면 실행하지 않고 확인 카드로 바꾼다.
 * 캘린더 출처 일정은 어떤 trigger 에서도 바꾸지 못한다.
 */
@Component
public class Permissions {

    public enum Kind { EXECUTE, CONFIRM, REJECT }

    /** CONFIRM 이면 confirmationKind(event_change 등), REJECT 면 message. */
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

    public Decision decide(String trigger, String tool, JsonNode args) {
        boolean fromUser = "user_message".equals(trigger);
        switch (tool) {
            case "upsert_event" -> {
                if (args.hasNonNull("id")) {
                    Store.Event e = store.event(args.get("id").asLong()).orElse(null);
                    if (e != null && "calendar".equals(e.source())) {
                        return Decision.reject(CALENDAR_REJECT);
                    }
                    boolean timeChange = args.hasNonNull("start") || args.hasNonNull("end");
                    if (timeChange && !fromUser) {
                        return Decision.confirm("event_change");
                    }
                    return Decision.execute();
                }
                // 생성
                return fromUser ? Decision.execute() : Decision.confirm("event_change");
            }
            case "cancel" -> {
                String entity = args.path("entity").asString("");
                if ("event".equals(entity)) {
                    Store.Event e = store.event(args.path("id").asLong()).orElse(null);
                    if (e != null && "calendar".equals(e.source())) {
                        return Decision.reject(CALENDAR_REJECT);
                    }
                    return fromUser ? Decision.execute() : Decision.confirm("event_cancel");
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
