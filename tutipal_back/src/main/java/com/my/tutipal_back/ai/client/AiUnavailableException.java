package com.my.tutipal_back.ai.client;

/** AI 서버(FastAPI/LLM)에 연결할 수 없거나 오류를 돌려줬을 때. 원인 내용은 담지 않는다. */
public class AiUnavailableException extends RuntimeException {

    private final boolean temporaryEmbeddingFailure;
    private final boolean afterOutput;

    public AiUnavailableException() {
        this(false);
    }

    AiUnavailableException(boolean temporaryEmbeddingFailure) {
        this(temporaryEmbeddingFailure, false);
    }

    private AiUnavailableException(boolean temporaryEmbeddingFailure, boolean afterOutput) {
        super("AI 조교에 연결할 수 없어요");
        this.temporaryEmbeddingFailure = temporaryEmbeddingFailure;
        this.afterOutput = afterOutput;
    }

    /**
     * 모델이 답변 조각을 하나 이상 낸 뒤에 실패했을 때 (스트림 중간 오류 · 시간 초과 · 연결 끊김).
     * 모델은 이미 GPU 를 쓰며 일했으므로 PT-97 사용자 한도에서는 환불하지 않는다.
     */
    public static AiUnavailableException afterOutput() {
        return new AiUnavailableException(false, true);
    }

    public boolean isTemporaryEmbeddingFailure() {
        return temporaryEmbeddingFailure;
    }

    /** true 면 모델이 답변을 내기 시작한 뒤의 실패. false 면 첫 조각 전(서버 다운 · 연결 실패 · 거절). */
    public boolean isAfterOutput() {
        return afterOutput;
    }
}
