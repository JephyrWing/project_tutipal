package com.my.tutipal_back.learning.insights.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.my.tutipal_back.common.exception.ApiException;
import com.my.tutipal_back.learning.insights.dto.MasteryResponse;
import com.my.tutipal_back.learning.insights.repository.MasteryStore;
import com.my.tutipal_back.learning.quiz.entity.QuizType;
import com.my.tutipal_back.sessions.service.StudySessionService;

/**
 * PT-87 AC-9..15: mastery is recomputed from the stored answers on every request (nothing saved),
 * per student x concept, for current STUDENT participants only.
 */
class MasteryServiceTest {

    private static final long TEACHER = 1L;
    private static final long SESSION = 10L;
    private static final long VARS = 100L;
    private static final long LOOPS = 200L;
    private static final long FUNCS = 300L;
    private static final long KIM = 21L;
    private static final long LEE = 22L;
    private static final long LEFT = 99L;

    private final StudySessionService sessions = mock(StudySessionService.class);
    private final MasteryStore store = mock(MasteryStore.class);
    private final MasteryService service = new MasteryService(sessions, store);

    private static final List<MasteryStore.ConceptInfo> CONCEPTS = List.of(
            new MasteryStore.ConceptInfo(VARS, "변수", 2),
            new MasteryStore.ConceptInfo(LOOPS, "반복문", 1),
            new MasteryStore.ConceptInfo(FUNCS, "함수", 0));
    private static final List<MasteryStore.StudentInfo> STUDENTS = List.of(
            new MasteryStore.StudentInfo(KIM, "김학생"),
            new MasteryStore.StudentInfo(LEE, "이학생"));

    private static MasteryStore.Observation choice(long user, long concept, Boolean correct) {
        return new MasteryStore.Observation(user, concept, QuizType.CHOICE, 4, correct);
    }

    private static MasteryStore.Observation text(long user, long concept, Boolean correct) {
        return new MasteryStore.Observation(user, concept, QuizType.TEXT, 0, correct);
    }

    private MasteryResponse load(List<MasteryStore.Observation> observations) {
        when(store.load(SESSION)).thenReturn(new MasteryStore.Snapshot(CONCEPTS, STUDENTS, observations));
        return service.mastery(TEACHER, SESSION);
    }

    private static MasteryResponse.Cell cell(MasteryResponse response, long userId, long conceptId) {
        return response.students().stream()
                .filter(s -> s.userId() == userId).findFirst().orElseThrow()
                .cells().stream().filter(c -> c.conceptId() == conceptId).findFirst().orElseThrow();
    }

    private static int percent(double p) {
        return (int) Math.round(p * 100);
    }

    // ------------------------------------------------------------------ AC-16 permission (service level)

    @Test
    void permissionIsCheckedBeforeAnyRead() {
        doThrow(new ApiException(HttpStatus.FORBIDDEN, "선생님만")).when(sessions).requireTeacherOf(TEACHER, SESSION);

        assertThatThrownBy(() -> service.mastery(TEACHER, SESSION))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        verify(store, never()).load(anyLong());
    }

    @Test
    void unknownOrOtherSessionIsNotFound() {
        doThrow(new ApiException(HttpStatus.NOT_FOUND, "방을 찾을 수 없어요")).when(sessions)
                .requireTeacherOf(TEACHER, SESSION);

        assertThatThrownBy(() -> service.mastery(TEACHER, SESSION))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        verify(store, never()).load(anyLong());
    }

    // ------------------------------------------------------------------ AC-15 shape

    @Test
    void responseHasConceptColumnsAndOneCellPerStudentAndConceptInColumnOrder() {
        MasteryResponse response = load(List.of(choice(KIM, VARS, true)));

        assertThat(response.concepts()).extracting(MasteryResponse.ConceptColumn::id)
                .containsExactly(VARS, LOOPS, FUNCS);
        assertThat(response.concepts()).extracting(MasteryResponse.ConceptColumn::name)
                .containsExactly("변수", "반복문", "함수");
        assertThat(response.concepts()).extracting(MasteryResponse.ConceptColumn::quizCount)
                .containsExactly(2, 1, 0);
        assertThat(response.students()).extracting(MasteryResponse.StudentRow::userId).containsExactly(KIM, LEE);
        assertThat(response.students()).extracting(MasteryResponse.StudentRow::name).containsExactly("김학생", "이학생");
        for (MasteryResponse.StudentRow row : response.students()) {
            assertThat(row.cells()).extracting(MasteryResponse.Cell::conceptId).containsExactly(VARS, LOOPS, FUNCS);
        }
    }

    @Test
    void pKnownIsAnIntegerPercentOfTheBktEstimate() {
        MasteryResponse response = load(List.of(choice(KIM, VARS, true), text(LEE, VARS, false)));

        assertThat(cell(response, KIM, VARS).pKnown()).isEqualTo(65);   // 0.646067
        assertThat(cell(response, LEE, VARS).pKnown()).isEqualTo(14);   // 0.140909
        assertThat(cell(response, KIM, VARS).pKnown()).isBetween(0, 100);
    }

    @Test
    void conceptWithoutAnyObservationHasNoClassAverage() {
        MasteryResponse response = load(List.of(choice(KIM, VARS, true)));

        assertThat(response.concepts().get(2).classAvg()).isNull();
    }

    // ------------------------------------------------------------------ AC-9 direction

    @Test
    void correctAnswersRaiseAndWrongAnswersLowerTheCell() {
        MasteryResponse response = load(List.of(
                choice(KIM, VARS, true), choice(KIM, VARS, true),
                choice(LEE, VARS, false), choice(LEE, VARS, false)));

        assertThat(cell(response, KIM, VARS).pKnown()).isGreaterThan(30);
        assertThat(cell(response, LEE, VARS).pKnown()).isLessThan(30);
        assertThat(cell(response, KIM, VARS).pKnown()).isEqualTo(percent(0.881132));
    }

    @Test
    void observationsAreAppliedInTheGivenOrder() {
        MasteryResponse rightThenWrong = load(List.of(choice(KIM, VARS, true), choice(KIM, VARS, false)));
        assertThat(cell(rightThenWrong, KIM, VARS).pKnown()).isEqualTo(percent(0.276170));

        MasteryResponse wrongThenRight = load(List.of(choice(KIM, VARS, false), choice(KIM, VARS, true)));
        assertThat(cell(wrongThenRight, KIM, VARS).pKnown()).isEqualTo(percent(0.447368));
    }

    // ------------------------------------------------------------------ AC-11 correct NULL

    @Test
    void ungradedAnswersAreIgnored() {
        MasteryResponse response = load(List.of(
                choice(KIM, VARS, null), choice(KIM, VARS, true), text(KIM, VARS, null)));

        MasteryResponse.Cell c = cell(response, KIM, VARS);
        assertThat(c.observations()).isEqualTo(1);
        assertThat(c.pKnown()).isEqualTo(65);
    }

    @Test
    void onlyUngradedAnswersMeanNoEstimate() {
        MasteryResponse response = load(List.of(choice(KIM, VARS, null)));

        assertThat(cell(response, KIM, VARS).pKnown()).isNull();
        assertThat(cell(response, KIM, VARS).observations()).isZero();
    }

    // ------------------------------------------------------------------ AC-12 recompute after regrade

    @Test
    void regradeIsReflectedByRecomputingFromTheStart() {
        MasteryResponse before = load(List.of(choice(KIM, VARS, false), choice(KIM, VARS, true)));
        assertThat(cell(before, KIM, VARS).pKnown()).isEqualTo(percent(0.447368));

        // the teacher regrades the first answer to correct: the next request sees the new history only
        MasteryResponse after = load(List.of(choice(KIM, VARS, true), choice(KIM, VARS, true)));
        assertThat(cell(after, KIM, VARS).pKnown()).isEqualTo(percent(0.881132));
    }

    @Test
    void sameHistoryGivesSameResultOnEveryRequest() {
        List<MasteryStore.Observation> history = List.of(choice(KIM, VARS, true), text(KIM, VARS, false));
        MasteryResponse first = load(history);
        MasteryResponse second = load(history);

        assertThat(second).isEqualTo(first);
    }

    // ------------------------------------------------------------------ AC-13 multi tag

    @Test
    void oneAnswerTaggedWithTwoConceptsUpdatesEachConceptIndependently() {
        // quiz tagged 변수 + 반복문 -> one observation row per concept; 반복문 also has an extra wrong answer
        MasteryResponse response = load(List.of(
                choice(KIM, VARS, true), choice(KIM, LOOPS, true),
                choice(KIM, LOOPS, false)));

        assertThat(cell(response, KIM, VARS).pKnown()).isEqualTo(percent(0.646067));
        assertThat(cell(response, KIM, VARS).observations()).isEqualTo(1);
        assertThat(cell(response, KIM, LOOPS).pKnown()).isEqualTo(percent(0.276170));
        assertThat(cell(response, KIM, LOOPS).observations()).isEqualTo(2);
    }

    // ------------------------------------------------------------------ AC-10 untagged quiz

    @Test
    void conceptsWithoutObservationsStayEmptyWhenOtherAnswersExist() {
        // answers to untagged quizzes never become observations, so 함수 has nothing
        MasteryResponse response = load(List.of(choice(KIM, VARS, true), choice(LEE, LOOPS, false)));

        assertThat(cell(response, KIM, FUNCS).pKnown()).isNull();
        assertThat(cell(response, LEE, FUNCS).pKnown()).isNull();
        assertThat(cell(response, KIM, LOOPS).pKnown()).isNull();
    }

    // ------------------------------------------------------------------ AC-14 confidence and left students

    @Test
    void zeroObservationsGiveNullAndFewerThanThreeAreLowConfidence() {
        MasteryResponse response = load(List.of(
                choice(KIM, VARS, true),
                choice(KIM, LOOPS, true), choice(KIM, LOOPS, true),
                choice(LEE, VARS, true), choice(LEE, VARS, true), choice(LEE, VARS, false)));

        assertThat(cell(response, KIM, FUNCS).pKnown()).isNull();
        assertThat(cell(response, KIM, FUNCS).observations()).isZero();
        assertThat(cell(response, KIM, VARS).lowConfidence()).isTrue();
        assertThat(cell(response, KIM, LOOPS).lowConfidence()).isTrue();
        assertThat(cell(response, LEE, VARS).observations()).isEqualTo(3);
        assertThat(cell(response, LEE, VARS).lowConfidence()).isFalse();
        assertThat(cell(response, LEE, VARS).pKnown()).isEqualTo(percent(0.547366));
    }

    @Test
    void studentWhoLeftIsNotInTheTableEvenIfOldAnswersComeBack() {
        List<MasteryStore.Observation> observations = new ArrayList<>();
        observations.add(choice(LEFT, VARS, true));
        observations.add(choice(KIM, VARS, true));

        MasteryResponse response = load(observations);

        assertThat(response.students()).extracting(MasteryResponse.StudentRow::userId).containsExactly(KIM, LEE);
        assertThat(response.students()).noneMatch(s -> s.userId() == LEFT);
        assertThat(cell(response, KIM, VARS).pKnown()).isEqualTo(65);
    }
}
