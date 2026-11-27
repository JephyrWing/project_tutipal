import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createScreenAnalysis } from '../src/utils/screenAnalysis.js'

const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r }); return { promise, resolve } }

test('display result is cleared before a changed frame fails, never replaced by an error', async () => {
  const results = []; let calls = 0
  const { engine } = setup({ onResult: value => results.push(value),
    capture: async () => ({status:'ok', changed:true, image:'synthetic'}),
    describe: async () => { if (++calls === 2) throw Error('failed'); return 'current summary' } })
  await engine.look(); assert.equal(results.at(-1), 'current summary')
  await engine.look(); assert.equal(results.at(-1), null)
})

for (const boundary of ['pause', 'reset', 'delete']) test(`display result ${boundary} clears and ignores late completion`, async () => {
  const results = [], pending = deferred(); let calls = 0
  const { engine } = setup({ onResult: value => results.push(value),
    capture: async () => ({status:'ok', changed:true, image:'synthetic'}),
    describe: () => ++calls === 1 ? Promise.resolve('old') : pending.promise })
  await engine.look(); const flight = engine.look(); await Promise.resolve()
  if (boundary === 'pause') engine.setRunning(false)
  else if (boundary === 'delete') engine.deletion(true)
  else engine.reset()
  pending.resolve('late'); await flight
  assert.equal(results.at(-1), null); assert.ok(!results.includes('late'))
})
function setup(overrides = {}) {
  const seen = { captures: 0, descriptions: 0, records: [], answers: [], suggestions: [], errors: [], gone: [] }
  const engine = createScreenAnalysis({
    capture: async () => { seen.captures++; return { status: 'ok', image: 'synthetic', changed: false } },
    describe: async () => { seen.descriptions++; return '합성 요약' },
    observe: async (s, seconds) => { seen.records.push([s, seconds]); return { recorded: true, suggestion: { id: 1 } } },
    onAnswer: a => seen.answers.push(a), onSuggestion: s => seen.suggestions.push(s),
    onError: e => seen.errors.push(e), onGone: g => seen.gone.push(g),
    now: () => 1000, ...overrides,
  })
  engine.setRunning(true)
  return { engine, seen }
}

test('closed target stops all future capture until explicit restart', async () => {
  let captures = 0
  const { engine, seen } = setup({ capture: async () => { captures++; return { status: 'gone' } } })
  await engine.look(); await engine.look(); await engine.look('question')
  assert.equal(captures, 1); assert.deepEqual(seen.gone, [true]); assert.equal(seen.records.length, 0)
  engine.reset(); engine.setRunning(true); await engine.look(); assert.equal(captures, 2)
})
test('disabled engine never captures', async () => {
  const { engine, seen } = setup(); engine.setRunning(false); await engine.look(); assert.equal(seen.captures, 0)
})
for (const status of [503, 500]) test(`failed ${status} summary retries same unchanged image`, async () => {
  let calls = 0
  const { engine, seen } = setup({ describe: async () => { if (++calls === 1) throw Object.assign(new Error('failure'), { status }); return 'recovered' } })
  await engine.look(); assert.equal(seen.records.length, 0)
  await engine.look(); assert.equal(calls, 2); assert.deepEqual(seen.answers, ['recovered'])
  await engine.look(); assert.equal(calls, 2) // Acknowledged identical screen adds time only.
})
for (const stop of ['pause', 'reset', 'delete']) test(`${stop} rejects late analysis and clears suggestion`, async () => {
  const pending = deferred()
  const { engine, seen } = setup({ describe: () => pending.promise })
  const look = engine.look(); await Promise.resolve(); await Promise.resolve()
  if (stop === 'pause') engine.setRunning(false)
  else if (stop === 'delete') engine.deletion(true)
  else engine.reset()
  pending.resolve('stale'); await look
  assert.equal(seen.answers.length, 0); assert.equal(seen.records.length, 0)
  assert.equal(seen.suggestions.at(-1), null)
})
test('deletion overlapping observation discards late suggestion and next summary write', async () => {
  const pending = deferred(), started = deferred(); let writes = 0
  const { engine, seen } = setup({ observe: async () => { writes++; started.resolve(); return pending.promise } })
  const look = engine.look(); await started.promise
  assert.equal(writes, 1)
  engine.deletion(true); await engine.look(); pending.resolve({ suggestion: { id: 99 } }); await look
  assert.equal(writes, 1); assert.equal(seen.suggestions.at(-1), null)
  assert.ok(!seen.suggestions.some(s => s?.id === 99))
})
test('deletion success or failure can resume fresh analysis, never old response', async () => {
  const { engine, seen } = setup()
  await engine.look(); engine.deletion(true); await engine.look()
  assert.equal(seen.captures, 1); assert.equal(seen.suggestions.at(-1), null)
  engine.deletion(false); await engine.look()
  assert.equal(seen.descriptions, 2)
})
test('concurrent timer and explicit question never duplicate work', async () => {
  const pending = deferred(); const { engine, seen } = setup({ describe: () => pending.promise })
  const look = engine.look(); await engine.look('question'); assert.equal(seen.captures, 1)
  pending.resolve('answer'); await look
})
test('question does not persist its answer or consume required automatic retry', async () => {
  const { engine, seen } = setup(); await engine.look('질문')
  assert.equal(seen.records.length, 0); await engine.look(); assert.equal(seen.descriptions, 2)
})
const quotaError = (retryAt) => Object.assign(new Error('limit'), { status: 429, retryAt: new Date(retryAt) })
test('429 on automatic summary pauses automatic describe until retryAt, then resumes', async () => {
  let clock = 1000; let calls = 0
  const quota = quotaError(61_000)
  const { engine, seen } = setup({
    now: () => clock,
    describe: async () => { if (++calls === 1) throw quota; return 'resumed' },
  })
  await engine.look(); assert.equal(calls, 1); assert.deepEqual(seen.errors, [quota])
  assert.equal(engine.autoPausedUntil(), 61_000)
  clock = 30_000; await engine.look(); assert.equal(calls, 1); assert.equal(seen.records.length, 0)
  assert.equal(seen.captures, 2) // Capture still runs so a closed window is noticed while paused.
  clock = 61_000; assert.equal(engine.autoPausedUntil(), 0)
  await engine.look(); assert.equal(calls, 2); assert.deepEqual(seen.answers, ['resumed'])
  assert.deepEqual(seen.records[0], [null, 0]) // Paused time is not counted as observed learning time.
})
test('closed window is still detected while automatic summaries are paused', async () => {
  let clock = 1000; let status = 'ok'
  const { engine, seen } = setup({
    now: () => clock,
    capture: async () => ({ status, image: 'synthetic', changed: true }),
    describe: async () => { throw quotaError(61_000) },
  })
  await engine.look(); clock = 5000; status = 'gone'
  await engine.look(); assert.deepEqual(seen.gone, [true])
})
test('question still works while automatic summaries are paused by quota', async () => {
  let clock = 1000; let calls = 0
  const { engine, seen } = setup({
    now: () => clock,
    describe: async (_, q) => { if (++calls === 1) throw quotaError(61_000); return q ? 'answer' : 'summary' },
  })
  await engine.look(); clock = 5000
  await engine.look('질문'); assert.equal(calls, 2); assert.deepEqual(seen.answers, ['answer'])
  await engine.look(); assert.equal(calls, 2)
})
test('429 on a question does not pause automatic summaries', async () => {
  let calls = 0
  const { engine, seen } = setup({ describe: async (_, q) => { calls++; if (q) throw quotaError(10 ** 12); return 'summary' } })
  await engine.look('질문'); assert.equal(engine.autoPausedUntil(), 0)
  await engine.look(); assert.equal(calls, 2); assert.deepEqual(seen.answers, ['summary'])
})
test('503 on automatic summary keeps the silent retry and never pauses', async () => {
  let calls = 0
  const busy = Object.assign(new Error('busy'), { status: 503 })
  const { engine } = setup({ describe: async () => { if (++calls === 1) throw busy; return 'ok' } })
  await engine.look(); assert.equal(engine.autoPausedUntil(), 0)
  await engine.look(); assert.equal(calls, 2)
})
test('quota pause is timed on the local clock even when it is far from the server retryAt', async () => {
  for (const skewMs of [15 * 60_000, -15 * 60_000]) {
    let clock = Date.parse('2026-11-25T00:00:00Z') + skewMs; let calls = 0
    // Server says retryAt 00:10 (server time), i.e. 600 s after the response.
    const quota = Object.assign(new Error('limit'), { status: 429, retryAt: new Date('2026-11-25T00:10:00Z'), retryAfterMs: 600_000 })
    const { engine } = setup({ now: () => clock, describe: async () => { if (++calls === 1) throw quota; return 'resumed' } })
    await engine.look(); assert.equal(calls, 1)
    for (let i = 0; i < 19; i++) { clock += 30_000; await engine.look() }
    assert.equal(calls, 1, `no re-request during the 10 min pause (skew ${skewMs})`)
    clock += 30_000; await engine.look()
    assert.equal(calls, 2, `resumes right after 10 min (skew ${skewMs})`)
  }
})
test('each automatic tick skipped by the quota pause reports onPaused so the notice can come back', async () => {
  let clock = 1000; let paused = 0
  const { engine } = setup({
    now: () => clock, onPaused: () => { paused++ },
    describe: async (_, q) => { if (!q) throw quotaError(61_000); return 'answer' },
  })
  await engine.look(); assert.equal(paused, 0)
  clock = 5000; await engine.look('질문'); assert.equal(paused, 0)
  clock = 45_000; await engine.look(); assert.equal(paused, 1)
})

test('invalidation clears a displayed answer and aborts the request signal', async () => {
  const pending = deferred(); let signal; let displayed = ''; let invalidations = 0
  const { engine, seen } = setup({
    onAnswer: value => { displayed = value },
    onInvalidate: () => { displayed = ''; invalidations++ },
    describe: async (_, __, value) => { signal = value; return pending.promise },
  })
  displayed = 'previous summary'
  const work = engine.look(); await Promise.resolve(); await Promise.resolve()
  engine.setRunning(false)
  assert.equal(displayed, ''); assert.equal(signal.aborted, true); assert.equal(invalidations, 2)
  pending.resolve('late'); await work
  assert.equal(displayed, ''); assert.equal(seen.records.length, 0)
})

test('new revision starts immediately and old finally cannot unlock it', async () => {
  const old = deferred(), fresh = deferred(); let calls = 0
  const { engine, seen } = setup({ describe: () => ++calls === 1 ? old.promise : fresh.promise })
  const first = engine.look(); await Promise.resolve()
  engine.reset()
  const second = engine.look(); await Promise.resolve()
  assert.equal(calls, 2)
  old.resolve('stale'); await first
  await engine.look('duplicate'); assert.equal(calls, 2)
  fresh.resolve('fresh'); await second
  assert.deepEqual(seen.answers, ['fresh'])
  assert.ok(!seen.records.some(([s]) => s === 'stale'))
})

test('late capture from a replaced window never starts inference', async () => {
  const old = deferred(); let calls = 0
  const { engine, seen } = setup({ capture: () => ++calls === 1 ? old.promise : Promise.resolve({status:'ok',image:'new',changed:true}) })
  const first = engine.look(); engine.reset(); await engine.look()
  old.resolve({status:'ok',image:'old',changed:true}); await first
  assert.equal(seen.descriptions, 1); assert.equal(seen.answers.length, 1)
})

test('closure during inference rejects result before display or recording', async () => {
  let status = 'ok'
  const { engine, seen } = setup({ checkTarget: async () => ({status}), describe: async () => { status = 'gone'; return 'closed summary' } })
  await engine.look(); await engine.look()
  assert.deepEqual(seen.gone, [true]); assert.deepEqual(seen.answers, []); assert.deepEqual(seen.records, [])
})

test('automatic tick detects closure even if model has not returned', async () => {
  const pending = deferred(); let status = 'ok'; let signal
  const { engine, seen } = setup({ checkTarget: async () => ({status}), describe: (_, __, s) => { signal=s; return pending.promise } })
  const first = engine.look(); await Promise.resolve()
  status='gone'; await engine.look()
  assert.deepEqual(seen.gone,[true]); assert.equal(signal.aborted,true)
  pending.resolve('late'); await first; assert.deepEqual(seen.answers,[])
})

test('late target check from a previous revision cannot stop the new target', async () => {
  const pending = deferred(); let checks = 0
  const { engine, seen } = setup({ checkTarget: () => ++checks === 1 ? pending.promise : Promise.resolve({status:'ok'}) })
  const first = engine.look(); await Promise.resolve(); await Promise.resolve()
  engine.reset(); await engine.look()
  pending.resolve({status:'gone'}); await first
  assert.deepEqual(seen.gone,[]); assert.deepEqual(seen.answers,['합성 요약'])
})

test('target check failure never publishes or records an unchecked answer', async () => {
  const {engine,seen}=setup({checkTarget:async()=>{throw Error('target unavailable')}})
  await engine.look(); assert.equal(seen.answers.length,0); assert.equal(seen.records.length,0); assert.equal(seen.errors.length,1)
})

test('late rejected model cannot replace the new answer with an error', async () => {
  let reject; const old = new Promise((_,r)=>{reject=r}); let calls=0
  const {engine,seen}=setup({describe:()=>++calls===1?old:Promise.resolve('new')})
  const first=engine.look(); await Promise.resolve(); engine.reset(); await engine.look()
  reject(Error('late failure')); await first
  assert.deepEqual(seen.answers,['new']); assert.deepEqual(seen.errors,[])
})

test('chat context contains only the latest automatic summary, never question answers',async()=>{
 const {engine}=setup({describe:async(_,q)=>q?'QUESTION ANSWER':'SCREEN SUMMARY'})
 assert.equal(await engine.summaryForChat(),undefined)
 await engine.look();assert.equal(await engine.summaryForChat(),'SCREEN SUMMARY')
 await engine.look('q');assert.equal(await engine.summaryForChat(),'SCREEN SUMMARY')
 engine.setRunning(false);assert.equal(await engine.summaryForChat(),undefined)
 engine.setRunning(true);assert.equal(await engine.summaryForChat(),undefined)
})
test('a changed frame whose analysis fails cannot reuse its previous summary',async()=>{
 let count=0
 const {engine}=setup({capture:async()=>({status:'ok',changed:true,image:'synthetic'}),describe:async()=>{if(++count>1)throw Error('failed');return'old'}})
 await engine.look();assert.equal(await engine.summaryForChat(),'old')
 await engine.look();assert.equal(await engine.summaryForChat(),undefined)
})
test('summary lookup cannot carry a late snapshot across reset or closure',async()=>{
 let status='ok'
 const {engine,seen}=setup({checkTarget:async()=>({status})})
 await engine.look();const lookup=engine.summaryForChat();engine.reset()
 assert.equal(await lookup,undefined)
 await engine.look();status='gone';assert.equal(await engine.summaryForChat(),undefined)
 assert.deepEqual(seen.gone,[true])
})
