package com.my.tutipal_back.common.auth.controller;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 시스템 브라우저 로그인의 마지막 페이지. 브라우저는 앱 화면을 내려받지 않고 1회용 코드만 설치된 앱으로 넘긴다.
 * 디자인은 앱의 AppLoginDonePage(Sage 캐릭터 · 테마 색)와 맞추고, 이미지는 /auth/app/sage-waving.webp 하나만 쓴다.
 * CSP는 그대로 막아 두고 이 페이지의 style·script만 해시로 허용한다.
 */
@RestController
public class AppLoginDoneController {

    private static final String STYLE = """
        :root{--bg:#eef3fa;--surface:#fff;--text:#1f2633;--muted:#5b6474;--primary:#1a73f0;--primary-hover:#1463d6;--primary-weak:#e4eefe}
        *{box-sizing:border-box}
        body{margin:0;min-height:100vh;display:grid;place-items:center;padding:24px;
          font-family:'Pretendard Variable',Pretendard,'Malgun Gothic',system-ui,sans-serif;color:var(--text);
          background:radial-gradient(circle at 20% 10%,var(--primary-weak),transparent 55%),var(--bg)}
        .card{width:min(420px,100%);background:var(--surface);border-radius:24px;padding:36px 32px 28px;text-align:center;
          box-shadow:0 18px 48px rgba(26,115,240,.12),0 2px 6px rgba(31,38,51,.06)}
        .sage{width:140px;height:120px;margin:0 auto 12px;background:url(/auth/app/sage-waving.webp) 0 0/560px 120px no-repeat;
          animation:wave .72s steps(4) infinite,float 3s ease-in-out infinite}
        @keyframes wave{to{background-position:-560px 0}}
        @keyframes float{50%{transform:translateY(-6px)}}
        h1{margin:4px 0 8px;font-size:24px;letter-spacing:-.02em}
        p{margin:0;color:var(--muted);line-height:1.6}
        .btn{display:block;margin:24px 0 14px;padding:15px 20px;border-radius:14px;background:var(--primary);color:#fff;
          font-size:17px;font-weight:700;text-decoration:none;transition:background .15s}
        .btn:hover{background:var(--primary-hover)}
        .small{font-size:13px}
        .badge{display:inline-block;margin-bottom:6px;padding:4px 10px;border-radius:999px;background:var(--primary-weak);
          color:var(--primary);font-size:12px;font-weight:700}
        @media (prefers-reduced-motion:reduce){.sage{animation:none}}
        """;

    /** 주소창에서 1회용 코드를 지우고 앱 열기를 한 번 시도한다 (막히면 버튼으로 연다). */
    private static final String SCRIPT =
        "(()=>{const b=document.getElementById('open');if(!b)return;const l=b.getAttribute('href');"
        + "history.replaceState(null,'','/auth/app/done');location.href=l;})();";

    private static final String CSP = "default-src 'none'; img-src 'self'; style-src '" + sha256(STYLE) + "'; script-src '"
        + sha256(SCRIPT) + "'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'";

    @GetMapping(value = "/auth/app/done", produces = "text/html;charset=UTF-8")
    public ResponseEntity<String> done(@RequestParam(required = false) String code) {
        if (code == null || !code.matches("[A-Za-z0-9_-]{43}")) {
            return page(HttpStatus.BAD_REQUEST, """
                <h1>로그인 정보가 없어요</h1>
                <p>이미 사용했거나 만료된 로그인이에요.<br>TUTIPAL 앱에서 로그인을 다시 시작해 주세요.</p>
                """, false);
        }
        String link = "tutipal://auth?code=" + code; // code는 위 정규식으로 검증된 값만 들어온다
        return page(HttpStatus.OK, """
            <span class="badge">TUTIPAL</span>
            <h1>로그인 완료!</h1>
            <p>Sage가 앱에서 기다리고 있어요.<br>버튼을 누르고 브라우저가 물어보면 <b>열기</b>를 눌러 주세요.</p>
            <a id="open" class="btn" href="%s">TUTIPAL 앱 열기</a>
            <p class="small">앱이 열리면 이 탭은 닫아도 돼요 · 로그인 코드는 60초 뒤 만료돼요</p>
            """.formatted(link), true);
    }

    private static ResponseEntity<String> page(HttpStatus status, String body, boolean withScript) {
        String html = "<!doctype html><html lang='ko'><head><meta charset='utf-8'>"
            + "<meta name='viewport' content='width=device-width,initial-scale=1'><title>TUTIPAL 로그인</title>"
            + "<style>" + STYLE + "</style></head><body><main class='card'><div class='sage' role='img' aria-label='손을 흔드는 Sage'></div>"
            + body + "</main>" + (withScript ? "<script>" + SCRIPT + "</script>" : "") + "</body></html>";
        return ResponseEntity.status(status).header("Cache-Control", "no-store")
            .header("Referrer-Policy", "no-referrer")
            .header("Content-Security-Policy", CSP)
            .body(html);
    }

    private static String sha256(String s) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return "sha256-" + Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
