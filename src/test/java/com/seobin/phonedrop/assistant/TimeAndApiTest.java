package com.seobin.phonedrop.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.seobin.phonedrop.assistant.api.ApiTokenFilter;

class TimeAndApiTest extends AssistantTestBase {

    @Autowired
    WebApplicationContext context;
    @Autowired
    ApiTokenFilter tokenFilter;
    @Autowired
    TimeUtil time;

    MockMvc mvc;

    @BeforeEach
    void setUpMvc() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(tokenFilter).build();
    }

    @Test
    void logicalDayBeforeWakeIsPreviousDay() {
        assertThat(time.logicalDay(at("2026-11-03T06:00"))).isEqualTo(LocalDate.parse("2026-11-02"));
        assertThat(time.logicalDay(at("2026-11-03T08:00"))).isEqualTo(LocalDate.parse("2026-11-03"));
    }

    @Test
    void activeWindowAcrossMidnight() {
        putSetting("life.sleep", "\"01:00\"");
        TimeUtil.Window w = time.activeWindow(LocalDate.parse("2026-11-02"));
        assertThat(w.start()).isEqualTo(at("2026-11-02T07:30"));
        assertThat(w.end()).isEqualTo(at("2026-11-03T01:00"));
    }

    @Test
    void quietWindowsMergeSleepAndDnd() {
        putSetting("dnd.windows", "[{\"days\":[1],\"start\":\"13:00\",\"end\":\"14:00\"},"
                + "{\"days\":[1],\"start\":\"23:00\",\"end\":\"23:45\"}]");
        List<TimeUtil.Window> windows = time.quietWindows(at("2026-11-02T09:00"), at("2026-11-03T09:00"));
        assertThat(windows).containsExactly(
                new TimeUtil.Window(at("2026-11-02T13:00"), at("2026-11-02T14:00")),
                new TimeUtil.Window(at("2026-11-02T23:00"), at("2026-11-03T07:30")));
        assertThat(time.isQuiet(at("2026-11-02T13:30"))).isTrue();
        assertThat(time.isQuiet(at("2026-11-02T14:00"))).isFalse();
        // 화요일에는 방해 금지가 없다.
        assertThat(time.isQuiet(at("2026-11-03T13:30"))).isFalse();
    }

    @Test
    void apiRequiresToken() throws Exception {
        mvc.perform(get("/api/assistant/v1/sync")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/assistant/v1/sync").header("Authorization", "Bearer wrong"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("받은 길이 5자")));
        mvc.perform(get("/api/assistant/v1/sync"))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("토큰이 없습니다")));
        mvc.perform(get("/api/assistant/v1/sync").header("Authorization", "Bearer sk-ant-xyz"))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("Anthropic 키")));
        mvc.perform(get("/api/assistant/v1/sync").header("Authorization", "Bearer test-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("plan.sync"));
        mvc.perform(get("/api/assistant/health")).andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    void adminChangesProviderAndModel() throws Exception {
        try {
            mvc.perform(post("/api/assistant/v1/admin/llm").header("Authorization", "Bearer test-token")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"provider\":\"factchat\",\"model\":\"gpt-5\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.model").value("gpt-5"));
            mvc.perform(get("/api/assistant/v1/admin/agent").header("Authorization", "Bearer test-token"))
                    .andExpect(jsonPath("$.provider").value("factchat"))
                    .andExpect(jsonPath("$.protocol").value("openai-chat(factchat)"));
            mvc.perform(post("/api/assistant/v1/admin/llm").header("Authorization", "Bearer test-token")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"provider\":\"openai\",\"model\":\"gpt-5\"}"))
                    .andExpect(status().isUnprocessableEntity());
            mvc.perform(post("/api/assistant/v1/admin/llm").header("Authorization", "Bearer test-token")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"provider\":\"anthropic\",\"model\":\"bad model\"}"))
                    .andExpect(status().isUnprocessableEntity());
            // 빈 모델 = 서버 기본 모델
            mvc.perform(post("/api/assistant/v1/admin/llm").header("Authorization", "Bearer test-token")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"provider\":\"anthropic\",\"model\":\"\"}"))
                    .andExpect(jsonPath("$.provider").value("anthropic"))
                    .andExpect(jsonPath("$.model").value(org.hamcrest.Matchers.not("")));
        } finally {
            tx.exec(() -> {
                settings.put("llm.provider", Json.MAPPER.valueToTree("anthropic"));
                settings.put("llm.model", Json.MAPPER.valueToTree(""));
            });
        }
    }

    @Test
    void registerThenCreateAndRejectOverlapOverHttp() throws Exception {
        mvc.perform(post("/api/assistant/v1/devices/register").header("Authorization", "Bearer test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fcm_token\":\"phone\",\"app_version\":\"1.0.0\",\"capabilities\":{\"notifications\":true}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.device_id").isNumber());

        String body = "{\"title\":\"보고서 초안\",\"first_action\":\"문서 파일만 열기\","
                + "\"start\":\"2026-11-02T10:00:00+09:00\",\"duration_min\":60}";
        mvc.perform(post("/api/assistant/v1/admin/tasks").header("Authorization", "Bearer test-token")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.block_id").isNumber());
        mvc.perform(post("/api/assistant/v1/admin/tasks").header("Authorization", "Bearer test-token")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("겹쳐요")));
        mvc.perform(get("/api/assistant/v1/admin/tasks").header("Authorization", "Bearer test-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks[0].blocks[0].start").value("2026-11-02T10:00:00+09:00"));
    }

    @Test
    void unknownSettingRejected() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/assistant/v1/settings")
                        .header("Authorization", "Bearer test-token")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"key\":\"nope\",\"value\":1}"))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/assistant/v1/settings")
                        .header("Authorization", "Bearer test-token")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"key\":\"life.wake\",\"value\":\"7시\"}"))
                .andExpect(status().isUnprocessableContent());
    }
}
