package com.my.tutipal_back.learning.conversation.repository;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

/**
 * PT-106 in-memory ConversationStore for service / web tests (JUnit has no DB).
 * Mimics the V21 schema rules the services rely on: soft delete (deleted_at) that hides a conversation with its
 * messages, summaries and memories from every port read, and a soft-deleted memory from recall / detail / cap;
 * physical deletion (retention, purge) with ON DELETE CASCADE from a conversation to its messages, summaries and
 * memories; FK failure when writing into a physically removed conversation; and the partial UNIQUE index
 * (at most one active summary per conversation; one user message per clientMessageId).
 * Every lock / delete / pair insert is recorded in {@link #calls} ("delete:7" is the soft delete of conversation 7,
 * "deleteMemory:3" the soft delete of memory 3).
 */
public class FakeConversationStore implements ConversationStore {

    private final Map<Long, Conversation> conversations = new LinkedHashMap<>();
    private final Map<Long, Message> messages = new TreeMap<>();
    private final Map<Long, Summary> summaries = new TreeMap<>();
    private final Map<Long, Memory> memories = new TreeMap<>();
    /** message id → clientMessageId (user rows saved with a key). */
    private final Map<Long, UUID> clientIds = new TreeMap<>();
    /** conversation id → deleted_at (soft-deleted rows stay until purged). */
    private final Map<Long, Instant> deletedConversations = new TreeMap<>();
    /** memory id → deleted_at. */
    private final Map<Long, Instant> deletedMemories = new TreeMap<>();
    private long nextConversation = 1;
    private long nextMessage = 1;
    private long nextSummary = 1;
    private long nextMemory = 1;

    /** "lockOwned:7", "lock:7", "delete:7", "insertPair:7", "insertSummary:7", "supersede:3" ... in call order. */
    public final List<String> calls = Collections.synchronizedList(new ArrayList<>());
    /** Arguments of every memories(userId, sessionId, limit) query: {userId, sessionId, limit}. */
    public final List<Object[]> memoryQueries = Collections.synchronizedList(new ArrayList<>());

    /** study_sessions columns the class retention reads (ended_at, ai_retention_days). */
    private record SessionRow(Instant endedAt, Integer aiRetentionDays) {
    }

    /** session id → its row; a conversation whose session is not seeded belongs to an open session. */
    private final Map<Long, SessionRow> sessions = new TreeMap<>();

    // ------------------------------------------------------------------ seeding helpers (not part of the port)

    /** endedAt null = open session; aiRetentionDays null = a row without the column (treated as 90). */
    public synchronized void seedSession(long sessionId, Instant endedAt, Integer aiRetentionDays) {
        sessions.put(sessionId, new SessionRow(endedAt, aiRetentionDays));
    }

    /** Marks a seeded conversation saved at the given time (test shortcut for {@link #setSaved}). */
    public synchronized void seedSaved(long conversationId, Instant savedAt) {
        Conversation c = conversations.get(conversationId);
        conversations.put(conversationId, withSavedAt(c, savedAt));
    }

    public synchronized Conversation seedConversation(long userId, Long sessionId, String topic, Instant lastMessageAt) {
        long id = nextConversation++;
        Conversation c = new Conversation(id, userId, sessionId, topic, null, 0, lastMessageAt, lastMessageAt);
        conversations.put(id, c);
        return c;
    }

    /** count messages alternating user / assistant (first is user), contents "m1", "m2", ... (1-based per call). */
    public synchronized List<Message> seedMessages(long conversationId, int count, Instant at) {
        List<Message> added = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            added.add(seedMessage(conversationId, i % 2 == 1 ? "user" : "assistant", "m" + i, at));
        }
        return added;
    }

    public synchronized Message seedMessage(long conversationId, String role, String content, Instant at) {
        Conversation c = conversations.get(conversationId);
        if (c == null) {
            throw new IllegalStateException("no conversation " + conversationId);
        }
        long id = nextMessage++;
        Message m = new Message(id, conversationId, role, content, at);
        messages.put(id, m);
        conversations.put(conversationId, new Conversation(c.id(), c.userId(), c.sessionId(), c.topic(), c.title(),
                c.messageCount() + 1, c.createdAt(), at, c.savedAt()));
        return m;
    }

    /** Inserts an active summary; a previously active one is superseded first (like the real save). */
    public synchronized Summary seedSummary(long conversationId, String text, long fromMessageId, long toMessageId,
                                            Instant at) {
        rawActiveSummary(conversationId).ifPresent(s -> summaries.put(s.id(), withSuperseded(s, at)));
        long id = nextSummary++;
        Summary s = new Summary(id, conversationId, text, fromMessageId, toMessageId, at, null, null);
        summaries.put(id, s);
        return s;
    }

    public synchronized Memory seedMemory(long userId, Long sessionId, long conversationId, String text, Instant at) {
        long id = nextMemory++;
        Memory m = new Memory(id, userId, sessionId, conversationId, text, at);
        memories.put(id, m);
        return m;
    }

    public synchronized void invalidate(long summaryId, Instant at) {
        Summary s = summaries.get(summaryId);
        summaries.put(summaryId, new Summary(s.id(), s.conversationId(), s.text(), s.fromMessageId(),
                s.toMessageId(), s.createdAt(), s.supersededAt(), at));
    }

    public synchronized void removeSummary(long summaryId) {
        summaries.remove(summaryId);
    }

    /** A user's delete at an unspecified time (test shortcut for {@link #softDeleteConversation}). */
    public synchronized void deleteConversation(long conversationId) {
        softDeleteConversation(conversationId, Instant.now());
    }

    // ------------------------------------------------------------------ inspection helpers

    /** The physical row, live or soft-deleted (empty only after a physical delete). */
    public synchronized Optional<Conversation> conversation(long id) {
        return Optional.ofNullable(conversations.get(id));
    }

    /** deleted_at of a conversation (empty while live or after a physical delete). */
    public synchronized Optional<Instant> deletedAt(long conversationId) {
        return Optional.ofNullable(deletedConversations.get(conversationId));
    }

    /** deleted_at of a memory (empty while live or after a physical delete). */
    public synchronized Optional<Instant> memoryDeletedAt(long memoryId) {
        return Optional.ofNullable(deletedMemories.get(memoryId));
    }

    public synchronized List<Message> messagesOf(long conversationId) {
        return messages.values().stream().filter(m -> m.conversationId() == conversationId).toList();
    }

    public synchronized List<Message> allMessages() {
        return List.copyOf(messages.values());
    }

    public synchronized List<Summary> summariesOf(long conversationId) {
        return summaries.values().stream().filter(s -> s.conversationId() == conversationId).toList();
    }

    public synchronized List<Summary> allSummaries() {
        return List.copyOf(summaries.values());
    }

    public synchronized Optional<Summary> summary(long id) {
        return Optional.ofNullable(summaries.get(id));
    }

    /** Every physical memory row, deleted or not. */
    public synchronized List<Memory> allMemories() {
        return List.copyOf(memories.values());
    }

    /** Whole state as text: equal snapshots mean no DB change. */
    public synchronized String snapshot() {
        return conversations.values() + "|" + messages.values() + "|" + summaries.values() + "|" + memories.values()
                + "|" + deletedConversations + "|" + deletedMemories;
    }

    private boolean live(long conversationId) {
        return conversations.containsKey(conversationId) && !deletedConversations.containsKey(conversationId);
    }

    private boolean liveMemory(Memory m) {
        return !deletedMemories.containsKey(m.id()) && live(m.conversationId());
    }

    // ------------------------------------------------------------------ ConversationStore

    @Override
    public synchronized Conversation insertConversation(long userId, Long sessionId, String topic, Instant now) {
        calls.add("insertConversation");
        long id = nextConversation++;
        Conversation c = new Conversation(id, userId, sessionId, topic, null, 0, now, now);
        conversations.put(id, c);
        return c;
    }

    @Override
    public synchronized Optional<Conversation> findOwned(long userId, long conversationId) {
        return Optional.ofNullable(conversations.get(conversationId))
                .filter(c -> c.userId() == userId && live(c.id()));
    }

    @Override
    public synchronized Optional<Conversation> lockOwned(long userId, long conversationId) {
        calls.add("lockOwned:" + conversationId);
        return findOwned(userId, conversationId);
    }

    @Override
    public synchronized Optional<Conversation> lock(long conversationId) {
        calls.add("lock:" + conversationId);
        return Optional.ofNullable(conversations.get(conversationId)).filter(c -> live(c.id()));
    }

    @Override
    public synchronized List<Conversation> list(long userId, Long sessionId, Cursor after, int limit) {
        return conversations.values().stream()
                .filter(c -> c.userId() == userId && Objects.equals(c.sessionId(), sessionId) && live(c.id()))
                .filter(c -> after == null || c.lastMessageAt().isBefore(after.lastMessageAt())
                        || c.lastMessageAt().equals(after.lastMessageAt()) && c.id() < after.id())
                .sorted(Comparator.comparing(Conversation::lastMessageAt).thenComparing(Conversation::id).reversed())
                .limit(limit)
                .toList();
    }

    @Override
    public synchronized void softDeleteConversation(long conversationId, Instant now) {
        calls.add("delete:" + conversationId);
        if (conversations.containsKey(conversationId)) {
            deletedConversations.putIfAbsent(conversationId, now);
        }
    }

    /** Physical delete with ON DELETE CASCADE (retention / purge). */
    private void hardDelete(long conversationId) {
        conversations.remove(conversationId);
        deletedConversations.remove(conversationId);
        messages.values().removeIf(m -> m.conversationId() == conversationId);
        summaries.values().removeIf(s -> s.conversationId() == conversationId);
        memories.values().removeIf(m -> {
            if (m.conversationId() == conversationId) {
                deletedMemories.remove(m.id());
                return true;
            }
            return false;
        });
    }

    @Override
    public synchronized List<Message> messagesBefore(long conversationId, Long beforeId, int limit) {
        if (!live(conversationId)) {
            return List.of();
        }
        return messages.values().stream()
                .filter(m -> m.conversationId() == conversationId && (beforeId == null || m.id() < beforeId))
                .sorted(Comparator.comparing(Message::id).reversed())
                .limit(limit)
                .toList();
    }

    @Override
    public synchronized List<Message> recentMessagesAfter(long conversationId, long afterMessageId, int limit) {
        if (!live(conversationId)) {
            return List.of();
        }
        List<Message> after = messages.values().stream()
                .filter(m -> m.conversationId() == conversationId && m.id() > afterMessageId)
                .toList();
        return List.copyOf(after.subList(Math.max(0, after.size() - limit), after.size()));
    }

    @Override
    public synchronized int countMessagesAfter(long conversationId, long afterMessageId) {
        if (!live(conversationId)) {
            return 0;
        }
        return (int) messages.values().stream()
                .filter(m -> m.conversationId() == conversationId && m.id() > afterMessageId).count();
    }

    @Override
    public synchronized List<Message> oldestMessagesAfter(long conversationId, long afterMessageId, int limit) {
        if (!live(conversationId)) {
            return List.of();
        }
        return messages.values().stream()
                .filter(m -> m.conversationId() == conversationId && m.id() > afterMessageId)
                .limit(limit)
                .toList();
    }

    @Override
    public synchronized SavedPair insertPair(long conversationId, String question, String answer, String title,
                                             Instant now, UUID clientMessageId) {
        calls.add("insertPair:" + conversationId);
        Conversation c = conversations.get(conversationId);
        if (c == null) {
            throw new DataIntegrityViolationException("ai_conversation_messages.conversation_id FK");
        }
        if (clientMessageId != null && keyedQuestion(conversationId, clientMessageId).isPresent()) {
            throw new DuplicateKeyException("uk_ai_conversation_messages_client_id");
        }
        long userId = nextMessage++;
        if (clientMessageId != null) {
            clientIds.put(userId, clientMessageId);
        }
        long assistantId = nextMessage++;
        Message user = new Message(userId, conversationId, "user", question, now);
        Message assistant = new Message(assistantId, conversationId, "assistant", answer, now);
        messages.put(userId, user);
        messages.put(assistantId, assistant);
        conversations.put(conversationId, new Conversation(c.id(), c.userId(), c.sessionId(), c.topic(),
                c.title() == null ? title : c.title(), c.messageCount() + 2, c.createdAt(), now, c.savedAt()));
        return new SavedPair(user, assistant);
    }

    @Override
    public synchronized Optional<SavedPair> findPairByClientMessageId(long conversationId, UUID clientMessageId) {
        calls.add("findPairByClientMessageId:" + conversationId);
        if (!live(conversationId)) {
            return Optional.empty();
        }
        return keyedQuestion(conversationId, clientMessageId).flatMap(user -> messages.values().stream()
                .filter(m -> m.conversationId() == conversationId && m.id() > user.id())
                .findFirst()
                .filter(next -> next.role().equals("assistant"))
                .map(next -> new SavedPair(user, next)));
    }

    /** The user message stored with this key in the conversation (the partial UNIQUE index of V21). */
    private Optional<Message> keyedQuestion(long conversationId, UUID clientMessageId) {
        return messages.values().stream()
                .filter(m -> m.conversationId() == conversationId && m.role().equals("user")
                        && clientMessageId.equals(clientIds.get(m.id())))
                .findFirst();
    }

    /** clientMessageId of a stored message, if it had one. */
    public synchronized Optional<UUID> clientMessageIdOf(long messageId) {
        return Optional.ofNullable(clientIds.get(messageId));
    }

    @Override
    public synchronized Optional<Summary> activeSummary(long conversationId) {
        return live(conversationId) ? rawActiveSummary(conversationId) : Optional.empty();
    }

    /** The partial UNIQUE index applies to every physical row. */
    private Optional<Summary> rawActiveSummary(long conversationId) {
        return summaries.values().stream()
                .filter(s -> s.conversationId() == conversationId && s.supersededAt() == null
                        && s.invalidatedAt() == null)
                .max(Comparator.comparing(Summary::id));
    }

    @Override
    public synchronized Optional<Summary> findSummary(long summaryId) {
        return Optional.ofNullable(summaries.get(summaryId)).filter(s -> live(s.conversationId()));
    }

    @Override
    public synchronized void supersedeSummary(long summaryId, Instant now) {
        calls.add("supersede:" + summaryId);
        Summary s = summaries.get(summaryId);
        if (s != null) {
            summaries.put(summaryId, withSuperseded(s, now));
        }
    }

    @Override
    public synchronized Summary insertSummary(long conversationId, String text, long fromMessageId, long toMessageId,
                                              Instant now) {
        calls.add("insertSummary:" + conversationId);
        if (!conversations.containsKey(conversationId)) {
            throw new DataIntegrityViolationException("ai_conversation_summaries.conversation_id FK");
        }
        if (rawActiveSummary(conversationId).isPresent()) {
            throw new DataIntegrityViolationException("partial unique: one active summary per conversation");
        }
        long id = nextSummary++;
        Summary s = new Summary(id, conversationId, text, fromMessageId, toMessageId, now, null, null);
        summaries.put(id, s);
        return s;
    }

    @Override
    public synchronized List<Memory> memories(long userId, Long sessionId, int limit) {
        memoryQueries.add(new Object[] {userId, sessionId, limit});
        return memories.values().stream()
                .filter(m -> m.userId() == userId && Objects.equals(m.sessionId(), sessionId) && liveMemory(m))
                .sorted(Comparator.comparing(Memory::createdAt).thenComparing(Memory::id).reversed())
                .limit(limit)
                .toList();
    }

    @Override
    public synchronized List<Memory> memoriesOf(long conversationId) {
        return memories.values().stream()
                .filter(m -> m.conversationId() == conversationId && liveMemory(m))
                .sorted(Comparator.comparing(Memory::createdAt).thenComparing(Memory::id).reversed())
                .toList();
    }

    @Override
    public synchronized int countMemories(long userId, Long sessionId) {
        return (int) memories.values().stream()
                .filter(m -> m.userId() == userId && Objects.equals(m.sessionId(), sessionId) && liveMemory(m))
                .count();
    }

    @Override
    public synchronized Memory insertMemory(long userId, Long sessionId, long conversationId, String text,
                                            Instant now) {
        calls.add("insertMemory:" + conversationId);
        if (!conversations.containsKey(conversationId)) {
            throw new DataIntegrityViolationException("ai_memories.conversation_id FK");
        }
        return seedMemory(userId, sessionId, conversationId, text, now);
    }

    @Override
    public synchronized boolean softDeleteMemory(long conversationId, long memoryId, Instant now) {
        calls.add("deleteMemory:" + memoryId);
        Memory m = memories.get(memoryId);
        // like the UPDATE: a live memory of a live conversation only
        if (m == null || m.conversationId() != conversationId || deletedMemories.containsKey(memoryId)
                || !live(conversationId)) {
            return false;
        }
        deletedMemories.put(memoryId, now);
        return true;
    }

    @Override
    public synchronized Optional<Conversation> setSaved(long conversationId, Instant savedAt) {
        calls.add("setSaved:" + conversationId);
        Conversation c = conversations.get(conversationId);
        if (c == null || !live(conversationId)) {
            return Optional.empty();
        }
        // like COALESCE(saved_at, ?): saving again keeps the first time
        Conversation updated = withSavedAt(c, savedAt == null ? null : c.savedAt() != null ? c.savedAt() : savedAt);
        conversations.put(conversationId, updated);
        return Optional.of(updated);
    }

    /** ended_at IS NOT NULL AND ended_at + COALESCE(ai_retention_days, 90) days < now. */
    private boolean sessionExpired(Long sessionId, Instant now) {
        SessionRow s = sessionId == null ? null : sessions.get(sessionId);
        if (s == null || s.endedAt() == null) {
            return false;
        }
        int days = s.aiRetentionDays() == null ? 90 : s.aiRetentionDays();
        return s.endedAt().plus(Duration.ofDays(days)).isBefore(now);
    }

    @Override
    public synchronized int deleteExpiredClassConversations(Instant now, int limit) {
        calls.add("deleteExpiredClass");
        List<Long> due = conversations.values().stream()
                .filter(c -> c.sessionId() != null && c.savedAt() == null && sessionExpired(c.sessionId(), now))
                .map(Conversation::id)
                .limit(limit)
                .toList();
        due.forEach(this::hardDelete);
        return due.size();
    }

    @Override
    public synchronized int deleteExpiredClassMemories(Instant now, int limit) {
        calls.add("deleteExpiredClassMemories");
        List<Long> due = memories.values().stream()
                .filter(m -> sessionExpired(m.sessionId(), now))
                .map(Memory::id)
                .limit(limit)
                .toList();
        due.forEach(id -> {
            memories.remove(id);
            deletedMemories.remove(id);
        });
        return due.size();
    }

    @Override
    public synchronized int deleteIdlePersonalConversationsBefore(Instant cutoff, int limit) {
        calls.add("deleteIdlePersonal");
        List<Long> old = conversations.values().stream()
                .filter(c -> c.sessionId() == null && c.savedAt() == null && c.lastMessageAt().isBefore(cutoff))
                .sorted(Comparator.comparing(Conversation::lastMessageAt).thenComparing(Conversation::id))
                .map(Conversation::id)
                .limit(limit)
                .toList();
        old.forEach(this::hardDelete);
        return old.size();
    }

    private static Conversation withSavedAt(Conversation c, Instant savedAt) {
        return new Conversation(c.id(), c.userId(), c.sessionId(), c.topic(), c.title(), c.messageCount(),
                c.createdAt(), c.lastMessageAt(), savedAt);
    }

    @Override
    public synchronized int purgeDeletedConversationsBefore(Instant cutoff, int limit) {
        calls.add("purgeConversations");
        List<Long> due = expired(deletedConversations, cutoff, limit);
        due.forEach(this::hardDelete);
        return due.size();
    }

    @Override
    public synchronized int purgeDeletedMemoriesBefore(Instant cutoff, int limit) {
        calls.add("purgeMemories");
        List<Long> due = expired(deletedMemories, cutoff, limit);
        due.forEach(id -> {
            memories.remove(id);
            deletedMemories.remove(id);
        });
        return due.size();
    }

    /** ids whose deleted_at < cutoff, oldest first (ORDER BY deleted_at, id LIMIT n). */
    private static List<Long> expired(Map<Long, Instant> deleted, Instant cutoff, int limit) {
        return deleted.entrySet().stream()
                .filter(e -> e.getValue().isBefore(cutoff))
                .sorted(Map.Entry.<Long, Instant>comparingByValue().thenComparing(Map.Entry.comparingByKey()))
                .map(Map.Entry::getKey)
                .limit(limit)
                .toList();
    }


    private static Summary withSuperseded(Summary s, Instant at) {
        return new Summary(s.id(), s.conversationId(), s.text(), s.fromMessageId(), s.toMessageId(), s.createdAt(),
                at, s.invalidatedAt());
    }
}
