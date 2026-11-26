package com.my.tutipal_back.materials.service;

import java.nio.file.Files;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.my.tutipal_back.ai.client.AiClient;
import com.my.tutipal_back.rag.dto.RagHit;
import com.my.tutipal_back.rag.repository.InMemoryRagSearchRepository;

/**
 * Offline dev-only probe (spec 11, follow-up card material): char-bigram BM25 (k1=1.2, b=0.75) inside the query's
 * session, and RRF(k=60) of BM25 top-20 with cosine top-20 (similarity >= 0.5). Not wired into Spring.
 */
final class RagEvalBm25 {

    private static final double K1 = 1.2;
    private static final double B = 0.75;
    private static final int TOP = 20;
    private static final int RRF_K = 60;

    private RagEvalBm25() {
    }

    static List<String> bigrams(String text) {
        String t = text.toLowerCase().replaceAll("\\s+", "");
        List<String> out = new ArrayList<>();
        for (int i = 0; i + 2 <= t.length(); i++) {
            out.add(t.substring(i, i + 2));
        }
        return out;
    }

    static void run(Map<String, String> args) throws Exception {
        String split = args.getOrDefault("split", "dev");
        if (!"dev".equals(split)) {
            throw new IllegalArgumentException("BM25 probe is dev-only");
        }
        String chunker = args.getOrDefault("chunker", Chunker.CHAR_V1);
        var cache = RagEvalMain.loadCache();
        String model = RagEvalMain.cachedModel(args);
        InMemoryRagSearchRepository repo = RagEvalMain.repository(chunker, model, cache);
        AiClient ai = RagEvalMain.cachedAi(model, cache);
        List<RagEvalMain.EvalChunk> chunks = RagEvalMain.chunks(RagEvalMain.corpus(), chunker);

        Map<String, int[]> totals = new LinkedHashMap<>();   // method -> {hits}
        Map<String, double[]> mrr = new LinkedHashMap<>();
        Map<String, Integer> noAnswerEmpty = new LinkedHashMap<>();
        for (String m : List.of("cosine", "bm25", "rrf")) {
            totals.put(m, new int[1]);
            mrr.put(m, new double[1]);
            noAnswerEmpty.put(m, 0);
        }
        int answerable = 0;
        int noAnswer = 0;
        for (RagEvalMain.Query q : RagEvalMain.queries(split)) {
            long session = RagEvalMain.sessionId(q.session());
            List<RagEvalMain.EvalChunk> docs = chunks.stream().filter(c -> c.sessionId() == session).toList();
            float[] qv = ai.embedWithModel(List.of(q.text().strip())).vectors().getFirst();
            List<RagHit> cosine = repo.findNearest(session, model, AiClient.pgVector(qv), TOP).stream()
                    .filter(h -> h.similarity() >= 0.5).toList();
            List<RagHit> bm25 = bm25(q.text(), docs);
            List<RagHit> rrf = rrf(cosine, bm25);
            Map<String, List<RagHit>> lists = Map.of("cosine", cosine, "bm25", bm25, "rrf", rrf);
            if (q.answerable()) {
                answerable++;
            } else {
                noAnswer++;
            }
            for (var e : lists.entrySet()) {
                List<RagHit> top = e.getValue().subList(0, Math.min(3, e.getValue().size()));
                if (q.answerable()) {
                    double r = RagEvalMain.rr(top, q.gold(), 3);
                    totals.get(e.getKey())[0] += r > 0 ? 1 : 0;
                    mrr.get(e.getKey())[0] += r;
                } else if (top.isEmpty()) {
                    noAnswerEmpty.merge(e.getKey(), 1, Integer::sum);
                }
            }
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("row", "bm25probe_" + split + "_" + chunker);
        summary.put("chunker", chunker);
        summary.put("answerable", answerable);
        for (String m : totals.keySet()) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("hit3", totals.get(m)[0]);
            s.put("mrr3", RagEvalMain.round(mrr.get(m)[0] / answerable, 4));
            s.put("no_answer_empty", noAnswerEmpty.get(m) + "/" + noAnswer);
            summary.put(m, s);
        }
        summary.put("run_at", OffsetDateTime.now().toString());
        Files.createDirectories(RagEvalMain.OUT.resolve("results"));
        Files.writeString(RagEvalMain.OUT.resolve("results").resolve("bm25probe_" + chunker + ".json"),
                RagEvalMain.JSON.writeValueAsString(summary));
        System.out.println("SUMMARY " + RagEvalMain.JSON.writeValueAsString(summary));
    }

    private static List<RagHit> bm25(String query, List<RagEvalMain.EvalChunk> docs) {
        List<List<String>> tokens = docs.stream().map(d -> bigrams(d.content())).toList();
        double avg = tokens.stream().mapToInt(List::size).average().orElse(1);
        Map<String, Integer> df = new HashMap<>();
        for (List<String> t : tokens) {
            t.stream().distinct().forEach(term -> df.merge(term, 1, Integer::sum));
        }
        List<String> qTerms = bigrams(query).stream().distinct().toList();
        record Scored(int index, double score) {
        }
        List<Scored> scored = new ArrayList<>();
        for (int i = 0; i < docs.size(); i++) {
            Map<String, Integer> tf = new HashMap<>();
            tokens.get(i).forEach(term -> tf.merge(term, 1, Integer::sum));
            double s = 0;
            for (String term : qTerms) {
                int f = tf.getOrDefault(term, 0);
                if (f == 0) {
                    continue;
                }
                int n = df.getOrDefault(term, 0);
                double idf = Math.log(1 + (docs.size() - n + 0.5) / (n + 0.5));
                s += idf * f * (K1 + 1) / (f + K1 * (1 - B + B * tokens.get(i).size() / avg));
            }
            if (s > 0) {
                scored.add(new Scored(i, s));
            }
        }
        return scored.stream().sorted(Comparator.comparingDouble((Scored s) -> -s.score())).limit(TOP)
                .map(s -> {
                    RagEvalMain.EvalChunk c = docs.get(s.index());
                    return new RagHit(c.filename(), c.page(), c.content(), s.score());
                }).toList();
    }

    private static List<RagHit> rrf(List<RagHit> a, List<RagHit> b) {
        Map<String, Double> score = new LinkedHashMap<>();
        Map<String, RagHit> byKey = new HashMap<>();
        for (List<RagHit> list : List.of(a, b)) {
            for (int i = 0; i < list.size(); i++) {
                RagHit h = list.get(i);
                String key = h.filename() + "|" + h.page() + "|" + h.content();
                score.merge(key, 1.0 / (RRF_K + i + 1), Double::sum);
                byKey.putIfAbsent(key, h);
            }
        }
        return score.entrySet().stream().sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(e -> byKey.get(e.getKey())).toList();
    }
}
