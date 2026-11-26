package com.my.tutipal_back.ai.client;

/**
 * PT-86: 재순위화를 쓸 수 없을 때. 메시지에는 사유 코드만 담고 질문·자료 조각은 담지 않는다.
 * 사유 코드: timeout · status · size · nonfinite · body · unconfigured
 */
public class RerankUnavailableException extends RuntimeException {

    private final String reason;

    public RerankUnavailableException(String reason) {
        super("rerank unavailable: " + reason);
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}
