package com.my.tutipal_back.ai.client;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.my.tutipal_back.common.exception.ApiException;

import static org.junit.jupiter.api.Assertions.*;

/** PT-26: test the Spring-owned slot boundary separately from Python cancellation. */
class AiSlotsTest {
    @Test
    void returnsSlotAfterSuccess() {
        var slots = new AiSlots(1, Duration.ZERO);
        assertEquals("first", slots.run(() -> "first"));
        assertEquals("next", slots.run(() -> "next"));
    }

    @Test
    void returnsSlotWhenConsumerCancelsOrProviderFails() {
        var slots = new AiSlots(1, Duration.ZERO);
        var cancelled = new RuntimeException("client gone");
        assertSame(cancelled, assertThrows(RuntimeException.class,
                () -> slots.run(() -> { throw cancelled; })));
        assertEquals("next", slots.run(() -> "next"));
        assertThrows(AiUnavailableException.class,
                () -> slots.run(() -> { throw new AiUnavailableException(); }));
        assertEquals("after failure", slots.run(() -> "after failure"));
    }

    @Test
    void enforcesTwoSlotsAndRejectsThirdWithoutRunningIt() throws Exception {
        var slots = new AiSlots(2, Duration.ZERO);
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> slots.run(() -> hold(entered, release)));
            var second = executor.submit(() -> slots.run(() -> hold(entered, release)));
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                assertThrows(ApiException.class, () -> slots.run(() -> fail("third ran")));
            } finally {
                release.countDown();
            }
            assertEquals("done", first.get(2, TimeUnit.SECONDS));
            assertEquals("done", second.get(2, TimeUnit.SECONDS));
            assertEquals("reused", slots.run(() -> "reused"));
        }
    }

    @Test
    void interruptionDoesNotCreateAnExtraPermit() {
        var slots = new AiSlots(1, Duration.ZERO);
        Thread.currentThread().interrupt();
        try {
            assertThrows(ApiException.class, () -> slots.run(() -> fail("interrupted work ran")));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        slots.run(() -> {
            assertThrows(ApiException.class, () -> slots.run(() -> fail("extra permit")));
            return null;
        });
    }

    private String hold(CountDownLatch entered, CountDownLatch release) {
        entered.countDown();
        try {
            if (!release.await(2, TimeUnit.SECONDS)) throw new AssertionError("release timed out");
            return "done";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
