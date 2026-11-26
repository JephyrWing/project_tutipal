package com.my.tutipal_back.learning.focus.client;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.my.tutipal_back.ai.client.AiAccessHeaders;
import com.my.tutipal_back.learning.focus.FocusState;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * PT-90: Spring -> FastAPI POST /v1/focus/predict (docs/AI_WORKFLOW_CONTRACT.md 8). Sends only the category
 * features (rows of minutes_ago, category, idle_sec, switch_count, plus hour and weekday): no exe name, user or
 * session id.
 * <p>
 * Every failure (status other than 200, timeout, connection, malformed or oversized body) is a
 * {@link FocusUnavailableException} with a reason code; the caller shows UNKNOWN. The whole wait, body included, is
 * cut at the timeout (same approach as RerankClient). Bean: {@code FocusConfig}.
 */
public class FocusClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(1);
    /** A valid answer is about 150 bytes. */
    static final int MAX_RESPONSE_BYTES = 8 * 1024;

    public record Request(List<Row> rows, double hour, int weekday) {
    }

    public record Row(int minutesAgo, String category, int idleSec, int switchCount) {
    }

    /** modelVersion is null for PENDING. */
    public record Prediction(FocusState state, String modelVersion) {
    }

    private final URI endpoint;
    private final String internalKey;
    private final AiAccessHeaders access;
    private final Duration timeout;
    private final HttpClient http;
    private final JsonMapper json = JsonMapper.builder().build();

    public FocusClient(String baseUrl, String internalKey, AiAccessHeaders access, Duration timeout) {
        this.endpoint = baseUrl == null || baseUrl.isBlank() ? null
                : URI.create(stripSlash(baseUrl.strip()) + "/v1/focus/predict");
        this.internalKey = internalKey == null ? "" : internalKey;
        this.access = Objects.requireNonNull(access, "access");
        this.timeout = timeout;
        // HTTP/1.1: uvicorn rejects the JDK's HTTP/2 upgrade attempt (same as AiClient / RerankClient)
        this.http = endpoint == null ? null : HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
    }

    public Prediction predict(Request request) {
        if (endpoint == null) {
            throw new FocusUnavailableException("unconfigured");
        }
        HttpRequest httpRequest = access.addTo(HttpRequest.newBuilder(endpoint))   // PT-110 Access token, if any
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("X-Internal-Key", internalKey)
                .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body(request))))
                .build();
        HttpResponse<byte[]> response = send(httpRequest);
        if (response.statusCode() != 200) {
            throw new FocusUnavailableException("status");
        }
        return parse(response.body());
    }

    private static Map<String, Object> body(Request request) {
        List<Map<String, Object>> rows = new ArrayList<>(request.rows().size());
        for (Row row : request.rows()) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("minutes_ago", row.minutesAgo());
            r.put("category", row.category());
            r.put("idle_sec", row.idleSec());
            r.put("switch_count", row.switchCount());
            rows.add(r);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("rows", rows);
        body.put("hour", request.hour());
        body.put("weekday", request.weekday());
        return body;
    }

    private HttpResponse<byte[]> send(HttpRequest request) {
        CompletableFuture<HttpResponse<byte[]>> future = http.sendAsync(request, info -> {
            OptionalLong length = info.headers().firstValueAsLong("Content-Length");
            if (length.isPresent() && length.getAsLong() > MAX_RESPONSE_BYTES) {
                return HttpResponse.BodySubscribers.replacing(null);
            }
            return new LimitedBody(MAX_RESPONSE_BYTES);
        });
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new FocusUnavailableException("timeout");
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new FocusUnavailableException("interrupted");
        } catch (ExecutionException e) {
            throw new FocusUnavailableException(e.getCause() instanceof HttpTimeoutException ? "timeout" : "connect");
        }
    }

    private Prediction parse(byte[] body) {
        if (body == null || body.length == 0 || body.length > MAX_RESPONSE_BYTES) {
            throw new FocusUnavailableException("body");
        }
        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (JacksonException e) {
            throw new FocusUnavailableException("body");
        }
        if (root == null || !root.isObject()) {
            throw new FocusUnavailableException("body");
        }
        JsonNode status = root.get("status");
        if (status == null || !status.isString()) {
            throw new FocusUnavailableException("body");
        }
        switch (status.asString()) {
            case "insufficient_data":
                return new Prediction(FocusState.PENDING, null);
            case "ok":
                return ok(root);
            default:
                throw new FocusUnavailableException("body");
        }
    }

    private static Prediction ok(JsonNode root) {
        JsonNode state = root.get("state");
        JsonNode version = root.get("model_version");
        if (state == null || !state.isString() || version == null || !version.isString()) {
            throw new FocusUnavailableException("body");
        }
        FocusState mapped = switch (state.asString()) {
            case "focus" -> FocusState.FOCUS;
            case "distracted" -> FocusState.DISTRACTED;
            case "break" -> FocusState.BREAK;
            default -> throw new FocusUnavailableException("state");
        };
        String modelVersion = version.asString();
        if (modelVersion.length() > 64) {
            throw new FocusUnavailableException("body");
        }
        return new Prediction(mapped, modelVersion);
    }

    /** Counts received bytes and gives up (null body) once the limit is passed, chunked responses included. */
    static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {

        private final int limit;
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        LimitedBody(int limit) {
            this.limit = limit;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription s) {
            subscription = s;
            s.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            if (result.isDone()) {
                return;
            }
            for (ByteBuffer item : items) {
                if (out.size() + item.remaining() > limit) {
                    subscription.cancel();
                    result.complete(null);
                    return;
                }
                byte[] chunk = new byte[item.remaining()];
                item.get(chunk);
                out.write(chunk, 0, chunk.length);
            }
        }

        @Override
        public void onError(Throwable error) {
            result.completeExceptionally(error);
        }

        @Override
        public void onComplete() {
            result.complete(out.toByteArray());
        }
    }

    private static String stripSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
