// PT-106 Round 1 (developer tests): SSE line endings and bad data, saved-before-cut, notices, scoped redirect.
import { test, beforeEach, afterEach } from 'node:test'
import assert from 'node:assert/strict'
import { BAD_EVENT_MESSAGE, readSse } from '../src/api/sse.js'
import { sendMessage } from '../src/api/conversations.js'
import {
  detailFailed,
  detailLoaded,
  initialDetailState,
  listPathFor,
  noticeFor,
  turnFailed,
  turnStarted,
} from '../src/utils/conversationView.js'

const encoder = new TextEncoder()
const streamOf = (chunks) => new ReadableStream({
  start(controller) {
    for (const chunk of chunks) controller.enqueue(encoder.encode(chunk))
    controller.close()
  },
})

let realFetch
beforeEach(() => { realFetch = globalThis.fetch })
afterEach(() => { globalThis.fetch = realFetch })

test('a CRLF split across chunks does not merge two events', async () => {
  const deltas = []
  await readSse(streamOf([
    'event:delta\r\ndata:{"t":"a"}\r\n\r',
    '\nevent:delta\r\ndata:{"t":"b"}\r\n\r\nevent:done\r\ndata:{}\r\n\r\n',
  ]), { onDelta: (t) => deltas.push(t) })

  assert.deepEqual(deltas, ['a', 'b'])
})

test('CR-only line endings are read', async () => {
  const deltas = []
  await readSse(streamOf(['event:delta\rdata:{"t":"a"}\r\revent:done\rdata:{}\r\r']), {
    onDelta: (t) => deltas.push(t),
  })

  assert.deepEqual(deltas, ['a'])
})

test('non-JSON data gives a friendly message instead of a SyntaxError', async () => {
  await assert.rejects(readSse(streamOf(['event:delta\ndata:not json\n\n']), {}),
    (error) => error.message === BAD_EVENT_MESSAGE)
})

test('a turn cut after saved resolves with the saved ids (the pair is stored, resending would duplicate it)', async () => {
  globalThis.fetch = async (url) => {
    if (String(url) === '/api/auth/csrf') {
      return new Response(JSON.stringify({ headerName: 'X-XSRF-TOKEN', token: 't' }), { status: 200 })
    }
    return new Response(streamOf(['event:delta\ndata:{"t":"답"}\n\n',
      'event:saved\ndata:{"userMessageId":"1","assistantMessageId":"2"}\n\n']), { status: 200 })
  }

  const result = await sendMessage('3', '질문')

  assert.deepEqual(result, { text: '답', saved: { userMessageId: '1', assistantMessageId: '2' } })
})

test('410 without a server message still explains the ended session', () => {
  const notice = noticeFor(Object.assign(new Error(''), { status: 410 }))

  assert.equal(notice.kind, 'ended')
  assert.match(notice.text, /끝난 수업/)
})

test('a missing session conversation goes back to that session list', () => {
  const conversation = { id: '5', topic: '파이썬', title: null, sessionId: '10', messageCount: 0, summary: null, memories: [] }
  const loaded = detailLoaded(initialDetailState(), conversation, { items: [], nextBefore: null, hasMore: false })
  const notFound = Object.assign(new Error('대화를 찾을 수 없어요'), { status: 404 })

  assert.equal(turnFailed(turnStarted(loaded, '질문'), notFound).redirectTo, '/conversations?sessionId=10')
  assert.equal(detailFailed(loaded, notFound).redirectTo, '/conversations?sessionId=10')
  assert.equal(listPathFor(null), '/conversations')
})
