package com.seobin.phonedrop.assistant;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import com.seobin.phonedrop.assistant.voice.Speech;

/** 테스트용 가짜 ElevenLabs. 받아쓸 글을 미리 넣고, 목소리로 만든 문장을 기록한다. */
public class FakeSpeech implements Speech {

    public final Deque<String> heard = new ArrayDeque<>();
    public final List<String> spoken = new ArrayList<>();
    public boolean key = true;
    /** 이 문장을 목소리로 만들 때 호출(끊고 들어오기 흉내). */
    public Runnable onSynthesize;

    public synchronized void reset() {
        heard.clear();
        spoken.clear();
        key = true;
        onSynthesize = null;
    }

    @Override
    public boolean configured() {
        return key;
    }

    @Override
    public String status() {
        return key ? "ok" : "no_key";
    }

    @Override
    public String transcribe(byte[] wav, String model) {
        String t = heard.poll();
        return t == null ? "" : t;
    }

    @Override
    public byte[] synthesize(String text, String voiceId, double speed) {
        spoken.add(text);
        if (onSynthesize != null) {
            onSynthesize.run();
        }
        return ("mp3:" + text).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public List<Voice> voices() {
        return List.of(new Voice("v1", "지수", "https://example.invalid/p.mp3"));
    }
}
