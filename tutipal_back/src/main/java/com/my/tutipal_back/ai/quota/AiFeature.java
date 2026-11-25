package com.my.tutipal_back.ai.quota;

/** PT-97: AI 호출 한도를 따로 세는 기능 단위. 사용자 × 기능마다 한도가 따로 있다. */
public enum AiFeature {
    /** 위젯 자동 화면 요약 (question 없음). */
    SCREEN_AUTO("화면 요약"),
    /** 위젯 화면 질문 (question 있음). */
    SCREEN_MANUAL("화면 질문"),
    /** 도우미 챗봇: 사용자 질문 하나 = 1회. */
    CHAT("챗봇 질문"),
    /** 선생님의 "AI로 문제 만들기". */
    QUIZ_GENERATE("AI 문제 만들기");

    private final String label;

    AiFeature(String label) {
        this.label = label;
    }

    /** 사용자 안내 문구에 쓰는 한국어 이름. */
    public String label() {
        return label;
    }
}
