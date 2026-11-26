package com.my.tutipal_back.ai.client;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * PT-86: Spring -> FastAPI POST /v1/rerank. 후보 조각마다 cross-encoder 점수를 받는다 (입력과 같은 순서·길이).
 * <p>
 * 실패는 전부 {@link RerankUnavailableException} (사유 코드만, 입력 원문 없음) → 호출자가 코사인 순서로 전환한다.
 * 채팅 경로에서 기다리는 시간을 묶어 두려고 응답 본문까지 포함한 전체 대기를 timeout 으로 자른다
 * (RestClient 의 read timeout 은 헤더까지만 걸려서 JDK HttpClient 를 직접 쓴다).
 * 빈 등록은 {@code RagRerankConfig}.
 */
public class RerankClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(1);
    /** 점수 32개면 1KB 남짓. 그보다 훨씬 큰 응답은 계약 위반으로 본다. */
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;

    private final URI endpoint;
    private final String internalKey;
    private final Duration timeout;
    private final HttpClient http;
    private final JsonMapper json = JsonMapper.builder().build();

    public RerankClient(String baseUrl, String internalKey, Duration timeout) {
        this.endpoint = baseUrl == null || baseUrl.isBlank() ? null : URI.create(stripSlash(baseUrl.strip()) + "/v1/rerank");
        this.internalKey = internalKey == null ? "" : internalKey;
        this.timeout = timeout;
        // HTTP/1.1 고정: JDK 기본(HTTP/2 업그레이드 시도)을 uvicorn 이 거부한다 (AiClient 와 같은 이유)
        this.http = endpoint == null ? null : HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
    }

    public boolean isConfigured() {
        return endpoint != null;
    }

    /** documents 와 같은 순서·길이의 점수. 높을수록 관련 있음 (값의 범위는 모델마다 다름). */
    public double[] rerank(String query, List<String> documents) {
        if (endpoint == null) {
            throw new RerankUnavailableException("unconfigured");
        }
        if (documents.isEmpty()) {
            return new double[0];
        }
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("X-Internal-Key", internalKey)
                .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(
                        Map.of("query", query, "documents", documents))))
                .build();
        HttpResponse<byte[]> response = send(request);
        if (response.statusCode() != 200) {
            throw new RerankUnavailableException("status");
        }
        return parse(response.body(), documents.size());
    }

    private HttpResponse<byte[]> send(HttpRequest request) {
        CompletableFuture<HttpResponse<byte[]>> future = http.sendAsync(request, info -> {
            OptionalLong length = info.headers().firstValueAsLong("Content-Length");
            if (length.isPresent() && length.getAsLong() > MAX_RESPONSE_BYTES) {
                return HttpResponse.BodySubscribers.replacing(null);
            }
            return new LimitedBody(MAX_RESPONSE_BYTES);   // chunked 응답도 상한을 넘는 순간 끊는다
        });
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new RerankUnavailableException("timeout");
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new RerankUnavailableException("interrupted");
        } catch (ExecutionException e) {
            throw new RerankUnavailableException(e.getCause() instanceof HttpTimeoutException
                    ? "timeout" : "connect");
        }
    }

    private double[] parse(byte[] body, int expected) {
        if (body == null || body.length == 0 || body.length > MAX_RESPONSE_BYTES) {
            throw new RerankUnavailableException("body");
        }
        JsonNode scores;
        try {
            JsonNode root = json.readTree(body);
            scores = root == null ? null : root.get("scores");
        } catch (JacksonException e) {
            throw new RerankUnavailableException("body");
        }
        if (scores == null || !scores.isArray()) {
            throw new RerankUnavailableException("body");
        }
        if (scores.size() != expected) {
            throw new RerankUnavailableException("size");
        }
        double[] out = new double[expected];
        for (int i = 0; i < expected; i++) {
            JsonNode score = scores.get(i);
            if (!score.isNumber()) {
                throw new RerankUnavailableException("body");
            }
            out[i] = score.doubleValue();
            if (!Double.isFinite(out[i])) {
                throw new RerankUnavailableException("nonfinite");
            }
        }
        return out;
    }

    /** 받은 바이트를 세다가 limit 을 넘으면 구독을 취소하고 null 로 끝낸다 (→ "body"). */
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
