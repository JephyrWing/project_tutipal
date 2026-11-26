package com.my.tutipal_back.rag.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/** PT-86 spec 2: tutipal.rag.rerank.* defaults and candidate clamping, tutipal.materials.chunker default. */
class RagRerankPropertiesTest {

    private static StandardEnvironment applicationYaml() throws Exception {
        var env = new StandardEnvironment();
        for (var source : new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml"))) {
            env.getPropertySources().addLast(source);
        }
        return env;
    }

    @Test
    void applicationYamlBindsRerankDefaults() throws Exception {
        RagRerankProperties props = Binder.get(applicationYaml()).bind("tutipal.rag.rerank", RagRerankProperties.class).get();

        assertThat(props.enabled()).isFalse();
        assertThat(props.candidates()).isEqualTo(10);
        assertThat(props.timeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(props.minCandidates()).isEqualTo(2);
    }

    @Test
    void applicationYamlDefaultsChunkerToChar800() throws Exception {
        assertThat(applicationYaml().getProperty("tutipal.materials.chunker")).isEqualTo("char800-v1");
    }

    @Test
    void kebabCaseKeysBindOverrides() {
        var source = new MapConfigurationPropertySource(Map.of(
                "tutipal.rag.rerank.enabled", "true",
                "tutipal.rag.rerank.candidates", "10",
                "tutipal.rag.rerank.timeout", "1500ms",
                "tutipal.rag.rerank.min-candidates", "3"));

        RagRerankProperties props = new Binder(source).bind("tutipal.rag.rerank", RagRerankProperties.class).get();

        assertThat(props.enabled()).isTrue();
        assertThat(props.candidates()).isEqualTo(10);
        assertThat(props.timeout()).isEqualTo(Duration.ofMillis(1500));
        assertThat(props.minCandidates()).isEqualTo(3);
    }

    @Test
    void candidatesAboveThirtyTwoAreClamped() {
        assertThat(new RagRerankProperties(true, 100, Duration.ofSeconds(2), 2).candidates()).isEqualTo(32);
        assertThat(new RagRerankProperties(true, 33, Duration.ofSeconds(2), 2).candidates()).isEqualTo(32);
    }

    @Test
    void candidatesBelowOneAreClamped() {
        assertThat(new RagRerankProperties(true, 0, Duration.ofSeconds(2), 2).candidates()).isEqualTo(1);
        assertThat(new RagRerankProperties(true, -5, Duration.ofSeconds(2), 2).candidates()).isEqualTo(1);
    }

    @Test
    void candidatesInsideRangeAreKept() {
        assertThat(new RagRerankProperties(true, 1, Duration.ofSeconds(2), 2).candidates()).isEqualTo(1);
        assertThat(new RagRerankProperties(true, 32, Duration.ofSeconds(2), 2).candidates()).isEqualTo(32);
    }
}
