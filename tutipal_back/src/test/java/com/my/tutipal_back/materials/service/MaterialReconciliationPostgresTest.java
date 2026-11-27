package com.my.tutipal_back.materials.service;

import static com.my.tutipal_back.materials.service.MaterialReconciliation.Result.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.my.tutipal_back.ai.client.AiClient;
import com.my.tutipal_back.ai.client.AiSlots;
import com.my.tutipal_back.common.exception.ApiException;
import com.my.tutipal_back.common.realtime.SessionEvents;
import com.my.tutipal_back.materials.repository.MaterialRepository;
import com.my.tutipal_back.rag.repository.RagSearchRepository;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.retries.StandardRetryStrategy;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

/** Runs only via materialDbTest, with disposable PostgreSQL and an S3 HTTP fixture on loopback. */
@SpringBootTest(properties = "spring.datasource.hikari.maximum-pool-size=6")
class MaterialReconciliationPostgresTest {
    private static final long TEACHER = 10701L;
    private static final long OUTSIDER = 10702L;
    private static final long LESSON_A = 107001L;
    private static final long LESSON_B = 107002L;
    private static final String MODEL = "pt107-fixture:1024";
    private static final String BUCKET = "pt107-isolated";
    private static final float[] VECTOR = unitVector();
    private static final byte[] ORIGINAL = "Original material for PT-107".getBytes(StandardCharsets.UTF_8);
    private static final byte[] RESTORED = "Restored text for PT-107".getBytes(StandardCharsets.UTF_8);
    private static final S3Fixture S3 = new S3Fixture();

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired MaterialService service;
    @Autowired MaterialRepository materials;
    @Autowired MaterialReconciliation reconciliation;
    @Autowired MaterialObjectStore objects;
    @Autowired MaterialTextExtractor extractor;
    @Autowired SessionEvents events;
    @Autowired RagSearchRepository rag;

    // Tests run the real worker on demand, so the app scheduler cannot race the assertions.
    @MockitoBean MaterialIndexWorker scheduledWorker;

    @DynamicPropertySource
    static void isolatedDb(DynamicPropertyRegistry props) {
        String url = System.getenv("MATERIAL_TEST_DB_URL");
        String password = System.getenv("MATERIAL_TEST_DB_PASSWORD");
        if (url == null || !url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/tutipal_pt107_e2e")
                || !"disposable".equals(System.getenv("MATERIAL_TEST_DB_ALLOW_RESET"))
                || password == null || password.isBlank()) {
            throw new IllegalStateException("Provide the explicit loopback tutipal_pt107_e2e disposable database");
        }
        props.add("spring.datasource.url", () -> url);
        props.add("spring.datasource.username", () -> "tutipal_pt107_e2e");
        props.add("spring.datasource.password", () -> password);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestStorage {
        @Bean
        @Primary
        MaterialObjectStore isolatedObjectStore() {
            return new MaterialObjectStore("s3", ".", BUCKET, "ap-northeast-2") {
                @Override
                S3Client s3() {
                    return S3Client.builder().endpointOverride(S3.endpoint()).forcePathStyle(true)
                            .region(Region.AP_NORTHEAST_2)
                            .serviceConfiguration(S3Configuration.builder().chunkedEncodingEnabled(false).build())
                            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                                    "pt107-fixture-key", "pt107-fixture-secret")))
                            .overrideConfiguration(config -> config
                                    .apiCallAttemptTimeout(Duration.ofSeconds(2))
                                    .retryStrategy(StandardRetryStrategy.builder().maxAttempts(1).build()))
                            .build();
                }
            };
        }
    }

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM material_object_cleanup WHERE session_id IN (?, ?)", LESSON_A, LESSON_B);
        jdbc.update("DELETE FROM materials WHERE session_id IN (?, ?)", LESSON_A, LESSON_B);
        jdbc.update("""
                INSERT INTO users (id, created_at, updated_at, name, provider, provider_id, role)
                VALUES (?, now(), now(), 'PT107 teacher', 'dev', 'pt107-teacher', 'USER'),
                       (?, now(), now(), 'PT107 outsider', 'dev', 'pt107-outsider', 'USER') ON CONFLICT DO NOTHING
                """, TEACHER, OUTSIDER);
        jdbc.update("""
                INSERT INTO study_sessions (id, created_at, updated_at, title, topic, invite_code, owner_id)
                VALUES (?, now(), now(), 'PT107 A', '코딩', 'PT107A', ?),
                       (?, now(), now(), 'PT107 B', '코딩', 'PT107B', ?) ON CONFLICT DO NOTHING
                """, LESSON_A, TEACHER, LESSON_B, TEACHER);
        jdbc.update("""
                INSERT INTO session_participants (created_at, updated_at, session_id, user_id, role)
                VALUES (now(), now(), ?, ?, 'TEACHER'), (now(), now(), ?, ?, 'TEACHER') ON CONFLICT DO NOTHING
                """, LESSON_A, TEACHER, LESSON_B, TEACHER);
        S3.files.clear();
        S3.headFailures.clear();
        S3.deletes.set(0);
        S3.rejectDelete = false;
        S3.rejectBucket = false;
        S3.loseDeleteResponse = false;
        S3.stalledHead = null;
    }

    @AfterAll
    static void closeStorage() {
        S3.server.stop(0);
    }

    @Test
    void rolledBackUploadKeepsCandidateWhenS3DeletionFailsAndCanBeRetried() {
        S3.rejectDelete = true;
        var tx = new TransactionTemplate(transactions);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            upload(LESSON_A);
            jdbc.queryForObject("SELECT 1 / 0", Integer.class);
        })).isInstanceOf(DataAccessException.class);

        var candidate = reconciliation.cleanupCandidates(LESSON_A).getFirst();
        assertThat(candidate.reason()).isEqualTo("UPLOAD");
        assertThat(candidate.lastError()).isEqualTo("DELETE_FAILED");
        assertThat(referenceCount(candidate.key())).isZero();
        assertThat(S3.files).containsKey(candidate.key());
        assertThat(list(LESSON_A)).contains("cleanup\t" + candidate.id() + "\tUPLOAD");
        assertThat(list(LESSON_B)).doesNotContain(candidate.key());
        assertThat(reconciliation.cleanup(candidate.id())).isEqualTo(RETRY);
        assertThat(S3.files).containsKey(candidate.key());

        S3.rejectDelete = false;
        assertThat(reconciliation.cleanup(candidate.id())).isEqualTo(DONE);
        int calls = S3.deletes.get();
        assertThat(reconciliation.cleanup(candidate.id())).isEqualTo(NO_CHANGE);
        assertThat(S3.deletes.get()).isEqualTo(calls);
        assertThat(S3.files).doesNotContainKey(candidate.key());
        assertThat(reconciliation.cleanupCandidates(LESSON_A)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void stillUncommittedUploadOrReuseHasNoVisibleReferenceButCannotBeDeleted(boolean reuse) throws Exception {
        long source = reuse ? upload(LESSON_B) : 0;
        var saved = new CountDownLatch(1);
        var commit = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var pending = executor.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                long id = reuse ? service.reuse(TEACHER, LESSON_A, source, false).id() : upload(LESSON_A);
                saved.countDown();
                await(commit);
                return id;
            }));
            try {
                await(saved);
                var candidate = reconciliation.cleanupCandidates(LESSON_A).getFirst();
                assertThat(referenceCount(candidate.key())).isZero();
                assertThat(reconciliation.cleanup(candidate.id())).isEqualTo(BUSY);
                assertThat(S3.files).containsKey(candidate.key());
                assertThat(S3.deletes.get()).isZero();
                commit.countDown();
                assertThat(key(pending.get(10, TimeUnit.SECONDS))).isEqualTo(candidate.key());
                assertThat(referenceCount(candidate.key())).isEqualTo(1);
                assertThat(reconciliation.cleanup(candidate.id())).isEqualTo(NO_CHANGE);
                assertThat(S3.files).containsKey(candidate.key());
            } finally {
                commit.countDown();
            }
        }
    }

    @Test
    void failedDeletionKeepsCopyAndAnyLiveReferenceDespiteStaleCandidate() {
        long original = upload(LESSON_A);
        long copy = service.reuse(TEACHER, LESSON_B, original, false).id();
        String originKey = key(original);
        String copyKey = key(copy);
        assertThat(copyKey).isNotEqualTo(originKey);
        assertThatThrownBy(() -> service.delete(OUTSIDER, original)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.download(OUTSIDER, copy)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.reuse(OUTSIDER, LESSON_B, original, false)).isInstanceOf(ApiException.class);
        S3.rejectDelete = true;
        service.delete(TEACHER, original);
        var candidate = reconciliation.cleanupCandidates(LESSON_A).getFirst();
        assertThat(candidate.reason()).isEqualTo("DELETE");
        assertThat(candidate.lastError()).isEqualTo("DELETE_FAILED");
        assertThat(referenceCount(originKey)).isZero();
        assertThat(S3.files).containsKeys(originKey, copyKey);
        S3.rejectDelete = false;

        jdbc.update("UPDATE materials SET storage_key = ? WHERE id = ?", originKey, copy);
        int calls = S3.deletes.get();
        assertThat(reconciliation.cleanup(candidate.id())).isEqualTo(REFERENCED);
        assertThat(S3.deletes.get()).isEqualTo(calls);
        assertThat(S3.files).containsKeys(originKey, copyKey);
        jdbc.update("UPDATE materials SET storage_key = ? WHERE id = ?", copyKey, copy);

        assertThat(reconciliation.cleanup(candidate.id())).isEqualTo(DONE);
        assertThat(S3.files).doesNotContainKey(originKey).containsKey(copyKey);
        assertThat(service.downloadData(service.download(TEACHER, copy))).isEqualTo(ORIGINAL);
        worker().indexPending();
        assertThat(search(LESSON_B)).containsExactly(new String(ORIGINAL, StandardCharsets.UTF_8));
        assertThat(search(LESSON_A)).isEmpty();
    }

    @Test
    void cleanupAfterAnUncertainDeleteDoesNotDeleteAnAlreadyMissingObjectAgain() {
        long original = upload(LESSON_A);
        String key = key(original);
        S3.loseDeleteResponse = true;
        service.delete(TEACHER, original);

        var candidate = reconciliation.cleanupCandidates(LESSON_A).getFirst();
        assertThat(candidate.lastError()).isEqualTo("DELETE_FAILED");
        assertThat(S3.files).doesNotContainKey(key);
        assertThat(S3.deletes.get()).isEqualTo(1);
        assertThat(reconciliation.cleanup(candidate.id())).isEqualTo(DONE);
        assertThat(S3.deletes.get()).isEqualTo(1);
        assertThat(reconciliation.cleanup(candidate.id())).isEqualTo(NO_CHANGE);
    }

    @Test
    void onlyConfirmedMissingHidesSearchAndOnlySuccessfulReindexRestoresIt() {
        long material = upload(LESSON_A);
        long copy = service.reuse(TEACHER, LESSON_B, material, false).id();
        String key = key(material);
        worker().indexPending();
        assertThat(rag.hasChunks(LESSON_A)).isTrue();
        assertThat(search(LESSON_A)).containsExactly(new String(ORIGINAL, StandardCharsets.UTF_8));

        S3.headFailures.put(key, 403);
        assertThat(reconciliation.markMissing(material)).isEqualTo(FORBIDDEN);
        S3.headFailures.put(key, 503);
        assertThat(reconciliation.markMissing(material)).isIn(UNKNOWN, TEMPORARY);
        S3.headFailures.remove(key);
        S3.stalledHead = key;
        assertThat(reconciliation.markMissing(material)).isEqualTo(TEMPORARY);
        S3.stalledHead = null;
        assertThat(rag.hasChunks(LESSON_A)).isTrue();
        assertThat(reconciliation.markMissing(material)).isEqualTo(PRESENT);

        S3.files.remove(key);
        S3.rejectBucket = true;
        assertThat(reconciliation.markMissing(material)).isEqualTo(UNKNOWN);
        S3.rejectBucket = false;
        assertThat(list(LESSON_A)).contains("original\t" + material + "\tMISSING");
        // Listing does not change the row or its previously published chunks.
        assertThat(rag.hasChunks(LESSON_A)).isTrue();
        assertThat(reconciliation.markMissing(material)).isEqualTo(DONE);
        assertThat(reconciliation.markMissing(material)).isEqualTo(NO_CHANGE);
        assertThat(rag.hasChunks(LESSON_A)).isFalse();
        assertThat(search(LESSON_A)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM material_chunks WHERE material_id = ?", Integer.class,
                material)).isEqualTo(1);
        assertThatThrownBy(() -> service.download(TEACHER, material)).isInstanceOfSatisfying(
                ApiException.class, ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(search(LESSON_B)).containsExactly(new String(ORIGINAL, StandardCharsets.UTF_8));
        assertThat(reconciliation.restore(material)).isEqualTo(MISSING);

        S3.files.put(key, new byte[] {' '});
        assertThat(list(LESSON_A)).contains("original\t" + material + "\tPRESENT");
        assertThat(reconciliation.restore(material)).isEqualTo(DONE);
        assertThat(reconciliation.restore(material)).isEqualTo(NO_CHANGE);
        assertThat(search(LESSON_A)).isEmpty();
        worker().indexPending();
        assertThat(search(LESSON_A)).isEmpty();

        S3.files.put(key, RESTORED);
        assertThat(reconciliation.restore(material)).isEqualTo(DONE);
        worker().indexPending();
        assertThat(rag.hasChunks(LESSON_A)).isTrue();
        assertThat(search(LESSON_A)).containsExactly(new String(RESTORED, StandardCharsets.UTF_8));
        assertThat(list(LESSON_A)).doesNotContain("original\t" + material);
        assertThat(service.downloadData(service.download(TEACHER, material))).isEqualTo(RESTORED);
        assertThat(service.downloadData(service.download(TEACHER, copy))).isEqualTo(ORIGINAL);
    }

    private List<String> search(long session) {
        return rag.findNearest(session, MODEL, AiClient.pgVector(VECTOR), 10).stream()
                .map(hit -> hit.content()).toList();
    }

    private MaterialIndexWorker worker() {
        var ai = mock(AiClient.class);
        doReturn(new AiClient.EmbedResponse(MODEL, List.of(VECTOR))).when(ai).embedWithModel(anyList());
        return new MaterialIndexWorker(materials, objects, extractor, ai, new AiSlots(1, Duration.ofSeconds(1)),
                jdbc, transactions, events, Chunker.CHAR_V1);
    }

    private long upload(long session) {
        return service.upload(TEACHER, session, new MockMultipartFile("file", "fixture.txt", "text/plain", ORIGINAL),
                false).id();
    }

    private String key(long id) {
        return jdbc.queryForObject("SELECT storage_key FROM materials WHERE id = ?", String.class, id);
    }

    private int referenceCount(String key) {
        return jdbc.queryForObject("SELECT count(*) FROM materials WHERE storage_key = ?", Integer.class, key);
    }

    private String list(long session) {
        var buffer = new ByteArrayOutputStream();
        try (var out = new PrintStream(buffer, true, StandardCharsets.UTF_8)) {
            assertThat(MaterialOpsCommand.execute(reconciliation, "list", session, out)).isZero();
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for the test transaction");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    private static float[] unitVector() {
        float[] vector = new float[1024];
        vector[0] = 1;
        return vector;
    }

    private static final class S3Fixture {
        private final HttpServer server;
        private final Map<String, byte[]> files = new ConcurrentHashMap<>();
        private final Map<String, Integer> headFailures = new ConcurrentHashMap<>();
        private final AtomicInteger deletes = new AtomicInteger();
        private volatile boolean rejectDelete;
        private volatile boolean rejectBucket;
        private volatile boolean loseDeleteResponse;
        private volatile String stalledHead;

        private S3Fixture() {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                server.createContext("/" + BUCKET, this::handle);
                server.start();
            } catch (IOException ex) {
                throw new IllegalStateException(ex);
            }
        }

        private URI endpoint() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        }

        private void handle(HttpExchange request) throws IOException {
            try (request) {
                String path = request.getRequestURI().getPath().substring(("/" + BUCKET).length());
                if (path.length() <= 1) {
                    request.sendResponseHeaders(rejectBucket ? 404 : 200, -1);
                    return;
                }
                String key = path.substring(1);
                byte[] file = files.get(key);
                switch (request.getRequestMethod()) {
                    case "PUT" -> {
                        files.put(key, request.getRequestBody().readAllBytes());
                        request.getResponseHeaders().set("ETag", "\"pt107-fixture\"");
                        request.sendResponseHeaders(200, -1);
                    }
                    case "HEAD" -> {
                        if (key.equals(stalledHead)) {
                            try {
                                Thread.sleep(Duration.ofMillis(2_500));
                            } catch (InterruptedException ex) {
                                Thread.currentThread().interrupt();
                            }
                        }
                        request.sendResponseHeaders(headFailures.getOrDefault(key, file == null ? 404 : 200), -1);
                    }
                    case "GET" -> {
                        if (file == null) {
                            error(request, 404, "NoSuchKey");
                        } else {
                            request.sendResponseHeaders(200, file.length);
                            request.getResponseBody().write(file);
                        }
                    }
                    case "DELETE" -> {
                        deletes.incrementAndGet();
                        if (rejectDelete) {
                            error(request, 503, "SlowDown");
                        } else {
                            files.remove(key);
                            if (loseDeleteResponse) {
                                error(request, 503, "SlowDown");
                            } else {
                                request.sendResponseHeaders(204, -1);
                            }
                        }
                    }
                    default -> request.sendResponseHeaders(405, -1);
                }
            }
        }

        private static void error(HttpExchange request, int status, String code) throws IOException {
            byte[] body = ("<Error><Code>" + code + "</Code></Error>").getBytes(StandardCharsets.UTF_8);
            request.getResponseHeaders().set("Content-Type", "application/xml");
            request.sendResponseHeaders(status, body.length);
            request.getResponseBody().write(body);
        }
    }
}
