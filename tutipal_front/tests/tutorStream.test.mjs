import { test } from 'node:test'
import assert from 'node:assert/strict'
import { streamChat } from '../src/api/tutor.js'
import { advanceAuthGeneration } from '../src/api/client.js'

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

test('screen chat sends one POST with bounded summary, no detail or image',async()=>{
 const calls=stubFetch(sse(['done',{}]))
 await streamChat('수학',[{role:'user',content:'q'}],()=>{},undefined,7,undefined,undefined,
  {screenSummary:'가'.repeat(4001),detail:'PRIVATE DETAIL',image:'PRIVATE IMAGE'})
 const posts=calls.filter(c=>c.url==='/api/tutor/chat');assert.equal(posts.length,1)
 const body=JSON.parse(posts[0].init.body)
 assert.equal(body.screenSummary.length,4000);assert.equal(body.sessionId,7)
 assert.deepEqual(Object.keys(body).sort(),['messages','screenSummary','sessionId','topic'])
 assert.ok(!posts[0].init.body.includes('PRIVATE'))
})
test('empty or non-string summary is omitted instead of serializing arbitrary context',async()=>{
 for(const screenSummary of ['',null,{detail:'PRIVATE'}]){
  const calls=stubFetch(sse(['done',{}]))
  await streamChat('자유 주제',[{role:'user',content:'q'}],()=>{},undefined,undefined,undefined,undefined,{screenSummary})
  assert.equal(JSON.parse(calls.at(-1).init.body).screenSummary,undefined)
 }
})
test('abort while CSRF is loading prevents the chat POST',async()=>{
 advanceAuthGeneration();let resolve;const calls=[]
 globalThis.fetch=async(url,init)=>{calls.push({url,init});return await new Promise(r=>{resolve=r})}
 const controller=new AbortController()
 const work=streamChat('수학',[{role:'user',content:'q'}],()=>{},controller.signal)
 controller.abort();resolve(new Response(JSON.stringify({headerName:'X-CSRF',token:'synthetic'})))
 await assert.rejects(work,{name:'AbortError'});assert.equal(calls.length,1)
 assert.equal(calls[0].init.signal,controller.signal)
 advanceAuthGeneration()
})
test('an aborted stream cannot emit even if a test transport ignores cancellation',async()=>{
 const controller=new AbortController(),deltas=[]
 stubFetch(sse(['delta',{t:'first'}],['delta',{t:'late'}],['done',{}]))
 await assert.rejects(streamChat('수학',[{role:'user',content:'q'}],v=>{deltas.push(v);controller.abort()},controller.signal),{name:'AbortError'})
 assert.deepEqual(deltas,['first'])
})
test('SSE error and disconnect are failures without automatic retransmission',async()=>{
 for(const body of [sse(['error',{message:'synthetic failure'}]),sse(['delta',{t:'partial'}])]){
  const calls=stubFetch(body)
  await assert.rejects(streamChat('수학',[{role:'user',content:'q'}],()=>{}))
  assert.equal(calls.filter(c=>c.url==='/api/tutor/chat').length,1)
 }
})
