package com.my.tutipal_back.ai.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * PT-87 D9 / AC-8: Spring -> FastAPI POST /v1/insights/cluster carries vectors and concept vectors only
 * (no text, no user id), with the internal key, numbers rounded to 5 decimals so the largest request stays
 * under the 4.5MB FastAPI body limit. Uses a real local HTTP server like AiClientChatFailureTest.
 */
class ClusterClientTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String OK_BODY = "{\"status\":\"OK\",\"algorithm\":\"kmeans-silhouette-v1\",\"k\":2,"
            + "\"silhouette\":0.61,\"clusters\":[{\"members\":[0,2],\"concept\":{\"index\":0,\"similarity\":0.83}},"
            + "{\"members\":[1],\"concept\":null}]}";

    private HttpServer server;
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<String> key = new AtomicReference<>();
    private final AtomicReference<String> path = new AtomicReference<>();

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private ClusterClient serving(int status, String response) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            key.set(exchange.getRequestHeaders().getFirst("X-Internal-Key"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            }
            exchange.close();
        });
        server.start();
        return new ClusterClient("http://127.0.0.1:" + server.getAddress().getPort(), "test-key", JSON);
    }

    private static float[] vector(float fill) {
        float[] v = new float[1024];
        Arrays.fill(v, fill);
        return v;
    }

    /** Ten vectors, the smallest request FastAPI accepts; the first three are given. */
    private static List<float[]> ten(float... firsts) {
        List<float[]> vectors = new ArrayList<>();
        for (float f : firsts) {
            vectors.add(vector(f));
        }
        while (vectors.size() < 10) {
            vectors.add(vector(0.05f * vectors.size()));
        }
        return vectors;
    }

    @Test
    void sendsOnlyVectorsAndConceptsWithTheInternalKey() throws IOException {
        ClusterClient client = serving(200, OK_BODY);

        client.cluster(ten(0.1f, 0.2f, 0.3f), List.of(vector(0.4f)));

        assertThat(path.get()).isEqualTo("/v1/insights/cluster");
        assertThat(key.get()).isEqualTo("test-key");
        JsonNode sent = JSON.readTree(body.get());
        List<String> fields = new ArrayList<>(sent.properties().stream().map(Map.Entry::getKey).toList());
        Collections.sort(fields);
        assertThat(fields).containsExactly("concepts", "vectors");
        assertThat(sent.get("vectors").size()).isEqualTo(10);
        assertThat(sent.get("vectors").get(0).size()).isEqualTo(1024);
        assertThat(sent.get("concepts").size()).isEqualTo(1);
        assertThat(body.get()).doesNotContain("user").doesNotContain("text").doesNotContain("question");
    }

    @Test
    void emptyConceptListIsSentAsEmptyArray() throws IOException {
        ClusterClient client = serving(200, OK_BODY);

        client.cluster(ten(0.1f, 0.2f, 0.3f), List.of());

        assertThat(JSON.readTree(body.get()).get("concepts").isArray()).isTrue();
        assertThat(JSON.readTree(body.get()).get("concepts").size()).isZero();
    }

    @Test
    void numbersAreRoundedToFiveDecimals() throws IOException {
        ClusterClient client = serving(200, OK_BODY);

        client.cluster(ten(0.123456789f, -0.987654321f, 0.5f), List.of());

        Matcher decimals = Pattern.compile("\\.(\\d+)").matcher(body.get());
        while (decimals.find()) {
            assertThat(decimals.group(1).length()).as("fraction digits").isLessThanOrEqualTo(5);
        }
        assertThat(JSON.readTree(body.get()).get("vectors").get(0).get(0).asDouble()).isEqualTo(0.12346);
        assertThat(JSON.readTree(body.get()).get("vectors").get(1).get(0).asDouble()).isEqualTo(-0.98765);
    }

    @Test
    void largestRequestStaysUnderFourAndAHalfMegabytes() throws IOException {
        ClusterClient client = serving(200, OK_BODY);
        List<float[]> vectors = new ArrayList<>();
        for (int i = 0; i < 256; i++) {
            vectors.add(vector(-0.876543f));
        }
        List<float[]> concepts = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            concepts.add(vector(-0.876543f));
        }

        client.cluster(vectors, concepts);

        assertThat(body.get().getBytes(StandardCharsets.UTF_8).length).isLessThan(4_500_000);
    }

    @Test
    void mapsTheResponse() throws IOException {
        ClusterClient client = serving(200, OK_BODY);

        ClusterClient.Result result = client.cluster(ten(0.1f, 0.2f, 0.3f),
                List.of(vector(0.4f)));

        assertThat(result.status()).isEqualTo("OK");
        assertThat(result.algorithm()).isEqualTo("kmeans-silhouette-v1");
        assertThat(result.k()).isEqualTo(2);
        assertThat(result.silhouette()).isEqualTo(0.61);
        assertThat(result.clusters()).hasSize(2);
        assertThat(result.clusters().get(0).members()).containsExactly(0, 2);
        assertThat(result.clusters().get(0).concept().index()).isEqualTo(0);
        assertThat(result.clusters().get(0).concept().similarity()).isEqualTo(0.83);
        assertThat(result.clusters().get(1).concept()).isNull();
    }

    @Test
    void lowQualityWithNullSilhouetteIsMapped() throws IOException {
        ClusterClient client = serving(200, "{\"status\":\"LOW_QUALITY\",\"algorithm\":\"kmeans-silhouette-v1\","
                + "\"k\":1,\"silhouette\":null,\"clusters\":[{\"members\":[0,1,2],\"concept\":null}]}");

        ClusterClient.Result result = client.cluster(ten(0.1f, 0.1f, 0.1f), List.of());

        assertThat(result.status()).isEqualTo("LOW_QUALITY");
        assertThat(result.silhouette()).isNull();
        assertThat(result.k()).isEqualTo(1);
    }

    @Test
    void serverErrorIsAiUnavailable() throws IOException {
        ClusterClient client = serving(503, "{\"detail\":\"busy\"}");

        assertThatThrownBy(() -> client.cluster(ten(0.1f), List.of()))
                .isInstanceOf(AiUnavailableException.class);
    }

    @Test
    void validationErrorIsAiUnavailable() throws IOException {
        ClusterClient client = serving(422, "{\"detail\":[]}");

        assertThatThrownBy(() -> client.cluster(ten(0.1f), List.of()))
                .isInstanceOf(AiUnavailableException.class);
    }

    @Test
    void unreachableServerIsAiUnavailable() {
        ClusterClient client = new ClusterClient("http://127.0.0.1:1", "test-key", JSON);

        assertThatThrownBy(() -> client.cluster(ten(0.1f), List.of()))
                .isInstanceOf(AiUnavailableException.class);
    }
}
