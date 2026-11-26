package com.my.tutipal_back.learning.focus.controller;

import static com.my.tutipal_back.learning.conversation.ConversationTestSupport.JSON;
import static com.my.tutipal_back.learning.conversation.ConversationTestSupport.login;
import static com.my.tutipal_back.learning.conversation.ConversationTestSupport.logout;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.StringJoiner;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.my.tutipal_back.common.exception.ApiException;
import com.my.tutipal_back.learning.conversation.ConversationTestSupport;
import com.my.tutipal_back.learning.focus.FocusState;
import com.my.tutipal_back.learning.focus.dto.FocusDtos.FocusStatusResponse;
import com.my.tutipal_back.learning.focus.dto.FocusDtos.FocusSubmitRequest;
import com.my.tutipal_back.learning.focus.dto.FocusDtos.StudentFocus;
import com.my.tutipal_back.learning.focus.service.FocusService;

import tools.jackson.databind.JsonNode;

/**
 * PT-90 FocusController over HTTP with a mocked FocusService: AC-1 (user id from the login only), AC-3 input
 * limits (400 without echoing input, service not called), AC-7 response shape (no exe, category or raw values).
 */
class FocusControllerWebTest {

    private static final long ME = 2L;
    private static final long SESSION = 10L;
    private static final Instant UPDATED = Instant.parse("2026-11-26T03:15:42Z");

    private final FocusService service = mock(FocusService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        login(ME);
        when(service.submit(anyLong(), anyLong(), any())).thenReturn(new FocusStatusResponse(FocusState.FOCUS, UPDATED));
        mvc = ConversationTestSupport.mvc(new FocusController(service));
    }

    @AfterEach
    void tearDown() {
        logout();
    }

    private ResultActions submit(String json) throws Exception {
        return mvc.perform(post("/api/sessions/{id}/focus", SESSION).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private static String row(String exeJson, int idle, int switches) {
        return "{\"exeName\":" + exeJson + ",\"idleSec\":" + idle + ",\"switchCount\":" + switches + "}";
    }

    private static String rows(int count) {
        StringJoiner joined = new StringJoiner(",", "{\"rows\":[", "]}");
        for (int i = 0; i < count; i++) {
            joined.add(row("\"code.exe\"", 5, 1));
        }
        return joined.toString();
    }

    private static JsonNode body(ResultActions result) throws Exception {
        return JSON.readTree(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static String text(ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ submit

    @Test
    void validSubmitReturnsTheCallersState() throws Exception {
        JsonNode response = body(submit(rows(1)).andExpect(status().isOk()));

        assertThat(response.get("state").asString()).isEqualTo("FOCUS");
        assertThat(response.get("updatedAt").asString()).isEqualTo("2026-11-26T03:15:42Z");
        ArgumentCaptor<FocusSubmitRequest> captor = ArgumentCaptor.forClass(FocusSubmitRequest.class);
        verify(service).submit(eq(ME), eq(SESSION), captor.capture());
        assertThat(captor.getValue().rows()).hasSize(1);
        assertThat(captor.getValue().rows().getFirst().exeName()).isEqualTo("code.exe");
        assertThat(captor.getValue().rows().getFirst().idleSec()).isEqualTo(5);
        assertThat(captor.getValue().rows().getFirst().switchCount()).isEqualTo(1);
    }

    @Test
    void userIdInTheBodyIsIgnored() throws Exception {
        submit("{\"userId\":99,\"rows\":[" + row("\"code.exe\"", 5, 1) + "]}").andExpect(status().isOk());

        verify(service).submit(eq(ME), eq(SESSION), any());
        verify(service, never()).submit(eq(99L), anyLong(), any());
    }

    @Test
    void fifteenRowsAndBoundaryValuesAreAccepted() throws Exception {
        submit(rows(15)).andExpect(status().isOk());
        submit("{\"rows\":[" + row("null", 0, 0) + "," + row("null", 60, 120) + ","
                + row("\"" + "a".repeat(64) + ".exe\"", 1, 1) + "," + row("\"my-app_v2.1.exe\"", 1, 1) + "]}")
                .andExpect(status().isOk());
    }

    @Test
    void sixteenRowsIs400() throws Exception {
        submit(rows(16)).andExpect(status().isBadRequest());

        verify(service, never()).submit(anyLong(), anyLong(), any());
    }

    @Test
    void emptyOrMissingRowsIs400() throws Exception {
        submit("{\"rows\":[]}").andExpect(status().isBadRequest());
        submit("{}").andExpect(status().isBadRequest());
        submit("{\"rows\":null}").andExpect(status().isBadRequest());

        verify(service, never()).submit(anyLong(), anyLong(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"exeName\":\"code.exe\",\"idleSec\":61,\"switchCount\":0}",
            "{\"exeName\":\"code.exe\",\"idleSec\":-1,\"switchCount\":0}",
            "{\"exeName\":\"code.exe\",\"idleSec\":0,\"switchCount\":121}",
            "{\"exeName\":\"code.exe\",\"idleSec\":0,\"switchCount\":-1}",
    })
    void numbersOutOfRangeAre400(String badRow) throws Exception {
        submit("{\"rows\":[" + badRow + "]}").andExpect(status().isBadRequest());

        verify(service, never()).submit(anyLong(), anyLong(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "C:\\\\Users\\\\secretuser\\\\secretapp.exe",
            "/home/secretuser/secretapp.exe",
            "..\\\\secretapp.exe",
            "SecretApp.exe",
            "secretapp.EXE",
            "secretapp",
            "secretapp.exe.txt",
            "secret app.exe",
            ".exe",
            "secretaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.exe",
    })
    void badExeNamesAre400WithoutEchoingTheValue(String exe) throws Exception {
        String response = text(submit("{\"rows\":[" + row("\"" + exe + "\"", 1, 1) + "]}")
                .andExpect(status().isBadRequest()));

        assertThat(response).doesNotContain("secret");
        verify(service, never()).submit(anyLong(), anyLong(), any());
    }

    @ParameterizedTest
    @ValueSource(ints = {404, 410, 403, 429})
    void serviceErrorsKeepTheirStatus(int code) throws Exception {
        HttpStatus status = HttpStatus.valueOf(code);
        when(service.submit(anyLong(), anyLong(), any())).thenThrow(new ApiException(status, "x"));

        submit(rows(1)).andExpect(status().is(code));
    }

    // ------------------------------------------------------------------ read

    @Test
    void teacherListHasOnlyUserIdStateAndUpdatedAt() throws Exception {
        when(service.list(ME, SESSION)).thenReturn(List.of(
                new StudentFocus(4L, FocusState.DISTRACTED, UPDATED),
                new StudentFocus(5L, FocusState.NOT_PARTICIPATING, null)));

        JsonNode list = body(mvc.perform(get("/api/sessions/{id}/focus", SESSION)).andExpect(status().isOk()));

        assertThat(list.isArray()).isTrue();
        assertThat(list.size()).isEqualTo(2);
        assertThat(list.get(0).get("userId").asLong()).isEqualTo(4L);
        assertThat(list.get(0).get("state").asString()).isEqualTo("DISTRACTED");
        for (JsonNode item : list) {
            assertThat(item.propertyNames()).isSubsetOf("userId", "state", "updatedAt");
        }
        verify(service).list(ME, SESSION);
    }

    @Test
    void studentReadsOwnState() throws Exception {
        when(service.me(ME, SESSION)).thenReturn(new FocusStatusResponse(FocusState.PENDING, UPDATED));

        JsonNode me = body(mvc.perform(get("/api/sessions/{id}/focus/me", SESSION)).andExpect(status().isOk()));

        assertThat(me.get("state").asString()).isEqualTo("PENDING");
        assertThat(me.propertyNames()).isSubsetOf("state", "updatedAt");
    }

    @Test
    void readErrorsKeepTheirStatus() throws Exception {
        when(service.list(ME, SESSION)).thenThrow(new ApiException(HttpStatus.FORBIDDEN, "x"));
        when(service.me(ME, SESSION)).thenThrow(new ApiException(HttpStatus.GONE, "x"));

        mvc.perform(get("/api/sessions/{id}/focus", SESSION)).andExpect(status().isForbidden());
        mvc.perform(get("/api/sessions/{id}/focus/me", SESSION)).andExpect(status().isGone());
    }
}
