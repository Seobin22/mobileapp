package com.seobin.phonedrop.assistant.push;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** 보낸 목록을 메모리에 쌓기만 한다. 테스트와 Firebase 미설정 운영(PUSH_MODE=fake)에서 쓴다. */
public class FakePush implements Push {

    public record Sent(String token, Map<String, String> data) {
    }

    private final List<Sent> sent = new CopyOnWriteArrayList<>();

    @Override
    public void send(String token, Map<String, String> data) {
        if (token.startsWith("invalid")) {
            throw new InvalidTokenException("fake invalid token");
        }
        sent.add(new Sent(token, Map.copyOf(data)));
    }

    public List<Sent> sent() {
        return sent;
    }

    public List<Map<String, String>> ofType(String type) {
        return sent.stream().map(Sent::data).filter(d -> type.equals(d.get("type"))).toList();
    }

    public void clear() {
        sent.clear();
    }
}
