package com.my.tutipal_back.ai.client;

import java.util.Map;

import com.my.tutipal_back.ai.dto.AgentDtos;

/** /v1/agent/chat 응답 하나가 정상으로 끝난 방식: 재검색 요청이거나 meta 로 끝난 답변. */
public sealed interface AgentOutcome {

    /** {"status":"need_search","query":"...","state":{...}}. state 는 해석하지 않고 다음 호출에 그대로 돌려준다. */
    record NeedSearch(String query, Map<String, Object> state) implements AgentOutcome {
    }

    record Completed(AgentDtos.Meta meta) implements AgentOutcome {
    }
}
