package com.my.tutipal_back.learning.conversation.service;

import static com.my.tutipal_back.learning.conversation.ConversationTestSupport.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.my.tutipal_back.ai.quota.MutableClock;
import com.my.tutipal_back.learning.conversation.ConversationProperties;
import com.my.tutipal_back.learning.conversation.repository.ConversationStore;
import com.my.tutipal_back.learning.conversation.repository.FakeConversationStore;

/** PT-106 AC-24: retention is off by default (PT-13 미정); when set, old conversations are deleted in batches. */
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
}
