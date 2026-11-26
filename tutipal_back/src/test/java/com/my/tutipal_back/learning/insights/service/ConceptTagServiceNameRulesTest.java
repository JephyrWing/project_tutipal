package com.my.tutipal_back.learning.insights.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
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
import com.my.tutipal_back.learning.insights.repository.ConceptStore;
import com.my.tutipal_back.learning.quiz.entity.Quiz;
import com.my.tutipal_back.learning.quiz.entity.QuizType;
import com.my.tutipal_back.learning.quiz.repository.QuizRepository;
import com.my.tutipal_back.sessions.service.StudySessionService;

/**
 * PT-87 Round 1 F6, F7, F9, F30, F31: name rules that must end in 400 (never a database error / 409), the 50-concept
 * limit counted after the replace, and one 404 text for missing and foreign quizzes.
 */
class ConceptTagServiceNameRulesTest {

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
        Quiz quiz = new Quiz(SESSION, QuizType.CHOICE, "변수란?", List.of("가", "나"), 0, null,
                Instant.parse("2026-11-25T01:00:00Z"));
        ReflectionTestUtils.setField(quiz, "id", QUIZ);
        when(quizzes.findById(QUIZ)).thenReturn(Optional.of(quiz));
        when(store.sessionConcepts(SESSION)).thenReturn(List.of());
        when(store.releasableConcepts(SESSION, QUIZ)).thenReturn(List.of());
        when(store.replaceQuizConcepts(anyLong(), anyLong(), anyList(), anyString())).thenReturn(List.of());
    }

    private void assertBadRequestWithoutWrite(List<String> requested) {
        assertThatThrownBy(() -> service.replace(TEACHER, QUIZ, new ConceptNamesRequest(requested, null)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        verify(store, never()).replaceQuizConcepts(anyLong(), anyLong(), anyList(), anyString());
    }

    private static List<ConceptView> concepts(int count) {
        return IntStream.rangeClosed(1, count).mapToObj(i -> new ConceptView((long) i, "개념 " + i)).toList();
    }

    // ------------------------------------------------------------------ F6 / F7: 400 instead of a DB error

    @Test
    void nameWhoseLowerCaseGrowsPastThirtyCodePointsIsBadRequest() {
        // 'İ' (U+0130) lower-cases to "i" + U+0307 in Locale.ROOT: 30 code points become 60
        assertBadRequestWithoutWrite(List.of("İ".repeat(30)));
    }

    @Test
    void controlCharactersAreBadRequest() {
        assertBadRequestWithoutWrite(List.of("변\0수"));
        assertBadRequestWithoutWrite(List.of("반복\007문"));
    }

    @Test
    void tabAndNewlineAreBadRequestEvenAtTheEnds() {
        assertBadRequestWithoutWrite(List.of("반복\t문"));
        assertBadRequestWithoutWrite(List.of("변수\n"));
        assertBadRequestWithoutWrite(List.of("\r\n함수"));
    }

    @Test
    void loneSurrogateIsBadRequest() {
        assertBadRequestWithoutWrite(List.of("변수" + '\uD800'));
    }

    // ------------------------------------------------------------------ F31: same whitespace rule as the screen

    @Test
    void noBreakAndIdeographicSpacesAreTrimmedAndCollapsed() {
        service.replace(TEACHER, QUIZ, new ConceptNamesRequest(
                List.of("　반복문 ", "For  Loop", "for loop"), null));

        verify(store).replaceQuizConcepts(eq(SESSION), eq(QUIZ), names.capture(), anyString());
        assertThat(names.getValue()).containsExactly("반복문", "For Loop");
    }

    // ------------------------------------------------------------------ F9: limit counted after the replace

    @Test
    void conceptOnlyThisQuizUsedDoesNotBlockAReplacementAtTheLimit() {
        List<ConceptView> fifty = concepts(50);
        when(store.sessionConcepts(SESSION)).thenReturn(fifty);
        when(store.releasableConcepts(SESSION, QUIZ)).thenReturn(List.of(fifty.get(49)));

        service.replace(TEACHER, QUIZ, new ConceptNamesRequest(List.of("새 개념"), null));

        verify(store).replaceQuizConcepts(eq(SESSION), eq(QUIZ), eq(List.of("새 개념")), anyString());
    }

    @Test
    void releasableConceptThatIsKeptStillCounts() {
        List<ConceptView> fifty = concepts(50);
        when(store.sessionConcepts(SESSION)).thenReturn(fifty);
        when(store.releasableConcepts(SESSION, QUIZ)).thenReturn(List.of(fifty.get(49)));

        assertBadRequestWithoutWrite(List.of("개념 50", "새 개념"));
    }

    // ------------------------------------------------------------------ F30: no quiz-id existence oracle

    @Test
    void missingQuizUsesTheSameNotFoundTextAsAForeignSession() {
        when(quizzes.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.replace(TEACHER, 404L, new ConceptNamesRequest(List.of("변수"), null)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(e.getMessage()).isEqualTo("방을 찾을 수 없어요");   // StudySessionService text
                });
    }
}
