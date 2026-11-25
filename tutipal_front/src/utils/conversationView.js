/**
 * PT-106 pure view-state helpers for ConversationsPage (list + detail). No React, no fetch: the page calls the API
 * and feeds results in, so empty / error / streaming / stop states are testable with node:test.
 */

export const CONVERSATIONS_PATH = '/conversations'
export const MEMORY_MAX = 1000
const MISSING_TEXT = '없는 대화예요. 목록으로 돌아갈게요'
const UNTITLED = '새 대화'

const messageOf = (error, fallback) => (error instanceof Error && error.message ? error.message : fallback)

/** Keeps the first occurrence of every id, in order. */
function uniqueById(items) {
  const seen = new Set()
  return items.filter((item) => {
    if (seen.has(item.id)) return false
    seen.add(item.id)
    return true
  })
}

export function conversationTitle(conversation) {
  return conversation?.title || UNTITLED
}

// ------------------------------------------------------------------ list

export function initialListState() {
  return { status: 'loading', items: [], nextCursor: null, hasMore: false, error: null }
}

export function listLoading(state) {
  return { ...state, status: 'loading', error: null }
}

/** append: next page (merged, duplicates dropped); otherwise the page replaces the list. */
export function listLoaded(state, page, { append = false } = {}) {
  const items = uniqueById(append ? [...state.items, ...page.items] : [...page.items])
  return {
    ...state,
    status: items.length ? 'ready' : 'empty',
    items,
    nextCursor: page.nextCursor ?? null,
    hasMore: !!page.hasMore,
    error: null,
  }
}

/** Keeps what was already shown so the user can retry. */
export function listFailed(state, error) {
  return { ...state, status: 'error', error: messageOf(error, '대화 목록을 불러오지 못했어요') }
}

// ------------------------------------------------------------------ detail

export function initialDetailState() {
  return {
    status: 'loading',
    conversation: null,
    messages: [],
    nextBefore: null,
    hasMore: false,
    input: '',
    pending: null,
    notice: null,
    redirectTo: null,
    error: null,
  }
}

export function detailLoaded(state, conversation, page) {
  return {
    ...state,
    status: 'ready',
    conversation,
    messages: [...page.items],
    nextBefore: page.nextBefore ?? null,
    hasMore: !!page.hasMore,
    error: null,
    redirectTo: null,
  }
}

/** List path of the conversation's scope (session conversations go back to that session's list). */
export function listPathFor(conversation) {
  const sessionId = conversation?.sessionId
  return sessionId ? `${CONVERSATIONS_PATH}?sessionId=${encodeURIComponent(sessionId)}` : CONVERSATIONS_PATH
}

function missing(state) {
  return {
    ...state,
    status: 'missing',
    pending: null,
    notice: { kind: 'missing', text: MISSING_TEXT },
    redirectTo: listPathFor(state.conversation),
  }
}

/** 404 (deleted or someone else's) → 없는 대화 and back to the list; anything else stays with a retry. */
export function detailFailed(state, error) {
  if (error?.status === 404) return missing(state)
  return { ...state, status: 'error', error: messageOf(error, '대화를 불러오지 못했어요'), redirectTo: null }
}

/** "이전 메시지 더 보기": older page goes in front, duplicates dropped. */
export function olderLoaded(state, page) {
  return {
    ...state,
    messages: uniqueById([...page.items, ...state.messages]),
    nextBefore: page.nextBefore ?? null,
    hasMore: !!page.hasMore,
  }
}

export function noticeFor(error) {
  if (error?.status === 429) return { kind: 'busy', text: messageOf(error, '앞 질문의 답을 아직 만들고 있어요') }
  if (error?.status === 410) return { kind: 'ended', text: messageOf(error, '끝난 수업이라 이어서 질문할 수 없어요') }
  if (error?.status === 404) return { kind: 'missing', text: MISSING_TEXT }
  return { kind: 'error', text: messageOf(error, '답변하지 못했어요') }
}

export function turnStarted(state, question) {
  return { ...state, pending: { question, answer: '' }, input: '', notice: null }
}

export function turnDelta(state, delta) {
  if (!state.pending) return state
  return { ...state, pending: { ...state.pending, answer: state.pending.answer + delta } }
}

/** The server stored the pair: show it with the server ids. */
export function turnSaved(state, saved, now = new Date().toISOString()) {
  if (!state.pending) return state
  const { question, answer } = state.pending
  const added = [
    { id: saved.userMessageId, role: 'user', content: question, createdAt: now },
    { id: saved.assistantMessageId, role: 'assistant', content: answer, createdAt: now },
  ]
  const conversation = state.conversation && {
    ...state.conversation,
    title: state.conversation.title || question.slice(0, 40),
    messageCount: (state.conversation.messageCount ?? 0) + 2,
    lastMessageAt: now,
  }
  return { ...state, conversation, messages: uniqueById([...state.messages, ...added]), pending: null }
}

/** Nothing was saved: drop the partial answer and give the question back. */
export function turnFailed(state, error) {
  if (error?.status === 404) return missing(state)
  return { ...state, pending: null, input: state.pending?.question ?? state.input, notice: noticeFor(error) }
}

export function turnStopped(state) {
  return { ...state, pending: null, input: state.pending?.question ?? state.input, notice: null }
}

export function isInputLocked(state) {
  return state.pending !== null
}

/** Stored messages plus the turn in progress (question + growing answer marked streaming). */
export function visibleMessages(state) {
  if (!state.pending) return state.messages
  return [
    ...state.messages,
    { id: 'pending-user', role: 'user', content: state.pending.question, createdAt: null },
    { id: 'pending-assistant', role: 'assistant', content: state.pending.answer, createdAt: null, streaming: true },
  ]
}

/** Memory form starts with the summary, else the last answer (the user edits it before saving). */
export function memoryDraft(state) {
  const summary = state.conversation?.summary?.text
  const lastAnswer = [...state.messages].reverse().find((m) => m.role === 'assistant')?.content
  return (summary || lastAnswer || '').slice(0, MEMORY_MAX)
}

/** Asks first; only after a successful delete goes back to the list. */
export async function confirmAndDelete({ confirm, remove, navigate }) {
  if (!confirm()) return false
  await remove()
  navigate(CONVERSATIONS_PATH)
  return true
}
