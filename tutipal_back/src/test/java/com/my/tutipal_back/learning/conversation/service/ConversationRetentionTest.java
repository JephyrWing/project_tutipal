package com.my.tutipal_back.learning.conversation.service;

import static com.my.tutipal_back.learning.conversation.ConversationTestSupport.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.my.tutipal_back.ai.quota.MutableClock;
import com.my.tutipal_back.learning.conversation.ConversationProperties;
import com.my.tutipal_back.learning.conversation.repository.ConversationStore;
import com.my.tutipal_back.learning.conversation.repository.FakeConversationStore;

/**
 * PT-106 retention per the PT-13 PM decision (2026-11-26):
 * A. class conversations go ended_at + the session's ai_retention_days after the session ended (open sessions never),
 * with the session-scoped memories; B. personal conversations go personal-retention-days (30) after the last activity;
 * C. saved conversations are skipped by both. Soft delete: rows the user deleted are purged after deleted-purge-days.
 */
class ConversationRetentionTest {

    private static final long STUDENT = 2L;
    private static final long OPEN = 10L;
    private static final long ENDED_EXPIRED = 11L;
    private static final long ENDED_RECENT = 12L;
    private static final long ENDED_ZERO = 13L;
    private static final long LEGACY = 14L;

    private final MutableClock clock = new MutableClock(NOW);

    private static ConversationProperties personal(int days, int batch) {
        return new ConversationProperties(days, batch, ConversationProperties.defaults().summary());
    }

    private static Instant daysAgo(long days) {
        return NOW.minus(Duration.ofDays(days));
    }

    // ------------------------------------------------------------------ A. class retention

    @Test
    void classConversationsOfASessionPastItsRetentionAreDeletedWithMessagesAndSummaries() {
        FakeConversationStore store = new FakeConversationStore();
        store.seedSession(ENDED_EXPIRED, daysAgo(8), 7);
        ConversationStore.Conversation expired = store.seedConversation(STUDENT, ENDED_EXPIRED, "자바", daysAgo(9));
        store.seedMessages(expired.id(), 2, daysAgo(9));
        store.seedSummary(expired.id(), "요약", 1, 2, daysAgo(9));

        int deleted = new ConversationRetention(store, ConversationProperties.defaults(), clock).purgeExpiredClass();

        assertThat(deleted).isEqualTo(1);
        assertThat(store.conversation(expired.id())).isEmpty();
        assertThat(store.messagesOf(expired.id())).isEmpty();
        assertThat(store.summariesOf(expired.id())).isEmpty();
    }

    @Test
    void classConversationsWithinTheRetentionPeriodAreKept() {
        FakeConversationStore store = new FakeConversationStore();
        store.seedSession(ENDED_RECENT, daysAgo(6), 7);
        ConversationStore.Conversation recent = store.seedConversation(STUDENT, ENDED_RECENT, "자바", daysAgo(400));

        int deleted = new ConversationRetention(store, ConversationProperties.defaults(), clock).purgeExpiredClass();

        assertThat(deleted).isZero();
        assertThat(store.conversation(recent.id())).isPresent();
    }

    /** Retention counts from ended_at, so an open session's conversations stay however old they are. */
    @Test
    void conversationsOfAnOpenSessionAreNeverDeleted() {
        FakeConversationStore store = new FakeConversationStore();
        store.seedSession(OPEN, null, 0);
        ConversationStore.Conversation open = store.seedConversation(STUDENT, OPEN, "자바", daysAgo(400));
        store.seedMemory(STUDENT, OPEN, open.id(), "수업 기억", daysAgo(400));

        int deleted = new ConversationRetention(store, ConversationProperties.defaults(), clock).purgeExpiredClass();

        assertThat(deleted).isZero();
        assertThat(store.conversation(open.id())).isPresent();
        assertThat(store.allMemories()).hasSize(1);
    }

    @Test
    void zeroDaysDeletesRightAfterTheSessionEnds() {
        FakeConversationStore store = new FakeConversationStore();
        store.seedSession(ENDED_ZERO, NOW.minusSeconds(60), 0);
        ConversationStore.Conversation c = store.seedConversation(STUDENT, ENDED_ZERO, "자바", NOW.minusSeconds(120));

        int deleted = new ConversationRetention(store, ConversationProperties.defaults(), clock).purgeExpiredClass();

        assertThat(deleted).isEqualTo(1);
        assertThat(store.conversation(c.id())).isEmpty();
    }

    /** A row without ai_retention_days (inserted outside the API) is treated as the 90-day maximum. */
    @Test
    void sessionWithoutARetentionValueUsesTheNinetyDayMaximum() {
        FakeConversationStore store = new FakeConversationStore();
        store.seedSession(LEGACY, daysAgo(89), null);
        ConversationStore.Conversation c = store.seedConversation(STUDENT, LEGACY, "자바", daysAgo(100));
        ConversationRetention retention = new ConversationRetention(store, ConversationProperties.defaults(), clock);

        assertThat(retention.purgeExpiredClass()).isZero();
        assertThat(store.conversation(c.id())).isPresent();

        clock.set(NOW.plus(Duration.ofDays(2)));
        assertThat(retention.purgeExpiredClass()).isEqualTo(1);
        assertThat(store.conversation(c.id())).isEmpty();
    }

    /**
     * A saved class conversation stays with its messages; session-scoped memories follow the session (memories are
     * per scope, not per conversation), including the ones written from the saved conversation.
     */
    @Test
    void savedClassConversationIsKeptButSessionScopedMemoriesAreDeleted() {
        FakeConversationStore store = new FakeConversationStore();
        store.seedSession(ENDED_EXPIRED, daysAgo(31), 30);
        store.seedSession(ENDED_RECENT, daysAgo(1), 30);
        ConversationStore.Conversation saved = store.seedConversation(STUDENT, ENDED_EXPIRED, "자바", daysAgo(32));
        store.seedMessages(saved.id(), 2, daysAgo(32));
        store.seedSaved(saved.id(), daysAgo(31));
        store.seedMemory(STUDENT, ENDED_EXPIRED, saved.id(), "저장한 대화의 수업 기억", daysAgo(32));
        ConversationStore.Conversation unsaved = store.seedConversation(STUDENT, ENDED_EXPIRED, "자바", daysAgo(32));
        store.seedMemory(STUDENT, ENDED_EXPIRED, unsaved.id(), "같이 지워질 기억", daysAgo(32));
        ConversationStore.Conversation other = store.seedConversation(STUDENT, ENDED_RECENT, "자바", daysAgo(2));
        ConversationStore.Memory otherMemory = store.seedMemory(STUDENT, ENDED_RECENT, other.id(), "다른 수업 기억",
                daysAgo(2));
        ConversationStore.Conversation personal = store.seedConversation(STUDENT, null, "수학", NOW);
        ConversationStore.Memory personalMemory = store.seedMemory(STUDENT, null, personal.id(), "개인 기억", NOW);

        int deleted = new ConversationRetention(store, ConversationProperties.defaults(), clock).purgeExpiredClass();

        assertThat(deleted).as("1 conversation (its memory by CASCADE) + 1 memory of the saved one").isEqualTo(2);
        assertThat(store.conversation(saved.id())).isPresent();
        assertThat(store.messagesOf(saved.id())).hasSize(2);
        assertThat(store.conversation(unsaved.id())).isEmpty();
        assertThat(store.allMemories()).extracting(ConversationStore.Memory::id)
                .containsExactly(otherMemory.id(), personalMemory.id());
    }

    /** Like the old inactivity retention: a soft-deleted expired class conversation goes too. */
    @Test
    void classRetentionAlsoRemovesSoftDeletedConversations() {
        FakeConversationStore store = new FakeConversationStore();
        store.seedSession(ENDED_EXPIRED, daysAgo(8), 7);
        ConversationStore.Conversation c = store.seedConversation(STUDENT, ENDED_EXPIRED, "자바", daysAgo(9));
        store.softDeleteConversation(c.id(), daysAgo(1));

        assertThat(new ConversationRetention(store, ConversationProperties.defaults(), clock).purgeExpiredClass())
                .isEqualTo(1);
        assertThat(store.conversation(c.id())).isEmpty();
        assertThat(store.deletedAt(c.id())).isEmpty();
    }

    @Test
    void classRetentionRunsInBatchesForConversationsThenMemoriesAtNow() {
        ConversationStore store = mock(ConversationStore.class);
        when(store.deleteExpiredClassConversations(any(), anyInt())).thenReturn(500, 7);
        when(store.deleteExpiredClassMemories(any(), anyInt())).thenReturn(3);

        int deleted = new ConversationRetention(store, ConversationProperties.defaults(), clock).purgeExpiredClass();

        assertThat(deleted).isEqualTo(510);
        verify(store, times(2)).deleteExpiredClassConversations(eq(NOW), eq(500));
        verify(store, times(1)).deleteExpiredClassMemories(eq(NOW), eq(500));
    }

    // ------------------------------------------------------------------ B. personal retention

    @Test
    void defaultPersonalRetentionIs30Days() {
        assertThat(ConversationProperties.defaults().personalRetentionDays()).isEqualTo(30);
    }

    @Test
    void personalRetentionBelowOneDayIsRejected() {
        assertThatThrownBy(() -> personal(0, 500))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("personal-retention-days");
    }

    @Test
    void personalConversationsIdleLongerThan30DaysAreDeletedInBatches() {
        ConversationStore store = mock(ConversationStore.class);
        when(store.deleteIdlePersonalConversationsBefore(any(), anyInt())).thenReturn(500, 500, 12);

        int deleted = new ConversationRetention(store, ConversationProperties.defaults(), clock).purgeIdlePersonal();

        assertThat(deleted).isEqualTo(1012);
        verify(store, times(3)).deleteIdlePersonalConversationsBefore(eq(daysAgo(30)), eq(500));
    }

    @Test
    void onlyUnsavedPersonalConversationsIdleBeyondTheCutoffAreDeleted() {
        FakeConversationStore store = new FakeConversationStore();
        ConversationStore.Conversation old = store.seedConversation(STUDENT, null, "수학", daysAgo(31));
        store.seedMessages(old.id(), 2, daysAgo(31));
        store.seedMemory(STUDENT, null, old.id(), "옛 기억", daysAgo(31));
        ConversationStore.Conversation recent = store.seedConversation(STUDENT, null, "수학", daysAgo(29));
        ConversationStore.Conversation savedOld = store.seedConversation(STUDENT, null, "수학", daysAgo(300));
        store.seedSaved(savedOld.id(), daysAgo(299));
        // a class conversation idle that long is not personal retention's business
        store.seedSession(OPEN, null, 7);
        ConversationStore.Conversation classOld = store.seedConversation(STUDENT, OPEN, "자바", daysAgo(300));

        int deleted = new ConversationRetention(store, personal(30, 1), clock).purgeIdlePersonal();

        assertThat(deleted).isEqualTo(1);
        assertThat(store.conversation(old.id())).isEmpty();
        assertThat(store.allMemories()).isEmpty();
        assertThat(store.conversation(recent.id())).isPresent();
        assertThat(store.conversation(savedOld.id())).isPresent();
        assertThat(store.conversation(classOld.id())).isPresent();
    }

    /** An empty conversation's last_message_at is its creation time, so it goes 30 days after it was made. */
    @Test
    void emptyPersonalConversationCountsFromItsCreation() {
        FakeConversationStore store = new FakeConversationStore();
        ConversationStore.Conversation empty = store.insertConversation(STUDENT, null, "수학", daysAgo(31));

        assertThat(new ConversationRetention(store, ConversationProperties.defaults(), clock).purgeIdlePersonal())
                .isEqualTo(1);
        assertThat(store.conversation(empty.id())).isEmpty();
    }

    @Test
    void personalRetentionAlsoRemovesSoftDeletedConversations() {
        FakeConversationStore store = new FakeConversationStore();
        ConversationStore.Conversation old = store.seedConversation(STUDENT, null, "수학", daysAgo(31));
        store.softDeleteConversation(old.id(), daysAgo(1));

        int deleted = new ConversationRetention(store, ConversationProperties.defaults(), clock).purgeIdlePersonal();

        assertThat(deleted).isEqualTo(1);
        assertThat(store.conversation(old.id())).isEmpty();
        assertThat(store.deletedAt(old.id())).isEmpty();
    }

    /** Unsaving makes a conversation follow retention again. */
    @Test
    void unsavedAgainFollowsRetention() {
        FakeConversationStore store = new FakeConversationStore();
        ConversationStore.Conversation c = store.seedConversation(STUDENT, null, "수학", daysAgo(40));
        store.seedSaved(c.id(), daysAgo(39));
        ConversationRetention retention = new ConversationRetention(store, ConversationProperties.defaults(), clock);

        assertThat(retention.purgeIdlePersonal()).isZero();
        store.setSaved(c.id(), null);
        assertThat(retention.purgeIdlePersonal()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ deleted purge (soft delete grace period)

    private static ConversationProperties purge(int days, int batch) {
        return new ConversationProperties(ConversationProperties.DEFAULT_PERSONAL_RETENTION_DAYS, batch, days,
                ConversationProperties.defaults().summary());
    }

    @Test
    void defaultGracePeriodIs30Days() {
        assertThat(ConversationProperties.defaults().deletedPurgeDays()).isEqualTo(30);
        assertThat(personal(30, 500).deletedPurgeDays()).isEqualTo(30);
    }

    @Test
    void gracePeriodBelowOneDayIsRejected() {
        assertThatThrownBy(() -> purge(0, 500))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("deleted-purge-days");
        assertThatThrownBy(() -> purge(-1, 500))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deletedPurgeRunsInBatchesForConversationsThenMemories() {
        ConversationStore store = mock(ConversationStore.class);
        when(store.purgeDeletedConversationsBefore(any(), anyInt())).thenReturn(500, 500, 3);
        when(store.purgeDeletedMemoriesBefore(any(), anyInt())).thenReturn(2);

        int purged = new ConversationRetention(store, ConversationProperties.defaults(), clock).purgeDeleted();

        assertThat(purged).isEqualTo(1005);
        Instant cutoff = NOW.minus(Duration.ofDays(30));
        verify(store, times(3)).purgeDeletedConversationsBefore(eq(cutoff), eq(500));
        verify(store, times(1)).purgeDeletedMemoriesBefore(eq(cutoff), eq(500));
    }

    /** The deleted purge never touches live rows (retention is a separate step). */
    @Test
    void deletedPurgeRemovesOnlyRowsDeletedMoreThanNDaysAgo() {
        FakeConversationStore store = new FakeConversationStore();
        ConversationStore.Conversation expired = store.seedConversation(2L, null, "수학", NOW);
        store.seedMessages(expired.id(), 2, NOW);
        store.seedSummary(expired.id(), "요약", 1, 2, NOW);
        store.seedMemory(2L, null, expired.id(), "같이 지워질 기억", NOW);
        store.softDeleteConversation(expired.id(), NOW.minus(Duration.ofDays(31)));
        ConversationStore.Conversation boundary = store.seedConversation(2L, null, "수학", NOW);
        store.softDeleteConversation(boundary.id(), NOW.minus(Duration.ofDays(30)));
        ConversationStore.Conversation recent = store.seedConversation(2L, null, "수학", NOW);
        store.softDeleteConversation(recent.id(), NOW.minus(Duration.ofDays(29)));
        ConversationStore.Conversation live = store.seedConversation(2L, null, "수학", NOW.minus(Duration.ofDays(400)));
        ConversationStore.Memory oldDeleted = store.seedMemory(2L, null, live.id(), "오래전 지운 기억", NOW);
        store.softDeleteMemory(live.id(), oldDeleted.id(), NOW.minus(Duration.ofDays(31)));
        ConversationStore.Memory newDeleted = store.seedMemory(2L, null, live.id(), "어제 지운 기억", NOW);
        store.softDeleteMemory(live.id(), newDeleted.id(), NOW.minus(Duration.ofDays(1)));
        ConversationStore.Memory liveMemory = store.seedMemory(2L, null, live.id(), "살아 있는 기억", NOW);

        int purged = new ConversationRetention(store, ConversationProperties.defaults(), clock).purgeDeleted();

        assertThat(purged).isEqualTo(2);
        assertThat(store.conversation(expired.id())).isEmpty();
        assertThat(store.messagesOf(expired.id())).isEmpty();
        assertThat(store.summariesOf(expired.id())).isEmpty();
        assertThat(store.conversation(boundary.id())).isPresent();
        assertThat(store.conversation(recent.id())).isPresent();
        assertThat(store.conversation(live.id())).isPresent();
        assertThat(store.allMemories()).extracting(ConversationStore.Memory::id)
                .containsExactly(newDeleted.id(), liveMemory.id());

        clock.set(NOW.plus(Duration.ofDays(2)));
        int later = new ConversationRetention(store, ConversationProperties.defaults(), clock).purgeDeleted();

        assertThat(later).isEqualTo(2);
        assertThat(store.conversation(boundary.id())).isEmpty();
        assertThat(store.conversation(recent.id())).isEmpty();
        assertThat(store.allMemories()).extracting(ConversationStore.Memory::id)
                .containsExactly(newDeleted.id(), liveMemory.id());
        assertThat(store.conversation(live.id())).isPresent();
    }

    // ------------------------------------------------------------------ the hourly job

    @Test
    void hourlyJobRunsTheDeletedPurgeAndBothRetentions() {
        ConversationStore store = mock(ConversationStore.class);

        new ConversationRetention(store, ConversationProperties.defaults(), clock).scheduledPurge();

        Instant cutoff = NOW.minus(Duration.ofDays(30));
        verify(store).purgeDeletedConversationsBefore(eq(cutoff), eq(500));
        verify(store).purgeDeletedMemoriesBefore(eq(cutoff), eq(500));
        verify(store).deleteExpiredClassConversations(eq(NOW), eq(500));
        verify(store).deleteExpiredClassMemories(eq(NOW), eq(500));
        verify(store).deleteIdlePersonalConversationsBefore(eq(cutoff), eq(500));
    }

    @Test
    void hourlyJobStillRunsRetentionWhenTheDeletedPurgeFails() {
        ConversationStore store = mock(ConversationStore.class);
        when(store.purgeDeletedConversationsBefore(any(), anyInt())).thenThrow(new IllegalStateException("db down"));

        assertThatCode(() -> new ConversationRetention(store, ConversationProperties.defaults(), clock)
                .scheduledPurge()).doesNotThrowAnyException();

        verify(store).deleteExpiredClassConversations(eq(NOW), eq(500));
        verify(store).deleteIdlePersonalConversationsBefore(eq(NOW.minus(Duration.ofDays(30))), eq(500));
    }

    @Test
    void hourlyJobStillRunsTheOtherPartsWhenClassRetentionFails() {
        ConversationStore store = mock(ConversationStore.class);
        when(store.deleteExpiredClassConversations(any(), anyInt())).thenThrow(new IllegalStateException("db down"));

        assertThatCode(() -> new ConversationRetention(store, ConversationProperties.defaults(), clock)
                .scheduledPurge()).doesNotThrowAnyException();

        verify(store).purgeDeletedConversationsBefore(any(), eq(500));
        verify(store).purgeDeletedMemoriesBefore(any(), eq(500));
        verify(store, never()).deleteExpiredClassMemories(any(), anyInt());
        verify(store).deleteIdlePersonalConversationsBefore(any(), eq(500));
    }

    @Test
    void hourlyJobStillPurgesDeletedRowsWhenPersonalRetentionFails() {
        ConversationStore store = mock(ConversationStore.class);
        when(store.deleteIdlePersonalConversationsBefore(any(), anyInt()))
                .thenThrow(new IllegalStateException("db down"));

        assertThatCode(() -> new ConversationRetention(store, ConversationProperties.defaults(), clock)
                .scheduledPurge()).doesNotThrowAnyException();

        verify(store).purgeDeletedConversationsBefore(any(), eq(500));
        verify(store).deleteExpiredClassConversations(any(), eq(500));
    }

    @Test
    void configuredGracePeriodAndBatchSizeAreUsed() {
        FakeConversationStore store = new FakeConversationStore();
        for (int i = 0; i < 3; i++) {
            ConversationStore.Conversation c = store.seedConversation(2L, null, "수학", NOW);
            store.softDeleteConversation(c.id(), NOW.minus(Duration.ofDays(8)));
        }

        int purged = new ConversationRetention(store, purge(7, 2), clock).purgeDeleted();

        assertThat(purged).isEqualTo(3);
        assertThat(store.calls.stream().filter("purgeConversations"::equals).count()).isEqualTo(2);
    }
}
