package com.my.tutipal_back.common.auth.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.regex.Pattern;

import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.my.tutipal_back.common.exception.ApiException;

/**
 * 앱(Electron) 로그인: 시스템 브라우저에서 소셜 로그인 → 1회용 코드(메모리 60초) → tutipal:// 딥링크 → 앱이 세션 쿠키로 교환.
 * 앱이 처음에 만든 비밀값(verifier)의 해시(challenge)를 코드에 묶어 둬서, 다른 프로그램이 딥링크를 가로채도
 * verifier 가 없으면 교환할 수 없다 (RFC 8252 · PKCE 와 같은 방식).
 */
@Service
public class AppLoginService {

    /** base64url(SHA-256) = 43자 */
    static final Pattern CHALLENGE = Pattern.compile("^[A-Za-z0-9_-]{43}$");
    private static final Pattern CODE = Pattern.compile("^[A-Za-z0-9_-]{43}$");
    private static final Pattern VERIFIER = Pattern.compile("^[A-Za-z0-9_-]{43,128}$");
    private static final Duration TTL = Duration.ofSeconds(60);
    private static final SecureRandom RANDOM = new SecureRandom();
    private record Code(Long userId, String challenge, long expiresAt) {}
    private final ConcurrentHashMap<String, Code> codes = new ConcurrentHashMap<>();

    public static boolean validChallenge(String challenge) {
        return challenge != null && CHALLENGE.matcher(challenge).matches();
    }

    /** 브라우저 로그인 성공 직후: 1회용 코드 발급. */
    public String issue(Long userId, String challenge) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String code = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        long now = System.currentTimeMillis();
        codes.entrySet().removeIf(entry -> entry.getValue().expiresAt() < now);
        if (codes.size() >= 10000) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "로그인 요청이 많아요");
        codes.put(code, new Code(userId, challenge, now + TTL.toMillis()));
        return code;
    }

    /** 앱이 코드 + verifier 로 교환: 한 번만 쓸 수 있고, verifier 해시가 맞아야 사용자 id. */
    public Long exchange(String code, String verifier) {
        if (code == null || verifier == null || !CODE.matcher(code).matches() || !VERIFIER.matcher(verifier).matches()) {
            throw invalid();
        }
        Code value = codes.remove(code);
        if (value == null || value.expiresAt() < System.currentTimeMillis()) {
            throw invalid();
        }
        byte[] expected = value.challenge().getBytes(StandardCharsets.US_ASCII);
        byte[] actual = challengeOf(verifier).getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(expected, actual)) {
            throw invalid();
        }
        return value.userId();
    }

    static String challengeOf(String verifier) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ApiException invalid() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "로그인 코드가 만료됐거나 맞지 않아요. 다시 로그인해 주세요");
    }
}
