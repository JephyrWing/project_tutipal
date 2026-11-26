package com.my.tutipal_back.learning.insights.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** PT-87 AC-8 / D5: the stored question text can only leave RawQuestion through revealForEmbedding(). */
class RawQuestionTest {

    private static final String MARKER = "SECRET-Q-9001 반복문이 왜 안 끝나요?";

    @Test
    void revealForEmbeddingReturnsTheText() {
        assertThat(new RawQuestion(MARKER).revealForEmbedding()).isEqualTo(MARKER);
    }

    @Test
    void toStringIsRedacted() {
        RawQuestion raw = new RawQuestion(MARKER);

        assertThat(raw.toString()).doesNotContain("SECRET-Q-9001").doesNotContain("반복문");
        assertThat(String.valueOf(raw)).doesNotContain("SECRET-Q-9001");
        assertThat(List.of(raw).toString()).doesNotContain("SECRET-Q-9001");
    }

    @Test
    void jacksonSerializationFails() {
        RawQuestion raw = new RawQuestion(MARKER);
        JsonMapper mapper = JsonMapper.builder().build();

        assertThatThrownBy(() -> mapper.writeValueAsString(raw))
                .isInstanceOf(RuntimeException.class)
                .satisfies(e -> assertThat(String.valueOf(e.getMessage())).doesNotContain("SECRET-Q-9001"));
    }

    @Test
    void jacksonSerializationFailsEvenWhenEmptyBeansAreAllowedOrNested() {
        RawQuestion raw = new RawQuestion(MARKER);
        JsonMapper lenient = JsonMapper.builder().disable(SerializationFeature.FAIL_ON_EMPTY_BEANS).build();

        assertThatThrownBy(() -> lenient.writeValueAsString(raw)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> lenient.writeValueAsString(Map.of("q", raw))).isInstanceOf(RuntimeException.class);
    }

    @Test
    void isFinalAndHasOnlyOnePublicInstanceMethodBesidesObjectMethods() {
        assertThat(Modifier.isFinal(RawQuestion.class.getModifiers())).isTrue();
        List<String> publicMethods = Arrays.stream(RawQuestion.class.getDeclaredMethods())
                .filter(m -> Modifier.isPublic(m.getModifiers()))
                .filter(m -> !Modifier.isStatic(m.getModifiers()))
                .map(Method::getName)
                .filter(n -> !List.of("toString", "equals", "hashCode").contains(n))
                .toList();
        assertThat(publicMethods).containsExactly("revealForEmbedding");
    }

    @Test
    void isNotARecord() {
        // a record would expose an accessor for the text
        assertThat(RawQuestion.class.isRecord()).isFalse();
    }
}
