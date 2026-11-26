package com.my.tutipal_back.learning.focus.controller;

import static com.my.tutipal_back.learning.conversation.ConversationTestSupport.JSON;
import static com.my.tutipal_back.learning.conversation.ConversationTestSupport.login;
import static com.my.tutipal_back.learning.conversation.ConversationTestSupport.logout;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.my.tutipal_back.common.exception.ApiException;
import com.my.tutipal_back.learning.conversation.ConversationTestSupport;
import com.my.tutipal_back.learning.focus.dto.FocusDtos.ConsentResponse;
import com.my.tutipal_back.learning.focus.service.FocusConsentService;

import tools.jackson.databind.JsonNode;

/** PT-90 AC-2 over HTTP: GET / PUT / DELETE /api/focus/consent for the logged-in user only. */
class FocusConsentWebTest {

    private static final long ME = 2L;
    private static final Instant AT = Instant.parse("2026-11-26T03:15:42Z");

    private final FocusConsentService service = mock(FocusConsentService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        login(ME);
        mvc = ConversationTestSupport.mvc(new FocusConsentController(service));
    }

    @AfterEach
    void tearDown() {
        logout();
    }

    private static JsonNode body(ResultActions result) throws Exception {
        return JSON.readTree(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private ResultActions putJson(String json) throws Exception {
        return mvc.perform(put("/api/focus/consent").contentType(MediaType.APPLICATION_JSON).content(json));
    }

    @Test
    void getReturnsTheConsentState() throws Exception {
        when(service.current(ME)).thenReturn(new ConsentResponse(false, null, null, "2026-11-26", 30, 31, 180));

        JsonNode response = body(mvc.perform(get("/api/focus/consent")).andExpect(status().isOk()));

        assertThat(response.get("consented").asBoolean()).isFalse();
        assertThat(response.get("currentVersion").asString()).isEqualTo("2026-11-26");
        // Codex round 1: the retention settings go with the consent so the screen text matches the server
        assertThat(response.propertyNames()).isSubsetOf("consented", "version", "consentedAt", "currentVersion",
                "retentionDays", "maxSampleAgeDays", "staleAfterSeconds");
        assertThat(response.get("retentionDays").asInt()).isEqualTo(30);
        assertThat(response.get("maxSampleAgeDays").asInt()).isEqualTo(31);
        assertThat(response.get("staleAfterSeconds").asLong()).isEqualTo(180);
    }

    @Test
    void putRecordsConsentForTheLoggedInUser() throws Exception {
        when(service.give(ME, "2026-11-26")).thenReturn(new ConsentResponse(true, "2026-11-26", AT, "2026-11-26", 30, 31, 180));

        JsonNode response = body(putJson("{\"version\":\"2026-11-26\",\"userId\":99}").andExpect(status().isOk()));

        assertThat(response.get("consented").asBoolean()).isTrue();
        assertThat(response.get("version").asString()).isEqualTo("2026-11-26");
        assertThat(response.get("consentedAt").asString()).isEqualTo("2026-11-26T03:15:42Z");
        verify(service).give(ME, "2026-11-26");
        verify(service, never()).give(99L, "2026-11-26");
    }

    @Test
    void putWithAnotherVersionKeepsThe409() throws Exception {
        when(service.give(ME, "2025-01-01")).thenThrow(new ApiException(HttpStatus.CONFLICT, "x"));

        putJson("{\"version\":\"2025-01-01\"}").andExpect(status().isConflict());
    }

    @Test
    void putWithBlankMissingOrTooLongVersionIs400() throws Exception {
        putJson("{\"version\":\"\"}").andExpect(status().isBadRequest());
        putJson("{\"version\":\"   \"}").andExpect(status().isBadRequest());
        putJson("{}").andExpect(status().isBadRequest());
        putJson("{\"version\":\"" + "9".repeat(21) + "\"}").andExpect(status().isBadRequest());

        verify(service, never()).give(anyLong(), anyString());
    }

    @Test
    void deleteWithdrawsAndReturns204() throws Exception {
        mvc.perform(delete("/api/focus/consent")).andExpect(status().isNoContent());

        verify(service).withdraw(ME);
    }
}
