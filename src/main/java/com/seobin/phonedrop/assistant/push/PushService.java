package com.seobin.phonedrop.assistant.push;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.seobin.phonedrop.assistant.AssistantClock;
import com.seobin.phonedrop.assistant.AssistantDb;
import com.seobin.phonedrop.assistant.TimeUtil;

/**
 * 등록된 모든 기기에 보낸다(단일 사용자, 기기 1~2대).
 * 트랜잭션 안에서 부르면 커밋된 뒤에 보낸다(롤백된 변경을 알리지 않도록).
 */
@Service
public class PushService {

    private static final Logger log = LoggerFactory.getLogger(PushService.class);
    private static final int RECENT_LIMIT = 50;

    private final Push push;
    private final AssistantDb db;
    private final AssistantClock clock;
    private final TimeUtil time;
    private final Deque<Map<String, String>> recent = new ArrayDeque<>();

    public PushService(Push push, AssistantDb db, AssistantClock clock, TimeUtil time) {
        this.push = push;
        this.db = db;
        this.clock = clock;
        this.time = time;
    }

    public void pushAll(Map<String, String> data) {
        Map<String, String> copy = Map.copyOf(data);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    sendNow(copy);
                }
            });
        } else {
            sendNow(copy);
        }
    }

    private void sendNow(Map<String, String> data) {
        List<Map.Entry<Long, String>> devices = db.jdbc().sql("SELECT id, fcm_token FROM devices ORDER BY id")
                .query((rs, n) -> Map.entry(rs.getLong(1), rs.getString(2))).list();
        int delivered = 0;
        for (Map.Entry<Long, String> device : devices) {
            // Firebase 없던 앱의 임시 토큰. FCM 으로는 못 보내므로 건너뛴다(앱이 진짜 토큰으로 다시 등록한다).
            if (push instanceof FirebasePush && device.getValue().startsWith("nofcm-")) {
                continue;
            }
            try {
                push.send(device.getValue(), data);
                delivered++;
            } catch (Push.InvalidTokenException e) {
                log.warn("device {} token invalid, removing", device.getKey());
                db.update("DELETE FROM devices WHERE id = ?", device.getKey());
            } catch (RuntimeException e) {
                log.error("push to device {} failed", device.getKey(), e);
            }
        }
        remember(data, delivered, devices.size());
    }

    private synchronized void remember(Map<String, String> data, int delivered, int devices) {
        Map<String, String> entry = new LinkedHashMap<>();
        entry.put("at", time.iso(clock.now()));
        entry.put("delivered", delivered + "/" + devices);
        entry.putAll(data);
        recent.addFirst(entry);
        while (recent.size() > RECENT_LIMIT) {
            recent.removeLast();
        }
    }

    /** 관리 화면용 최근 발송 목록(최신순). */
    public synchronized List<Map<String, String>> recent() {
        return new ArrayList<>(recent);
    }

    public String mode() {
        return push instanceof FirebasePush ? "firebase" : "fake";
    }
}
