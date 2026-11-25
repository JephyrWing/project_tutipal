import { csrfHeader, request, toApiError } from './client.js'
import { readSse } from './sse.js'

/**
 * PT-106 stored AI conversations (Spring /api/conversations, docs/AI_CONVERSATIONS.md).
 * Ids are strings. HTTP errors are ApiRequestError with the server status and message.
 */

const BASE = '/api/conversations'
const json = { 'Content-Type': 'application/json' }
const path = (id) => `${BASE}/${encodeURIComponent(id)}`

/** Query string from the given values only (null / undefined / '' are left out). */
function withQuery(url, params) {
  const query = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== null && value !== '') query.set(key, String(value))
  }
  const text = query.toString()
  return text ? `${url}?${text}` : url
}

/** No sessionId → personal conversations; sessionId → my conversations in that session. */
export function listConversations({ sessionId, cursor, limit } = {}, signal) {
  return request(withQuery(BASE, { sessionId, cursor, limit }), { signal })
}

export function getConversation(id, signal) {
  return request(path(id), { signal })
}

/** Newest page first (ascending inside the page); pass nextBefore as before for the older page. */
export function listMessages(id, { before, limit } = {}, signal) {
  return request(withQuery(`${path(id)}/messages`, { before, limit }), { signal })
}

/** Personal: { topic }. Session: { sessionId } (the session topic is used). */
export function createConversation({ topic, sessionId } = {}) {
  return request(BASE, { method: 'POST', headers: json, body: JSON.stringify({ topic, sessionId }) })
}

export function deleteConversation(id) {
  return request(path(id), { method: 'DELETE' })
}

export function saveMemory(id, text) {
  return request(`${path(id)}/memories`, { method: 'POST', headers: json, body: JSON.stringify({ text }) })
}

export function deleteMemory(id, memoryId) {
  return request(`${path(id)}/memories/${encodeURIComponent(memoryId)}`, { method: 'DELETE' })
}

/**
 * Continues a conversation. Resolves with { text, saved } once the server stored the pair (saved event) and sent
 * done; rejects with the SSE error message, the cut-stream message, or an ApiRequestError before the stream.
 */
export async function sendMessage(id, content, handlers = {}, signal) {
  const res = await fetch(`${path(id)}/messages`, {
    method: 'POST',
    headers: { ...json, Accept: 'text/event-stream', ...await csrfHeader(signal) },
    body: JSON.stringify({ content }),
    signal,
  })
  if (!res.ok || !res.body) throw await toApiError(res)
  let text = ''
  let saved = null
  await readSse(res.body, {
    onDelta: (t) => {
      text += t
      handlers.onDelta?.(t)
    },
    onSources: (items) => handlers.onSources?.(items),
    onAgent: (event) => handlers.onAgent?.(event),
    onSaved: (ids) => {
      saved = ids
      handlers.onSaved?.(ids)
    },
  })
  return { text, saved }
}
