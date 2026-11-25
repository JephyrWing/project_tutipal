package com.my.tutipal_back.ai.client;

/** AI 서버(FastAPI/LLM)에 연결할 수 없거나 오류를 돌려줬을 때. 원인 내용은 담지 않는다. */
public class AiUnavailableException extends RuntimeException {

    private final boolean temporaryEmbeddingFailure;

    public AiUnavailableException() {
        this(false);
    }

    AiUnavailableException(boolean temporaryEmbeddingFailure) {
        super("AI 조교에 연결할 수 없어요");
        this.temporaryEmbeddingFailure = temporaryEmbeddingFailure;
    }

    public boolean isTemporaryEmbeddingFailure() {
        return temporaryEmbeddingFailure;
    }
}
