package com.my.tutipal_back.common.config;

import java.util.Arrays;
import java.util.List;

import org.springframework.core.env.Environment;

/**
 * 개발 로그인(app.dev-login)을 실제로 켤지 정하는 한 곳.
 * app.dev-login=true 이고 활성 프로필에 local 이 있으며 prod 가 없을 때만 켠다.
 * SecurityConfig(/dev/login 공개), CsrfController(/api/auth/config), AppLoginController(dev 공급자)가 같은 판단을 쓴다.
 * 조건 밖에서 app.dev-login=true 면 DevLoginGuard 가 앱 시작을 막는다.
 */
public final class DevLogin {

    public static final String PROPERTY = "app.dev-login";

    private DevLogin() {
    }

    public static boolean requested(Environment env) {
        // @ConditionalOnProperty(havingValue = "true") 와 같은 기준: "true"(대소문자 무시)만 켠 것으로 본다
        return "true".equalsIgnoreCase(env.getProperty(PROPERTY, "").trim());
    }

    public static boolean allowedProfiles(Environment env) {
        List<String> profiles = Arrays.asList(env.getActiveProfiles());
        return profiles.contains("local") && !profiles.contains("prod");
    }

    public static boolean enabled(Environment env) {
        return requested(env) && allowedProfiles(env);
    }
}
