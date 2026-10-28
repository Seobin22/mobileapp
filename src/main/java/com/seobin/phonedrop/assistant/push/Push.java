package com.seobin.phonedrop.assistant.push;

import java.util.Map;

/** FCM data 메시지 발송. 값은 모두 문자열이다(FCM data 제약). */
public interface Push {

    /** 토큰이 더 이상 유효하지 않으면 InvalidTokenException. */
    void send(String token, Map<String, String> data);

    class InvalidTokenException extends RuntimeException {
        public InvalidTokenException(String message) {
            super(message);
        }
    }
}
