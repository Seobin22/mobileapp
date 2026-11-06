package com.seobin.phonedrop.assistant.voice;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.seobin.phonedrop.assistant.Json;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * ElevenLabs Scribe(말→글)·Flash TTS(글→말). 키는 환경변수 ELEVENLABS_API_KEY 또는 관리 화면이 저장한
 * data/assistant/elevenlabs_api_key(권한 600). 키·오디오·글 내용은 로그에 남기지 않는다.
 */
@Component
public class ElevenLabsSpeech implements Speech {

    private static final Logger log = LoggerFactory.getLogger(ElevenLabsSpeech.class);
    static final String BASE = "https://api.elevenlabs.io";
    static final String TTS_MODEL = "eleven_flash_v2_5";
    /** voice.stt_model 이 서버에서 거절되면 이 모델로 한 번 더. */
    static final String STT_FALLBACK = "scribe_v1";

    private final Path keyFile;
    private final String envKey;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private volatile String status = "unknown";
    private volatile String defaultVoice;

    public ElevenLabsSpeech(@Value("${assistant.data-dir:data/assistant}") String dataDir,
                            @Value("${ELEVENLABS_API_KEY:}") String envKey) {
        this.keyFile = Path.of(dataDir, "elevenlabs_api_key");
        this.envKey = envKey == null ? "" : envKey.trim();
    }

    private String key() {
        if (!envKey.isEmpty()) {
            return envKey;
        }
        try {
            return Files.exists(keyFile) ? Files.readString(keyFile).trim() : "";
        } catch (IOException e) {
            return "";
        }
    }

    public void saveKey(String key) throws IOException {
        Files.createDirectories(keyFile.getParent());
        Files.writeString(keyFile, key.trim() + "\n");
        try {
            Files.setPosixFilePermissions(keyFile, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException e) {
            // Windows(로컬 개발)
        }
        status = "unknown";
        defaultVoice = null;
    }

    @Override
    public boolean configured() {
        return !key().isEmpty();
    }

    @Override
    public String status() {
        return configured() ? status : "no_key";
    }

    @Override
    public String transcribe(byte[] wav, String model) {
        String m = model == null || model.isBlank() ? "scribe_v2" : model.trim();
        try {
            return transcribeOnce(wav, m);
        } catch (SpeechException e) {
            if (!m.equals(STT_FALLBACK) && e.getMessage().startsWith("4")) {
                log.warn("STT model {} rejected, falling back to {}", m, STT_FALLBACK);
                return transcribeOnce(wav, STT_FALLBACK);
            }
            throw e;
        }
    }

    private String transcribeOnce(byte[] wav, String model) {
        String boundary = "----pd" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        field(body, boundary, "model_id", model);
        field(body, boundary, "language_code", "kor");
        write(body, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"speech.wav\"\r\n"
                + "Content-Type: audio/wav\r\n\r\n");
        body.writeBytes(wav);
        write(body, "\r\n--" + boundary + "--\r\n");
        HttpRequest req = HttpRequest.newBuilder(URI.create(BASE + "/v1/speech-to-text"))
                .timeout(Duration.ofSeconds(30))
                .header("xi-api-key", key())
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();
        JsonNode r = Json.parse(new String(send(req), StandardCharsets.UTF_8));
        return r.path("text").asString("").trim();
    }

    @Override
    public byte[] synthesize(String text, String voiceId, double speed) {
        String voice = voiceId == null || voiceId.isBlank() ? defaultVoice() : voiceId.trim();
        ObjectNode body = Json.object();
        body.put("text", text);
        body.put("model_id", TTS_MODEL);
        body.put("language_code", "ko");
        body.putObject("voice_settings").put("speed", speed);
        HttpRequest req = HttpRequest.newBuilder(URI.create(BASE + "/v1/text-to-speech/"
                        + URLEncoder.encode(voice, StandardCharsets.UTF_8) + "?output_format=mp3_44100_64"))
                .timeout(Duration.ofSeconds(30))
                .header("xi-api-key", key())
                .header("Content-Type", "application/json")
                .header("Accept", "audio/mpeg")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body)))
                .build();
        return send(req);
    }

    @Override
    public List<Voice> voices() {
        HttpRequest req = HttpRequest.newBuilder(URI.create(BASE + "/v1/voices"))
                .timeout(Duration.ofSeconds(15))
                .header("xi-api-key", key())
                .GET().build();
        JsonNode r = Json.parse(new String(send(req), StandardCharsets.UTF_8));
        List<Voice> list = new ArrayList<>();
        for (JsonNode v : r.path("voices")) {
            list.add(new Voice(v.path("voice_id").asString(""), v.path("name").asString(""),
                    v.path("preview_url").asString("")));
        }
        return list;
    }

    /** voice.voice_id 가 비면 계정의 첫 목소리. */
    private String defaultVoice() {
        String v = defaultVoice;
        if (v == null) {
            List<Voice> list = voices();
            if (list.isEmpty()) {
                throw new SpeechException("쓸 수 있는 목소리가 없습니다");
            }
            v = list.getFirst().id();
            defaultVoice = v;
        }
        return v;
    }

    private byte[] send(HttpRequest req) {
        if (!configured()) {
            status = "no_key";
            throw new SpeechException("ElevenLabs 키가 설정되지 않았습니다");
        }
        try {
            HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            int code = resp.statusCode();
            if (code / 100 == 2) {
                status = "ok";
                return resp.body();
            }
            status = code == 401 ? "bad_key" : code == 402 || code == 429 ? "quota" : "error_" + code;
            String detail = new String(resp.body(), StandardCharsets.UTF_8);
            log.warn("ElevenLabs {} {}", code, detail.length() > 200 ? detail.substring(0, 200) : detail);
            throw new SpeechException(code + " ElevenLabs 오류");
        } catch (IOException e) {
            status = "network";
            throw new SpeechException("ElevenLabs 연결 실패");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SpeechException("중단됨");
        }
    }

    private static void field(ByteArrayOutputStream out, String boundary, String name, String value) {
        write(out, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n");
    }

    private static void write(ByteArrayOutputStream out, String s) {
        out.writeBytes(s.getBytes(StandardCharsets.UTF_8));
    }
}
