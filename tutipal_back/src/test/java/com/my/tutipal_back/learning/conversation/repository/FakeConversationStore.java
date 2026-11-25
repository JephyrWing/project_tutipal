package com.my.tutipal_back.learning.conversation.repository;

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

import org.springframework.dao.DataIntegrityViolationException;

/**
 * PT-106 in-memory ConversationStore for service / web tests (JUnit has no DB).
 * Mimics the V21 schema rules the services rely on: ON DELETE CASCADE from a conversation to its messages,
 * summaries and memories, FK failure when writing into a deleted conversation, and the partial UNIQUE index
 * (at most one active summary per conversation). Every lock / delete / pair insert is recorded in {@link #calls}.
 */
public class FakeConversationStore implements ConversationStore {

    private final Map<Long, Conversation> conversations = new LinkedHashMap<>();
    private final Map<Long, Message> messages = new TreeMap<>();
    private final Map<Long, Summary> summaries = new TreeMap<>();
    private final Map<Long, Memory> memories = new TreeMap<>();
    private long nextConversation = 1;
    private long nextMessage = 1;
    private long nextSummary = 1;
    private long nextMemory = 1;

    /** "lockOwned:7", "lock:7", "delete:7", "insertPair:7", "insertSummary:7", "supersede:3" ... in call order. */
    public final List<String> calls = Collections.synchronizedList(new ArrayList<>());
    /** Arguments of every memories(userId, sessionId, limit) query: {userId, sessionId, limit}. */
    public final List<Object[]> memoryQueries = Collections.synchronizedList(new ArrayList<>());

    // ------------------------------------------------------------------ seeding helpers (not part of the port)

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
                c.messageCount() + 1, c.createdAt(), at));
        return m;
    }

    /** Inserts an active summary; a previously active one is superseded first (like the real save). */
    public synchronized Summary seedSummary(long conversationId, String text, long fromMessageId, long toMessageId,
                                            Instant at) {
        activeSummary(conversationId).ifPresent(s -> summaries.put(s.id(), withSuperseded(s, at)));
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

    // ------------------------------------------------------------------ inspection helpers

    public synchronized Optional<Conversation> conversation(long id) {
        return Optional.ofNullable(conversations.get(id));
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

    public synchronized List<Memory> allMemories() {
        return List.copyOf(memories.values());
    }

    /** Whole state as text: equal snapshots mean no DB change. */
    public synchronized String snapshot() {
        return conversations.values() + "|" + messages.values() + "|" + summaries.values() + "|" + memories.values();
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
        return Optional.ofNullable(conversations.get(conversationId)).filter(c -> c.userId() == userId);
    }

    @Override
    public synchronized Optional<Conversation> lockOwned(long userId, long conversationId) {
        calls.add("lockOwned:" + conversationId);
        return findOwned(userId, conversationId);
    }

    @Override
    public synchronized Optional<Conversation> lock(long conversationId) {
        calls.add("lock:" + conversationId);
        return Optional.ofNullable(conversations.get(conversationId));
    }

    @Override
    public synchronized List<Conversation> list(long userId, Long sessionId, Cursor after, int limit) {
        return conversations.values().stream()
                .filter(c -> c.userId() == userId && Objects.equals(c.sessionId(), sessionId))
                .filter(c -> after == null || c.lastMessageAt().isBefore(after.lastMessageAt())
                        || c.lastMessageAt().equals(after.lastMessageAt()) && c.id() < after.id())
                .sorted(Comparator.comparing(Conversation::lastMessageAt).thenComparing(Conversation::id).reversed())
                .limit(limit)
                .toList();
    }

    @Override
    public synchronized void deleteConversation(long conversationId) {
        calls.add("delete:" + conversationId);
        conversations.remove(conversationId);
        messages.values().removeIf(m -> m.conversationId() == conversationId);
        summaries.values().removeIf(s -> s.conversationId() == conversationId);
        memories.values().removeIf(m -> m.conversationId() == conversationId);
    }

    @Override
    public synchronized List<Message> messagesBefore(long conversationId, Long beforeId, int limit) {
        return messages.values().stream()
                .filter(m -> m.conversationId() == conversationId && (beforeId == null || m.id() < beforeId))
                .sorted(Comparator.comparing(Message::id).reversed())
                .limit(limit)
                .toList();
    }

    @Override
    public synchronized List<Message> recentMessagesAfter(long conversationId, long afterMessageId, int limit) {
        List<Message> after = messages.values().stream()
                .filter(m -> m.conversationId() == conversationId && m.id() > afterMessageId)
                .toList();
        return List.copyOf(after.subList(Math.max(0, after.size() - limit), after.size()));
    }

    @Override
    public synchronized int countMessagesAfter(long conversationId, long afterMessageId) {
        return (int) messages.values().stream()
                .filter(m -> m.conversationId() == conversationId && m.id() > afterMessageId).count();
    }

    @Override
    public synchronized List<Message> oldestMessagesAfter(long conversationId, long afterMessageId, int limit) {
        return messages.values().stream()
                .filter(m -> m.conversationId() == conversationId && m.id() > afterMessageId)
                .limit(limit)
                .toList();
    }

    @Override
    public synchronized SavedPair insertPair(long conversationId, String question, String answer, String title,
                                             Instant now) {
        calls.add("insertPair:" + conversationId);
        Conversation c = conversations.get(conversationId);
        if (c == null) {
            throw new DataIntegrityViolationException("ai_conversation_messages.conversation_id FK");
        }
        long userId = nextMessage++;
        long assistantId = nextMessage++;
        Message user = new Message(userId, conversationId, "user", question, now);
        Message assistant = new Message(assistantId, conversationId, "assistant", answer, now);
        messages.put(userId, user);
        messages.put(assistantId, assistant);
        conversations.put(conversationId, new Conversation(c.id(), c.userId(), c.sessionId(), c.topic(),
                c.title() == null ? title : c.title(), c.messageCount() + 2, c.createdAt(), now));
        return new SavedPair(user, assistant);
    }

    @Override
    public synchronized Optional<Summary> activeSummary(long conversationId) {
        return summaries.values().stream()
                .filter(s -> s.conversationId() == conversationId && s.supersededAt() == null
                        && s.invalidatedAt() == null)
                .max(Comparator.comparing(Summary::id));
    }

    @Override
    public synchronized Optional<Summary> findSummary(long summaryId) {
        return Optional.ofNullable(summaries.get(summaryId));
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
        if (activeSummary(conversationId).isPresent()) {
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
                .filter(m -> m.userId() == userId && Objects.equals(m.sessionId(), sessionId))
                .sorted(Comparator.comparing(Memory::createdAt).thenComparing(Memory::id).reversed())
                .limit(limit)
                .toList();
    }

    @Override
    public synchronized List<Memory> memoriesOf(long conversationId) {
        return memories.values().stream()
                .filter(m -> m.conversationId() == conversationId)
                .sorted(Comparator.comparing(Memory::createdAt).thenComparing(Memory::id).reversed())
                .toList();
    }

    @Override
    public synchronized int countMemories(long userId, Long sessionId) {
        return (int) memories.values().stream()
                .filter(m -> m.userId() == userId && Objects.equals(m.sessionId(), sessionId)).count();
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
    public synchronized boolean deleteMemory(long conversationId, long memoryId) {
        calls.add("deleteMemory:" + memoryId);
        Memory m = memories.get(memoryId);
        if (m == null || m.conversationId() != conversationId) {
            return false;
        }
        memories.remove(memoryId);
        return true;
    }

    @Override
    public synchronized int deleteInactiveBefore(Instant cutoff, int limit) {
        List<Long> old = conversations.values().stream()
                .filter(c -> c.lastMessageAt().isBefore(cutoff))
                .map(Conversation::id)
                .limit(limit)
                .toList();
        old.forEach(this::deleteConversation);
        return old.size();
    }

    private static Summary withSuperseded(Summary s, Instant at) {
        return new Summary(s.id(), s.conversationId(), s.text(), s.fromMessageId(), s.toMessageId(), s.createdAt(),
                at, s.invalidatedAt());
    }
}
