package com.seobin.phonedrop.assistant;

import static com.seobin.phonedrop.assistant.agent.llm.ScriptedLlm.call;
import static com.seobin.phonedrop.assistant.agent.llm.ScriptedLlm.tools;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.seobin.phonedrop.assistant.agent.ChatService;
import com.seobin.phonedrop.assistant.agent.ContextBuilder;
import com.seobin.phonedrop.assistant.agent.Tools;
import com.seobin.phonedrop.assistant.voice.VoiceService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** 빌드문서 v3.6 §7 T14·T15·T17·T19·T22 (음성 탭, 가짜 ElevenLabs + ScriptedLLM). */
class V36VoiceTest extends AssistantTestBase {

    @Autowired
    VoiceService voice;
    @Autowired
    ChatService chat;
    @Autowired
    ContextBuilder contextBuilder;
    @Autowired
    Tools agentTools;

    static final byte[] WAV = new byte[32000];

    List<JsonNode> talk(long session, String heard) throws Exception {
        speech.heard.add(heard);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        voice.turn(session, WAV.clone(), out);
        List<JsonNode> events = new ArrayList<>();
        for (String line : out.toString(StandardCharsets.UTF_8).split("\n")) {
            if (!line.isBlank()) {
                events.add(Json.parse(line));
            }
        }
        return events;
    }

    static List<String> types(List<JsonNode> events) {
        return events.stream().map(e -> e.path("type").asString()).toList();
    }

    void reply(String text) {
        llm.then(tools(call("send_message", "{\"text\":\"" + text + "\",\"kind\":\"reply\"}")));
    }

    @Test
    void t14_oneExchangeStreamsSentencesAndStoresOnlyText() throws Exception {
        long session = voice.start();
        reply("두 시부터 세 시 반까지 영어야. 더 들을래?");
        List<JsonNode> ev = talk(session, "오늘 뭐 해야 돼");
        assertThat(types(ev)).containsExactly("user_text", "assistant_text", "audio", "audio", "done");
        assertThat(speech.spoken).containsExactly("두 시부터 세 시 반까지 영어야.", "더 들을래?");
        assertThat(db.jdbc().sql("SELECT COUNT(*) FROM messages WHERE channel = 'voice'").query(Integer.class).single())
                .isEqualTo(2);
        assertThat(db.jdbc().sql("SELECT silent FROM messages WHERE role = 'assistant' AND channel = 'voice'")
                .query(Boolean.class).single()).isTrue();
        assertThat(db.jdbc().sql("SELECT trigger FROM turns ORDER BY id DESC LIMIT 1").query(String.class).single())
                .isEqualTo("voice");
        assertThat(llm.userContents.getLast()).contains("voice: 음성 대화에서 사용자가 말함").contains("오늘 뭐 해야 돼");
        assertThat(db.jdbc().sql("SELECT stt_seconds FROM voice_sessions WHERE id = ?").param(session)
                .query(Double.class).single()).isEqualTo(1.0);
        assertThat(WAV).containsOnly((byte) 0);
    }

    @Test
    void t15_bargeInStopsSpeechAndMarksInterrupted() throws Exception {
        long session = voice.start();
        reply("첫 문장이야. 둘째 문장이야. 셋째 문장이야.");
        speech.onSynthesize = () -> {
            if (speech.spoken.size() == 1) {
                Long id = db.jdbc().sql("SELECT MAX(id) FROM messages WHERE role = 'assistant'").query(Long.class).single();
                voice.interrupt(session, id, "첫 문장이야.");
            }
        };
        List<JsonNode> ev = talk(session, "말해 봐");
        assertThat(types(ev)).doesNotContain("audio"); // 첫 문장을 만든 순간 끊겼다
        assertThat(speech.spoken).hasSize(1);
        record M(String content, boolean interrupted) {
        }
        M m = db.jdbc().sql("SELECT content, interrupted FROM messages WHERE role = 'assistant' ORDER BY id DESC LIMIT 1")
                .query((rs, n) -> new M(rs.getString(1), rs.getBoolean(2))).single();
        assertThat(m.interrupted()).isTrue();
        assertThat(m.content()).startsWith("첫 문장이야.").doesNotContain("둘째");
        String ctx = tx.run(() -> contextBuilder.build("voice", Json.parse("{\"message_id\":0}")));
        assertThat(ctx).contains("음성 끊김");
    }

    long voiceCard(long session) {
        long id = db.insert("""
                        INSERT INTO confirmations(kind, tool, args, text, expires_at, created_at, voice_session_id)
                        VALUES ('assistant_initiated', 'upsert_task', ?, '회의 준비 할 일 넣을까요?', ?, ?, ?)""",
                "{\"title\":\"회의 준비\",\"first_action\":\"자료 열기\",\"est_minutes\":30}",
                clock.now().plusSeconds(3600), clock.now(), session);
        db.insert("""
                        INSERT INTO messages(role, content, kind, importance, silent, confirmation_id, channel, created_at)
                        VALUES ('assistant', '회의 준비 할 일 넣을까요?', 'confirmation', 'normal', TRUE, ?, 'voice', ?)""",
                id, clock.now());
        long read = db.insert("""
                        INSERT INTO messages(role, content, kind, importance, silent, channel, created_at)
                        VALUES ('assistant', '회의 준비 넣을까?', 'reply', 'normal', TRUE, 'voice', ?)""", clock.now());
        db.update("UPDATE confirmations SET voice_read_message_id = ? WHERE id = ?", read, id);
        return id;
    }

    String cardStatus(long id) {
        return db.jdbc().sql("SELECT status FROM confirmations WHERE id = ?").param(id).query(String.class).single();
    }

    @Test
    void t17_clearYesApprovesCardWithUndo() throws Exception {
        long session = voice.start();
        long card = voiceCard(session);
        llm.then(tools(call("card_decide", "{\"card_id\":" + card + ",\"decision\":\"approve\",\"quote\":\"응 넣어\"}"),
                call("send_message", "{\"text\":\"넣어 뒀어\",\"kind\":\"reply\"}")));
        List<JsonNode> ev = talk(session, "응 넣어");
        assertThat(cardStatus(card)).isEqualTo("accepted");
        assertThat(db.jdbc().sql("SELECT COUNT(*) FROM tasks WHERE title = '회의 준비'").query(Integer.class).single()).isEqualTo(1);
        assertThat(db.jdbc().sql("SELECT voice_quote FROM confirmations WHERE id = ?").param(card).query(String.class).single())
                .isEqualTo("응 넣어");
        JsonNode saved = ev.stream().filter(e -> "saved".equals(e.path("type").asString())).findFirst().orElseThrow();
        assertThat(saved.path("text").asString()).contains("음성으로 승인됨");
        assertThat(db.jdbc().sql("SELECT action FROM messages WHERE kind = 'system' ORDER BY id DESC LIMIT 1")
                .query(String.class).single()).isEqualTo("undo_card:" + card);
        assertThat(speech.spoken).containsExactly("넣어 뒀어"); // 저장됨 줄은 소리로 읽지 않는다
    }

    @Test
    void t19_cardDecideRejectedOutsideVoiceOrWithWrongQuote() throws Exception {
        long session = voice.start();
        long card = voiceCard(session);
        // 텍스트 turn
        long turnId = db.insert("INSERT INTO turns(trigger, status, started_at) VALUES ('user_message', 'running', ?)", clock.now());
        ObjectNode r = tx.run(() -> agentTools.dispatch(new Tools.Context(turnId, "user_message", false), "card_decide",
                Json.parse("{\"card_id\":" + card + ",\"decision\":\"approve\",\"quote\":\"응\"}")));
        assertThat(r.path("ok").asBoolean(true)).isFalse();
        // quote 불일치
        llm.then(tools(call("card_decide", "{\"card_id\":" + card + ",\"decision\":\"approve\",\"quote\":\"그래 넣어줘\"}")));
        llm.then(tools(call("send_message", "{\"text\":\"넣을까 말까?\",\"kind\":\"reply\"}")));
        talk(session, "음 글쎄");
        assertThat(cardStatus(card)).isEqualTo("pending");
        assertThat(llm.toolResults.stream().anyMatch(t -> t.isError() && t.content().contains("quote"))).isTrue();
        // 카드를 읽은 '바로 다음' 말이 아니면(이미 한 번 말함) 거절
        llm.then(tools(call("card_decide", "{\"card_id\":" + card + ",\"decision\":\"approve\",\"quote\":\"응 넣어\"}")));
        llm.then(tools(call("send_message", "{\"text\":\"화면에 카드 둘게\",\"kind\":\"reply\"}")));
        talk(session, "응 넣어");
        assertThat(cardStatus(card)).isEqualTo("pending");
    }

    @Test
    void t22_voiceTurnsCountedSeparately() throws Exception {
        putSetting("voice.daily_turn_cap", "1");
        long session = voice.start();
        int before = tx.run(chat::turnsToday);
        reply("응");
        talk(session, "안녕");
        assertThat(tx.run(chat::turnsToday)).isEqualTo(before);
        assertThat(tx.run(voice::voiceTurnsToday)).isEqualTo(1);
        List<JsonNode> ev = talk(session, "또 말해");
        assertThat(types(ev)).containsExactly("error");
    }

    @Test
    void endedSessionRejectsTurns() {
        long session = voice.start();
        voice.end(session, "volume_long_press");
        assertThat(db.jdbc().sql("SELECT end_reason FROM voice_sessions WHERE id = ?").param(session)
                .query(String.class).single()).isEqualTo("volume_long_press");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> talk(session, "여보세요"))
                .isInstanceOf(ApiException.class);
    }
}
