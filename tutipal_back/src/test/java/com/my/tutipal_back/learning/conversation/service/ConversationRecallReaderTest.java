package com.my.tutipal_back.learning.conversation.service;

import static com.my.tutipal_back.learning.conversation.ConversationTestSupport.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.my.tutipal_back.ai.dto.AgentDtos;
import com.my.tutipal_back.learning.conversation.repository.ConversationStore;
import com.my.tutipal_back.learning.conversation.repository.FakeConversationStore;
import com.my.tutipal_back.learning.tutor.service.ConversationMemory;

/**
 * PT-106: a recall reads its stored context from one consistent snapshot (one read-only REPEATABLE READ
 * transaction), so a concurrent delete can never mix a summary of the conversation with an empty history.
 * MVCC itself is not emulated here (no database in JUnit): the tests pin the transaction attributes and that the
 * port has no other way to read the store.
 */
class ConversationRecallReaderTest {

    private static final long ME = 2L;

    @Test
    void readIsOneReadOnlyRepeatableReadTransaction() throws NoSuchMethodException {
        Method read = ConversationRecallReader.class.getMethod("read", long.class, long.class, Long.class, long.class,
                Long.class, int.class, int.class);

        Transactional tx = read.getAnnotation(Transactional.class);
        assertThat(tx).isNotNull();
        assertThat(tx.readOnly()).isTrue();
        assertThat(tx.isolation()).isEqualTo(Isolation.REPEATABLE_READ);
    }

    @Test
    void storedMemoryHasNoDirectStoreAccess() {
        List<Class<?>> fieldTypes = Arrays.stream(StoredConversationMemory.class.getDeclaredFields())
                .<Class<?>>map(Field::getType).toList();

        assertThat(fieldTypes).doesNotContain(ConversationStore.class).contains(ConversationRecallReader.class);
    }

    @Test
    void boundRecallMakesExactlyOneSnapshotRead() {
        ConversationRecallReader reader = mock(ConversationRecallReader.class);
        when(reader.read(anyLong(), anyLong(), eq(5L), anyLong(), eq(10L), anyInt(), anyInt()))
                .thenReturn(new ConversationRecallReader.Snapshot(true, List.of(), null, List.of()));
        ConversationBinding binding = new ConversationBinding();
        StoredConversationMemory port = new StoredConversationMemory(reader, binding);
        AgentDtos.Scope scope = AgentDtos.Scope.session(10L);

        binding.run(new ConversationBinding.Bound(ME, scope, 7L, 5L, 12L),
                () -> port.recall(ME, scope, "질문", List.of()));

        verify(reader).read(ME, 7L, 5L, 12L, 10L, ConversationService.MAX_HISTORY,
                StoredConversationMemory.MAX_MEMORIES);
        verifyNoMoreInteractions(reader);
    }

    @Test
    void deletedBeforeRecallYieldsNoHistoryNoSummaryOnlyOtherLiveScopeMemories() {
        FakeConversationStore store = new FakeConversationStore();
        ConversationStore.Conversation gone = store.seedConversation(ME, null, "수학", NOW);
        store.seedMessages(gone.id(), 4, NOW);
        ConversationStore.Summary summary = store.seedSummary(gone.id(), "지운 요약", 1, 2, NOW);
        store.seedMemory(ME, null, gone.id(), "지운 대화 기억", NOW);
        ConversationStore.Conversation kept = store.seedConversation(ME, null, "수학", NOW);
        ConversationStore.Memory keptMemory = store.seedMemory(ME, null, kept.id(), "남는 기억", NOW);
        store.softDeleteConversation(gone.id(), NOW);

        ConversationRecallReader.Snapshot snapshot = new ConversationRecallReader(store)
                .read(ME, gone.id(), summary.id(), 0L, null, 39, 20);

        assertThat(snapshot.live()).isFalse();
        assertThat(snapshot.history()).isEmpty();
        assertThat(snapshot.summary()).isNull();
        assertThat(snapshot.memories()).extracting(ConversationStore.Memory::id).containsExactly(keptMemory.id());

        ConversationBinding binding = new ConversationBinding();
        StoredConversationMemory port = new StoredConversationMemory(store, binding);
        ConversationMemory.Recall[] recall = new ConversationMemory.Recall[1];
        binding.run(new ConversationBinding.Bound(ME, AgentDtos.Scope.personal(), gone.id(), summary.id(), 0L),
                () -> recall[0] = port.recall(ME, AgentDtos.Scope.personal(), "질문", List.of()));
        assertThat(recall[0].messages()).isEmpty();
        assertThat(recall[0].summary()).isNull();
        assertThat(recall[0].memories()).extracting(ConversationMemory.StoredMemory::id)
                .containsExactly("m_" + keptMemory.id());
    }
}
