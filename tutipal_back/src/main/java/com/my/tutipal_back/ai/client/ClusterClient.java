package com.my.tutipal_back.ai.client;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * PT-87 D9: Spring -> FastAPI POST /v1/insights/cluster. The body carries the question vectors and the concept
 * vectors only (no text, no user id); members in the answer are positions in the vector list, which Spring maps back
 * to questions in memory. Numbers are rounded to 5 decimals so the largest request (256 + 50 vectors x 1024) stays
 * well under FastAPI's 4.5MB body limit. Separate from AiClient so that the shared client is not modified.
 */
@Component
public class ClusterClient {

    public record Result(String status, String algorithm, int k, Double silhouette, List<Cluster> clusters) {
    }

    /** members = positions in the vectors list. concept = the matched concept vector, or null. */
    public record Cluster(List<Integer> members, ConceptMatch concept) {
    }

    /** index = position in the concepts list. */
    public record ConceptMatch(int index, double similarity) {
    }

    private static final int DECIMALS = 5;

    private final RestClient rest;
    private final JsonMapper json;

    public ClusterClient(@Value("${app.ai.base-url}") String baseUrl,
                         @Value("${app.ai.internal-key}") String internalKey,
                         JsonMapper json) {
        // HTTP/1.1 fixed for uvicorn, same as AiClient
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(60));
        this.rest = RestClient.builder()
                .requestFactory(factory)
                .baseUrl(baseUrl)
                .defaultHeader("X-Internal-Key", internalKey)
                .build();
        this.json = json;
    }

    /** Connection failure, any 4xx/5xx and an unreadable or malformed answer are all AiUnavailableException. */
    public Result cluster(List<float[]> vectors, List<float[]> concepts) {
        String body = "{\"vectors\":" + matrix(vectors) + ",\"concepts\":" + matrix(concepts) + "}";
        try {
            String response = rest.post().uri("/v1/insights/cluster")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            if (response == null) {
                throw new AiUnavailableException();
            }
            Result result = json.readValue(response, Result.class);
            if (result == null || result.status() == null || result.clusters() == null
                    || result.clusters().stream().anyMatch(c -> c == null || c.members() == null)) {
                throw new AiUnavailableException();
            }
            return result;
        } catch (RestClientException | JacksonException e) {
            throw new AiUnavailableException();
        }
    }

    private static String matrix(List<float[]> rows) {
        StringBuilder sb = new StringBuilder(rows.size() * 1024 * 9 + 2);
        sb.append('[');
        for (int r = 0; r < rows.size(); r++) {
            if (r > 0) {
                sb.append(',');
            }
            float[] row = rows.get(r);
            sb.append('[');
            for (int i = 0; i < row.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(round(row[i]));
            }
            sb.append(']');
        }
        return sb.append(']').toString();
    }

    /** At most 5 fraction digits, plain notation, trailing zeros dropped ("0.12346", "-0.5", "0"). */
    static String round(float value) {
        if (!Float.isFinite(value)) {
            throw new AiUnavailableException();
        }
        long scaled = Math.round((double) value * 100_000d);
        return BigDecimal.valueOf(scaled, DECIMALS).stripTrailingZeros().toPlainString();
    }
}
