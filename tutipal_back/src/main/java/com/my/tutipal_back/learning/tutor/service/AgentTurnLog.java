package com.my.tutipal_back.learning.tutor.service;

import java.util.List;
import java.util.regex.Pattern;

import com.my.tutipal_back.ai.client.AiUnavailableException;
import com.my.tutipal_back.ai.dto.AgentDtos;
import com.my.tutipal_back.common.exception.ApiException;

/**
 * PT-85 AC-26: Agent 턴마다 남기는 로그 한 줄. 이름표와 숫자만 담고 질문 · 검색어 · 자료 · 답안 원문과 userId 는
 * 넣지 않는다. FastAPI 가 준 이름표도 영문 · 숫자 · 밑줄만 남겨 줄바꿈 등으로 로그를 꾸밀 수 없게 한다.
 */
final class AgentTurnLog {

    private static final Pattern UNSAFE = Pattern.compile("[^A-Za-z0-9_]");
    private static final int MAX_LABEL = 40;

    private AgentTurnLog() {
    }

    static String format(String kind, AgentDtos.Meta meta, List<String> tools, int searchRounds, long elapsedMs) {
        List<String> nodes = meta.nodes() == null ? List.of() : meta.nodes();
        return "agent turn kind=" + label(kind)
                + " result=ok"
                + " path=" + String.join(">", nodes.stream().map(AgentTurnLog::label).toList())
                + " intent=" + label(meta.route())
                + " hint=" + meta.hintLevel()
                + " help=" + meta.suggestHelp()
                + " tools=" + String.join(",", tools.stream().map(AgentTurnLog::label).toList())
                + " searchRounds=" + searchRounds
                + " variant=" + label(meta.variant())
                + " elapsedMs=" + elapsedMs;
    }

    /**
     * 실패한 턴도 한 줄 (AC-26 "턴마다 한 줄"). 사유는 상태 코드 · 예외 종류 이름표만 남기고 예외 메시지는 넣지 않는다
     * (FastAPI 오류 문구나 사용자 입력이 섞일 수 있음).
     */
    static String failure(String kind, List<String> tools, int searchRounds, Throwable error, long elapsedMs) {
        return "agent turn kind=" + label(kind)
                + " result=error"
                + " reason=" + reason(error)
                + " tools=" + String.join(",", tools.stream().map(AgentTurnLog::label).toList())
                + " searchRounds=" + searchRounds
                + " elapsedMs=" + elapsedMs;
    }

    private static String reason(Throwable error) {
        if (error instanceof ApiException api) {
            return "status_" + api.getStatus().value();
        }
        if (error instanceof AiUnavailableException down) {
            return down.isAfterOutput() ? "ai_unavailable_after_output" : "ai_unavailable";
        }
        return label(error.getClass().getSimpleName());
    }

    private static String label(String value) {
        if (value == null) {
            return "null";
        }
        String safe = UNSAFE.matcher(value).replaceAll("");
        return safe.length() > MAX_LABEL ? safe.substring(0, MAX_LABEL) : safe;
    }
}
