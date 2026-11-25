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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.my.tutipal_back.ai.quota.MutableClock;
import com.my.tutipal_back.learning.conversation.ConversationProperties;
import com.my.tutipal_back.learning.conversation.repository.ConversationStore;
import com.my.tutipal_back.learning.conversation.repository.FakeConversationStore;

/**
 * PT-106 AC-24: retention is off by default (PT-13 미정); when set, old conversations are deleted in batches.
 * Soft delete: rows the user deleted are purged after deleted-purge-days (default 30, tentative until PT-13).
 */
class ConversationRetentionTest {

    private final MutableClock clock = new MutableClock(NOW);

    private static ConversationProperties retention(int days, int batch) {
        return new ConversationProperties(days, batch, ConversationProperties.defaults().summary());
    }

    @Test
    void defaultRetentionIsOff() {
        assertThat(ConversationProperties.defaults().retentionDays()).isZero();
    }

    @Test
    void zeroDaysNeverTouchesTheStore() {
        ConversationStore store = mock(ConversationStore.class);

        int deleted = new ConversationRetention(store, ConversationProperties.defaults(), clock).purgeExpired();

        assertThat(deleted).isZero();
        verifyNoInteractions(store);
    }

    @Test
    void nDaysDeletesConversationsIdleLongerThanNDaysInBatches() {
        ConversationStore store = mock(ConversationStore.class);
        when(store.deleteInactiveBefore(any(), anyInt())).thenReturn(500, 500, 12);

        int deleted = new ConversationRetention(store, retention(30, 500), clock).purgeExpired();

        assertThat(deleted).isEqualTo(1012);
        verify(store, times(3)).deleteInactiveBefore(eq(NOW.minus(Duration.ofDays(30))), eq(500));
    }

    @Test
    void onlyConversationsOlderThanTheCutoffAreDeleted() {
        FakeConversationStore store = new FakeConversationStore();
        ConversationStore.Conversation old = store.seedConversation(2L, null, "수학", NOW.minus(Duration.ofDays(31)));
        ConversationStore.Conversation recent = store.seedConversation(2L, null, "수학",
                NOW.minus(Duration.ofDays(29)));
        store.seedMessages(old.id(), 2, NOW.minus(Duration.ofDays(31)));
        store.seedMemory(2L, null, old.id(), "옛 기억", NOW.minus(Duration.ofDays(31)));

        int deleted = new ConversationRetention(store, retention(30, 1), clock).purgeExpired();

        assertThat(deleted).isEqualTo(1);
        assertThat(store.conversation(old.id())).isEmpty();
        assertThat(store.conversation(recent.id())).isPresent();
        assertThat(store.allMemories()).isEmpty();
    }

    /** Inactivity retention removes soft-deleted conversations too (consistent with live ones). */
    @Test
    void inactivityRetentionAlsoRemovesSoftDeletedConversations() {
        FakeConversationStore store = new FakeConversationStore();
        ConversationStore.Conversation old = store.seedConversation(2L, null, "수학", NOW.minus(Duration.ofDays(31)));
        store.softDeleteConversation(old.id(), NOW.minus(Duration.ofDays(1)));

        int deleted = new ConversationRetention(store, retention(30, 500), clock).purgeExpired();

        assertThat(deleted).isEqualTo(1);
        assertThat(store.conversation(old.id())).isEmpty();
        assertThat(store.deletedAt(old.id())).isEmpty();
    }

    // ------------------------------------------------------------------ deleted purge (soft delete grace period)

    private static ConversationProperties purge(int days, int batch) {
        return new ConversationProperties(0, batch, days, ConversationProperties.defaults().summary());
    }

    @Test
    void defaultGracePeriodIs30Days() {
        assertThat(ConversationProperties.defaults().deletedPurgeDays()).isEqualTo(30);
        assertThat(retention(0, 500).deletedPurgeDays()).isEqualTo(30);
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

    /** Inactivity retention off (default) does not stop the deleted purge, and the purge never touches live rows. */
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
    void hourlyJobPurgesDeletedRowsEvenWithInactivityRetentionOff() {
        ConversationStore store = mock(ConversationStore.class);

        new ConversationRetention(store, ConversationProperties.defaults(), clock).scheduledPurge();

        Instant cutoff = NOW.minus(Duration.ofDays(30));
        verify(store).purgeDeletedConversationsBefore(eq(cutoff), eq(500));
        verify(store).purgeDeletedMemoriesBefore(eq(cutoff), eq(500));
        verify(store, never()).deleteInactiveBefore(any(), anyInt());
    }

    @Test
    void hourlyJobStillRunsInactivityRetentionWhenTheDeletedPurgeFails() {
        ConversationStore store = mock(ConversationStore.class);
        when(store.purgeDeletedConversationsBefore(any(), anyInt())).thenThrow(new IllegalStateException("db down"));

        assertThatCode(() -> new ConversationRetention(store, retention(30, 500), clock).scheduledPurge())
                .doesNotThrowAnyException();

        verify(store).deleteInactiveBefore(eq(NOW.minus(Duration.ofDays(30))), eq(500));
    }

    @Test
    void hourlyJobStillPurgesDeletedRowsWhenInactivityRetentionFails() {
        ConversationStore store = mock(ConversationStore.class);
        when(store.deleteInactiveBefore(any(), anyInt())).thenThrow(new IllegalStateException("db down"));

        assertThatCode(() -> new ConversationRetention(store, retention(30, 500), clock).scheduledPurge())
                .doesNotThrowAnyException();

        verify(store).purgeDeletedConversationsBefore(any(), eq(500));
        verify(store).purgeDeletedMemoriesBefore(any(), eq(500));
        verify(store).deleteInactiveBefore(any(), eq(500));
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
