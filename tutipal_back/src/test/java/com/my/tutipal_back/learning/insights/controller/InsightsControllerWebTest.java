package com.my.tutipal_back.learning.insights.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.my.tutipal_back.ai.client.AiUnavailableException;
import com.my.tutipal_back.common.exception.ApiException;
import com.my.tutipal_back.common.exception.GlobalExceptionHandler;
import com.my.tutipal_back.learning.insights.dto.ConfusionsResponse;
import com.my.tutipal_back.learning.insights.dto.MasteryResponse;
import com.my.tutipal_back.learning.insights.service.MasteryService;
import com.my.tutipal_back.learning.insights.service.TopicAnalysisService;

/** PT-87 AC-15/16/17 over HTTP: GET mastery, POST confusions. Teacher of that session only. */
class InsightsControllerWebTest {

    private static final long ME = 2L;
    private static final long SESSION = 10L;
    private static final String MARKER = "SECRET-Q-7731";

    private final TopicAnalysisService topics = mock(TopicAnalysisService.class);
    private final MasteryService mastery = mock(MasteryService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        var principal = new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("ROLE_USER")), Map.of("id", "2"), "id");
        var login = UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(login);
        mvc = MockMvcBuilders.standaloneSetup(new InsightsController(topics, mastery))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void clearLogin() {
        SecurityContextHolder.clearContext();
    }

    private static ConfusionsResponse okResponse() {
        return new ConfusionsResponse("OK", null, 22, 6, "kmeans-silhouette-v2", 5, 0.42, false, List.of(
                new ConfusionsResponse.Topic(1, "반복문", 200L, 9, 5),
                new ConfusionsResponse.Topic(2, "태그되지 않은 주제 1", null, 4, 4),
                new ConfusionsResponse.Topic(3, "변수", 100L, 4, 3)));
    }

    private static MasteryResponse masteryResponse() {
        return new MasteryResponse(
                List.of(new MasteryResponse.ConceptColumn(100L, "변수", 2, 65, false),
                        new MasteryResponse.ConceptColumn(300L, "함수", 0, null, false)),
                List.of(new MasteryResponse.StudentRow(21L, "김학생", List.of(
                        new MasteryResponse.Cell(100L, 65, 1, true),
                        new MasteryResponse.Cell(300L, null, 0, true)))));
    }

    // ------------------------------------------------------------------ AC-16

    @Test
    void sameSessionStudentGets403OnBothEndpoints() throws Exception {
        when(topics.analyze(ME, SESSION)).thenThrow(new ApiException(HttpStatus.FORBIDDEN, "이 방의 선생님만 할 수 있어요"));
        when(mastery.mastery(ME, SESSION)).thenThrow(new ApiException(HttpStatus.FORBIDDEN, "이 방의 선생님만 할 수 있어요"));

        mvc.perform(post("/api/sessions/10/insights/confusions")).andExpect(status().isForbidden());
        mvc.perform(get("/api/sessions/10/insights/mastery")).andExpect(status().isForbidden());
    }

    @Test
    void otherSessionOrMissingSessionGets404OnBothEndpoints() throws Exception {
        when(topics.analyze(ME, SESSION)).thenThrow(new ApiException(HttpStatus.NOT_FOUND, "방을 찾을 수 없어요"));
        when(mastery.mastery(ME, SESSION)).thenThrow(new ApiException(HttpStatus.NOT_FOUND, "방을 찾을 수 없어요"));

        mvc.perform(post("/api/sessions/10/insights/confusions")).andExpect(status().isNotFound());
        mvc.perform(get("/api/sessions/10/insights/mastery")).andExpect(status().isNotFound());
    }

    @Test
    void callerIdComesFromTheLoginNotFromParameters() throws Exception {
        when(mastery.mastery(ME, SESSION)).thenReturn(masteryResponse());

        mvc.perform(get("/api/sessions/10/insights/mastery?userId=99")).andExpect(status().isOk());
        verify(mastery).mastery(ME, SESSION);
    }

    // ------------------------------------------------------------------ confusions

    @Test
    void confusionsIsPostOnly() throws Exception {
        mvc.perform(get("/api/sessions/10/insights/confusions")).andExpect(status().isMethodNotAllowed());
        verifyNoInteractions(topics);
    }

    @Test
    void confusionsReturnsTopThreeWithoutStudentIdentifiersOrQuestionText() throws Exception {
        when(topics.analyze(ME, SESSION)).thenReturn(okResponse());

        mvc.perform(post("/api/sessions/10/insights/confusions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OK"))
                .andExpect(jsonPath("$.questionCount").value(22))
                .andExpect(jsonPath("$.studentCount").value(6))
                .andExpect(jsonPath("$.algorithm").value("kmeans-silhouette-v2"))
                .andExpect(jsonPath("$.k").value(5))
                .andExpect(jsonPath("$.silhouette").value(0.42))
                .andExpect(jsonPath("$.cached").value(false))
                .andExpect(jsonPath("$.topics.length()").value(3))
                .andExpect(jsonPath("$.topics[0].rank").value(1))
                .andExpect(jsonPath("$.topics[0].label").value("반복문"))
                .andExpect(jsonPath("$.topics[0].conceptId").value(200))
                .andExpect(jsonPath("$.topics[0].questionCount").value(9))
                .andExpect(jsonPath("$.topics[0].studentCount").value(5))
                .andExpect(jsonPath("$.topics[1].conceptId").doesNotExist())
                .andExpect(content().string(not(containsString("userId"))))
                .andExpect(content().string(not(containsString("members"))))
                .andExpect(content().string(not(containsString(MARKER))));
    }

    @Test
    void insufficientDataIsA200WithReason() throws Exception {
        when(topics.analyze(ME, SESSION)).thenReturn(new ConfusionsResponse("INSUFFICIENT_DATA", "FEW_QUESTIONS", 9, 3,
                "kmeans-silhouette-v2", null, null, false, List.of()));

        mvc.perform(post("/api/sessions/10/insights/confusions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INSUFFICIENT_DATA"))
                .andExpect(jsonPath("$.reason").value("FEW_QUESTIONS"))
                .andExpect(jsonPath("$.topics.length()").value(0));
    }

    // ------------------------------------------------------------------ AC-17: AI down does not touch mastery

    @Test
    void aiUnavailableIs503AndMasteryStillWorks() throws Exception {
        when(topics.analyze(ME, SESSION)).thenThrow(new AiUnavailableException());
        when(mastery.mastery(ME, SESSION)).thenReturn(masteryResponse());

        mvc.perform(post("/api/sessions/10/insights/confusions"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503));
        mvc.perform(get("/api/sessions/10/insights/mastery"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.students[0].cells[0].pKnown").value(65));
    }

    // ------------------------------------------------------------------ AC-15 mastery shape

    @Test
    void masteryHasConceptColumnsAndStudentCells() throws Exception {
        when(mastery.mastery(ME, SESSION)).thenReturn(masteryResponse());

        mvc.perform(get("/api/sessions/10/insights/mastery"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.concepts[0].id").value(100))
                .andExpect(jsonPath("$.concepts[0].name").value("변수"))
                .andExpect(jsonPath("$.concepts[0].quizCount").value(2))
                .andExpect(jsonPath("$.concepts[0].classAvg").value(65))
                .andExpect(jsonPath("$.concepts[0].belowHalf").value(false))
                .andExpect(jsonPath("$.concepts[1].classAvg").doesNotExist())
                .andExpect(jsonPath("$.students[0].userId").value(21))
                .andExpect(jsonPath("$.students[0].name").value("김학생"))
                .andExpect(jsonPath("$.students[0].cells[0].conceptId").value(100))
                .andExpect(jsonPath("$.students[0].cells[0].pKnown").value(65))
                .andExpect(jsonPath("$.students[0].cells[0].observations").value(1))
                .andExpect(jsonPath("$.students[0].cells[0].lowConfidence").value(true))
                .andExpect(jsonPath("$.students[0].cells[1].pKnown").doesNotExist())
                .andExpect(content().string(not(containsString(MARKER))));
    }
}
