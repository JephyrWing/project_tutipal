package com.my.tutipal_back.materials.service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.my.tutipal_back.ai.client.AiClient;
import com.my.tutipal_back.ai.client.RerankClient;
import com.my.tutipal_back.ai.client.RerankUnavailableException;
import com.my.tutipal_back.rag.config.RagRerankProperties;
import com.my.tutipal_back.rag.dto.RagHit;
import com.my.tutipal_back.rag.repository.InMemoryRagSearchRepository;
import com.my.tutipal_back.rag.service.RagSearchService;

/**
 * One result row: real RagSearchService + cached embeddings + in-memory exact-cosine repository (+ real HTTP
 * RerankClient against local FastAPI when --mode rerank). search() is called with limit = max(3, N) so the full
 * reordered candidate list is visible for MRR@N; the first 3 are exactly what limit=3 returns (prefix property of
 * both paths), and Hit@3 / MRR@3 / sources use only those.
 */
final class RagEvalSearch {

    private RagEvalSearch() {
    }

    /** Records the latency and outcome of each rerank call. */
    static final class TimedRerankClient extends RerankClient {
        Double lastMs;
        String lastError;

        TimedRerankClient(String baseUrl, String key, Duration timeout) {
            super(baseUrl, key, timeout);
        }

        void reset() {
            lastMs = null;
            lastError = null;
        }

        @Override
        public double[] rerank(String query, List<String> documents) {
            long started = System.nanoTime();
            try {
                return super.rerank(query, documents);
            } catch (RerankUnavailableException e) {
                lastError = e.reason();
                throw e;
            } catch (RuntimeException e) {
                lastError = e.getClass().getSimpleName();
                throw e;
            } finally {
                lastMs = (System.nanoTime() - started) / 1e6;
            }
        }
    }

    static void run(Map<String, String> args) throws Exception {
        String split = args.getOrDefault("split", "dev");
        String chunker = args.getOrDefault("chunker", Chunker.CHAR_V1);
        String mode = args.getOrDefault("mode", "cosine");
        boolean rerank = "rerank".equals(mode);
        int candidates = Integer.parseInt(args.getOrDefault("candidates", "20"));
        String label = args.getOrDefault("label", rerank ? "rerank" : "cosine");
        Duration timeout = RagEvalMain.duration(args.getOrDefault("timeout", "10s"));
        Path resultDir = RagEvalMain.OUT.resolve("results");
        Files.createDirectories(resultDir);
        String rowId = split + "_" + chunker + "_" + label + (rerank ? "_n" + candidates : "");

        boolean isSealed = "sealed".equals(split);

        // ---- preflight: everything that can fail without scoring a query, BEFORE the sealed marker ----
        Map<String, Object> sealed = null;
        if (isSealed) {
            sealed = RagEvalMain.requireSealedUnchanged(RagEvalMain.ROOT);   // mismatch -> abort
            Path report = Path.of("../docs/PT-86_RAG_EVAL.md");
            if (!Files.exists(report) || !Files.readString(report).contains("## 3. 동결 결정")) {
                throw new IllegalStateException("write the frozen decisions into docs/PT-86_RAG_EVAL.md before sealed");
            }
            if (Files.exists(SealedRunGuard.marker(resultDir, rowId)) && !SealedRunGuard.retryAllowed(resultDir, rowId)) {
                throw new IllegalStateException("sealed row already scored once: " + rowId);
            }
        }
        var cache = RagEvalMain.loadCache();
        String model = RagEvalMain.cachedModel(args);
        InMemoryRagSearchRepository repo = RagEvalMain.repository(chunker, model, cache);   // every chunk cached
        AiClient ai = RagEvalMain.cachedAi(model, cache);
        List<RagEvalMain.Query> queries = RagEvalMain.queries(split);
        for (RagEvalMain.Query q : queries) {
            ai.embedWithModel(List.of(q.text().strip()));   // every query cached (throws otherwise)
        }
        TimedRerankClient reranker = new TimedRerankClient(rerank ? args.get("rerank-url") : null,
                RagEvalMain.internalKey(args), timeout);
        if (rerank) {
            try {   // endpoint reachable with a synthetic pair (not an eval query, not counted)
                reranker.rerank("preflight", List.of("preflight document"));
            } catch (RuntimeException e) {
                throw new IllegalStateException("rerank endpoint not ready: " + reranker.lastError, e);
            }
            reranker.reset();
        }
        RagRerankProperties props = RagEvalMain.properties(rerank, candidates, timeout);
        RagSearchService service = RagEvalMain.service(ai, repo, reranker, props);
        int callLimit = Math.max(RagEvalMain.LIMIT, props.candidates());
        Map<String, String> sessionOfFile = new HashMap<>();
        for (RagEvalMain.Doc d : RagEvalMain.corpus()) {
            sessionOfFile.put(d.filename(), d.session());
        }

        if (isSealed) {
            SealedRunGuard.begin(resultDir, rowId);   // marker only now, right before the first sealed query
        }
        int[] scored = {0};
        try {
            measure(split, chunker, mode, rerank, label, rowId, sealed, model, repo, reranker, props, service, callLimit,
                    sessionOfFile, queries, timeout, resultDir, scored, args.getOrDefault("rerank-model", ""));
        } catch (Exception e) {
            if (isSealed) {
                SealedRunGuard.recordFailure(resultDir, rowId, e, scored[0]);
            }
            throw e;
        }
    }

    private static void measure(String split, String chunker, String mode, boolean rerank, String label, String rowId,
                                Map<String, Object> sealed, String model, InMemoryRagSearchRepository repo,
                                TimedRerankClient reranker, RagRerankProperties props, RagSearchService service,
                                int callLimit, Map<String, String> sessionOfFile, List<RagEvalMain.Query> queries,
                                Duration timeout, Path resultDir, int[] scored, String rerankModel) throws Exception {

        for (int i = 0; i < Math.min(RagEvalMain.WARMUP, queries.size()); i++) {   // warm-up, not measured
            RagEvalMain.Query q = queries.get(i);
            service.search(RagEvalMain.sessionId(q.session()), q.text(), callLimit);
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        List<Double> searchMs = new ArrayList<>();
        List<Double> rerankMs = new ArrayList<>();
        int answerable = 0;
        int hits = 0;
        double rr3 = 0;
        double rrN = 0;
        int returned = 0;
        int consistent = 0;
        int noAnswer = 0;
        int noAnswerEmpty = 0;
        int leakQueries = 0;
        int leakHits = 0;
        int rerankCalls = 0;
        int rerankErrors = 0;
        int over2s = 0;
        for (RagEvalMain.Query q : queries) {
            reranker.reset();
            long started = System.nanoTime();
            List<RagHit> all = service.search(RagEvalMain.sessionId(q.session()), q.text(), callLimit);
            double ms = (System.nanoTime() - started) / 1e6;
            List<RagHit> top = all.subList(0, Math.min(RagEvalMain.LIMIT, all.size()));
            searchMs.add(ms);
            if (reranker.lastMs != null) {
                rerankCalls++;
                rerankMs.add(reranker.lastMs);
                if (reranker.lastMs > 2000) {
                    over2s++;
                }
                if (reranker.lastError != null) {
                    rerankErrors++;
                }
            }
            int leaks = (int) top.stream().filter(h -> !q.session().equals(sessionOfFile.get(h.filename()))).count();
            leakHits += leaks;
            leakQueries += leaks > 0 ? 1 : 0;
            boolean hit = false;
            double r3 = 0;
            double rn = 0;
            if (q.answerable()) {
                answerable++;
                r3 = RagEvalMain.rr(top, q.gold(), RagEvalMain.LIMIT);
                rn = RagEvalMain.rr(all, q.gold(), all.size());
                hit = r3 > 0;
                hits += hit ? 1 : 0;
                rr3 += r3;
                rrN += rn;
                returned += top.size();
                consistent += (int) top.stream().filter(h -> RagEvalMain.sameSource(h, q.gold())).count();
            } else {
                noAnswer++;
                noAnswerEmpty += top.isEmpty() ? 1 : 0;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", q.id());
            row.put("session", q.session());
            row.put("note", q.note());
            row.put("answerable", q.answerable());
            row.put("hit3", hit);
            row.put("rr3", r3);
            row.put("rrN", rn);
            row.put("search_ms", RagEvalMain.round(ms, 2));
            row.put("rerank_ms", reranker.lastMs == null ? null : RagEvalMain.round(reranker.lastMs, 2));
            row.put("rerank_error", reranker.lastError);
            row.put("returned", all.size());
            List<Map<String, Object>> tops = new ArrayList<>();
            for (RagHit h : top) {
                Map<String, Object> t = new LinkedHashMap<>();
                t.put("file", h.filename());
                t.put("page", h.page());
                t.put("similarity", RagEvalMain.round(h.similarity(), 4));
                t.put("match", RagEvalMain.matchesAny(h, q.gold()));
                tops.add(t);
            }
            row.put("top3", tops);
            rows.add(row);
            scored[0]++;
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("row", rowId);
        summary.put("split", split);
        summary.put("chunker", chunker);
        summary.put("mode", mode);
        summary.put("label", label);
        summary.put("candidates", rerank ? props.candidates() : null);
        summary.put("rerank_model", rerank ? rerankModel : null);
        summary.put("embedding_model", model);
        summary.put("chunks", repo.size());
        summary.put("queries", queries.size());
        summary.put("answerable", answerable);
        summary.put("hit3", hits);
        summary.put("hit3_rate", RagEvalMain.round(answerable == 0 ? 0 : (double) hits / answerable, 4));
        summary.put("mrr3", RagEvalMain.round(answerable == 0 ? 0 : rr3 / answerable, 4));
        summary.put("mrrN", RagEvalMain.round(answerable == 0 ? 0 : rrN / answerable, 4));
        summary.put("source_consistency", RagEvalMain.round(returned == 0 ? 0 : (double) consistent / returned, 4));
        summary.put("no_answer", noAnswer);
        summary.put("no_answer_accuracy", RagEvalMain.round(noAnswer == 0 ? 0 : (double) noAnswerEmpty / noAnswer, 4));
        summary.put("leak_queries", leakQueries);
        summary.put("leak_hits", leakHits);
        summary.put("search_p50_ms", RagEvalMain.round(RagEvalMain.percentile(searchMs, 50), 2));
        summary.put("search_p95_ms", RagEvalMain.round(RagEvalMain.percentile(searchMs, 95), 2));
        summary.put("rerank_calls", rerankCalls);
        summary.put("rerank_errors", rerankErrors);
        summary.put("rerank_over_2s", over2s);
        summary.put("rerank_p50_ms", rerankMs.isEmpty() ? null : RagEvalMain.round(RagEvalMain.percentile(rerankMs, 50), 2));
        summary.put("rerank_p95_ms", rerankMs.isEmpty() ? null : RagEvalMain.round(RagEvalMain.percentile(rerankMs, 95), 2));
        summary.put("timeout_ms", timeout.toMillis());
        summary.put("warmup_excluded", RagEvalMain.WARMUP);
        summary.put("run_at", OffsetDateTime.now().toString());
        if (sealed != null) {
            summary.put("sealed_sha256", sealed);
        }

        StringBuilder lines = new StringBuilder();
        for (Map<String, Object> r : rows) {
            lines.append(RagEvalMain.JSON.writeValueAsString(r)).append('\n');
        }
        Files.writeString(resultDir.resolve(rowId + ".jsonl"), lines, StandardCharsets.UTF_8);
        Files.writeString(resultDir.resolve("summary.jsonl"), RagEvalMain.JSON.writeValueAsString(summary) + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        System.out.println("SUMMARY " + RagEvalMain.JSON.writeValueAsString(summary));
    }
}
