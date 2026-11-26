package com.my.tutipal_back.ai.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

/** PT-110: Cloudflare Access service token headers, configured once and shared by every FastAPI client. */
class AiAccessHeadersTest {

    @Test
    void bothValuesSetAddsBothHeaders() {
        AiAccessHeaders access = new AiAccessHeaders(" id.access ", " secret-value ");
        Map<String, String> got = new LinkedHashMap<>();

        access.forEach(got::put);

        assertThat(access.enabled()).isTrue();
        assertThat(got).containsExactly(
                Map.entry("CF-Access-Client-Id", "id.access"),
                Map.entry("CF-Access-Client-Secret", "secret-value"));
    }

    @Test
    void emptyOrNullValuesAddNoHeader() {
        for (AiAccessHeaders access : new AiAccessHeaders[] {
                AiAccessHeaders.none(), new AiAccessHeaders("", ""), new AiAccessHeaders(null, null),
                new AiAccessHeaders("  ", "\t")}) {
            HttpHeaders headers = new HttpHeaders();
            access.addTo(headers);
            HttpRequest request = access.addTo(HttpRequest.newBuilder(URI.create("http://127.0.0.1/"))).build();

            assertThat(access.enabled()).isFalse();
            assertThat(headers.isEmpty()).isTrue();
            assertThat(request.headers().map()).isEmpty();
        }
    }

    @Test
    void onlyOneValueSetStopsStartupWithoutPrintingIt() {
        assertThatThrownBy(() -> new AiAccessHeaders("id-only-value", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("id-only-value");
        assertThatThrownBy(() -> new AiAccessHeaders(null, "secret-only-value"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("secret-only-value");
    }

    @Test
    void toStringNeverShowsTheValues() {
        AiAccessHeaders access = new AiAccessHeaders("id-value", "secret-value");

        assertThat(access.toString()).doesNotContain("id-value").doesNotContain("secret-value")
                .isEqualTo("AiAccessHeaders[enabled=true]");
    }

    @Test
    void addsToJdkRequestAndSpringHeaders() {
        AiAccessHeaders access = new AiAccessHeaders("id-value", "secret-value");
        HttpHeaders headers = new HttpHeaders();
        access.addTo(headers);
        HttpRequest request = access.addTo(HttpRequest.newBuilder(URI.create("http://127.0.0.1/"))).build();

        assertThat(headers.getFirst("CF-Access-Client-Id")).isEqualTo("id-value");
        assertThat(headers.getFirst("CF-Access-Client-Secret")).isEqualTo("secret-value");
        assertThat(request.headers().firstValue("CF-Access-Client-Id")).hasValue("id-value");
        assertThat(request.headers().firstValue("CF-Access-Client-Secret")).hasValue("secret-value");
    }
}
