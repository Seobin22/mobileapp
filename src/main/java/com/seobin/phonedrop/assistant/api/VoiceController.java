package com.seobin.phonedrop.assistant.api;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import com.seobin.phonedrop.assistant.ApiException;
import com.seobin.phonedrop.assistant.PlanService;
import com.seobin.phonedrop.assistant.Tx;
import com.seobin.phonedrop.assistant.agent.Changes;
import com.seobin.phonedrop.assistant.voice.ElevenLabsSpeech;
import com.seobin.phonedrop.assistant.voice.Speech;
import com.seobin.phonedrop.assistant.voice.VoiceService;

import jakarta.servlet.http.HttpServletRequest;
import tools.jackson.databind.JsonNode;

/** 음성 탭(빌드문서 v3.6 §3). 앱은 항상 서버를 거친다(ElevenLabs 키는 서버에만). */
@RestController
@RequestMapping("/api/assistant")
public class VoiceController {

    /** 말 한 덩어리 최대 크기(16kHz mono 16bit 로 약 2분). */
    static final int MAX_AUDIO_BYTES = 4_000_000;

    private final VoiceService voice;
    private final Speech speech;
    private final Tx tx;
    private final Changes changes;
    private final PlanService plan;

    public VoiceController(VoiceService voice, Speech speech, Tx tx, Changes changes, PlanService plan) {
        this.voice = voice;
        this.speech = speech;
        this.tx = tx;
        this.changes = changes;
        this.plan = plan;
    }

    @PostMapping("/v1/voice/sessions")
    public Map<String, Object> start() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("session_id", voice.start());
        r.put("configured", speech.configured());
        return r;
    }

    @PostMapping("/v1/voice/sessions/{id}/end")
    public Map<String, Object> end(@PathVariable long id, @RequestBody(required = false) JsonNode body) {
        voice.end(id, body == null ? "user" : body.path("reason").asString("user"));
        return Map.of("ok", true);
    }

    /** 본문 = 말 한 덩어리 WAV. 응답 = NDJSON 흐름(nginx 버퍼링 끔). */
    @PostMapping(value = "/v1/voice/sessions/{id}/turn", produces = "application/x-ndjson")
    public ResponseEntity<StreamingResponseBody> turn(@PathVariable long id, HttpServletRequest request) throws IOException {
        byte[] wav;
        try (InputStream in = request.getInputStream()) {
            wav = in.readNBytes(MAX_AUDIO_BYTES + 1);
        }
        if (wav.length == 0 || wav.length > MAX_AUDIO_BYTES) {
            throw ApiException.badRequest("오디오가 비었거나 너무 깁니다");
        }
        StreamingResponseBody body = out -> voice.turn(id, wav, out);
        return ResponseEntity.ok()
                .header("X-Accel-Buffering", "no")
                .header("Cache-Control", "no-cache")
                .contentType(MediaType.parseMediaType("application/x-ndjson"))
                .body(body);
    }

    /** {message_id, spoken}: 사용자가 끊고 들어왔다. */
    @PostMapping("/v1/voice/sessions/{id}/interrupt")
    public Map<String, Object> interrupt(@PathVariable long id, @RequestBody JsonNode body) {
        Long messageId = body.hasNonNull("message_id") ? body.get("message_id").asLong() : null;
        voice.interrupt(id, messageId, body.path("spoken").asString(""));
        return Map.of("ok", true);
    }

    /** 음성으로 승인한 카드 [되돌리기](기존 되돌리기 경로). */
    @PostMapping("/v1/voice/undo/{confirmationId}")
    public Map<String, Object> undo(@PathVariable long confirmationId) {
        return tx.run(() -> {
            Changes.UndoResult u = changes.undoConfirmation(confirmationId);
            plan.bumpAndSync();
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("ok", true);
            r.put("restored", u.restored());
            r.put("skipped", u.skipped());
            return r;
        });
    }

    @GetMapping("/v1/voice/voices")
    public Map<String, Object> voices() {
        return Map.of("voices", voice.voices().stream().map(v -> Map.of("id", v.id(), "name", v.name(),
                "preview_url", v.previewUrl())).toList());
    }

    /** v3.6 §5: 적극 개입 시간대 먼저 거는 말을 ElevenLabs 목소리로(mp3). */
    @GetMapping(value = "/v1/voice/messages/{id}/audio", produces = "audio/mpeg")
    public ResponseEntity<byte[]> messageAudio(@PathVariable long id) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("audio/mpeg")).body(voice.speakMessage(id));
    }

    // ---------------------------------------------------------------- 관리 화면 '음성 연결'

    @GetMapping("/v1/admin/voice_status")
    public Map<String, Object> status() {
        Map<String, Object> r = new LinkedHashMap<>(voice.usageToday());
        r.put("configured", speech.configured());
        return r;
    }

    /** ElevenLabs API 키 저장(서버 data/assistant/elevenlabs_api_key, 권한 600). 값은 다시 보여 주지 않는다. */
    @PostMapping("/v1/admin/elevenlabs_key")
    public Map<String, Object> saveKey(@RequestBody JsonNode body) throws IOException {
        String key = body.path("key").asString("").trim();
        if (key.isEmpty() || key.length() > 200 || !key.matches("[A-Za-z0-9_-]+")) {
            throw ApiException.badRequest("ElevenLabs API 키 형식이 맞지 않습니다");
        }
        if (!(speech instanceof ElevenLabsSpeech el)) {
            throw ApiException.unprocessable("음성 연결을 쓸 수 없습니다");
        }
        el.saveKey(key);
        try {
            speech.voices(); // 바로 한 번 불러 키가 맞는지 확인(상태는 status 에 남는다)
        } catch (Speech.SpeechException ignored) {
            // 상태로 보여 준다
        }
        return status();
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, String>> apiError(ApiException e) {
        return ResponseEntity.status(e.status()).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(Speech.SpeechException.class)
    public ResponseEntity<Map<String, String>> speechError(Speech.SpeechException e) {
        return ResponseEntity.status(502).body(Map.of("error", e.getMessage()));
    }
}
