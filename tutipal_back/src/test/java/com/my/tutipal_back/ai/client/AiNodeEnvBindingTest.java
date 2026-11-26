package com.my.tutipal_back.ai.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * PT-110: the env names passed by infra/docker-compose.prod.yml really reach the Spring keys (relaxed binding).
 * A wrong name would be silently ignored and keep the 2s default.
 */
class AiNodeEnvBindingTest {

    private static Binder binder(Map<String, Object> env) {
        StandardEnvironment environment = new StandardEnvironment();
        MutablePropertySources sources = environment.getPropertySources();
        sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        sources.addFirst(new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, env));
        return new Binder(ConfigurationPropertySources.get(environment));
    }

    @Test
    void composeTimeoutNamesBindToTheClientTimeouts() {
        Binder binder = binder(Map.of("TUTIPAL_RAG_RERANK_TIMEOUT", "5s", "APP_FOCUS_PREDICT_TIMEOUT", "7s"));

        assertThat(binder.bind("tutipal.rag.rerank.timeout", Duration.class).get()).isEqualTo(Duration.ofSeconds(5));
        assertThat(binder.bind("app.focus.predict-timeout", Duration.class).get()).isEqualTo(Duration.ofSeconds(7));
    }
}
