package com.my.tutipal_back.ai.client;

import java.net.http.HttpRequest;
import java.util.function.BiConsumer;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

/**
 * PT-110: Cloudflare Access service token for the AI node (Spring on EC2 -> https://ai.&lt;domain&gt; -> PC FastAPI).
 * Every FastAPI client (AiClient, ClusterClient, RerankClient, FocusClient) adds the headers through this one class,
 * next to the unchanged X-Internal-Key. Both values empty = no headers (same-host FastAPI, local development).
 * Only one of the two set is a configuration mistake and stops the start-up (the value itself is never printed).
 * The secret is never logged: toString() shows only whether the token is configured.
 */
@Component
public final class AiAccessHeaders {

    public static final String CLIENT_ID = "CF-Access-Client-Id";
    public static final String CLIENT_SECRET = "CF-Access-Client-Secret";

    private final String clientId;
    private final String clientSecret;

    public AiAccessHeaders(@Value("${app.ai.access.client-id:}") String clientId,
                           @Value("${app.ai.access.client-secret:}") String clientSecret) {
        String id = clientId == null ? "" : clientId.strip();
        String secret = clientSecret == null ? "" : clientSecret.strip();
        if (id.isEmpty() != secret.isEmpty()) {
            throw new IllegalArgumentException(
                    "app.ai.access.client-id 와 client-secret 은 둘 다 설정하거나 둘 다 비워야 해요");
        }
        this.clientId = id;
        this.clientSecret = secret;
    }

    /** No Access token (FastAPI on the same host or network). */
    public static AiAccessHeaders none() {
        return new AiAccessHeaders("", "");
    }

    public boolean enabled() {
        return !clientId.isEmpty();
    }

    /** Calls header(name, value) for each Access header; does nothing when not configured. */
    public void forEach(BiConsumer<String, String> header) {
        if (enabled()) {
            header.accept(CLIENT_ID, clientId);
            header.accept(CLIENT_SECRET, clientSecret);
        }
    }

    /** For RestClient.Builder#defaultHeaders. */
    public void addTo(HttpHeaders headers) {
        forEach(headers::set);
    }

    /** For JDK HttpClient requests (RerankClient, FocusClient). */
    public HttpRequest.Builder addTo(HttpRequest.Builder request) {
        forEach(request::header);
        return request;
    }

    @Override
    public String toString() {
        return "AiAccessHeaders[enabled=" + enabled() + "]";
    }
}
