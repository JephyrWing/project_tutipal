package com.my.tutipal_back.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/** CD: 운영 프로필은 서버 전용 override 없이도 cloudflared의 X-Forwarded-*를 믿는다. */
class ProdProfileConfigTest {

    private Object prodProperty(String name) throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("prod", new ClassPathResource("application-prod.yaml"));
        return sources.stream()
                .map(source -> source.getProperty(name))
                .filter(value -> value != null)
                .findFirst()
                .orElse(null);
    }

    @Test
    void prodTrustsForwardedHeadersSoOAuthRedirectUsesHttps() throws IOException {
        assertThat(prodProperty("server.forward-headers-strategy")).hasToString("native");
    }

    @Test
    void prodKeepsSecureSessionCookie() throws IOException {
        assertThat(prodProperty("server.servlet.session.cookie.secure")).hasToString("true");
    }
}
