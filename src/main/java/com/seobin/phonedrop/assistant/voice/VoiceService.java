package com.seobin.phonedrop.assistant.voice;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.seobin.phonedrop.assistant.ApiException;
import com.seobin.phonedrop.assistant.AssistantClock;
import com.seobin.phonedrop.assistant.AssistantDb;
import com.seobin.phonedrop.assistant.Json;
import com.seobin.phonedrop.assistant.SettingsService;
import com.seobin.phonedrop.assistant.TimeUtil;
import com.seobin.phonedrop.assistant.TurnQueue;
import com.seobin.phonedrop.assistant.Tx;
import com.seobin.phonedrop.assistant.agent.AgentWorker;
import com.seobin.phonedrop.assistant.agent.TimeWindowRunner;

import tools.jackson.databind.node.ObjectNode;

/**
 * 음성 탭(빌드문서 v3.6 §3). 한 번 주고받기 = 말 한 덩어리(WAV) → Scribe 로 글 → channel=voice 사용자 메시지 + voice turn(일반
 * 대화와 같은 맥락·도구·검증기) → 답 문장마다 TTS 를 흘려보낸다(NDJSON). 오디오는 메모리에서만 쓰고 저장하지 않는다.
 * nginx 를 바꾸지 않으려고 WebSocket 대신 HTTP 스트리밍 응답을 쓴다(DECISIONS).
 */
@Service
public class VoiceService {

    public static final String TRIGGER = "voice";
    static final Duration TURN_WAIT = Duration.ofSeconds(120);
    private static final Pattern SENTENCE = Pattern.compile("(?<=[.!?。…~])\\s+|\\n+");
    private static final Logger log = LoggerFactory.getLogger(VoiceService.class);

    private final AssistantDb db;
    private final AssistantClock clock;
    private final Tx tx;
    private final TimeUtil time;
    private final SettingsService settings;
    private final TurnQueue turns;
    private final Speech speech;
    private final ObjectProvider<AgentWorker> worker;
    private final ObjectProvider<TimeWindowRunner> windowRunner;
    private final boolean workerEnabled;
    /** 세션별 '끊고 들어오기' 신호(§3.3). */
    private final Map<Long, AtomicBoolean> interrupts = new ConcurrentHashMap<>();

    public VoiceService(AssistantDb db, AssistantClock clock, Tx tx, TimeUtil time, SettingsService settings,
                        TurnQueue turns, Speech speech, ObjectProvider<AgentWorker> worker,
                        ObjectProvider<TimeWindowRunner> windowRunner,
                        @Value("${assistant.worker.enabled:true}") boolean workerEnabled) {
        this.db = db;
        this.clock = clock;
        this.tx = tx;
        this.time = time;
        this.settings = settings;
        this.turns = turns;
        this.speech = speech;
        this.worker = worker;
        this.windowRunner = windowRunner;
        this.workerEnabled = workerEnabled;
    }

    // ------------------------------------------------------------------ 세션

    public long start() {
        long id = tx.run(() -> {
            // 앱이 죽어 끝나지 않은 세션은 새 세션을 열 때 닫는다(§4.3 앱 종료 = 세션 끝).
            db.update("UPDATE voice_sessions SET ended_at = ?, end_reason = 'app_restarted' WHERE ended_at IS NULL",
                    clock.now());
            return db.insert("INSERT INTO voice_sessions(started_at) VALUES (?)", clock.now());
        });
        interrupts.put(id, new AtomicBoolean(false));
        return id;
    }

    public void end(long sessionId, String reason) {
        String r = reason == null || reason.isBlank() ? "user" : reason.replaceAll("[^a-z_]", "");
        tx.exec(() -> db.update("UPDATE voice_sessions SET ended_at = ?, end_reason = ? WHERE id = ? AND ended_at IS NULL",
                clock.now(), r.isEmpty() ? "user" : r, sessionId));
        interrupts.remove(sessionId);
    }

    private void requireOpen(long sessionId) {
        boolean open = tx.run(() -> db.jdbc().sql("SELECT COUNT(*) FROM voice_sessions WHERE id = ? AND ended_at IS NULL")
                .param(sessionId).query(Integer.class).single() > 0);
        if (!open) {
            throw ApiException.notFound("음성 세션이 없거나 끝났습니다");
        }
    }

    /** 오늘(논리 날짜) 음성 turn 수. daily_turn_hard 와 따로 센다(§3.7). */
    public int voiceTurnsToday() {
        Instant dayStart = time.activeWindow(time.logicalDay(clock.now())).start();
        return db.jdbc().sql("SELECT COUNT(*) FROM turns WHERE trigger = 'voice' AND started_at >= ?")
                .param(AssistantDb.ts(dayStart)).query(Integer.class).single();
    }

    // ------------------------------------------------------------------ 한 번 주고받기 (§3.2)

    /**
     * NDJSON 으로 흘려보낸다: user_text → assistant_text·audio(문장마다)·saved·card → done. 문제가 있으면 error 한 줄.
     */
    public void turn(long sessionId, byte[] wav, OutputStream out) throws IOException {
        requireOpen(sessionId);
        AtomicBoolean interrupted = interrupts.computeIfAbsent(sessionId, k -> new AtomicBoolean(false));
        interrupted.set(false);
        if (tx.run(this::voiceTurnsToday) >= settings.getInt("voice.daily_turn_cap")) {
            emit(out, event("error", "오늘 음성 대화 한도(" + settings.getInt("voice.daily_turn_cap") + "번)를 다 썼어요. 채팅으로 말해 주세요."));
            return;
        }
        if (!speech.configured()) {
            emit(out, event("error", "ElevenLabs 키가 아직 없어요. 관리 화면 '음성 연결'에서 넣어 주세요."));
            return;
        }
        double seconds = wavSeconds(wav);
        String text;
        try {
            text = speech.transcribe(wav, settings.getString("voice.stt_model"));
        } catch (Speech.SpeechException e) {
            emit(out, event("error", "말을 글로 바꾸지 못했어요(" + e.getMessage() + ")"));
            return;
        } finally {
            java.util.Arrays.fill(wav, (byte) 0); // 오디오는 바로 지운다(§0-5)
        }
        addUsage(sessionId, seconds, 0, 0);
        if (text.isBlank()) {
            emit(out, event("empty", ""));
            return;
        }
        long[] ids = tx.run(() -> {
            long messageId = db.insert("""
                            INSERT INTO messages(role, content, kind, source, channel, created_at)
                            VALUES ('user', ?, 'reply', 'voice', 'voice', ?)""",
                    text.length() > 8000 ? text.substring(0, 8000) : text, clock.now());
            TimeWindowRunner runner = windowRunner.getIfAvailable();
            if (runner != null) {
                runner.ack(false);
            }
            ObjectNode ref = Json.object();
            ref.put("message_id", messageId);
            ref.put("session_id", sessionId);
            return new long[]{messageId, turns.enqueue(TRIGGER, ref, TurnQueue.PRIORITY_USER)};
        });
        ObjectNode userEvent = event("user_text", text);
        userEvent.put("message_id", ids[0]);
        emit(out, userEvent);

        waitForTurn(ids[1]);
        record Msg(long id, String kind, String content, Long confirmationId, String action) {
        }
        List<Msg> replies = tx.run(() -> db.jdbc().sql("""
                        SELECT id, kind, content, confirmation_id, action FROM messages
                        WHERE role = 'assistant' AND channel = 'voice' AND id > ? ORDER BY id""")
                .param(ids[0])
                .query((rs, n) -> new Msg(rs.getLong(1), rs.getString(2), rs.getString(3),
                        AssistantDb.nullableLong(rs, "confirmation_id"), rs.getString("action")))
                .list());

        int ttsChars = 0;
        Long lastSpoken = null;
        String voiceId = settings.getString("voice.voice_id");
        double speed = settings.getDouble("voice.speed");
        for (Msg m : replies) {
            if ("system".equals(m.kind())) {
                ObjectNode saved = event("saved", m.content()); // '저장됨' 줄은 화면에만, 소리로 읽지 않는다(§3.4)
                saved.put("message_id", m.id());
                if (m.action() != null) {
                    saved.put("action", m.action()); // undo_card:{id} → [되돌리기]
                }
                emit(out, saved);
                continue;
            }
            if ("confirmation".equals(m.kind()) && m.confirmationId() != null) {
                ObjectNode card = event("card", m.content());
                card.put("confirmation_id", m.confirmationId());
                emit(out, card);
                continue;
            }
            ObjectNode said = event("assistant_text", m.content());
            said.put("message_id", m.id());
            emit(out, said);
            lastSpoken = m.id();
            int seq = 0;
            for (String sentence : SENTENCE.split(m.content())) {
                String s = sentence.strip();
                if (s.isEmpty() || interrupted.get()) {
                    continue;
                }
                try {
                    byte[] mp3 = speech.synthesize(s, voiceId, speed);
                    ttsChars += s.length();
                    if (interrupted.get()) {
                        break;
                    }
                    ObjectNode audio = event("audio", s);
                    audio.put("message_id", m.id());
                    audio.put("seq", seq++);
                    audio.put("b64", Base64.getEncoder().encodeToString(mp3));
                    emit(out, audio);
                } catch (Speech.SpeechException e) {
                    emit(out, event("error", "목소리를 만들지 못했어요(" + e.getMessage() + ")"));
                    break;
                }
            }
        }
        final Long readBy = lastSpoken;
        final int chars = ttsChars;
        tx.exec(() -> {
            // §3.6: 이 세션에서 띄운 카드는 그 카드를 읽은 비서 말을 기억한다(다음 사용자 말에서만 결정할 수 있다).
            if (readBy != null) {
                db.update("""
                                UPDATE confirmations SET voice_read_message_id = ?
                                WHERE voice_session_id = ? AND status = 'pending' AND voice_read_message_id IS NULL""",
                        readBy, sessionId);
            }
            long tokens = db.jdbc().sql("SELECT COALESCE(input_tokens, 0) + COALESCE(output_tokens, 0) FROM turns WHERE id = ?")
                    .param(ids[1]).query(Long.class).optional().orElse(0L);
            addUsage(sessionId, 0, chars, tokens);
        });
        emit(out, event("done", ""));
    }

    private void waitForTurn(long turnId) {
        Instant until = Instant.now().plus(TURN_WAIT);
        while (Instant.now().isBefore(until)) {
            if (!workerEnabled) {
                // 테스트·워커 없는 실행: 이 스레드에서 바로 돌린다.
                AgentWorker w = worker.getIfAvailable();
                while (w != null && w.runOnce()) {
                    // 대기열이 빌 때까지
                }
            }
            String status = tx.run(() -> db.jdbc().sql("SELECT status FROM turns WHERE id = ?").param(turnId)
                    .query(String.class).optional().orElse("failed"));
            if (!"queued".equals(status) && !"running".equals(status)) {
                return;
            }
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        log.warn("voice turn {} did not finish in time", turnId);
    }

    private void addUsage(long sessionId, double seconds, int chars, long tokens) {
        tx.exec(() -> db.update("""
                        UPDATE voice_sessions SET stt_seconds = stt_seconds + ?, tts_chars = tts_chars + ?,
                        llm_tokens = llm_tokens + ? WHERE id = ?""", seconds, chars, tokens, sessionId));
    }

    // ------------------------------------------------------------------ 끊고 들어오기 (§3.3)

    /** 사용자가 말을 시작했다: 남은 TTS 를 멈추고, 그때까지 말한 부분만 남기고 interrupted 로 표시한다. */
    public void interrupt(long sessionId, Long messageId, String spoken) {
        interrupts.computeIfAbsent(sessionId, k -> new AtomicBoolean()).set(true);
        if (messageId == null) {
            return;
        }
        tx.exec(() -> {
            if (spoken != null && !spoken.isBlank()) {
                db.update("""
                                UPDATE messages SET content = ?, interrupted = TRUE
                                WHERE id = ? AND role = 'assistant' AND channel = 'voice'""",
                        spoken.strip() + " …", messageId);
            } else {
                db.update("UPDATE messages SET interrupted = TRUE WHERE id = ? AND role = 'assistant' AND channel = 'voice'",
                        messageId);
            }
        });
    }

    // ------------------------------------------------------------------ 비서가 먼저 거는 말 소리로(§5)

    /** 적극 개입 시간대의 먼저 거는 말을 ElevenLabs 목소리로. 앱이 §2.4 조건을 확인한 뒤 부른다. */
    public byte[] speakMessage(long messageId) {
        String text = tx.run(() -> db.jdbc().sql("SELECT content FROM messages WHERE id = ? AND role = 'assistant'")
                .param(messageId).query(String.class).optional()
                .orElseThrow(() -> ApiException.notFound("메시지가 없습니다")));
        if (!speech.configured()) {
            throw ApiException.notFound("ElevenLabs 키가 없습니다");
        }
        String t = text.length() > 300 ? text.substring(0, 300) : text;
        byte[] mp3 = speech.synthesize(t, settings.getString("voice.voice_id"), settings.getDouble("voice.speed"));
        return mp3;
    }

    // ------------------------------------------------------------------ 관리 화면 (§3.7)

    /** 오늘 합계와 대략 비용. 요금은 요금제마다 달라 '대략'(DECISIONS). */
    public Map<String, Object> usageToday() {
        Instant dayStart = time.activeWindow(time.logicalDay(clock.now())).start();
        return tx.run(() -> db.jdbc().sql("""
                        SELECT COUNT(*) AS n, COALESCE(SUM(stt_seconds), 0) AS stt, COALESCE(SUM(tts_chars), 0) AS tts,
                        COALESCE(SUM(llm_tokens), 0) AS tok FROM voice_sessions WHERE started_at >= ?""")
                .param(AssistantDb.ts(dayStart))
                .query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    double stt = rs.getDouble("stt");
                    long tts = rs.getLong("tts");
                    m.put("sessions", rs.getInt("n"));
                    m.put("stt_seconds", Math.round(stt));
                    m.put("tts_chars", tts);
                    m.put("llm_tokens", rs.getLong("tok"));
                    m.put("voice_turns", voiceTurnsToday());
                    m.put("turn_cap", settings.getInt("voice.daily_turn_cap"));
                    // 대략: Scribe 약 $0.40/시간, Flash TTS 약 $0.05/1000자(요금제에 따라 다름). LLM 토큰은 따로.
                    m.put("approx_usd", Math.round((stt / 3600 * 0.40 + tts / 1000.0 * 0.05) * 1000) / 1000.0);
                    m.put("status", speech.status());
                    return m;
                }).single());
    }

    public List<Speech.Voice> voices() {
        return new ArrayList<>(speech.voices());
    }

    // ------------------------------------------------------------------ 공통

    static double wavSeconds(byte[] wav) {
        if (wav.length < 44 || !new String(wav, 0, 4, StandardCharsets.US_ASCII).equals("RIFF")) {
            return wav.length / 32000.0; // 16kHz mono 16bit 로 본다
        }
        ByteBuffer b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        int byteRate = b.getInt(28);
        return byteRate <= 0 ? 0 : (wav.length - 44) / (double) byteRate;
    }

    private static ObjectNode event(String type, String text) {
        ObjectNode o = Json.object();
        o.put("type", type);
        if (text != null && !text.isEmpty()) {
            o.put("text", text);
        }
        return o;
    }

    private static void emit(OutputStream out, ObjectNode event) throws IOException {
        out.write((Json.write(event) + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }
}
