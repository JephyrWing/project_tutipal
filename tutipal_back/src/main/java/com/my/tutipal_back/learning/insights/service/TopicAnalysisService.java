package com.my.tutipal_back.learning.insights.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.my.tutipal_back.ai.client.AiClient;
import com.my.tutipal_back.ai.client.AiSlots;
import com.my.tutipal_back.ai.client.AiUnavailableException;
import com.my.tutipal_back.ai.client.ClusterClient;
import com.my.tutipal_back.ai.quota.AiFeature;
import com.my.tutipal_back.ai.quota.AiQuota;
import com.my.tutipal_back.learning.insights.dto.ConceptView;
import com.my.tutipal_back.learning.insights.dto.ConfusionsResponse;
import com.my.tutipal_back.learning.insights.repository.ConceptStore;
import com.my.tutipal_back.learning.insights.repository.QuestionRow;
import com.my.tutipal_back.sessions.service.StudySessionService;

/**
 * PT-87 AC-16..18: "what are students confused about" TOP3 for the teacher.
 *
 * <p>Order: teacher check -> enough data (else INSUFFICIENT_DATA, no AI, no quota) -> cache (hit = no AI, no quota)
 * -> INSIGHTS quota -> embeddings (existing /v1/embed, batches of 64) -> FastAPI clustering -> merge / filter / rank
 * in Spring memory. Question text only goes to the embedding call; FastAPI gets vectors and positions; the response
 * has labels and counts only (no text, no student id).
 *
 * <p>No @Transactional: the remote calls must not hold a database transaction (reads are single statements).
 */
@Service
public class TopicAnalysisService {

    static final String ALGORITHM = "kmeans-silhouette-v1";
    /** Newest questions analyzed per request (4 embedding calls of 64). */
    static final int MAX_QUESTIONS = 256;
    /** Rows read before the short-question filter, so short chatter does not push out real questions. */
    static final int FETCH_LIMIT = 512;
    static final int EMBED_BATCH = 64;
    static final int MIN_QUESTION_CODE_POINTS = 5;
    static final int MIN_QUESTIONS = 10;
    /** Also the per-topic minimum: a topic shared by fewer students is dropped (small groups are identifiable). */
    static final int MIN_STUDENTS = 3;
    static final int TOP = 3;
    static final String UNTAGGED_LABEL = "태그되지 않은 주제 ";

    private final StudySessionService sessions;
    private final SessionQuestionSource questions;
    private final ConceptStore concepts;
    private final AiClient ai;
    private final ClusterClient cluster;
    private final AiSlots slots;
    private final AiQuota quota;
    private final TopicResultCache cache;
    /**
     * Embedding model of the last successful analysis. The cache key needs the model before embedding (a hit must not
     * call the AI); AiClient has no "current model" call, so the last seen one is used. After a provider switch the
     * first request may still hit an entry of the old model for at most the cache TTL (10 minutes).
     */
    private volatile String lastModel;

    public TopicAnalysisService(StudySessionService sessions, SessionQuestionSource questions, ConceptStore concepts,
                                AiClient ai, ClusterClient cluster, AiSlots slots, AiQuota quota,
                                TopicResultCache cache) {
        this.sessions = sessions;
        this.questions = questions;
        this.concepts = concepts;
        this.ai = ai;
        this.cluster = cluster;
        this.slots = slots;
        this.quota = quota;
        this.cache = cache;
    }

    public ConfusionsResponse analyze(Long userId, Long sessionId) {
        sessions.requireTeacherOf(userId, sessionId);

        List<QuestionRow> rows = analyzable(questions.recentQuestions(sessionId, FETCH_LIMIT));
        int questionCount = rows.size();
        int studentCount = distinctStudents(rows, null);
        if (questionCount < MIN_QUESTIONS) {
            return insufficient(ConfusionsResponse.FEW_QUESTIONS, questionCount, studentCount);
        }
        if (studentCount < MIN_STUDENTS) {
            return insufficient(ConfusionsResponse.FEW_STUDENTS, questionCount, studentCount);
        }

        List<ConceptView> conceptList = List.copyOf(concepts.sessionConcepts(sessionId));
        long maxId = rows.stream().mapToLong(QuestionRow::id).max().orElse(0);
        // The key's fingerprint covers the concept set AND the exact analyzed question ids: with more than 256
        // questions a student leaving keeps (max id, count) but changes the id set (Round 1 F11).
        String fingerprint = fingerprint(conceptList, rows);
        String model = lastModel;
        if (model != null) {
            Optional<ConfusionsResponse> hit = cache.get(
                    new TopicResultCache.Key(sessionId, maxId, questionCount, fingerprint, model));
            if (hit.isPresent()) {
                return hit.get().asCached();
            }
        }

        // Post-processing runs inside the ticket: a malformed FastAPI answer is an AiUnavailableException and is
        // refunded like any other AI failure (Round 1 F8).
        Analysis analysis = quota.reserve(userId, AiFeature.INSIGHTS).run(() -> {
            Embedded embedded = runAi(rows, conceptList);
            return new Analysis(embedded.model(),
                    postProcess(embedded.result(), rows, conceptList, questionCount, studentCount));
        });
        lastModel = analysis.model();
        cache.put(new TopicResultCache.Key(sessionId, maxId, questionCount, fingerprint, analysis.model()),
                analysis.response());
        return analysis.response();
    }

    private record Embedded(String model, ClusterClient.Result result) {
    }

    private record Analysis(String model, ConfusionsResponse response) {
    }

    /** Newest first, at most 256 questions of 5+ characters (shorter ones stay stored but are not analyzed). */
    private static List<QuestionRow> analyzable(List<QuestionRow> recent) {
        List<QuestionRow> kept = new ArrayList<>();
        for (QuestionRow row : recent) {
            String text = row.text().revealForEmbedding().strip();
            if (text.codePointCount(0, text.length()) >= MIN_QUESTION_CODE_POINTS) {
                kept.add(row);
                if (kept.size() == MAX_QUESTIONS) {
                    break;
                }
            }
        }
        return kept;
    }

    /** Embeddings then clustering. Mixed embedding models are a 503 (refunded). */
    private Embedded runAi(List<QuestionRow> rows, List<ConceptView> conceptList) {
        List<String> questionTexts = rows.stream().map(r -> r.text().revealForEmbedding()).toList();
        List<String> conceptNames = conceptList.stream().map(ConceptView::name).toList();
        String[] model = new String[1];
        List<float[]> questionVectors = new ArrayList<>();
        List<float[]> conceptVectors = new ArrayList<>();
        embedInto(questionTexts, questionVectors, model);
        embedInto(conceptNames, conceptVectors, model);
        ClusterClient.Result result = cluster.cluster(questionVectors, conceptVectors);
        return new Embedded(model[0], result);
    }

    /** One AiSlots seat per batch, released in between so chat turns can take the GPU (Round 1 F20). */
    private void embedInto(List<String> texts, List<float[]> out, String[] model) {
        for (int from = 0; from < texts.size(); from += EMBED_BATCH) {
            List<String> batch = List.copyOf(texts.subList(from, Math.min(texts.size(), from + EMBED_BATCH)));
            AiClient.EmbedResponse response = slots.run(() -> ai.embedWithModel(batch));
            if (response == null || response.model() == null || response.vectors() == null
                    || response.vectors().size() != batch.size()) {
                throw new AiUnavailableException();
            }
            if (model[0] == null) {
                model[0] = response.model();
            } else if (!model[0].equals(response.model())) {
                throw new AiUnavailableException();   // vectors from two models are not comparable
            }
            out.addAll(response.vectors());
        }
    }

    /** A topic under construction: question positions and the matched concept (null = untagged). */
    private static final class Group {
        private final ConceptView concept;
        private final List<Integer> members = new ArrayList<>();

        private Group(ConceptView concept) {
            this.concept = concept;
        }
    }

    private record Ranked(Group group, int questionCount, int studentCount, int firstMember) {
    }

    /**
     * Same-concept clusters are merged and their distinct students recounted; topics shared by fewer than 3 students
     * are dropped; ranking = students desc, questions desc (then the newest question first for a stable order).
     */
    private static ConfusionsResponse postProcess(ClusterClient.Result result, List<QuestionRow> rows,
                                                  List<ConceptView> conceptList, int questionCount,
                                                  int studentCount) {
        String status = result.status();
        if (!ConfusionsResponse.OK.equals(status) && !ConfusionsResponse.LOW_QUALITY.equals(status)) {
            throw new AiUnavailableException();
        }
        Map<Long, Group> byConcept = new LinkedHashMap<>();
        List<Group> groups = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (ClusterClient.Cluster c : result.clusters()) {
            Group group;
            if (c.concept() == null) {
                group = new Group(null);
                groups.add(group);
            } else {
                int index = c.concept().index();
                if (index < 0 || index >= conceptList.size()) {
                    throw new AiUnavailableException();
                }
                ConceptView concept = conceptList.get(index);
                group = byConcept.computeIfAbsent(concept.id(), id -> {
                    Group g = new Group(concept);
                    groups.add(g);
                    return g;
                });
            }
            for (Integer member : c.members()) {
                if (member == null || member < 0 || member >= rows.size() || !seen.add(member)) {
                    throw new AiUnavailableException();
                }
                group.members.add(member);
            }
        }
        // Every question position must appear exactly once (no duplicates and no range errors above, none missing
        // here). A partial answer is treated as an AI failure: 503 and, inside the quota ticket, refunded.
        if (seen.size() != rows.size()) {
            throw new AiUnavailableException();
        }

        List<Ranked> ranked = new ArrayList<>();
        for (Group g : groups) {
            int students = distinctStudents(rows, g.members);
            if (students >= MIN_STUDENTS) {
                int first = g.members.stream().mapToInt(Integer::intValue).min().orElse(Integer.MAX_VALUE);
                ranked.add(new Ranked(g, g.members.size(), students, first));
            }
        }
        ranked.sort(Comparator.comparingInt(Ranked::studentCount).reversed()
                .thenComparing(Comparator.comparingInt(Ranked::questionCount).reversed())
                .thenComparingInt(Ranked::firstMember));

        List<ConfusionsResponse.Topic> topics = new ArrayList<>();
        // k = 1 means FastAPI found no grouping at all: "everyone's questions" is not a topic (Round 1 F12).
        List<Ranked> shown = result.k() <= 1 ? List.of() : ranked.subList(0, Math.min(TOP, ranked.size()));
        int untagged = 0;
        for (Ranked r : shown) {
            String label;
            Long conceptId;
            if (r.group().concept == null) {
                untagged++;
                label = UNTAGGED_LABEL + untagged;
                conceptId = null;
            } else {
                label = r.group().concept.name();
                conceptId = r.group().concept.id();
            }
            topics.add(new ConfusionsResponse.Topic(topics.size() + 1, label, conceptId, r.questionCount(),
                    r.studentCount()));
        }
        String reason = topics.isEmpty() ? ConfusionsResponse.NO_SHARED_TOPIC : null;
        return new ConfusionsResponse(status, reason, questionCount, studentCount, result.algorithm(), result.k(),
                result.silhouette(), false, List.copyOf(topics));
    }

    /** Distinct users among the given positions (all rows when positions is null). */
    private static int distinctStudents(List<QuestionRow> rows, List<Integer> positions) {
        Set<Long> users = new HashSet<>();
        if (positions == null) {
            rows.forEach(r -> users.add(r.userId()));
        } else {
            positions.forEach(p -> users.add(rows.get(p).userId()));
        }
        return users.size();
    }

    private static ConfusionsResponse insufficient(String reason, int questionCount, int studentCount) {
        return new ConfusionsResponse(ConfusionsResponse.INSUFFICIENT_DATA, reason, questionCount, studentCount,
                ALGORITHM, null, null, false, List.of());
    }

    /**
     * Changes when a concept is added, removed or renamed (ids and names, in id order) or when the set of analyzed
     * question ids changes.
     */
    private static String fingerprint(List<ConceptView> conceptList, List<QuestionRow> rows) {
        StringBuilder sb = new StringBuilder();
        conceptList.stream().sorted(Comparator.comparing(ConceptView::id))
                .forEach(c -> sb.append(c.id()).append('\u0000').append(c.name()).append('\u0001'));
        sb.append('\u0002');
        rows.forEach(r -> sb.append(r.id()).append(','));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
