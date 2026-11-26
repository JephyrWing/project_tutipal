package com.my.tutipal_back.sessions.controller;

import static com.my.tutipal_back.learning.conversation.ConversationTestSupport.login;
import static com.my.tutipal_back.learning.conversation.ConversationTestSupport.logout;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.my.tutipal_back.common.exception.ApiException;
import com.my.tutipal_back.learning.conversation.ConversationTestSupport;
import com.my.tutipal_back.sessions.dto.CreateSessionRequest;
import com.my.tutipal_back.sessions.dto.SessionResponse;
import com.my.tutipal_back.sessions.entity.SessionRole;
import com.my.tutipal_back.sessions.service.InviteCodeJoinService;
import com.my.tutipal_back.sessions.service.StudySessionService;

/**
 * PT-13 decision A over HTTP: aiRetentionDays is required on create (no implicit default) and must be 0..90, both
 * on create and on PUT /api/sessions/{id}/ai-retention; a rejected body never reaches the service.
 */
class StudySessionAiRetentionWebTest {

    private static final long TEACHER = 1L;

    private final StudySessionService service = mock(StudySessionService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        login(TEACHER);
        mvc = ConversationTestSupport.mvc(new StudySessionController(service, mock(InviteCodeJoinService.class)));
    }

    @AfterEach
    void tearDown() {
        logout();
    }

    private static SessionResponse response(int days) {
        return new SessionResponse(10L, "3주차", "자바", "ABC234", SessionRole.TEACHER, 3, false, false, 1, false,
                false, days);
    }

    @Test
    void createWithARetentionIs201AndEchoesIt() throws Exception {
        when(service.create(eq(TEACHER), any(CreateSessionRequest.class))).thenReturn(response(0));

        mvc.perform(post("/api/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"3주차\",\"topic\":\"자바\",\"aiRetentionDays\":0}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.aiRetentionDays").value(0));
        verify(service).create(TEACHER, new CreateSessionRequest("3주차", "자바", 0));
    }

    @Test
    void createWithoutARetentionIs400() throws Exception {
        mvc.perform(post("/api/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"3주차\",\"topic\":\"자바\"}"))
                .andExpect(status().isBadRequest());
        verify(service, never()).create(anyLong(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "-1", "91"})
    void createWithAnInvalidRetentionIs400(String days) throws Exception {
        mvc.perform(post("/api/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"3주차\",\"topic\":\"자바\",\"aiRetentionDays\":" + days + "}"))
                .andExpect(status().isBadRequest());
        verify(service, never()).create(anyLong(), any());
    }

    @Test
    void createAcceptsTheNinetyDayMaximum() throws Exception {
        when(service.create(eq(TEACHER), any(CreateSessionRequest.class))).thenReturn(response(90));

        mvc.perform(post("/api/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"3주차\",\"topic\":\"자바\",\"aiRetentionDays\":90}"))
                .andExpect(status().isCreated());
    }

    @Test
    void updateRetentionIs200WithTheNewValue() throws Exception {
        when(service.updateAiRetention(TEACHER, 10L, 60)).thenReturn(response(60));

        mvc.perform(put("/api/sessions/10/ai-retention").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"aiRetentionDays\":60}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aiRetentionDays").value(60));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"aiRetentionDays\":null}", "{\"aiRetentionDays\":-1}",
            "{\"aiRetentionDays\":91}"})
    void updateWithAnInvalidRetentionIs400(String body) throws Exception {
        mvc.perform(put("/api/sessions/10/ai-retention").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        verify(service, never()).updateAiRetention(anyLong(), anyLong(), anyInt());
    }

    @Test
    void forbiddenFromTheServiceIs403() throws Exception {
        when(service.updateAiRetention(TEACHER, 10L, 7))
                .thenThrow(new ApiException(HttpStatus.FORBIDDEN, "이 방의 선생님만 할 수 있어요"));

        mvc.perform(put("/api/sessions/10/ai-retention").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"aiRetentionDays\":7}"))
                .andExpect(status().isForbidden());
    }
}
