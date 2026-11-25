// PT-106 Codex re-review: idempotency key (clientMessageId) lifecycle on the conversation screen.
// The pair can be saved while the saved event is lost; a resend of the unchanged question must reuse the key so the
// server replays the stored answer instead of storing it twice. Editing the text gets a new key.
import { test, beforeEach, afterEach } from 'node:test'
import assert from 'node:assert/strict'
import { sendMessage } from '../src/api/conversations.js'
import {
  clientMessageIdFor,
  detailLoaded,
  initialDetailState,
  newClientMessageId,
  turnDelta,
  turnFailed,
  turnSaved,
  turnStarted,
  turnStopped,
  visibleMessages,
} from '../src/utils/conversationView.js'

const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/

const encoder = new TextEncoder()
const sseResponse = (chunks) => new Response(new ReadableStream({
  start(controller) {
    for (const chunk of chunks) controller.enqueue(encoder.encode(chunk))
    controller.close()
  },
}), { status: 200, headers: { 'Content-Type': 'text/event-stream' } })
const ev = (name, data) => `event:${name}\ndata:${JSON.stringify(data)}\n\n`

let calls
let realFetch
beforeEach(() => {
  calls = []
  realFetch = globalThis.fetch
})
afterEach(() => { globalThis.fetch = realFetch })

function serve(handler) {
  globalThis.fetch = async (url, init = {}) => {
    const path = String(url)
    if (path === '/api/auth/csrf') {
      return new Response(JSON.stringify({ headerName: 'X-XSRF-TOKEN', token: 'csrf-1' }),
        { status: 200, headers: { 'Content-Type': 'application/json' } })
    }
    calls.push({ path, init })
    return handler(path, init)
  }
}

const loaded = () => detailLoaded(initialDetailState(),
  { id: '5', topic: '수학', title: '미분', sessionId: null, messageCount: 2, summary: null, memories: [] },
  { items: [{ id: '3', role: 'user', content: '미분?' }, { id: '4', role: 'assistant', content: '변화율' }],
    nextBefore: null, hasMore: false })

let counter = 0
const ids = () => `id-${++counter}`

test('a new question gets a fresh UUID v4 key', () => {
  const a = newClientMessageId()
  const b = newClientMessageId()
  assert.match(a, UUID_V4)
  assert.notEqual(a, b)

  const state = turnStarted(loaded(), '적분은?')
  assert.match(state.pending.clientMessageId, UUID_V4)
})

test('a failed or cut turn keeps its key for an unchanged resend', () => {
  const started = turnStarted(loaded(), '적분은?', 'key-1')
  assert.equal(started.pending.clientMessageId, 'key-1')

  const cut = turnFailed(turnDelta(started, '넓'), new Error('연결이 끊겨 답변을 받지 못했어요'))
  assert.equal(cut.input, '적분은?')
  assert.deepEqual(cut.retry, { question: '적분은?', clientMessageId: 'key-1' })

  assert.equal(clientMessageIdFor(cut, '적분은?', ids), 'key-1')
  const resent = turnStarted(cut, '적분은?')
  assert.equal(resent.pending.clientMessageId, 'key-1')
  assert.equal(resent.retry, null)
})

test('a stopped turn also keeps its key', () => {
  const stopped = turnStopped(turnStarted(loaded(), '적분은?', 'key-2'))
  assert.equal(stopped.input, '적분은?')
  assert.equal(clientMessageIdFor(stopped, '적분은?', ids), 'key-2')
})

test('editing the restored question produces a new key', () => {
  const failed = turnFailed(turnStarted(loaded(), '적분은?', 'key-3'), Object.assign(new Error('x'), { status: 503 }))
  const edited = { ...failed, input: '적분이 뭐예요?' }

  const next = clientMessageIdFor(edited, '적분이 뭐예요?', ids)
  assert.notEqual(next, 'key-3')
  assert.match(next, /^id-\d+$/)
  // going back to the original text is the same question again: same key (the server replays if it was saved)
  assert.equal(clientMessageIdFor(edited, '적분은?', ids), 'key-3')
})

test('after a saved turn the next question always gets a new key', () => {
  let state = turnStarted(loaded(), '적분은?', 'key-4')
  state = turnSaved(turnDelta(state, '넓이'), { userMessageId: '5', assistantMessageId: '6' })
  assert.equal(state.retry, null)
  assert.notEqual(clientMessageIdFor(state, '적분은?', ids), 'key-4')
})

test('a 409 (key used for another text) drops the key so the resend is a new question', () => {
  const conflict = turnFailed(turnStarted(loaded(), '적분은?', 'key-5'),
    Object.assign(new Error('이미 다른 질문에 쓴 전송 번호예요'), { status: 409 }))
  assert.equal(conflict.input, '적분은?')
  assert.equal(conflict.retry, null)
  assert.notEqual(clientMessageIdFor(conflict, '적분은?', ids), 'key-5')
})

test('a 404 failure clears the key with the rest of the turn', () => {
  const missing = turnFailed(turnStarted(loaded(), '적분은?', 'key-6'), Object.assign(new Error('x'), { status: 404 }))
  assert.equal(missing.retry, null)
})

test('sendMessage sends the key with the content', async () => {
  serve(() => sseResponse([ev('delta', { t: '답' }), ev('saved', { userMessageId: '1', assistantMessageId: '2' }),
    ev('done', {})]))

  await sendMessage('12', '질문', {}, undefined, 'key-7')

  assert.deepEqual(JSON.parse(calls[0].init.body), { content: '질문', clientMessageId: 'key-7' })
})

test('a lost saved event, then a resend with the same key, shows the replayed pair once', async () => {
  // first attempt: the server saved, but the stream was cut before saved reached the screen
  serve(() => sseResponse([ev('delta', { t: '저장된 답' })]))
  let state = turnStarted(loaded(), '적분은?')
  const key = state.pending.clientMessageId
  await assert.rejects(sendMessage('5', '적분은?', { onDelta: (t) => { state = turnDelta(state, t) } }, undefined, key),
    /연결이 끊겨/)
  state = turnFailed(state, new Error('연결이 끊겨 답변을 받지 못했어요'))

  // resend unchanged: same key; the server replays the stored answer (one delta, the stored ids, done)
  serve(() => sseResponse([ev('delta', { t: '저장된 답' }), ev('saved', { userMessageId: '7', assistantMessageId: '8' }),
    ev('done', {})]))
  const resendKey = clientMessageIdFor(state, state.input)
  assert.equal(resendKey, key)
  state = turnStarted(state, state.input, resendKey)
  const result = await sendMessage('5', '적분은?', { onDelta: (t) => { state = turnDelta(state, t) } }, undefined,
    resendKey)
  state = turnSaved(state, result.saved)

  assert.equal(JSON.parse(calls[0].init.body).clientMessageId, key)
  assert.deepEqual(visibleMessages(state).map((m) => [m.id, m.content]),
    [['3', '미분?'], ['4', '변화율'], ['7', '적분은?'], ['8', '저장된 답']])
})
