package com.my.tutipal_back.ai.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

/** PT-86 review round 1: a chunked (no Content-Length) oversized response is cut as soon as it passes 64KB. */
class RerankClientBodyLimitTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void chunkedOversizedResponseFailsWithoutWaitingForTheEnd() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 0);   // chunked
            try (OutputStream out = exchange.getResponseBody()) {
                out.write("{\"model\":\"m\",\"scores\":[".getBytes());
                byte[] filler = "0.1,".repeat(2048).getBytes();   // 8KB
                for (int i = 0; i < 12; i++) {
                    out.write(filler);
                    out.flush();
                }
                Thread.sleep(5_000);   // never finishes within the client timeout
            } catch (IOException | InterruptedException ignored) {
                // client cancelled
            }
            exchange.close();
        });
        server.start();
        RerankClient client = new RerankClient("http://127.0.0.1:" + server.getAddress().getPort(), "k",
                AiAccessHeaders.none(),
                Duration.ofSeconds(4));

        long started = System.nanoTime();
        assertThatThrownBy(() -> client.rerank("q", List.of("a", "b")))
                .isInstanceOf(RerankUnavailableException.class)
                .extracting(e -> ((RerankUnavailableException) e).reason()).isEqualTo("body");
        assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(3_000);
    }
}
