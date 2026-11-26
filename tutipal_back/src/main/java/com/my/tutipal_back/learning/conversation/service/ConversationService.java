package com.my.tutipal_back.learning.conversation.service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.my.tutipal_back.ai.dto.AgentDtos;
import com.my.tutipal_back.common.exception.ApiException;
import com.my.tutipal_back.learning.conversation.ConversationProperties;
import com.my.tutipal_back.learning.conversation.dto.ConversationDtos;
import com.my.tutipal_back.learning.conversation.repository.ConversationStore;
import com.my.tutipal_back.learning.tutor.dto.ChatRequest;
import com.my.tutipal_back.sessions.entity.SessionRole;
import com.my.tutipal_back.sessions.service.StudySessionService;

/**
 * PT-106 stored AI conversations: create, list, detail, message pages, delete, memories, and the transactional
 * parts of a turn (building the request from stored history, saving the finished pair) and of a summary.
 *
 * <p>Ownership: every conversation is read through (userId, id); someone else's conversation is 404, the same as a
 * missing one. Session checks (participant, active, student) only guard creating and continuing; the owner can
 * always view and delete an ended session's conversations.
 */
@Service
public class ConversationService {

    public static final String DELETED_WHILE_STREAMING = "대화가 삭제되어 저장하지 않았어요";
    static final String NOT_FOUND = "대화를 찾을 수 없어요";
    static final String CLIENT_ID_REUSED = "이미 다른 질문에 쓴 전송 번호예요. 화면을 새로 고친 뒤 다시 보내 주세요";

    static final int DEFAULT_LIST_LIMIT = 20;
    static final int MAX_LIST_LIMIT = 50;
    static final int DEFAULT_MESSAGE_LIMIT = 50;
    static final int MAX_MESSAGE_LIMIT = 100;
    static final int MAX_TOPIC_LENGTH = 30;
    static final int TITLE_LENGTH = 40;
    static final int MAX_QUESTION_LENGTH = 8000;
    static final int MAX_MEMORY_LENGTH = 1000;
    static final int MAX_MEMORIES_PER_SCOPE = 20;
    /** Contract 6.2: at most 39 prior messages + the question, each cut to 8000 for the model. */
    static final int MAX_HISTORY = 39;
    static final int MAX_HISTORY_MESSAGE_LENGTH = 8000;
    /** Stored answer limit (V21 CHECK). */
    static final int MAX_ANSWER_LENGTH = 32000;
    private static final String DEFAULT_SESSION_TOPIC = "수업";
    /** Cursor timestamps outside this range cannot come from us (and PostgreSQL would reject them). */
    private static final Instant CURSOR_MIN = Instant.parse("2000-01-01T00:00:00Z");
    private static final Instant CURSOR_MAX = Instant.parse("9999-12-31T23:59:59Z");

    private final ConversationStore store;
    private final StudySessionService sessions;
    private final Clock clock;
    /** Sent to the screen so its retention notice matches what ConversationRetention really deletes. */
    private final int personalRetentionDays;

    @Autowired
    public ConversationService(ConversationStore store, StudySessionService sessions,
                               ConversationProperties properties) {
        this(store, sessions, Clock.systemUTC(), properties);
    }

    /** Default properties (tests). */
    public ConversationService(ConversationStore store, StudySessionService sessions) {
        this(store, sessions, Clock.systemUTC());
    }

    /** Default properties (tests). */
    public ConversationService(ConversationStore store, StudySessionService sessions, Clock clock) {
        this(store, sessions, clock, ConversationProperties.defaults());
    }

    public ConversationService(ConversationStore store, StudySessionService sessions, Clock clock,
                               ConversationProperties properties) {
        this.store = store;
        this.sessions = sessions;
        this.clock = clock;
        this.personalRetentionDays = properties.personalRetentionDays();
    }

    // ------------------------------------------------------------------ CRUD

    /** Personal: topic 1..30 required. Session: the caller must be an active student; the session topic is used. */
    @Transactional
    public ConversationDtos.Detail create(long userId, String topic, Long sessionId) {
        String chosen;
        if (sessionId == null) {
            chosen = topic == null ? "" : topic.strip();
            if (chosen.isEmpty()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "주제를 입력해 주세요");
            }
            if (chosen.length() > MAX_TOPIC_LENGTH) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "주제는 30자까지 쓸 수 있어요");
            }
            rejectNul(chosen, "주제");
        } else {
            requireStudent(userId, sessionId);
            String sessionTopic = sessions.chatSettings(userId, sessionId).topic();
            chosen = sessionTopic == null || sessionTopic.isBlank() ? DEFAULT_SESSION_TOPIC : cut(sessionTopic.strip(),
                    MAX_TOPIC_LENGTH);
        }
        ConversationStore.Conversation created = store.insertConversation(userId, sessionId, chosen, clock.instant());
        return detail(created, null, List.of());
    }

    @Transactional(readOnly = true)
    public ConversationDtos.Page list(long userId, Long sessionId, String cursor, Integer limit) {
        int size = limit(limit, DEFAULT_LIST_LIMIT, MAX_LIST_LIMIT);
        ConversationStore.Cursor after = cursor == null || cursor.isEmpty() ? null : decodeCursor(cursor);
        List<ConversationStore.Conversation> rows = store.list(userId, sessionId, after, size + 1);
        boolean hasMore = rows.size() > size;
        List<ConversationStore.Conversation> page = hasMore ? rows.subList(0, size) : rows;
        String next = hasMore ? encodeCursor(page.getLast()) : null;
        return new ConversationDtos.Page(page.stream().map(ConversationService::item).toList(), next, hasMore,
                personalRetentionDays);
    }

    @Transactional(readOnly = true)
    public ConversationDtos.Detail detail(long userId, long conversationId) {
        ConversationStore.Conversation c = owned(userId, conversationId);
        return detail(c, store.activeSummary(c.id()).orElse(null), store.memoriesOf(c.id()));
    }

    /** Newest page first; ascending inside the page. */
    @Transactional(readOnly = true)
    public ConversationDtos.MessagePage messages(long userId, long conversationId, Long before, Integer limit) {
        int size = limit(limit, DEFAULT_MESSAGE_LIMIT, MAX_MESSAGE_LIMIT);
        if (before != null && before < 1) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "before 값이 올바르지 않아요");
        }
        ConversationStore.Conversation c = owned(userId, conversationId);
        List<ConversationStore.Message> newestFirst = store.messagesBefore(c.id(), before, size + 1);
        boolean hasMore = newestFirst.size() > size;
        List<ConversationStore.Message> page = new ArrayList<>(hasMore ? newestFirst.subList(0, size) : newestFirst);
        Collections.reverse(page);
        String nextBefore = hasMore ? String.valueOf(page.getFirst().id()) : null;
        return new ConversationDtos.MessagePage(page.stream().map(ConversationService::message).toList(), nextBefore,
                hasMore);
    }

    /**
     * Soft delete: locks the live row and sets deleted_at. From then on the conversation, its messages, summaries
     * and memories are invisible to every read (404 like a missing one, never in an Agent request, not counted in
     * the memory cap); ConversationRetention removes them physically after deleted-purge-days. A turn already
     * streaming finds no live row in savePair and saves nothing.
     */
    @Transactional
    public void delete(long userId, long conversationId) {
        ConversationStore.Conversation c = store.lockOwned(userId, conversationId)
                .orElseThrow(ConversationService::notFound);
        store.softDeleteConversation(c.id(), clock.instant());
    }

    /**
     * PT-13 결정 C: the owner marks ("저장") or unmarks a conversation. A saved conversation is skipped by both
     * retention periods and kept until the user deletes it; unmarking makes it follow retention again (an already
     * expired one goes at the next hourly run). Someone else's, missing or deleted conversation is 404. Locks the
     * live row first, so the retention DELETE (FOR UPDATE SKIP LOCKED + saved_at re-check) and this cannot cross.
     * Allowed in an ended session too (that is when it matters).
     */
    @Transactional
    public ConversationDtos.SavedState setSaved(long userId, long conversationId, boolean saved) {
        ConversationStore.Conversation c = store.lockOwned(userId, conversationId)
                .orElseThrow(ConversationService::notFound);
        ConversationStore.Conversation updated = store.setSaved(c.id(), saved ? clock.instant() : null)
                .orElseThrow(ConversationService::notFound);
        return new ConversationDtos.SavedState(String.valueOf(updated.id()), updated.saved(), updated.savedAt());
    }

    /** At most 20 memories per (user, scope). Text is what the user wrote (the screen pre-fills it). */
    @Transactional
    public ConversationDtos.MemoryView saveMemory(long userId, long conversationId, String text) {
        String value = text == null ? "" : text.strip();
        if (value.isEmpty() || value.length() > MAX_MEMORY_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "기억은 1~1000자로 써 주세요");
        }
        rejectNul(value, "기억");
        ConversationStore.Conversation c = store.lockOwned(userId, conversationId)
                .orElseThrow(ConversationService::notFound);
        store.lockMemoryScope(userId, c.sessionId());
        if (store.countMemories(userId, c.sessionId()) >= MAX_MEMORIES_PER_SCOPE) {
            throw new ApiException(HttpStatus.CONFLICT, "기억은 범위마다 20개까지 저장할 수 있어요. 오래된 기억을 지운 뒤 다시 저장해 주세요");
        }
        ConversationStore.Memory saved = store.insertMemory(userId, c.sessionId(), c.id(), value, clock.instant());
        return memory(saved);
    }

    /**
     * Soft delete (deleted_at): excluded at once from recall, detail and the cap; purged with the grace period.
     * Locks the live conversation row first, so a concurrent conversation delete either commits before (404 here)
     * or waits for this transaction; the UPDATE also requires a live parent (0 rows = 404).
     */
    @Transactional
    public void deleteMemory(long userId, long conversationId, long memoryId) {
        ConversationStore.Conversation c = store.lockOwned(userId, conversationId)
                .orElseThrow(ConversationService::notFound);
        if (!store.softDeleteMemory(c.id(), memoryId, clock.instant())) {

            throw new ApiException(HttpStatus.NOT_FOUND, "기억을 찾을 수 없어요");
        }
    }

    // ------------------------------------------------------------------ turn

    /** Request for TutorService plus the binding for StoredConversationMemory. */
    public record PreparedTurn(ChatRequest request, ConversationBinding.Bound bound) {
    }

    /**
     * Ownership (404), then the request: the last 39 stored messages after the active summary (each cut to 8000)
     * + the question. Session checks (404 / 410 / 403) are done by ConversationTurnService right after this.
     */
    @Transactional(readOnly = true)
    public PreparedTurn prepareTurn(long userId, long conversationId, String question) {
        ConversationStore.Conversation c = owned(userId, conversationId);
        Optional<ConversationStore.Summary> active = store.activeSummary(c.id());
        long after = active.map(ConversationStore.Summary::toMessageId).orElse(0L);
        List<ConversationStore.Message> recent = store.recentMessagesAfter(c.id(), after, MAX_HISTORY);
        List<ChatRequest.Message> messages = new ArrayList<>(recent.size() + 1);
        for (ConversationStore.Message m : recent) {
            String content = cut(m.content(), MAX_HISTORY_MESSAGE_LENGTH);
            messages.add(new ChatRequest.Message(m.role(), content));
        }
        messages.add(new ChatRequest.Message("user", question));
        ChatRequest request = new ChatRequest(c.topic(), List.copyOf(messages), c.sessionId(), null, null);
        AgentDtos.Scope scope = c.sessionId() == null ? AgentDtos.Scope.personal()
                : AgentDtos.Scope.session(c.sessionId());
        ConversationBinding.Bound bound = new ConversationBinding.Bound(userId, scope, c.id(),
                active.map(ConversationStore.Summary::id).orElse(null), after);
        return new PreparedTurn(request, bound);
    }

    /**
     * Saves the finished question / answer pair in one transaction (answer cut to 32000; title = first 40 characters
     * of the first question). If the conversation was deleted meanwhile nothing is saved or recreated.
     */
    @Transactional
    public ConversationStore.SavedPair savePair(long userId, long conversationId, String question, String answer) {
        return savePair(userId, conversationId, question, answer, null);
    }

    /**
     * Same, with the screen's idempotency key stored on the question. If the key was already saved in this
     * conversation (a concurrent request with the same key got here first) the stored pair is returned and nothing
     * is inserted; a different question under the same key is 409. Runs after the row lock, so two such requests
     * are serialized here; the partial UNIQUE index is the last guard.
     */
    @Transactional
    public ConversationStore.SavedPair savePair(long userId, long conversationId, String question, String answer,
                                                UUID clientMessageId) {
        ConversationStore.Conversation c = store.lockOwned(userId, conversationId)
                .orElseThrow(() -> new ApiException(HttpStatus.GONE, DELETED_WHILE_STREAMING));
        if (clientMessageId != null) {
            Optional<ConversationStore.SavedPair> existing = store.findPairByClientMessageId(c.id(), clientMessageId);
            if (existing.isPresent()) {
                return sameQuestion(existing.get(), question);
            }
        }
        return store.insertPair(c.id(), question, cut(answer, MAX_ANSWER_LENGTH), cut(question.strip(), TITLE_LENGTH),
                clock.instant(), clientMessageId);
    }

    /**
     * The pair already saved under this key in the caller's conversation (404 if not the caller's). Present only
     * when the stored question equals {@code question}; a different question under the same key is 409.
     */
    @Transactional(readOnly = true)
    public Optional<ConversationStore.SavedPair> findSaved(long userId, long conversationId, UUID clientMessageId,
                                                           String question) {
        ConversationStore.Conversation c = owned(userId, conversationId);
        return store.findPairByClientMessageId(c.id(), clientMessageId).map(pair -> sameQuestion(pair, question));
    }

    private static ConversationStore.SavedPair sameQuestion(ConversationStore.SavedPair pair, String question) {
        if (!pair.user().content().equals(question)) {
            throw new ApiException(HttpStatus.CONFLICT, CLIENT_ID_REUSED);
        }
        return pair;
    }

    // ------------------------------------------------------------------ summary

    /** Agent scope of a conversation (empty if it no longer exists). Not an ownership check: system use only. */
    @Transactional(readOnly = true)
    public Optional<AgentDtos.Scope> scopeOf(long conversationId) {
        return store.find(conversationId).map(c -> c.sessionId() == null ? AgentDtos.Scope.personal()
                : AgentDtos.Scope.session(c.sessionId()));
    }

    /**
     * Saves a new summary if the conversation still exists and its active summary is still the one the plan was
     * based on; the previous one gets superseded_at. Returns false when the result was discarded.
     */
    @Transactional
    public boolean saveSummary(long conversationId, SummaryPlan plan, String text) {
        if (store.lock(conversationId).isEmpty()) {
            return false;
        }
        Optional<ConversationStore.Summary> active = store.activeSummary(conversationId);
        if (!Objects.equals(active.map(ConversationStore.Summary::id).orElse(null), plan.basedOnSummaryId())) {
            return false;
        }
        Instant now = clock.instant();
        active.ifPresent(s -> store.supersedeSummary(s.id(), now));
        store.insertSummary(conversationId, text, plan.fromMessageId(), plan.toMessageId(), now);
        return true;
    }

    // ------------------------------------------------------------------ helpers

    private void requireStudent(long userId, long sessionId) {
        if (sessions.requireActiveRole(userId, sessionId) == SessionRole.TEACHER) {
            throw new ApiException(HttpStatus.FORBIDDEN, "수업 AI 대화는 학생만 쓸 수 있어요");
        }
    }

    private ConversationStore.Conversation owned(long userId, long conversationId) {
        return store.findOwned(userId, conversationId).orElseThrow(ConversationService::notFound);
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, NOT_FOUND);
    }

    private static int limit(Integer given, int fallback, int max) {
        if (given == null) {
            return fallback;
        }
        if (given < 1 || given > max) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "limit 은 1~" + max + " 사이여야 해요");
        }
        return given;
    }

    /** Opaque cursor: base64url("<lastMessageAt ISO>|<id>"). */
    static String encodeCursor(ConversationStore.Conversation last) {
        String raw = last.lastMessageAt().toString() + "|" + last.id();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    static ConversationStore.Cursor decodeCursor(String cursor) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int bar = raw.indexOf('|');
            if (bar < 0) {
                throw badCursor();
            }
            long id = Long.parseLong(raw.substring(bar + 1));
            if (id < 1) {
                throw badCursor();
            }
            Instant at = Instant.parse(raw.substring(0, bar));
            if (at.isBefore(CURSOR_MIN) || at.isAfter(CURSOR_MAX)) {
                throw badCursor();
            }
            return new ConversationStore.Cursor(at, id);
        } catch (IllegalArgumentException | DateTimeParseException e) {
            throw badCursor();
        }
    }

    /** NUL cannot be stored in PostgreSQL text: reject it as bad input instead of failing in the DB. */
    static void rejectNul(String text, String what) {
        if (text.indexOf('\u0000') >= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, what + "에 쓸 수 없는 문자가 들어 있어요");
        }
    }

    private static ApiException badCursor() {
        return new ApiException(HttpStatus.BAD_REQUEST, "목록 위치(cursor)가 올바르지 않아요");
    }

    /** Cuts to at most max UTF-16 units without leaving a lone high surrogate at the end. */
    static String cut(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        int end = Character.isHighSurrogate(text.charAt(max - 1)) ? max - 1 : max;
        return text.substring(0, end);
    }

    private static String id(Long value) {
        return value == null ? null : String.valueOf(value);
    }

    private static ConversationDtos.ListItem item(ConversationStore.Conversation c) {
        return new ConversationDtos.ListItem(String.valueOf(c.id()), c.topic(), c.title(), id(c.sessionId()),
                c.messageCount(), c.lastMessageAt(), c.saved(), c.savedAt());
    }

    private ConversationDtos.Detail detail(ConversationStore.Conversation c, ConversationStore.Summary summary,
                                           List<ConversationStore.Memory> memories) {
        ConversationDtos.SummaryView view = summary == null ? null
                : new ConversationDtos.SummaryView(summary.text(), summary.createdAt());
        return new ConversationDtos.Detail(String.valueOf(c.id()), c.topic(), c.title(), id(c.sessionId()),
                c.messageCount(), c.createdAt(), c.lastMessageAt(), view,
                memories.stream().map(ConversationService::memory).toList(), c.saved(), c.savedAt(),
                personalRetentionDays);
    }

    private static ConversationDtos.MemoryView memory(ConversationStore.Memory m) {
        return new ConversationDtos.MemoryView(String.valueOf(m.id()), m.text(), m.createdAt());
    }

    private static ConversationDtos.MessageView message(ConversationStore.Message m) {
        return new ConversationDtos.MessageView(String.valueOf(m.id()), m.role(), m.content(), m.createdAt());
    }
}
