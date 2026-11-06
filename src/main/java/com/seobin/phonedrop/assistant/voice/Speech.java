package com.seobin.phonedrop.assistant.voice;

import java.util.List;

/**
 * 귀와 입(빌드문서 v3.6 §3). 운영은 ElevenLabsSpeech, 테스트는 FakeSpeech. 오디오는 저장하지 않는다.
 */
public interface Speech {

    record Voice(String id, String name, String previewUrl) {
    }

    class SpeechException extends RuntimeException {
        public SpeechException(String message) {
            super(message);
        }
    }

    boolean configured();

    /** 관리 화면 표시(키 없음·정상·키 오류 등). */
    String status();

    /** 말 한 덩어리(WAV) → 글. */
    String transcribe(byte[] wav, String model);

    /** 글 → mp3. */
    byte[] synthesize(String text, String voiceId, double speed);

    List<Voice> voices();
}
