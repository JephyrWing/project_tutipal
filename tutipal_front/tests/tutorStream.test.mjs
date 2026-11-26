import { test } from 'node:test'
import assert from 'node:assert/strict'
import { streamChat } from '../src/api/tutor.js'

const sse = (...events) => events.map(([name, data]) => `event:${name}\ndata:${JSON.stringify(data)}\n\n`).join('')

function stubFetch(body) {
  const calls = []
  globalThis.fetch = async (url, init) => {
    calls.push({ url, init })
    if (url === '/api/auth/csrf') {
      return new Response(JSON.stringify({ headerName: 'X-XSRF-TOKEN', token: 't' }), { status: 200 })
    }
    return new Response(new ReadableStream({
      start(controller) {
        controller.enqueue(new TextEncoder().encode(body))
        controller.close()
      },
    }), { status: 200, headers: { 'Content-Type': 'text/event-stream' } })
  }
  return calls
}

const AGENT = { route: 'code_debug', suggestHelp: true, variant: null, answerHidden: true, retryable: false,
  helpDraft: '[AI 조교 요약] 질문 종류: 코드 오류' }

test('agent event is passed to onAgent once and unknown events are still ignored', async () => {
  const calls = stubFetch(sse(['delta', { t: '힌트' }], ['foo', { x: 1 }], ['agent', AGENT], ['done', {}]))
  const deltas = []
  const agents = []
  await streamChat('수학', [{ role: 'user', content: '에러' }], (d) => deltas.push(d), undefined, 10,
    undefined, (event) => agents.push(event))
  assert.deepEqual(deltas, ['힌트'])
  assert.deepEqual(agents, [AGENT])
  const sent = JSON.parse(calls.find((c) => c.url === '/api/tutor/chat').init.body)
  assert.deepEqual(Object.keys(sent).sort(), ['messages', 'sessionId', 'topic'])
})

test('streamChat without onAgent still finishes on done', async () => {
  stubFetch(sse(['delta', { t: 'a' }], ['agent', AGENT], ['delta', { t: 'b' }], ['done', {}]))
  const deltas = []
  await streamChat('수학', [{ role: 'user', content: '질문' }], (d) => deltas.push(d), undefined, 10)
  assert.deepEqual(deltas, ['a', 'b'])
})
