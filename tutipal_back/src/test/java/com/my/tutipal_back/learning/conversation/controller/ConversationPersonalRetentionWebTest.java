package com.my.tutipal_back.learning.conversation.controller;

import static com.my.tutipal_back.learning.conversation.ConversationTestSupport.JSON;
import static com.my.tutipal_back.learning.conversation.ConversationTestSupport.NOW;
import static com.my.tutipal_back.learning.conversation.ConversationTestSupport.login;
import static com.my.tutipal_back.learning.conversation.ConversationTestSupport.logout;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.my.tutipal_back.ai.quota.MutableClock;
import com.my.tutipal_back.learning.conversation.ConversationProperties;
import com.my.tutipal_back.learning.conversation.ConversationTestSupport;
import com.my.tutipal_back.learning.conversation.repository.ConversationStore;
import com.my.tutipal_back.learning.conversation.repository.FakeConversationStore;
import com.my.tutipal_back.learning.conversation.service.ConversationService;
import com.my.tutipal_back.sessions.service.StudySessionService;

import tools.jackson.databind.JsonNode;

/**
 * Codex P2 (round 2): the list and detail carry personalRetentionDays from the server's
 * app.ai.conversations.personal-retention-days, so the screen's notice matches what ConversationRetention deletes
 * even when operations change AI_CONVERSATION_PERSONAL_RETENTION_DAYS.
 */
class ConversationPersonalRetentionWebTest {

    private static final long ME = 2L;

    private final FakeConversationStore store = new FakeConversationStore();
    private final MutableClock clock = new MutableClock(NOW);

    @BeforeEach
    void setUp() {
        login(ME);
    }

    @AfterEach
    void tearDown() {
        logout();
    }

    private MockMvc mvc(ConversationService service) {
        return ConversationTestSupport.mvc(new ConversationController(service));
    }

    private static ConversationProperties personal(int days) {
        return new ConversationProperties(days, 500, ConversationProperties.defaults().summary());
    }

    private static JsonNode body(ResultActions result) throws Exception {
        return JSON.readTree(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    void listAndDetailCarryTheConfiguredPersonalRetention() throws Exception {
        ConversationStore.Conversation c = store.seedConversation(ME, null, "수학", NOW.minus(Duration.ofDays(1)));
        MockMvc mvc = mvc(new ConversationService(store, mock(StudySessionService.class), clock, personal(14)));

        JsonNode page = body(mvc.perform(get("/api/conversations")).andExpect(status().isOk()));
        JsonNode detail = body(mvc.perform(get("/api/conversations/{id}", c.id())).andExpect(status().isOk()));
        JsonNode created = body(mvc.perform(post("/api/conversations").contentType(MediaType.APPLICATION_JSON)
                .content("{\"topic\":\"영어\"}")).andExpect(status().isCreated()));

        assertThat(page.get("personalRetentionDays").asInt()).isEqualTo(14);
        assertThat(detail.get("personalRetentionDays").asInt()).isEqualTo(14);
        assertThat(created.get("personalRetentionDays").asInt()).isEqualTo(14);
    }

    /** Also on an empty list (the notice is shown before the first conversation). */
    @Test
    void emptyListStillCarriesTheDefault() throws Exception {
        MockMvc mvc = mvc(new ConversationService(store, mock(StudySessionService.class), clock));

        JsonNode page = body(mvc.perform(get("/api/conversations")).andExpect(status().isOk()));

        assertThat(page.get("items").size()).isZero();
        assertThat(page.get("personalRetentionDays").asInt()).isEqualTo(30);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ConversationProperties.class)
    @Import(ConversationService.class)
    static class Wiring {

        @Bean
        ConversationStore store() {
            return new FakeConversationStore();
        }

        @Bean
        StudySessionService sessions() {
            return mock(StudySessionService.class);
        }
    }

    /** The bean Spring builds reads the bound property (environment variable / application.yaml). */
    @Test
    void springBeanUsesTheBoundProperty() {
        new ApplicationContextRunner().withUserConfiguration(Wiring.class)
                .withPropertyValues("app.ai.conversations.personal-retention-days=45")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(ConversationService.class).list(ME, null, null, null)
                            .personalRetentionDays()).isEqualTo(45);
                });
    }
}
