package com.my.tutipal_back.users.dto;

/** 변경 대상 계정은 요청 본문이 아닌 로그인 세션으로 결정한다. */
public record UpdateNameRequest(String name) {
}
