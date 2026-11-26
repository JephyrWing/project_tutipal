package com.my.tutipal_back.learning.insights.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.my.tutipal_back.common.exception.ApiException;
import com.my.tutipal_back.learning.insights.dto.ConceptNamesRequest;
import com.my.tutipal_back.learning.insights.dto.ConceptView;
import com.my.tutipal_back.learning.insights.dto.QuizConceptView;
import com.my.tutipal_back.learning.insights.repository.ConceptStore;
import com.my.tutipal_back.learning.quiz.entity.Quiz;
import com.my.tutipal_back.learning.quiz.repository.QuizRepository;
import com.my.tutipal_back.sessions.service.StudySessionService;

/**
 * PT-87 AC-2..4: concept tags on quizzes. Teacher of the quiz's session only (student of that session 403,
 * anyone else 404 via requireTeacherOf). Ended sessions can still be tagged and viewed.
 */
@Service
public class ConceptTagService {

    static final int MAX_NAMES_PER_QUIZ = 3;
    static final int MAX_NAME_LENGTH = 30;
    static final int MAX_CONCEPTS_PER_SESSION = 50;
    static final Set<String> SOURCES = Set.of("TEACHER", "AI");
    static final String NOT_FOUND_MESSAGE = "방을 찾을 수 없어요";

    private final StudySessionService sessions;
    private final QuizRepository quizzes;
    private final ConceptStore store;

    public ConceptTagService(StudySessionService sessions, QuizRepository quizzes, ConceptStore store) {
        this.sessions = sessions;
        this.quizzes = quizzes;
        this.store = store;
    }

    @Transactional(readOnly = true)
    public List<ConceptView> sessionConcepts(Long userId, Long sessionId) {
        sessions.requireTeacherOf(userId, sessionId);
        return store.sessionConcepts(sessionId);
    }

    @Transactional(readOnly = true)
    public Map<Long, List<QuizConceptView>> quizConcepts(Long userId, Long sessionId) {
        sessions.requireTeacherOf(userId, sessionId);
        return store.quizConcepts(sessionId);
    }

    /**
     * Replaces the whole tag set of the quiz (idempotent). Order: quiz exists (404) -> teacher of its session
     * (403/404) -> validation (400) -> store. Names are trimmed and case/space variants are merged keeping the
     * first spelling.
     */
    @Transactional
    public List<QuizConceptView> replace(Long userId, Long quizId, ConceptNamesRequest request) {
        // Same 404 text as requireTeacherOf for another session's quiz: no existence oracle on quiz ids.
        Quiz quiz = quizzes.findById(quizId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, NOT_FOUND_MESSAGE));
        long sessionId = quiz.getSessionId();
        sessions.requireTeacherOf(userId, sessionId);

        String source = request.source() == null ? "TEACHER" : request.source();
        if (!SOURCES.contains(source)) {
            throw badRequest("source 는 TEACHER 또는 AI 만 쓸 수 있어요");
        }
        List<String> names = cleanNames(request.names());

        store.lockSession(sessionId);
        // Count the session as it will be after the replace: concepts only this quiz used (and already unused ones)
        // are removed by the store unless they are re-used here.
        Set<String> after = new HashSet<>();
        store.sessionConcepts(sessionId).forEach(c -> after.add(ConceptStore.normalize(c.name())));
        store.releasableConcepts(sessionId, quizId).forEach(c -> after.remove(ConceptStore.normalize(c.name())));
        names.forEach(n -> after.add(ConceptStore.normalize(n)));
        if (after.size() > MAX_CONCEPTS_PER_SESSION) {
            throw badRequest("한 수업의 개념은 " + MAX_CONCEPTS_PER_SESSION + "개까지 만들 수 있어요");
        }
        return store.replaceQuizConcepts(sessionId, quizId, names, source);
    }

    /**
     * Reject control characters (incl. tab / newline) and lone surrogates in the raw input, clean Unicode whitespace,
     * check 1..30 code points for both the
     * display name and the normalized name (lower-casing can grow, e.g. 'İ' becomes two code points), merge duplicates.
     */
    private static List<String> cleanNames(List<String> raw) {
        if (raw == null) {
            throw badRequest("names 가 필요해요");
        }
        if (raw.size() > MAX_NAMES_PER_QUIZ) {
            throw badRequest("개념은 문항당 " + MAX_NAMES_PER_QUIZ + "개까지 붙일 수 있어요");
        }
        Map<String, String> byNormalized = new LinkedHashMap<>();
        for (String name : raw) {
            if (name == null) {
                throw badRequest("개념 이름이 비어 있어요");
            }
            // Checked on the raw input: clean() would turn tab / newline (control characters that are also
            // whitespace) into spaces. Unicode spaces such as NBSP or U+3000 are not control characters.
            if (name.codePoints().anyMatch(ConceptTagService::forbidden)) {
                throw badRequest("개념 이름에 쓸 수 없는 문자가 있어요");
            }
            String cleaned = ConceptStore.clean(name);
            String normalized = ConceptStore.normalize(cleaned);
            int length = cleaned.codePointCount(0, cleaned.length());
            if (length < 1 || length > MAX_NAME_LENGTH
                    || normalized.codePointCount(0, normalized.length()) > MAX_NAME_LENGTH) {
                throw badRequest("개념 이름은 1~" + MAX_NAME_LENGTH + "자로 써 주세요");
            }
            byNormalized.putIfAbsent(normalized, cleaned);
        }
        return new ArrayList<>(byNormalized.values());
    }

    /** Control characters (PostgreSQL rejects NUL) and unpaired surrogates. */
    private static boolean forbidden(int codePoint) {
        int type = Character.getType(codePoint);
        return type == Character.CONTROL || type == Character.SURROGATE;
    }

    private static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }
}
