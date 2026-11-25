package com.my.tutipal_back.learning.conversation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.my.tutipal_back.ai.dto.AgentDtos;

/** PT-106 AC-3: the binding is visible only inside run(...) on the same thread and is released on every exit. */
class ConversationBindingTest {

    private static final ConversationBinding.Bound BOUND = new ConversationBinding.Bound(2L,
            AgentDtos.Scope.session(10L), 7L, 5L, 12L);

    private final ConversationBinding binding = new ConversationBinding();

    @Test
    void currentIsEmptyOutsideRun() {
        assertThat(binding.current()).isEmpty();
    }

    @Test
    void currentIsTheBoundValueInsideRunAndEmptyAfterNormalReturn() {
        AtomicReference<Optional<ConversationBinding.Bound>> seen = new AtomicReference<>();

        binding.run(BOUND, () -> seen.set(binding.current()));

        assertThat(seen.get()).contains(BOUND);
        assertThat(binding.current()).isEmpty();
    }

    @Test
    void bindingIsReleasedWhenTheWorkThrows() {
        RuntimeException failure = new IllegalStateException("client gone");

        assertThatThrownBy(() -> binding.run(BOUND, () -> {
            throw failure;
        })).isSameAs(failure);

        assertThat(binding.current()).isEmpty();
    }

    @Test
    void bindingIsReleasedWhenTheWorkThrowsAnError() {
        Error failure = new AssertionError("boom");

        assertThatThrownBy(() -> binding.run(BOUND, () -> {
            throw failure;
        })).isSameAs(failure);

        assertThat(binding.current()).isEmpty();
    }

    @Test
    void bindingIsNotVisibleFromAnotherThread() throws Exception {
        AtomicReference<Optional<ConversationBinding.Bound>> seen = new AtomicReference<>();

        binding.run(BOUND, () -> {
            Thread other = Thread.ofVirtual().start(() -> seen.set(binding.current()));
            try {
                other.join();
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        });

        assertThat(seen.get()).isEmpty();
    }
}
