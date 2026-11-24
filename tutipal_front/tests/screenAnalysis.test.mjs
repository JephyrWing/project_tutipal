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
