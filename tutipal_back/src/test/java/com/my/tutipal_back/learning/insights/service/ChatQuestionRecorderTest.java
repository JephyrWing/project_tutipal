package com.my.tutipal_back.learning.insights.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import com.my.tutipal_back.learning.insights.repository.ChatQuestionStore;
import com.my.tutipal_back.learning.tutor.service.StudentQuestionSink;

/**
 * PT-87 AC-6 (recorder side): strip, cut to 1000 code points, skip blank, never break the chat, never log the text.
 * Which turns reach the recorder (STUDENT + sessionId, no variant) is tested in TutorServiceQuestionSinkTest;
 * the participant / open-session condition of the INSERT is tested in ConceptInsightsPostgresTest.
 */
@ExtendWith(OutputCaptureExtension.class)
class ChatQuestionRecorderTest {

    private static final long USER = 21L;
    private static final long SESSION = 10L;
    private static final String MARKER = "SECRET-Q-5150";

    private final ChatQuestionStore store = mock(ChatQuestionStore.class);
    private final ChatQuestionRecorder recorder = new ChatQuestionRecorder(store);

    @Test
    void isTheStudentQuestionSinkImplementation() {
        assertThat(recorder).isInstanceOf(StudentQuestionSink.class);
    }

    @Test
    void storesTheStrippedQuestion() {
        recorder.record(USER, SESSION, "  \n변수가 뭐예요? " + MARKER + "\t ");

        verify(store).insertIfActiveStudent(SESSION, USER, "변수가 뭐예요? " + MARKER);
    }

    @Test
    void blankQuestionIsNotStored() {
        recorder.record(USER, SESSION, "   \n\t ");
        recorder.record(USER, SESSION, "");

        verify(store, never()).insertIfActiveStudent(anyLong(), anyLong(), anyString());
    }

    @Test
    void longQuestionIsCutToOneThousandCodePoints() {
        recorder.record(USER, SESSION, "가".repeat(1000) + "나머지");

        verify(store).insertIfActiveStudent(SESSION, USER, "가".repeat(1000));
    }

    @Test
    void cuttingCountsCodePointsNotUtf16Units() {
        String emoji = "😀";   // one code point, two chars
        ArgumentCaptor<String> saved = ArgumentCaptor.forClass(String.class);

        recorder.record(USER, SESSION, emoji.repeat(1001));

        verify(store).insertIfActiveStudent(eq(SESSION), eq(USER), saved.capture());
        assertThat(saved.getValue().codePointCount(0, saved.getValue().length())).isEqualTo(1000);
        assertThat(saved.getValue()).isEqualTo(emoji.repeat(1000));
    }

    @Test
    void exactlyOneThousandCodePointsIsKept() {
        recorder.record(USER, SESSION, "a".repeat(1000));

        verify(store).insertIfActiveStudent(SESSION, USER, "a".repeat(1000));
    }

    @Test
    void storeFailureIsSwallowedAndLogHasNoQuestionTextOrDatabaseMessage(CapturedOutput output) {
        when(store.insertIfActiveStudent(anyLong(), anyLong(), anyString()))
                .thenThrow(new DataAccessResourceFailureException("insert failed for value " + MARKER));

        assertThatCode(() -> recorder.record(USER, SESSION, "반복문 질문 " + MARKER)).doesNotThrowAnyException();

        assertThat(output.getAll()).doesNotContain(MARKER);
        assertThat(output.getAll()).contains("DataAccessResourceFailureException");
    }

    @Test
    void zeroRowsInsertedIsNotAnError(CapturedOutput output) {
        when(store.insertIfActiveStudent(anyLong(), anyLong(), anyString())).thenReturn(0);

        assertThatCode(() -> recorder.record(USER, SESSION, "끝난 수업 질문 " + MARKER)).doesNotThrowAnyException();

        assertThat(output.getAll()).doesNotContain(MARKER);
    }
}
