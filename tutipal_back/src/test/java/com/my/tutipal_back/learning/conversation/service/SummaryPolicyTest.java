package com.my.tutipal_back.learning.conversation.service;

import static com.my.tutipal_back.learning.conversation.ConversationTestSupport.NOW;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.my.tutipal_back.ai.dto.AgentDtos;
import com.my.tutipal_back.learning.conversation.ConversationProperties;
import com.my.tutipal_back.learning.conversation.repository.ConversationStore;

/**
 * PT-106 AC-20 (pure): messages after the active summary, excluding the newest 6, reaching 20 → plan the oldest
 * up to 40 of them (each cut to 8000) plus the previous summary. Numbers come from configuration.
 */
class SummaryPolicyTest {

    private static final long CONVERSATION = 7L;
    private final SummaryPolicy policy = new SummaryPolicy(ConversationProperties.defaults());

    /** Messages with ids from firstId, alternating user / assistant, content "c<id>". */
    private static List<ConversationStore.Message> messages(long firstId, int count) {
        List<ConversationStore.Message> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            long id = firstId + i;
            out.add(new ConversationStore.Message(id, CONVERSATION, i % 2 == 0 ? "user" : "assistant", "c" + id, NOW));
        }
        return out;
    }

    private static ConversationStore.Summary active(long id, long from, long to) {
        return new ConversationStore.Summary(id, CONVERSATION, "이전 요약", from, to, NOW, null, null);
    }

    @Test
    void defaultsAreThreshold20KeepRecent6MaxInput40() {
        ConversationProperties.Summary summary = ConversationProperties.defaults().summary();

        assertThat(summary.threshold()).isEqualTo(20);
        assertThat(summary.keepRecent()).isEqualTo(6);
        assertThat(summary.maxInput()).isEqualTo(40);
        assertThat(summary.slotWait()).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void twentyFiveMessagesWithoutSummaryIsNotEnough() {
        assertThat(policy.plan(null, 25, messages(1, 25))).isEmpty();
    }

    @Test
    void firstSummaryHappensAtThe26thMessage() {
        Optional<SummaryPlan> plan = policy.plan(null, 26, messages(1, 26));

        assertThat(plan).isPresent();
        SummaryPlan p = plan.get();
        assertThat(p.messages()).hasSize(20);
        assertThat(p.messages().getFirst()).isEqualTo(new AgentDtos.ContextMessage("user", "c1"));
        assertThat(p.messages().getLast().content()).isEqualTo("c20");
        assertThat(p.fromMessageId()).isEqualTo(1L);
        assertThat(p.toMessageId()).isEqualTo(20L);
        assertThat(p.previousSummary()).isNull();
        assertThat(p.basedOnSummaryId()).isNull();
    }

    @Test
    void countsOnlyMessagesAfterTheActiveSummary() {
        ConversationStore.Summary s = active(3L, 1L, 10L);

        assertThat(policy.plan(s, 25, messages(11, 25))).isEmpty();

        SummaryPlan p = policy.plan(s, 30, messages(11, 30)).orElseThrow();
        assertThat(p.messages()).hasSize(24);
        assertThat(p.fromMessageId()).isEqualTo(11L);
        assertThat(p.toMessageId()).isEqualTo(34L);
        assertThat(p.previousSummary()).isEqualTo("이전 요약");
        assertThat(p.basedOnSummaryId()).isEqualTo(3L);
    }

    @Test
    void inputIsAtMost40OldestAndAtLeast6Remain() {
        // 60 after the summary: 54 eligible, only the oldest 40 go in, 20 remain unsummarized
        SummaryPlan p = policy.plan(active(1L, 1L, 10L), 60, messages(11, 40)).orElseThrow();

        assertThat(p.messages()).hasSize(40);
        assertThat(p.fromMessageId()).isEqualTo(11L);
        assertThat(p.toMessageId()).isEqualTo(50L);
        long remaining = 70 - p.toMessageId();
        assertThat(remaining).isGreaterThanOrEqualTo(6);
    }

    @Test
    void neverTakesMoreThanTheEligibleMessagesEvenIfMoreWereFetched() {
        // 30 after the summary: 24 eligible; the newest 6 must stay out of the input
        SummaryPlan p = policy.plan(null, 30, messages(1, 30)).orElseThrow();

        assertThat(p.messages()).hasSize(24);
        assertThat(p.toMessageId()).isEqualTo(24L);
    }

    @Test
    void eachInputMessageIsCutTo8000Characters() {
        List<ConversationStore.Message> given = new ArrayList<>(messages(1, 26));
        given.set(0, new ConversationStore.Message(1L, CONVERSATION, "user", "x".repeat(9000), NOW));

        SummaryPlan p = policy.plan(null, 26, given).orElseThrow();

        assertThat(p.messages().getFirst().content()).hasSize(8000);
        assertThat(p.messages().getFirst().role()).isEqualTo("user");
    }

    @Test
    void numbersAreConfigurable() {
        ConversationProperties custom = new ConversationProperties(0, 500,
                new ConversationProperties.Summary(5, 2, 3, Duration.ofSeconds(1)));
        SummaryPolicy small = new SummaryPolicy(custom);

        assertThat(small.plan(null, 6, messages(1, 6))).isEmpty();
        SummaryPlan p = small.plan(null, 7, messages(1, 7)).orElseThrow();
        assertThat(p.messages()).hasSize(3);
        assertThat(p.toMessageId()).isEqualTo(3L);
    }
}
