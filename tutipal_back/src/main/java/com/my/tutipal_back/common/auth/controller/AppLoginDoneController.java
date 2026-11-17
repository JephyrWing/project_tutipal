package com.my.tutipal_back.common.auth.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 브라우저는 앱 화면을 내려받지 않고 1회용 코드만 설치된 앱으로 넘긴다. */
@RestController
public class AppLoginDoneController {
    @GetMapping(value="/auth/app/done", produces="text/html;charset=UTF-8")
    public ResponseEntity<String> done(@RequestParam String code) {
        if (!code.matches("[A-Za-z0-9_-]{43}")) return ResponseEntity.badRequest().body("잘못된 로그인 코드입니다");
        String link = "tutipal://auth?code=" + code;
        return ResponseEntity.ok().header("Cache-Control", "no-store")
            .header("Referrer-Policy", "no-referrer")
            .header("Content-Security-Policy", "default-src 'none'; base-uri 'none'; frame-ancestors 'none'")
            .body("<!doctype html><html lang='ko'><meta charset='utf-8'><title>TUTIPAL 로그인</title>"
                + "<meta http-equiv='refresh' content='0;url=" + link + "'><body><h1>앱으로 돌아가세요</h1><a href='"
                + link + "'>TUTIPAL 열기</a><p>로그인 코드는 60초 뒤 만료됩니다.</p></body></html>");
    }
}
