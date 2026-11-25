package com.my.tutipal_back.ai.client;

import java.util.List;

import com.my.tutipal_back.ai.dto.AgentDtos;

/**
 * /v1/agent/chat NDJSON 줄을 받는 쪽 (계약 7.5). 콜백이 던진 예외(화면이 연결을 끊음 등)는 그대로 올라가고
 * 스트림이 닫힌다.
 */
public interface AgentListener {

    /** {"ping": true}: 출력 없는 구간의 연결 확인. SSE 로 보내지 않고 화면이 남아 있는지만 본다. */
    default void onPing() {
    }

    /** {"start": {"materialIds": [...]}}: 첫 delta 직전, 실제 지시문에 넣은 자료 id. */
    void onStart(List<String> materialIds);

    void onDelta(String delta);

    /** {"variant": {...}}: 변형 도구 결과 (정답 · 해설 포함, 내부 전용). */
    void onVariant(AgentDtos.VariantResult result);
}
