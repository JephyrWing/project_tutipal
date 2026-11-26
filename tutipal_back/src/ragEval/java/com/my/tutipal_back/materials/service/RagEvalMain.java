package com.my.tutipal_back.materials.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.my.tutipal_back.ai.client.AiAccessHeaders;
import com.my.tutipal_back.ai.client.AiClient;
import com.my.tutipal_back.rag.config.RagRerankProperties;
import com.my.tutipal_back.rag.dto.RagHit;
import com.my.tutipal_back.rag.repository.InMemoryRagSearchRepository;
import com.my.tutipal_back.rag.service.RagSearchService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * PT-86 RAG evaluation harness (Gradle task ragEval; not part of test/build). Commands:
 * <pre>
 * embed  --base-url URL --key K                 corpus chunks (both chunkers) + all queries -> embedding cache
 * search --split dev|sealed --chunker C --mode cosine|rerank [--label L --candidates N --rerank-url URL
 *        --key K --timeout 10s --model M --force]  one result row
 * bm25   --split dev --chunker C                 offline char-bigram BM25 + RRF(k=60) probe (dev only)
 * </pre>
 * Embeddings are cached in build/rag-eval/cache keyed by (chunker, model, sha256(text)); search never calls the
 * embedding API. Results go to build/rag-eval/results.
 */
public final class RagEvalMain {

    static final Path ROOT = Path.of("src/ragEval/resources/rag-eval");
    static final Path OUT = Path.of("build/rag-eval");
    static final Path CACHE = OUT.resolve("cache/embeddings.jsonl");
    static final Path INDEX_META = OUT.resolve("cache/index-meta.json");
    static final List<String> CHUNKERS = List.of(Chunker.CHAR_V1, Chunker.SECTION_V1);
    static final String QUERY_KIND = "query";
    static final int LIMIT = 3;
    static final int WARMUP = 3;

    static final JsonMapper JSON = JsonMapper.builder().build();

    private RagEvalMain() {
    }

    public static void main(String[] argv) throws Exception {
        if (argv.length == 0) {
            throw new IllegalArgumentException("command required: embed | search | bm25");
        }
        Map<String, String> args = options(argv);
        switch (argv[0]) {
            case "embed" -> embed(args);
            case "search" -> RagEvalSearch.run(args);
            case "bm25" -> RagEvalBm25.run(args);
            case "chunks" -> chunkFingerprint();
            case "sealed-check" -> sealedCheckCommand(args);
            default -> throw new IllegalArgumentException("unknown command " + argv[0]);
        }
    }

    /** Internal key from the AI_INTERNAL_KEY environment variable (like Spring); --key only as a local override. */
    static String internalKey(Map<String, String> args) {
        String env = System.getenv("AI_INTERNAL_KEY");
        return args.getOrDefault("key", env == null ? "" : env);
    }

    /**
     * PT-110 Cloudflare Access service token from CF_ACCESS_CLIENT_ID / CF_ACCESS_CLIENT_SECRET (same names as the
     * Spring env), so the harness can also reach the AI node through the tunnel. Both unset = no header (local
     * FastAPI); only one set fails like Spring start-up. No command-line option: secrets stay out of argv.
     */
    static AiAccessHeaders accessHeaders() {
        return new AiAccessHeaders(System.getenv("CF_ACCESS_CLIENT_ID"), System.getenv("CF_ACCESS_CLIENT_SECRET"));
    }

    static Map<String, String> options(String[] argv) {
        Map<String, String> out = new HashMap<>();
        for (int i = 1; i < argv.length; i++) {
            if (!argv[i].startsWith("--")) {
                throw new IllegalArgumentException("unexpected " + argv[i]);
            }
            String key = argv[i].substring(2);
            if (i + 1 < argv.length && !argv[i + 1].startsWith("--")) {
                out.put(key, argv[++i]);
            } else {
                out.put(key, "true");
            }
        }
        return out;
    }

    // ---- corpus ----

    record Doc(String session, long sessionId, String filename, List<AiClient.Page> pages) {
    }

    record EvalChunk(String session, long sessionId, String filename, Integer page, String content) {
    }

    static long sessionId(String session) {
        return switch (session) {
            case "session-a" -> 1L;
            case "session-b" -> 2L;
            default -> throw new IllegalArgumentException(session);
        };
    }

    /** Only corpus/session-a and corpus/session-b (MANIFEST.md / SOURCES.md are not material). */
    static List<Doc> corpus() throws IOException {
        MaterialTextExtractor extractor = new MaterialTextExtractor();
        List<Doc> docs = new ArrayList<>();
        for (String session : List.of("session-a", "session-b")) {
            try (Stream<Path> files = Files.list(ROOT.resolve("corpus").resolve(session))) {
                for (Path file : files.sorted().toList()) {
                    String name = file.getFileName().toString();
                    docs.add(new Doc(session, sessionId(session), name, extractor.extract(name, Files.readAllBytes(file))));
                }
            }
        }
        return docs;
    }

    /** Same chunking as MaterialIndexWorker.chunk for each setting. */
    static List<EvalChunk> chunks(List<Doc> docs, String chunker) {
        List<EvalChunk> out = new ArrayList<>();
        for (Doc doc : docs) {
            if (Chunker.SECTION_V1.equals(chunker)) {
                for (Chunker.Chunk c : Chunker.split(doc.pages())) {
                    out.add(new EvalChunk(doc.session(), doc.sessionId(), doc.filename(), c.page(), c.content()));
                }
            } else {
                for (AiClient.Page page : doc.pages()) {
                    for (String content : Chunker.split(page.text(), 800, 100)) {
                        out.add(new EvalChunk(doc.session(), doc.sessionId(), doc.filename(), page.page(), content));
                    }
                }
            }
        }
        return out;
    }

    /** Prints chunk count and a sha256 over all (file, page, content) per chunker, to show whether a chunker change
     * alters the corpus output (and therefore whether cached embeddings / measured rows still apply). */
    private static void chunkFingerprint() throws IOException {
        List<Doc> docs = corpus();
        for (String chunker : CHUNKERS) {
            StringBuilder all = new StringBuilder();
            List<EvalChunk> list = chunks(docs, chunker);
            for (EvalChunk c : list) {
                all.append(c.filename()).append('|').append(c.page()).append('|').append(c.content()).append('\u0000');
            }
            System.out.printf("CHUNKS %s count=%d sha256=%s%n", chunker, list.size(), sha256(all.toString()));
            StringBuilder dump = new StringBuilder();
            for (EvalChunk c : list) {
                dump.append(c.filename()).append(" p=").append(c.page()).append(" len=").append(c.content().length())
                        .append(" sha=").append(sha256(c.content()), 0, 12).append(" | ")
                        .append(c.content().replace("\n", "\\n"), 0, Math.min(90, c.content().replace("\n", "\\n").length()))
                        .append('\n');
            }
            Files.createDirectories(OUT);
            Files.writeString(OUT.resolve("chunks-" + chunker + ".txt"), dump);
        }
    }

    // ---- queries ----

    record Gold(String file, Integer page, String anchor) {
        String basename() {
            return file.substring(file.lastIndexOf('/') + 1);
        }
    }

    record Query(String id, String session, String text, List<Gold> gold, String note) {
        boolean answerable() {
            return !gold.isEmpty();
        }
    }

    static List<Query> queries(String split) throws IOException {
        List<Query> out = new ArrayList<>();
        for (String line : Files.readAllLines(ROOT.resolve("queries-" + split + ".jsonl"), StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode n = JSON.readTree(line);
            List<Gold> gold = new ArrayList<>();
            for (JsonNode g : n.get("gold")) {
                JsonNode page = g.get("page");
                gold.add(new Gold(g.get("file").asString(), page == null || page.isNull() ? null : page.asInt(),
                        g.get("anchor").asString()));
            }
            out.add(new Query(n.get("id").asString(), n.get("session").asString(), n.get("query").asString(),
                    gold, n.has("note") ? n.get("note").asString() : ""));
        }
        return out;
    }

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String sha256(String text) {
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    /** Recorded hash vs actual hash of queries-sealed.jsonl. */
    static Map<String, Object> sealedCheck() throws IOException {
        return sealedCheck(ROOT);
    }

    static Map<String, Object> sealedCheck(Path root) throws IOException {
        String recorded = Files.readString(root.resolve("sealed.sha256")).trim().split("\\s+")[0];
        String actual = sha256(Files.readAllBytes(root.resolve("queries-sealed.jsonl")));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("recorded", recorded);
        out.put("actual", actual);
        out.put("match", recorded.equalsIgnoreCase(actual));
        return out;
    }

    /**
     * Sealed runs only on the frozen query file. A mismatch aborts (non-zero exit via the exception) before the
     * run-once marker is written, so a changed file can never produce "sealed" results under any label.
     * Re-freezing sealed.sha256 is a leader decision (e.g. after the 11/28 review), not something the harness does.
     */
    static Map<String, Object> requireSealedUnchanged(Path root) throws IOException {
        Map<String, Object> check = sealedCheck(root);
        if (!Boolean.TRUE.equals(check.get("match"))) {
            throw new IllegalStateException("queries-sealed.jsonl does not match sealed.sha256 (recorded "
                    + check.get("recorded") + ", actual " + check.get("actual")
                    + "); sealed run aborted - re-freeze the sealed set by decision before running it");
        }
        return check;
    }

    /** sealed-check [--root DIR]: same guard as a sealed search, without running anything (exit 1 on mismatch). */
    private static void sealedCheckCommand(Map<String, String> args) throws IOException {
        Path root = Path.of(args.getOrDefault("root", ROOT.toString()));
        System.out.println("SEALED " + JSON.writeValueAsString(requireSealedUnchanged(root)));
    }

    // ---- embedding cache ----

    static String key(String kind, String model, String sha) {
        return kind + "|" + model + "|" + sha;
    }

    static Map<String, float[]> loadCache() throws IOException {
        Map<String, float[]> cache = new HashMap<>();
        if (!Files.exists(CACHE)) {
            return cache;
        }
        for (String line : Files.readAllLines(CACHE, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode n = JSON.readTree(line);
            JsonNode v = n.get("vector");
            float[] vector = new float[v.size()];
            for (int i = 0; i < vector.length; i++) {
                vector[i] = (float) v.get(i).doubleValue();
            }
            cache.put(key(n.get("kind").asString(), n.get("model").asString(), n.get("sha").asString()), vector);
        }
        return cache;
    }

    /** The single embedding model present in the cache (or --model). */
    static String cachedModel(Map<String, String> args) throws IOException {
        if (args.containsKey("model")) {
            return args.get("model");
        }
        List<String> models = Files.readAllLines(CACHE, StandardCharsets.UTF_8).stream()
                .filter(l -> !l.isBlank())
                .map(l -> JSON.readTree(l).get("model").asString())
                .distinct().toList();
        if (models.size() != 1) {
            throw new IllegalStateException("expected one embedding model in cache, found " + models);
        }
        return models.getFirst();
    }

    private static void embed(Map<String, String> args) throws Exception {
        AiClient ai = new AiClient(args.getOrDefault("base-url", "http://127.0.0.1:18086"),
                internalKey(args), accessHeaders(), JSON);
        Files.createDirectories(CACHE.getParent());
        Map<String, float[]> cache = loadCache();
        List<Doc> docs = corpus();

        List<String> queryTexts = new ArrayList<>();
        for (String split : List.of("dev", "sealed")) {
            queries(split).forEach(q -> queryTexts.add(q.text().strip()));
        }
        String model = ai.embedWithModel(List.of(queryTexts.getFirst())).model();
        System.out.println("embedding model: " + model);

        Map<String, Object> meta = Files.exists(INDEX_META)
                ? JSON.readValue(Files.readString(INDEX_META), LinkedHashMap.class) : new LinkedHashMap<>();
        for (String chunker : CHUNKERS) {
            List<String> texts = chunks(docs, chunker).stream().map(EvalChunk::content).toList();
            long ms = embedMissing(ai, cache, chunker, model, texts);
            if (ms >= 0) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("chunks", texts.size());
                m.put("embed_ms", ms);
                m.put("model", model);
                m.put("measured_at", OffsetDateTime.now().toString());
                meta.put(chunker, m);
            }
            System.out.printf("%s: %d chunks, embed %s%n", chunker, texts.size(), ms < 0 ? "cached" : ms + " ms");
        }
        long qms = embedMissing(ai, cache, QUERY_KIND, model, queryTexts);
        System.out.printf("queries: %d, embed %s%n", queryTexts.size(), qms < 0 ? "cached" : qms + " ms");
        Files.writeString(INDEX_META, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(meta));
    }

    /** Embeds the texts not yet cached (batches of 32 like MaterialIndexWorker). -1 when everything was cached. */
    private static long embedMissing(AiClient ai, Map<String, float[]> cache, String kind, String model,
                                     List<String> texts) throws IOException {
        List<String> missing = texts.stream().distinct()
                .filter(t -> !cache.containsKey(key(kind, model, sha256(t)))).toList();
        if (missing.isEmpty()) {
            return -1;
        }
        long started = System.nanoTime();
        for (int from = 0; from < missing.size(); from += 32) {
            List<String> batch = missing.subList(from, Math.min(missing.size(), from + 32));
            AiClient.EmbedResponse res = ai.embedWithModel(batch);
            if (!model.equals(res.model())) {
                throw new IllegalStateException("embedding model changed: " + res.model());
            }
            StringBuilder lines = new StringBuilder();
            for (int i = 0; i < batch.size(); i++) {
                String sha = sha256(batch.get(i));
                cache.put(key(kind, model, sha), res.vectors().get(i));
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("kind", kind);
                row.put("model", model);
                row.put("sha", sha);
                row.put("vector", res.vectors().get(i));
                lines.append(JSON.writeValueAsString(row)).append('\n');
            }
            Files.writeString(CACHE, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        // Only meaningful when the whole set was embedded in this run.
        return missing.size() == texts.stream().distinct().count() ? (System.nanoTime() - started) / 1_000_000 : -2;
    }

    // ---- shared helpers for search / bm25 ----

    /** Repository with both sessions' chunks for one chunker (cached vectors only). */
    static InMemoryRagSearchRepository repository(String chunker, String model, Map<String, float[]> cache)
            throws IOException {
        InMemoryRagSearchRepository repo = new InMemoryRagSearchRepository();
        for (EvalChunk c : chunks(corpus(), chunker)) {
            float[] v = cache.get(key(chunker, model, sha256(c.content())));
            if (v == null) {
                throw new IllegalStateException("missing cached embedding; run embed first");
            }
            repo.add(new InMemoryRagSearchRepository.Row(c.sessionId(), true, c.filename(), c.page(), c.content(),
                    model, v));
        }
        return repo;
    }

    /** AiClient whose embeddings come from the cache only (no network). */
    static AiClient cachedAi(String model, Map<String, float[]> cache) {
        return new AiClient("http://127.0.0.1:9", "", AiAccessHeaders.none(), JSON) {   // no network
            @Override
            public EmbedResponse embedWithModel(List<String> texts) {
                List<float[]> vectors = new ArrayList<>();
                for (String t : texts) {
                    float[] v = cache.get(key(QUERY_KIND, model, sha256(t)));
                    if (v == null) {
                        throw new IllegalStateException("query embedding not cached");
                    }
                    vectors.add(v);
                }
                return new EmbedResponse(model, vectors);
            }
        };
    }

    static String norm(String s) {
        return s.replaceAll("\\s+", " ").strip();
    }

    /** hit = same file (and page for PDFs) and whitespace-normalised anchor contained in the chunk content. */
    static boolean matches(RagHit hit, Gold g) {
        return g.basename().equals(hit.filename()) && (g.page() == null || g.page().equals(hit.page()))
                && norm(hit.content()).contains(norm(g.anchor()));
    }

    static boolean matchesAny(RagHit hit, List<Gold> gold) {
        return gold.stream().anyMatch(g -> matches(hit, g));
    }

    static boolean sameSource(RagHit hit, List<Gold> gold) {
        return gold.stream().anyMatch(g -> g.basename().equals(hit.filename())
                && (g.page() == null || g.page().equals(hit.page())));
    }

    /** Reciprocal rank of the first matching hit within the first k (0 when none). */
    static double rr(List<RagHit> hits, List<Gold> gold, int k) {
        for (int i = 0; i < Math.min(k, hits.size()); i++) {
            if (matchesAny(hits.get(i), gold)) {
                return 1.0 / (i + 1);
            }
        }
        return 0;
    }

    static double percentile(List<Double> values, double p) {
        if (values.isEmpty()) {
            return Double.NaN;
        }
        List<Double> sorted = values.stream().sorted().toList();
        int idx = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.clamp(idx, 0, sorted.size() - 1));
    }

    static double round(double v, int digits) {
        double f = Math.pow(10, digits);
        return Math.round(v * f) / f;
    }

    static Duration duration(String text) {
        String t = text.trim().toLowerCase();
        if (t.endsWith("ms")) {
            return Duration.ofMillis(Long.parseLong(t.substring(0, t.length() - 2)));
        }
        if (t.endsWith("s")) {
            return Duration.ofMillis((long) (Double.parseDouble(t.substring(0, t.length() - 1)) * 1000));
        }
        return Duration.ofMillis(Long.parseLong(t));
    }

    static RagRerankProperties properties(boolean enabled, int candidates, Duration timeout) {
        return new RagRerankProperties(enabled, candidates, timeout, 2);
    }

    static RagSearchService service(AiClient ai, InMemoryRagSearchRepository repo,
                                    com.my.tutipal_back.ai.client.RerankClient reranker, RagRerankProperties props) {
        return new RagSearchService(ai, repo, reranker, props);
    }
}
