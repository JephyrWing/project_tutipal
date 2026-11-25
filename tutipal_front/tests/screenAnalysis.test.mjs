import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createScreenAnalysis } from '../src/utils/screenAnalysis.js'

const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r }); return { promise, resolve } }
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
  const pending = deferred(); let writes = 0
  const { engine, seen } = setup({ observe: async () => { writes++; return pending.promise } })
  const look = engine.look(); await Promise.resolve(); await Promise.resolve(); await Promise.resolve()
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
