package com.my.tutipal_back.rag.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.my.tutipal_back.ai.client.AiClient;
import com.my.tutipal_back.rag.dto.RagHit;

/**
 * PT-86: dedicated Gradle task ragDbTest only (excluded from test). Disposable loopback PostgreSQL/pgvector,
 * synthetic data, no env files, production DB or external model. Missing env fails instead of skipping.
 * Checks: 2 sessions x 2 embedding models -> only the asked session/model/indexed chunks, in the same order as an
 * in-memory exact-cosine reference; V20 chunker column default applied to old and new rows.
 */
class RagSearchRepositoryPostgresTest {

    private static final long OWNER = 301L;
    private static final long SESSION_A = 3001L;
    private static final long SESSION_B = 3002L;
    private static final String MODEL_A = "synthetic-a:1024";
    private static final String MODEL_B = "synthetic-b:1024";

    private static JdbcTemplate jdbc;
    private static RagSearchRepository repository;
    private static final List<Stored> stored = new ArrayList<>();
    private static float[] query;

    private record Stored(long session, String model, boolean indexed, String filename, int page, String content,
                          float[] embedding) {
    }

    @BeforeAll
    static void prepareDisposableDatabase() {
        String url = System.getenv("RAG_TEST_DB_URL");
        if (url == null || !url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/tutipal_rag_e2e")
                || !"disposable".equals(System.getenv("RAG_TEST_DB_ALLOW_RESET"))) {
            throw new IllegalStateException("Provide an explicit loopback tutipal_rag_e2e disposable database");
        }
        String password = System.getenv("RAG_TEST_DB_PASSWORD");
        if (password == null || password.isBlank()) {
            throw new IllegalStateException("Missing disposable DB password");
        }
        String user = url.substring(url.lastIndexOf('/') + 1);
        var ds = new DriverManagerDataSource(url, user, password);
        jdbc = new JdbcTemplate(ds);

        // A chunk written before V20 must get the default chunker after the forward migration.
        Flyway.configure().dataSource(ds).target("19").load().migrate();
        cleanSyntheticRows();
        insertOwnerAndSessions();
        long legacyMaterial = insertMaterial(SESSION_B, "legacy.md", true);
        jdbc.update("INSERT INTO material_chunks(material_id,session_id,page,content,embedding,embedding_model) "
                + "VALUES (?,?,NULL,'legacy chunk before V20',?::vector,?)",
                legacyMaterial, SESSION_B, AiClient.pgVector(unit(1024, 0)), "synthetic-legacy:1024");
        Flyway.configure().dataSource(ds).load().migrate();

        repository = new RagSearchRepository(jdbc);
        Random random = new Random(86);
        query = randomVector(random);
        long a1 = insertMaterial(SESSION_A, "a-sort.pdf", true);
        long a2 = insertMaterial(SESSION_A, "a-graph.md", true);
        long aPending = insertMaterial(SESSION_A, "a-pending.pdf", false);
        long b1 = insertMaterial(SESSION_B, "b-sort.pdf", true);
        int n = 0;
        for (long material : List.of(a1, a2, aPending, b1)) {
            long session = material == b1 ? SESSION_B : SESSION_A;
            boolean indexed = material != aPending;
            String filename = jdbc.queryForObject("SELECT filename FROM materials WHERE id=?", String.class, material);
            for (String model : List.of(MODEL_A, MODEL_B)) {
                for (int i = 0; i < 6; i++) {
                    float[] v = randomVector(random);
                    String content = "synthetic chunk " + (n++) + " " + filename + " " + model;
                    jdbc.update("INSERT INTO material_chunks(material_id,session_id,page,content,embedding,embedding_model) "
                            + "VALUES (?,?,?,?,?::vector,?)", material, session, i + 1, content, AiClient.pgVector(v), model);
                    stored.add(new Stored(session, model, indexed, filename, i + 1, content, v));
                }
            }
        }
    }

    /**
     * Added in PT-86 review round 1 (developer): the eval harness uses {@link InMemoryRagSearchRepository} as its
     * pgvector stand-in, so the real SQL must return the same hits in the same order as that class too.
     */
    @Test
    void realSqlMatchesTheEvalInMemoryStandIn() {
        InMemoryRagSearchRepository standIn = new InMemoryRagSearchRepository();
        for (Stored s : stored) {
            standIn.add(new InMemoryRagSearchRepository.Row(s.session(), s.indexed(), s.filename(), s.page(), s.content(),
                    s.model(), s.embedding()));
        }
        String vector = AiClient.pgVector(query);
        for (long session : List.of(SESSION_A, SESSION_B)) {
            for (String model : List.of(MODEL_A, MODEL_B)) {
                for (int limit : List.of(3, 10, 32)) {
                    List<RagHit> real = repository.findNearest(session, model, vector, limit);
                    List<RagHit> eval = standIn.findNearest(session, model, vector, limit);
                    assertThat(real).extracting(RagHit::content).containsExactlyElementsOf(
                            eval.stream().map(RagHit::content).toList());
                    for (int i = 0; i < real.size(); i++) {
                        assertThat(real.get(i).similarity()).isCloseTo(eval.get(i).similarity(), within(1e-4));
                    }
                }
            }
        }
    }

    private static void cleanSyntheticRows() {
        jdbc.update("DELETE FROM material_chunks WHERE session_id IN (?,?)", SESSION_A, SESSION_B);
        jdbc.update("DELETE FROM materials WHERE session_id IN (?,?)", SESSION_A, SESSION_B);
        jdbc.update("DELETE FROM study_sessions WHERE id IN (?,?)", SESSION_A, SESSION_B);
    }

    private static void insertOwnerAndSessions() {
        jdbc.update("INSERT INTO users(id,created_at,updated_at,name,provider,provider_id,role) VALUES "
                + "(?,now(),now(),'synthetic rag owner','dev','rag-owner','USER') ON CONFLICT DO NOTHING", OWNER);
        jdbc.update("INSERT INTO study_sessions(id,created_at,updated_at,title,topic,invite_code,owner_id) VALUES "
                + "(?,now(),now(),'rag a','코딩','RAGA01',?),(?,now(),now(),'rag b','코딩','RAGB01',?)",
                SESSION_A, OWNER, SESSION_B, OWNER);
    }

    private static long insertMaterial(long session, String filename, boolean indexed) {
        return jdbc.queryForObject("INSERT INTO materials(created_at,updated_at,session_id,uploader_id,filename,size_bytes,"
                + "storage_key,status,chunk_count,indexed_at) VALUES (now(),now(),?,?,?,1,?,?,6,"
                + (indexed ? "now()" : "NULL") + ") RETURNING id", Long.class,
                session, OWNER, filename, "synthetic/" + filename, indexed ? "DONE" : "PROCESSING");
    }

    private static float[] unit(int size, int index) {
        float[] v = new float[size];
        v[index] = 1;
        return v;
    }

    private static float[] randomVector(Random random) {
        float[] v = new float[1024];
        for (int i = 0; i < v.length; i++) {
            v[i] = (float) random.nextGaussian();
        }
        return v;
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    /** In-memory stand-in with the same WHERE semantics: session, embedding model, indexed material; exact cosine. */
    private static List<Stored> reference(long session, String model, int limit) {
        return stored.stream()
                .filter(s -> s.session() == session && s.model().equals(model) && s.indexed())
                .sorted(Comparator.comparingDouble((Stored s) -> cosine(s.embedding(), query)).reversed())
                .limit(limit)
                .toList();
    }

    private static void assertMatchesReference(long session, String model, int limit) {
        List<RagHit> hits = repository.findNearest(session, model, AiClient.pgVector(query), limit);
        List<Stored> expected = reference(session, model, limit);

        assertThat(hits).extracting(RagHit::content).containsExactlyElementsOf(expected.stream().map(Stored::content).toList());
        assertThat(hits).extracting(RagHit::filename).containsExactlyElementsOf(expected.stream().map(Stored::filename).toList());
        for (int i = 0; i < hits.size(); i++) {
            assertThat(hits.get(i).similarity()).isCloseTo(cosine(expected.get(i).embedding(), query), within(1e-4));
        }
    }

    @Test
    void sessionAModelAMatchesExactCosineReference() {
        assertMatchesReference(SESSION_A, MODEL_A, 32);
    }

    @Test
    void sessionAModelBMatchesExactCosineReference() {
        assertMatchesReference(SESSION_A, MODEL_B, 32);
    }

    @Test
    void sessionBModelAMatchesExactCosineReference() {
        assertMatchesReference(SESSION_B, MODEL_A, 32);
    }

    @Test
    void sessionBModelBMatchesExactCosineReference() {
        assertMatchesReference(SESSION_B, MODEL_B, 32);
    }

    @Test
    void candidateLimitIsHonouredInReferenceOrder() {
        assertMatchesReference(SESSION_A, MODEL_A, 3);
        assertThat(repository.findNearest(SESSION_A, MODEL_A, AiClient.pgVector(query), 3)).hasSize(3);
    }

    @Test
    void neverReturnsOtherSessionOtherModelOrUnindexedChunks() {
        List<RagHit> hits = repository.findNearest(SESSION_A, MODEL_A, AiClient.pgVector(query), 32);

        assertThat(hits).hasSize(12);
        assertThat(hits).extracting(RagHit::filename).containsOnly("a-sort.pdf", "a-graph.md");
        assertThat(hits).extracting(RagHit::content).allSatisfy(c -> assertThat(c).endsWith(MODEL_A));
    }

    @Test
    void hiddenMaterialCannotActivateSearchOrSupplyAnyVectorHits() {
        long session = 3003L;
        jdbc.update("INSERT INTO study_sessions(id,created_at,updated_at,title,topic,invite_code,owner_id) "
                + "VALUES (?,now(),now(),'moderation synthetic','수학','RAGC01',?)", session, OWNER);
        long material = insertMaterial(session, "moderated.txt", true);
        String vector = AiClient.pgVector(query);
        try {
            jdbc.update("INSERT INTO material_chunks(material_id,session_id,content,embedding,embedding_model) "
                    + "VALUES (?,?,'synthetic content',?::vector,?)", material, session, vector, MODEL_A);
            assertThat(repository.hasChunks(session)).isTrue();
            assertThat(repository.findNearest(session, MODEL_A, vector, 10)).hasSize(1);

            jdbc.update("UPDATE materials SET hidden_at = now() WHERE id = ?", material);
            assertThat(repository.hasChunks(session)).isFalse();
            assertThat(repository.findNearest(session, MODEL_A, vector, 10)).isEmpty();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM material_chunks WHERE material_id = ?", Long.class,
                    material)).isEqualTo(1L);

            jdbc.update("UPDATE materials SET hidden_at = NULL WHERE id = ?", material);
            assertThat(repository.hasChunks(session)).isTrue();
            assertThat(repository.findNearest(session, MODEL_A, vector, 10))
                    .extracting(RagHit::content).containsExactly("synthetic content");
        } finally {
            jdbc.update("DELETE FROM materials WHERE id = ?", material);
            jdbc.update("DELETE FROM study_sessions WHERE id = ?", session);
        }
    }

    @Test
    void v20AddsChunkerColumnWithCharDefaultForOldAndNewRows() {
        assertThat(jdbc.queryForObject(
                "SELECT chunker FROM material_chunks WHERE content='legacy chunk before V20'", String.class))
                .isEqualTo("char800-v1");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM material_chunks WHERE session_id IN (?,?) AND chunker <> 'char800-v1'",
                Long.class, SESSION_A, SESSION_B)).isZero();
        assertThat(jdbc.queryForObject("SELECT is_nullable FROM information_schema.columns "
                + "WHERE table_name='material_chunks' AND column_name='chunker'", String.class)).isEqualTo("NO");
        assertThat(jdbc.queryForObject("SELECT max(version::int) FROM flyway_schema_history WHERE success",
                Integer.class)).isGreaterThanOrEqualTo(20);
    }
}
