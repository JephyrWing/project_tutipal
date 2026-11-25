// PT-106 AC-25 / AC-26: AI conversation API module, SSE parser and pure view-state helpers.
import { test, beforeEach, afterEach } from 'node:test'
import assert from 'node:assert/strict'
import { ApiRequestError } from '../src/api/client.js'
import { parseSseEvent, readSse } from '../src/api/sse.js'
import {
  listConversations,
  getConversation,
  listMessages,
  createConversation,
  deleteConversation,
  saveMemory,
  deleteMemory,
  sendMessage,
} from '../src/api/conversations.js'
import {
  CONVERSATIONS_PATH,
  conversationTitle,
  initialListState,
  listLoading,
  listLoaded,
  listFailed,
  initialDetailState,
  detailLoaded,
  detailFailed,
  olderLoaded,
  turnStarted,
  turnDelta,
  turnSaved,
  turnFailed,
  turnStopped,
  isInputLocked,
  visibleMessages,
  noticeFor,
  memoryDraft,
  confirmAndDelete,
} from '../src/utils/conversationView.js'

// ------------------------------------------------------------------ fetch mock

const encoder = new TextEncoder()
const streamOf = (chunks) => new ReadableStream({
  start(controller) {
    for (const chunk of chunks) controller.enqueue(encoder.encode(chunk))
    controller.close()
  },
})
const sseResponse = (chunks) => new Response(streamOf(chunks), {
  status: 200, headers: { 'Content-Type': 'text/event-stream' },
})
const jsonResponse = (status, body, headers = {}) => new Response(body === null ? null : JSON.stringify(body), {
  status, headers: { 'Content-Type': 'application/json', ...headers },
})
const ev = (name, data) => `event:${name}\ndata:${JSON.stringify(data)}\n\n`

let calls
let realFetch
function serve(handler) {
  globalThis.fetch = async (url, init = {}) => {
    const path = String(url)
    if (path === '/api/auth/csrf') return jsonResponse(200, { headerName: 'X-XSRF-TOKEN', token: 'csrf-1' })
    calls.push({ path, init })
    return handler(path, init)
  }
}
beforeEach(() => { calls = []; realFetch = globalThis.fetch })
afterEach(() => { globalThis.fetch = realFetch })

// ------------------------------------------------------------------ AC-25 sse.js

test('parseSseEvent reads the event name and joins data lines', () => {
  assert.deepEqual(parseSseEvent('event: delta\ndata: {"t":"a"}'), { name: 'delta', data: '{"t":"a"}' })
  assert.deepEqual(parseSseEvent('event:saved\ndata:{"a":1}'), { name: 'saved', data: '{"a":1}' })
  assert.deepEqual(parseSseEvent(': ping'), { name: 'message', data: '' })
})

test('readSse dispatches events across chunk boundaries and CRLF and resolves on done', async () => {
  const deltas = []
  const sources = []
  const agents = []
  const saved = []
  const body = streamOf([
    'event:sources\r\ndata:{"items":["a.pdf p.1"]}\r\n\r\nevent:del',
    'ta\ndata:{"t":"안녕"}\n\n: ping\n\nevent:delta\ndata:{"t":"하세요"}\n',
    '\nevent:agent\ndata:{"route":"concept"}\n\n',
    ev('saved', { userMessageId: '11', assistantMessageId: '12' }) + ev('done', {}),
  ])
  await readSse(body, {
    onDelta: (t) => deltas.push(t),
    onSources: (items) => sources.push(items),
    onAgent: (event) => agents.push(event),
    onSaved: (ids) => saved.push(ids),
  })
  assert.deepEqual(deltas, ['안녕', '하세요'])
  assert.deepEqual(sources, [['a.pdf p.1']])
  assert.deepEqual(agents, [{ route: 'concept' }])
  assert.deepEqual(saved, [{ userMessageId: '11', assistantMessageId: '12' }])
})

test('readSse throws the server message on an error event', async () => {
  await assert.rejects(
    readSse(streamOf([ev('delta', { t: '부분' }), ev('error', { message: '대화가 삭제되어 저장하지 않았어요' })]), {}),
    (error) => error instanceof Error && error.message === '대화가 삭제되어 저장하지 않았어요',
  )
})

test('readSse throws when the stream ends without done', async () => {
  await assert.rejects(readSse(streamOf([ev('delta', { t: '부분' })]), {}), /연결이 끊겨/)
  await assert.rejects(
    readSse(streamOf([ev('delta', { t: '답' }), ev('saved', { userMessageId: '1', assistantMessageId: '2' })]), {}),
    /연결이 끊겨/,
  )
})

// ------------------------------------------------------------------ AC-25 conversations.js send

test('sendMessage posts the content with CSRF and accumulates deltas until done', async () => {
  serve(() => sseResponse([ev('delta', { t: '안녕' }), ev('delta', { t: '하세요' }),
    ev('saved', { userMessageId: '21', assistantMessageId: '22' }), ev('done', {})]))
  const pieces = []
  const controller = new AbortController()

  const result = await sendMessage('12', '인사해 줘', { onDelta: (t) => pieces.push(t) }, controller.signal)

  assert.deepEqual(pieces, ['안녕', '하세요'])
  assert.equal(result.text, '안녕하세요')
  assert.deepEqual(result.saved, { userMessageId: '21', assistantMessageId: '22' })
  assert.equal(calls.length, 1)
  const { path, init } = calls[0]
  assert.equal(path, '/api/conversations/12/messages')
  assert.equal(init.method, 'POST')
  const headers = new Headers(init.headers)
  assert.equal(headers.get('accept'), 'text/event-stream')
  assert.equal(headers.get('content-type'), 'application/json')
  assert.equal(headers.get('x-xsrf-token'), 'csrf-1')
  assert.deepEqual(JSON.parse(init.body), { content: '인사해 줘' })
  assert.equal(init.signal, controller.signal)
})

test('sendMessage passes agent and sources events through', async () => {
  serve(() => sseResponse([ev('sources', { items: ['b.pdf'] }), ev('delta', { t: '답' }),
    ev('agent', { route: 'concept', suggestHelp: false }),
    ev('saved', { userMessageId: '1', assistantMessageId: '2' }), ev('done', {})]))
  const seen = []

  await sendMessage('3', '질문', {
    onSources: (items) => seen.push(['sources', items]),
    onAgent: (event) => seen.push(['agent', event.route]),
    onSaved: (ids) => seen.push(['saved', ids.assistantMessageId]),
  })

  assert.deepEqual(seen, [['sources', ['b.pdf']], ['agent', 'concept'], ['saved', '2']])
})

test('sendMessage rejects with the SSE error message and no saved ids', async () => {
  serve(() => sseResponse([ev('delta', { t: '부분' }), ev('error', { message: 'AI 조교에 연결할 수 없어요' })]))

  await assert.rejects(sendMessage('3', '질문'), /AI 조교에 연결할 수 없어요/)
})

test('sendMessage rejects when the stream is cut before done', async () => {
  serve(() => sseResponse([ev('delta', { t: '부분' })]))

  await assert.rejects(sendMessage('3', '질문'), /연결이 끊겨/)
})

for (const status of [400, 403, 404, 410]) {
  test(`sendMessage keeps HTTP ${status} status and server message`, async () => {
    serve(() => jsonResponse(status, { status, message: `서버 문구 ${status}` }))

    await assert.rejects(sendMessage('3', '질문'), (error) => {
      assert.ok(error instanceof ApiRequestError)
      assert.equal(error.status, status)
      assert.equal(error.message, `서버 문구 ${status}`)
      return true
    })
  })
}

test('sendMessage keeps a 429 turn-in-progress message and a 429 quota problem', async () => {
  serve(() => jsonResponse(429, { status: 429, message: '이미 답변을 만들고 있어요' }))
  await assert.rejects(sendMessage('3', '질문'), (error) => error.status === 429
    && error.message === '이미 답변을 만들고 있어요')

  serve(() => jsonResponse(429, {
    status: 429, title: 'AI 사용 한도 초과', detail: '챗봇 질문 한도를 다 썼어요', message: '챗봇 질문 한도를 다 썼어요',
    feature: 'CHAT', retryAt: '2026-11-25T01:00:00Z', retryAfterSeconds: 3600,
  }, { 'Retry-After': '3600' }))
  await assert.rejects(sendMessage('3', '질문'), (error) => error.status === 429
    && error.message === '챗봇 질문 한도를 다 썼어요' && error.feature === 'CHAT')
})

// ------------------------------------------------------------------ conversations.js REST

test('listConversations builds the query from given values only', async () => {
  serve(() => jsonResponse(200, { items: [], nextCursor: null, hasMore: false }))

  assert.deepEqual(await listConversations(), { items: [], nextCursor: null, hasMore: false })
  await listConversations({ sessionId: '10', cursor: 'abc=', limit: 20 })

  assert.equal(calls[0].path, '/api/conversations')
  const url = new URL(calls[1].path, 'http://x')
  assert.equal(url.pathname, '/api/conversations')
  assert.equal(url.searchParams.get('sessionId'), '10')
  assert.equal(url.searchParams.get('cursor'), 'abc=')
  assert.equal(url.searchParams.get('limit'), '20')
})

test('detail, messages page, create, delete and memory calls hit the PT-106 endpoints', async () => {
  serve((path, init) => {
    if (init.method === 'DELETE') return new Response(null, { status: 204 })
    if (path === '/api/conversations' && init.method === 'POST') return jsonResponse(201, { id: '5' })
    if (path.endsWith('/memories')) return jsonResponse(201, { id: '9', text: '기억', createdAt: '2026-11-25T00:00:00Z' })
    return jsonResponse(200, { ok: path })
  })

  await getConversation('5')
  await listMessages('5', { before: '40', limit: 50 })
  assert.deepEqual(await createConversation({ topic: '수학' }), { id: '5' })
  await saveMemory('5', '기억')
  await deleteMemory('5', '9')
  await deleteConversation('5')

  assert.equal(calls[0].path, '/api/conversations/5')
  const page = new URL(calls[1].path, 'http://x')
  assert.equal(page.pathname, '/api/conversations/5/messages')
  assert.equal(page.searchParams.get('before'), '40')
  assert.equal(page.searchParams.get('limit'), '50')
  assert.equal(calls[2].path, '/api/conversations')
  assert.deepEqual(JSON.parse(calls[2].init.body), { topic: '수학' })
  assert.equal(calls[3].path, '/api/conversations/5/memories')
  assert.deepEqual(JSON.parse(calls[3].init.body), { text: '기억' })
  assert.equal(calls[4].path, '/api/conversations/5/memories/9')
  assert.equal(calls[4].init.method, 'DELETE')
  assert.equal(calls[5].path, '/api/conversations/5')
  assert.equal(calls[5].init.method, 'DELETE')
})

test('REST errors keep status and message', async () => {
  serve(() => jsonResponse(404, { status: 404, message: '대화를 찾을 수 없어요' }))

  await assert.rejects(getConversation('77'), (error) => error instanceof ApiRequestError && error.status === 404
    && error.message === '대화를 찾을 수 없어요')
})

// ------------------------------------------------------------------ AC-26 list view

const item = (id, title = null) => ({ id, topic: '수학', title, sessionId: null, messageCount: 0, lastMessageAt: '2026-11-25T00:00:00Z' })

test('list starts loading, then empty or ready', () => {
  const start = initialListState()
  assert.equal(start.status, 'loading')
  assert.deepEqual(start.items, [])

  assert.equal(listLoaded(start, { items: [], nextCursor: null, hasMore: false }).status, 'empty')
  const ready = listLoaded(start, { items: [item('1')], nextCursor: 'c1', hasMore: true })
  assert.equal(ready.status, 'ready')
  assert.equal(ready.nextCursor, 'c1')
  assert.equal(ready.hasMore, true)
})

test('list error keeps items and retry goes back to loading', () => {
  const ready = listLoaded(initialListState(), { items: [item('1')], nextCursor: null, hasMore: false })
  const failed = listFailed(ready, new Error('서버 오류'))
  assert.equal(failed.status, 'error')
  assert.equal(failed.error, '서버 오류')
  assert.deepEqual(failed.items.map((i) => i.id), ['1'])

  const retry = listLoading(failed)
  assert.equal(retry.status, 'loading')
  assert.equal(retry.error, null)
})

test('next page merges and drops duplicates by id', () => {
  const first = listLoaded(initialListState(), { items: [item('3'), item('2')], nextCursor: 'c', hasMore: true })
  const merged = listLoaded(first, { items: [item('2'), item('1')], nextCursor: null, hasMore: false }, { append: true })

  assert.deepEqual(merged.items.map((i) => i.id), ['3', '2', '1'])
  assert.equal(merged.hasMore, false)
  assert.equal(merged.nextCursor, null)
  const replaced = listLoaded(merged, { items: [item('9')], nextCursor: null, hasMore: false })
  assert.deepEqual(replaced.items.map((i) => i.id), ['9'])
})

test('a conversation without title shows 새 대화', () => {
  assert.equal(conversationTitle(item('1')), '새 대화')
  assert.equal(conversationTitle(item('1', '미분 질문')), '미분 질문')
})

// ------------------------------------------------------------------ AC-26 detail view

const msg = (id, role, content) => ({ id, role, content, createdAt: '2026-11-25T00:00:00Z' })
const conversation = (summary = null) => ({ id: '5', topic: '수학', title: '미분', sessionId: null, messageCount: 4,
  createdAt: '2026-11-25T00:00:00Z', lastMessageAt: '2026-11-25T00:00:00Z', summary, memories: [] })
const loaded = (summary = null) => detailLoaded(initialDetailState(), conversation(summary), {
  items: [msg('3', 'user', '미분이 뭐야?'), msg('4', 'assistant', '변화율이에요')], nextBefore: '3', hasMore: true,
})

test('older page is prepended without duplicates', () => {
  const state = olderLoaded(loaded(), {
    items: [msg('1', 'user', '안녕'), msg('2', 'assistant', '안녕하세요'), msg('3', 'user', '미분이 뭐야?')],
    nextBefore: null, hasMore: false,
  })
  assert.deepEqual(state.messages.map((m) => m.id), ['1', '2', '3', '4'])
  assert.equal(state.hasMore, false)
  assert.equal(state.nextBefore, null)
})

test('streaming shows the question and the growing answer and locks the input', () => {
  let state = loaded()
  assert.equal(isInputLocked(state), false)

  state = turnStarted(state, '적분은?')
  assert.equal(isInputLocked(state), true)
  assert.equal(state.input, '')
  state = turnDelta(turnDelta(state, '넓이'), '예요')

  const shown = visibleMessages(state)
  assert.equal(shown.length, 4)
  assert.deepEqual(shown.slice(-2).map((m) => [m.role, m.content]), [['user', '적분은?'], ['assistant', '넓이예요']])
  assert.equal(shown.at(-1).streaming, true)
})

test('saved turn becomes two stored messages with server ids and unlocks the input', () => {
  let state = turnDelta(turnStarted(loaded(), '적분은?'), '넓이예요')
  state = turnSaved(state, { userMessageId: '5', assistantMessageId: '6' })

  assert.equal(isInputLocked(state), false)
  assert.deepEqual(state.messages.slice(-2).map((m) => [m.id, m.role, m.content]),
    [['5', 'user', '적분은?'], ['6', 'assistant', '넓이예요']])
  assert.ok(visibleMessages(state).every((m) => !m.streaming))
})

test('stop removes the partial answer and restores the question to the input', () => {
  const state = turnStopped(turnDelta(turnStarted(loaded(), '적분은?'), '넓'))

  assert.equal(isInputLocked(state), false)
  assert.equal(state.input, '적분은?')
  assert.deepEqual(visibleMessages(state).map((m) => m.id), ['3', '4'])
  assert.equal(state.notice, null)
})

test('error removes the partial answer, restores the input and shows a notice', () => {
  const error = Object.assign(new Error('AI 조교에 연결할 수 없어요'), { status: 503 })
  const state = turnFailed(turnDelta(turnStarted(loaded(), '적분은?'), '넓'), error)

  assert.equal(isInputLocked(state), false)
  assert.equal(state.input, '적분은?')
  assert.deepEqual(visibleMessages(state).map((m) => m.id), ['3', '4'])
  assert.equal(state.notice.kind, 'error')
  assert.equal(state.notice.text, 'AI 조교에 연결할 수 없어요')
})

test('429 and 410 get their own notices', () => {
  const busy = noticeFor(Object.assign(new Error('이미 답변을 만들고 있어요'), { status: 429 }))
  assert.equal(busy.kind, 'busy')
  assert.equal(busy.text, '이미 답변을 만들고 있어요')

  const ended = noticeFor(Object.assign(new Error('끝난 수업이에요'), { status: 410 }))
  assert.equal(ended.kind, 'ended')
  assert.ok(ended.text.length > 0)

  const failed = turnFailed(turnStarted(loaded(), '질문'), Object.assign(new Error('끝난 수업이에요'), { status: 410 }))
  assert.equal(failed.notice.kind, 'ended')
})

test('404 shows 없는 대화 and sends the user back to the list', () => {
  const missing = detailFailed(initialDetailState(), Object.assign(new Error('대화를 찾을 수 없어요'), { status: 404 }))
  assert.equal(missing.status, 'missing')
  assert.match(missing.notice.text, /없는 대화/)
  assert.equal(missing.redirectTo, CONVERSATIONS_PATH)
  assert.equal(CONVERSATIONS_PATH, '/conversations')

  const deletedMeanwhile = turnFailed(turnStarted(loaded(), '질문'),
    Object.assign(new Error('대화를 찾을 수 없어요'), { status: 404 }))
  assert.equal(deletedMeanwhile.status, 'missing')
  assert.match(deletedMeanwhile.notice.text, /없는 대화/)
  assert.equal(deletedMeanwhile.redirectTo, '/conversations')
})

test('other detail load errors stay on the page with a retryable error', () => {
  const failed = detailFailed(initialDetailState(), Object.assign(new Error('서버 오류'), { status: 500 }))
  assert.equal(failed.status, 'error')
  assert.equal(failed.redirectTo ?? null, null)
})

test('memory form is pre-filled with the summary, else the last answer, cut to 1000', () => {
  assert.equal(memoryDraft(loaded({ text: '미분 요약', updatedAt: '2026-11-25T00:00:00Z' })), '미분 요약')
  assert.equal(memoryDraft(loaded()), '변화율이에요')
  assert.equal(memoryDraft(detailLoaded(initialDetailState(), conversation(), { items: [], nextBefore: null, hasMore: false })), '')
  const long = detailLoaded(initialDetailState(), conversation(), {
    items: [msg('1', 'assistant', '가'.repeat(1500))], nextBefore: null, hasMore: false,
  })
  assert.equal(memoryDraft(long).length, 1000)
})

test('delete asks for confirmation, then removes and goes back to the list', async () => {
  const done = []
  assert.equal(await confirmAndDelete({ confirm: () => false, remove: async () => done.push('remove'),
    navigate: (to) => done.push(to) }), false)
  assert.deepEqual(done, [])

  assert.equal(await confirmAndDelete({ confirm: () => true, remove: async () => done.push('remove'),
    navigate: (to) => done.push(to) }), true)
  assert.deepEqual(done, ['remove', '/conversations'])
})

test('failed delete does not navigate away', async () => {
  const done = []
  await assert.rejects(confirmAndDelete({ confirm: () => true, remove: async () => { throw new Error('실패') },
    navigate: (to) => done.push(to) }), /실패/)
  assert.deepEqual(done, [])
})
