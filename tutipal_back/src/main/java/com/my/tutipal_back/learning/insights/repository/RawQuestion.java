package com.my.tutipal_back.learning.insights.repository;

import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * PT-87 D5: a stored student question. The text leaves this object only through {@link #revealForEmbedding()},
 * which the topic analysis passes to the embedding call. toString is redacted and JSON serialization always fails,
 * so the text cannot end up in a response or a log by accident.
 *
 * <p>Not a record on purpose: a record would expose a public accessor for the text.
 */
public final class RawQuestion {

    private final String text;

    public RawQuestion(String text) {
        this.text = Objects.requireNonNull(text, "text");
    }

    /** The only way to read the text: for the embedding request (and the length filter right before it). */
    public String revealForEmbedding() {
        return text;
    }

    /**
     * Jackson calls this for any serialization (also with FAIL_ON_EMPTY_BEANS off, also nested in a Map or a
     * record) and the exception stops it. The message does not contain the text.
     */
    @JsonValue
    private Object refuseSerialization() {
        throw new IllegalStateException("RawQuestion must not be serialized");
    }

    @Override
    public String toString() {
        return "RawQuestion[redacted]";
    }
}
