package com.my.tutipal_back.learning.insights.controller;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.my.tutipal_back.common.exception.ApiException;
import com.my.tutipal_back.common.exception.GlobalExceptionHandler;
import com.my.tutipal_back.learning.insights.dto.ConceptView;
import com.my.tutipal_back.learning.insights.dto.QuizConceptView;
import com.my.tutipal_back.learning.insights.repository.ConceptStore;
import com.my.tutipal_back.learning.insights.service.ConceptTagService;
import com.my.tutipal_back.learning.quiz.entity.Quiz;
import com.my.tutipal_back.learning.quiz.entity.QuizType;
import com.my.tutipal_back.learning.quiz.repository.QuizRepository;
import com.my.tutipal_back.sessions.service.StudySessionService;

/**
 * PT-87 AC-2..4 over HTTP: GET concepts, GET quiz-concepts, PUT quiz concepts. Real ConceptTagService with mocked
 * session access and store (same standalone style as QuizGenerateQuotaWebTest).
 */
class ConceptControllerWebTest {

    private static final long ME = 2L;
    private static final long SESSION = 10L;
    private static final long QUIZ = 77L;

    private final StudySessionService sessions = mock(StudySessionService.class);
    private final QuizRepository quizzes = mock(QuizRepository.class);
    private final ConceptStore store = mock(ConceptStore.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        var principal = new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("ROLE_USER")), Map.of("id", "2"), "id");
        var login = UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(login);
        Quiz quiz = new Quiz(SESSION, QuizType.CHOICE, "변수란?", List.of("가", "나"), 0, null,
                Instant.parse("2026-11-25T01:00:00Z"));
        ReflectionTestUtils.setField(quiz, "id", QUIZ);
        when(quizzes.findById(QUIZ)).thenReturn(Optional.of(quiz));
        when(quizzes.findById(404L)).thenReturn(Optional.empty());
        when(store.sessionConcepts(SESSION)).thenReturn(List.of());
        when(store.replaceQuizConcepts(anyLong(), anyLong(), anyList(), anyString()))
                .thenReturn(List.of(new QuizConceptView(5L, "변수", "TEACHER")));
        mvc = MockMvcBuilders.standaloneSetup(new ConceptController(new ConceptTagService(sessions, quizzes, store)))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void clearLogin() {
        SecurityContextHolder.clearContext();
    }

    private ResultActions putNames(long quizId, String json) throws Exception {
        return mvc.perform(put("/api/quizzes/" + quizId + "/concepts")
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private void neverWritten() {
        verify(store, never()).replaceQuizConcepts(anyLong(), anyLong(), anyList(), anyString());
    }

    // ------------------------------------------------------------------ reads

    @Test
    void listsSessionConceptsForTheTeacher() throws Exception {
        when(store.sessionConcepts(SESSION)).thenReturn(List.of(new ConceptView(5L, "변수"), new ConceptView(6L, "반복문")));

        mvc.perform(get("/api/sessions/10/concepts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(5))
                .andExpect(jsonPath("$[0].name").value("변수"))
                .andExpect(jsonPath("$[1].name").value("반복문"));
        verify(sessions).requireTeacherOf(ME, SESSION);
    }

    @Test
    void listsTagsPerQuizKeyedByQuizId() throws Exception {
        when(store.quizConcepts(SESSION)).thenReturn(Map.of(QUIZ, List.of(new QuizConceptView(5L, "변수", "AI"))));

        mvc.perform(get("/api/sessions/10/quiz-concepts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$['77'][0].conceptId").value(5))
                .andExpect(jsonPath("$['77'][0].name").value("변수"))
                .andExpect(jsonPath("$['77'][0].source").value("AI"));
    }

    // ------------------------------------------------------------------ AC-4

    @Test
    void studentOfTheSameSessionGets403OnEveryConceptEndpoint() throws Exception {
        doThrow(new ApiException(HttpStatus.FORBIDDEN, "이 방의 선생님만 할 수 있어요"))
                .when(sessions).requireTeacherOf(ME, SESSION);

        mvc.perform(get("/api/sessions/10/concepts")).andExpect(status().isForbidden());
        mvc.perform(get("/api/sessions/10/quiz-concepts")).andExpect(status().isForbidden());
        putNames(QUIZ, "{\"names\":[\"변수\"]}").andExpect(status().isForbidden());
        neverWritten();
        verify(store, never()).sessionConcepts(anyLong());
        verify(store, never()).quizConcepts(anyLong());
    }

    @Test
    void otherSessionTeacherOrMissingSessionGets404() throws Exception {
        doThrow(new ApiException(HttpStatus.NOT_FOUND, "방을 찾을 수 없어요"))
                .when(sessions).requireTeacherOf(ME, SESSION);

        mvc.perform(get("/api/sessions/10/concepts")).andExpect(status().isNotFound());
        mvc.perform(get("/api/sessions/10/quiz-concepts")).andExpect(status().isNotFound());
        putNames(QUIZ, "{\"names\":[\"변수\"]}").andExpect(status().isNotFound());
        neverWritten();
    }

    @Test
    void missingQuizGets404() throws Exception {
        putNames(404L, "{\"names\":[\"변수\"]}").andExpect(status().isNotFound());
        neverWritten();
    }

    // ------------------------------------------------------------------ AC-2

    @Test
    void putReplacesTheWholeSetAndReturnsTheTags() throws Exception {
        putNames(QUIZ, "{\"names\":[\"변수\",\"반복문\"],\"source\":\"TEACHER\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].conceptId").value(5))
                .andExpect(jsonPath("$[0].name").value("변수"));
        verify(sessions).requireTeacherOf(ME, SESSION);
        verify(store).replaceQuizConcepts(eq(SESSION), eq(QUIZ), eq(List.of("변수", "반복문")), eq("TEACHER"));
    }

    @Test
    void putWithEmptyListIsAllowed() throws Exception {
        putNames(QUIZ, "{\"names\":[]}").andExpect(status().isOk());
        verify(store).replaceQuizConcepts(eq(SESSION), eq(QUIZ), eq(List.of()), eq("TEACHER"));
    }

    // ------------------------------------------------------------------ AC-3 -> 400

    @Test
    void moreThanThreeNamesIs400() throws Exception {
        putNames(QUIZ, "{\"names\":[\"가나\",\"다라\",\"마바\",\"사아\"]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
        neverWritten();
    }

    @Test
    void blankEmptyOrThirtyOneCharacterNameIs400() throws Exception {
        for (String name : List.of("", "   ", "가".repeat(31))) {
            putNames(QUIZ, "{\"names\":[\"" + name + "\"]}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));
        }
        neverWritten();
    }

    @Test
    void missingNamesOrNullNameIs400() throws Exception {
        putNames(QUIZ, "{}").andExpect(status().isBadRequest());
        putNames(QUIZ, "{\"names\":[null]}").andExpect(status().isBadRequest());
        neverWritten();
    }

    @Test
    void unknownSourceIs400() throws Exception {
        putNames(QUIZ, "{\"names\":[\"변수\"],\"source\":\"ROBOT\"}").andExpect(status().isBadRequest());
        neverWritten();
    }

    @Test
    void fiftyFirstConceptInTheSessionIs400() throws Exception {
        when(store.sessionConcepts(SESSION)).thenReturn(java.util.stream.IntStream.rangeClosed(1, 50)
                .mapToObj(i -> new ConceptView((long) i, "개념 " + i)).toList());

        putNames(QUIZ, "{\"names\":[\"새 개념\"]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
        neverWritten();
    }

    @Test
    void thirtyCharacterNameAndCaseVariantsAreAccepted() throws Exception {
        putNames(QUIZ, "{\"names\":[\"" + "가".repeat(30) + "\",\"FOR LOOP\",\"for loop\"]}")
                .andExpect(status().isOk());
        verify(store).replaceQuizConcepts(eq(SESSION), eq(QUIZ),
                org.mockito.ArgumentMatchers.argThat(list -> list.size() == 2), eq("TEACHER"));
    }
}
