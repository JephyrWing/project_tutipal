package com.my.tutipal_back.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * PT-100: 세션 쿠키 설정을 실제 application.yaml / application-prod.yaml 에서 읽어 확인한다.
 * 환경 변수는 가장 높은 우선순위의 속성 출처로 흉내 낸다 (Spring Boot 와 같은 순서: 환경 변수 > 프로필 파일 > 기본 파일).
 */
class CookieProfileConfigTest {

    private static final String SECURE = "server.servlet.session.cookie.secure";
    private static final String HTTP_ONLY = "server.servlet.session.cookie.http-only";
    private static final String SAME_SITE = "server.servlet.session.cookie.same-site";

    @Test
    void prodSessionCookieIsSecureHttpOnlyAndLax() {
        StandardEnvironment env = environment(Map.of(), "application-prod.yaml");

        assertThat(env.getProperty(SECURE, Boolean.class)).isTrue();
        assertThat(env.getProperty(HTTP_ONLY, Boolean.class)).isTrue();
        assertThat(env.getProperty(SAME_SITE)).isEqualToIgnoringCase("lax");
    }

    @Test
    void prodSessionCookieSecureCannotBeTurnedOffByEnvironment() {
        StandardEnvironment env = environment(Map.of("SESSION_COOKIE_SECURE", "false"), "application-prod.yaml");

        assertThat(env.getProperty(SECURE, Boolean.class)).isTrue();
    }

    @Test
    void defaultSessionCookieIsSecureWhenNothingIsSet() {
        StandardEnvironment env = environment(Map.of());

        assertThat(env.getProperty(SECURE, Boolean.class)).isTrue();
        assertThat(env.getProperty(HTTP_ONLY, Boolean.class)).isTrue();
        assertThat(env.getProperty(SAME_SITE)).isEqualToIgnoringCase("lax");
    }

    static StandardEnvironment environment(Map<String, Object> environmentVariables, String... profileFiles) {
        StandardEnvironment env = new StandardEnvironment();
        var sources = env.getPropertySources();
        // 실제 OS 환경 변수가 시험 결과를 바꾸지 않게 비운다
        sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        sources.addFirst(new MapPropertySource("test-env", environmentVariables));
        for (int i = profileFiles.length - 1; i >= 0; i--) {
            sources.addLast(new PropertiesPropertySource(profileFiles[i], yaml(profileFiles[i])));
        }
        sources.addLast(new PropertiesPropertySource("application.yaml", yaml("application.yaml")));
        return env;
    }

    private static Properties yaml(String file) {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new ClassPathResource(file));
        Properties properties = factory.getObject();
        assertThat(properties).as(file).isNotNull();
        return properties;
    }
}
