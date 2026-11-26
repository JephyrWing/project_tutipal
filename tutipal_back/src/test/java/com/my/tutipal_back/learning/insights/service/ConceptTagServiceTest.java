package com.my.tutipal_back.learning.insights.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import com.my.tutipal_back.common.exception.ApiException;
import com.my.tutipal_back.learning.insights.dto.ConceptNamesRequest;
import com.my.tutipal_back.learning.insights.dto.ConceptView;
import com.my.tutipal_back.learning.insights.dto.QuizConceptView;
import com.my.tutipal_back.learning.insights.repository.ConceptStore;
import com.my.tutipal_back.learning.quiz.entity.Quiz;
import com.my.tutipal_back.learning.quiz.entity.QuizType;
import com.my.tutipal_back.learning.quiz.repository.QuizRepository;
import com.my.tutipal_back.sessions.service.StudySessionService;

/** PT-87 AC-2..4: concept tags per quiz, teacher of the quiz's session only. */
class ConceptTagServiceTest {

    private static final long TEACHER = 1L;
    private static final long SESSION = 10L;
    private static final long QUIZ = 77L;

    private final StudySessionService sessions = mock(StudySessionService.class);
    private final QuizRepository quizzes = mock(QuizRepository.class);
    private final ConceptStore store = mock(ConceptStore.class);
    private final ConceptTagService service = new ConceptTagService(sessions, quizzes, store);

    @SuppressWarnings("unchecked")
    private final ArgumentCaptor<List<String>> names = ArgumentCaptor.forClass(List.class);

    @BeforeEach
    void quizInSession() {
        Quiz quiz = new Quiz(SESSION, QuizType.CHOICE, "변수란?", List.of("가", "나", "다", "라"), 0, null,
                Instant.parse("2026-11-25T01:00:00Z"));
        ReflectionTestUtils.setField(quiz, "id", QUIZ);
        when(quizzes.findById(QUIZ)).thenReturn(Optional.of(quiz));
        when(store.sessionConcepts(SESSION)).thenReturn(List.of());
        when(store.replaceQuizConcepts(anyLong(), anyLong(), anyList(), anyString())).thenReturn(List.of());
    }

    private static void assertStatus(Throwable t, HttpStatus status) {
        assertThat(t).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(status));
    }

    // ------------------------------------------------------------------ AC-4 permission

    @Test
    void replaceChecksTeacherOfTheQuizSessionBeforeWriting() {
        service.replace(TEACHER, QUIZ, new ConceptNamesRequest(List.of("변수"), null));

        verify(sessions).requireTeacherOf(TEACHER, SESSION);
        verify(store).replaceQuizConcepts(eq(SESSION), eq(QUIZ), anyList(), anyString());
    }

    @Test
    void studentOfTheSameSessionIsForbiddenAndNothingIsWritten() {
        doThrow(new ApiException(HttpStatus.FORBIDDEN, "선생님만")).when(sessions).requireTeacherOf(TEACHER, SESSION);

        assertThatThrownBy(() -> service.replace(TEACHER, QUIZ, new ConceptNamesRequest(List.of("변수"), null)))
                .satisfies(t -> assertStatus(t, HttpStatus.FORBIDDEN));
        verify(store, never()).replaceQuizConcepts(anyLong(), anyLong(), anyList(), anyString());
    }

    @Test
    void teacherOfAnotherSessionGetsNotFound() {
        doThrow(new ApiException(HttpStatus.NOT_FOUND, "방을 찾을 수 없어요")).when(sessions)
                .requireTeacherOf(TEACHER, SESSION);

        assertThatThrownBy(() -> service.replace(TEACHER, QUIZ, new ConceptNamesRequest(List.of("변수"), null)))
                .satisfies(t -> assertStatus(t, HttpStatus.NOT_FOUND));
        verify(store, never()).replaceQuizConcepts(anyLong(), anyLong(), anyList(), anyString());
    }

    @Test
    void missingQuizIsNotFound() {
        when(quizzes.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.replace(TEACHER, 404L, new ConceptNamesRequest(List.of("변수"), null)))
                .satisfies(t -> assertStatus(t, HttpStatus.NOT_FOUND));
        verify(store, never()).replaceQuizConcepts(anyLong(), anyLong(), anyList(), anyString());
    }

    @Test
    void listingConceptsChecksTeacherFirst() {
        doThrow(new ApiException(HttpStatus.FORBIDDEN, "선생님만")).when(sessions).requireTeacherOf(TEACHER, SESSION);

        assertThatThrownBy(() -> service.sessionConcepts(TEACHER, SESSION))
                .satisfies(t -> assertStatus(t, HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> service.quizConcepts(TEACHER, SESSION))
                .satisfies(t -> assertStatus(t, HttpStatus.FORBIDDEN));
        verify(store, never()).sessionConcepts(anyLong());
        verify(store, never()).quizConcepts(anyLong());
    }

    @Test
    void listingReturnsWhatTheStoreHas() {
        when(store.sessionConcepts(SESSION)).thenReturn(List.of(new ConceptView(5L, "변수")));
        when(store.quizConcepts(SESSION)).thenReturn(Map.of(QUIZ, List.of(new QuizConceptView(5L, "변수", "TEACHER"))));

        assertThat(service.sessionConcepts(TEACHER, SESSION)).containsExactly(new ConceptView(5L, "변수"));
        assertThat(service.quizConcepts(TEACHER, SESSION))
                .containsEntry(QUIZ, List.of(new QuizConceptView(5L, "변수", "TEACHER")));
    }

    // ------------------------------------------------------------------ AC-2 full replace

    @Test
    void replaceSendsTheWholeSetInOneCall() {
        service.replace(TEACHER, QUIZ, new ConceptNamesRequest(List.of("변수", "반복문"), "TEACHER"));

        verify(store).replaceQuizConcepts(eq(SESSION), eq(QUIZ), names.capture(), eq("TEACHER"));
        assertThat(names.getValue()).containsExactly("변수", "반복문");
    }

    @Test
    void emptyListClearsTheTags() {
        service.replace(TEACHER, QUIZ, new ConceptNamesRequest(List.of(), null));

        verify(store).replaceQuizConcepts(eq(SESSION), eq(QUIZ), names.capture(), anyString());
        assertThat(names.getValue()).isEmpty();
    }

    @Test
    void sameRequestTwiceSendsTheSameSet() {
        service.replace(TEACHER, QUIZ, new ConceptNamesRequest(List.of("변수", "반복문"), null));
        service.replace(TEACHER, QUIZ, new ConceptNamesRequest(List.of("변수", "반복문"), null));

        verify(store, times(2)).replaceQuizConcepts(eq(SESSION), eq(QUIZ), names.capture(), anyString());
        assertThat(names.getAllValues().get(1)).isEqualTo(names.getAllValues().get(0));
    }

    @Test
    void missingSourceMeansTeacherAndAiIsKept() {
        service.replace(TEACHER, QUIZ, new ConceptNamesRequest(List.of("변수"), null));
        verify(store).replaceQuizConcepts(eq(SESSION), eq(QUIZ), anyList(), eq("TEACHER"));

        service.replace(TEACHER, QUIZ, new ConceptNamesRequest(List.of("반복문"), "AI"));
        verify(store).replaceQuizConcepts(eq(SESSION), eq(QUIZ), anyList(), eq("AI"));
    }

    // ------------------------------------------------------------------ AC-3 rules (count, length and source
    // are also checked through the controller in ConceptControllerWebTest)

    @Test
    void caseAndSpacesVariantsAreMergedAndNamesAreTrimmed() {
        service.replace(TEACHER, QUIZ, new ConceptNamesRequest(List.of(" 반복문 ", "반복문", "For  Loop"), null));

        verify(store).replaceQuizConcepts(eq(SESSION), eq(QUIZ), names.capture(), anyString());
        assertThat(names.getValue()).hasSize(2);
        assertThat(names.getValue().get(0)).isEqualTo("반복문");
        assertThat(names.getValue()).allSatisfy(n -> assertThat(n).isEqualTo(n.strip()));
    }

    @Test
    void upperAndLowerCaseAreTheSameConcept() {
        service.replace(TEACHER, QUIZ, new ConceptNamesRequest(List.of("FOR LOOP", "for loop"), null));

        verify(store).replaceQuizConcepts(eq(SESSION), eq(QUIZ), names.capture(), anyString());
        assertThat(names.getValue()).hasSize(1);
    }

    @Test
    void thirtyCharacterNameIsAccepted() {
        service.replace(TEACHER, QUIZ, new ConceptNamesRequest(List.of("가".repeat(30)), null));

        verify(store).replaceQuizConcepts(eq(SESSION), eq(QUIZ), names.capture(), anyString());
        assertThat(names.getValue()).containsExactly("가".repeat(30));
    }

    @Test
    void newConceptBeyondFiftyPerSessionIsBadRequest() {
        List<ConceptView> existing = IntStream.rangeClosed(1, 50)
                .mapToObj(i -> new ConceptView((long) i, "개념 " + i)).toList();
        when(store.sessionConcepts(SESSION)).thenReturn(existing);

        assertThatThrownBy(() -> service.replace(TEACHER, QUIZ, new ConceptNamesRequest(List.of("새 개념"), null)))
                .satisfies(t -> assertStatus(t, HttpStatus.BAD_REQUEST));
        verify(store, never()).replaceQuizConcepts(anyLong(), anyLong(), anyList(), anyString());
    }

    @Test
    void reusingExistingConceptsAtTheLimitIsAllowedEvenWithDifferentCase() {
        List<ConceptView> existing = new ArrayList<>(IntStream.rangeClosed(1, 49)
                .mapToObj(i -> new ConceptView((long) i, "개념 " + i)).toList());
        existing.add(new ConceptView(50L, "For Loop"));
        when(store.sessionConcepts(SESSION)).thenReturn(existing);

        service.replace(TEACHER, QUIZ, new ConceptNamesRequest(List.of("개념 1", "for loop"), null));

        verify(store).replaceQuizConcepts(eq(SESSION), eq(QUIZ), anyList(), anyString());
    }

    @Test
    void reachingExactlyFiftyIsAllowed() {
        List<ConceptView> existing = IntStream.rangeClosed(1, 49)
                .mapToObj(i -> new ConceptView((long) i, "개념 " + i)).toList();
        when(store.sessionConcepts(SESSION)).thenReturn(existing);

        service.replace(TEACHER, QUIZ, new ConceptNamesRequest(List.of("오십번째"), null));

        verify(store).replaceQuizConcepts(eq(SESSION), eq(QUIZ), any(), anyString());
    }
}
