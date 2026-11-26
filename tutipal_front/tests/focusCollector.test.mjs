// PT-90 AC-16 (when collection runs), AC-17 (stop conditions, unit level), AC-18 (payload).
// electron/focusCollector.cjs is pure: probe, idle time, transport, clock and timers are injected.
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'

const require = createRequire(import.meta.url)
const { createFocusCollector } = require('../electron/focusCollector.cjs')

const MINUTE = 60_000
const HOUR = 60 * MINUTE

/** Fake clock + interval registry. advance() moves time in 1 s steps and fires due intervals. */
function fakeTime(start = Date.parse('2026-11-26T03:00:00Z')) {
  let now = start
  let nextId = 1
  const timers = new Map()
  return {
    now: () => now,
    setInterval: (fn, ms) => {
      const id = nextId++
      timers.set(id, { fn, ms, due: now + ms })
      return id
    },
    clearInterval: id => { timers.delete(id) },
    active: () => timers.size,
    async advance(ms) {
      const end = now + ms
      while (now < end) {
        now = Math.min(end, now + 1000)
        for (const [id, timer] of [...timers]) {
          if (!timers.has(id)) continue
          while (timers.has(id) && timer.due <= now) {
            timer.due += timer.ms
            timer.fn()
          }
        }
        await flush()
      }
    },
  }
}

const flush = () => new Promise(resolve => setImmediate(resolve))

function setup(options = {}) {
  const time = fakeTime()
  const log = { probeStarts: 0, kills: 0, idleCalls: 0, sends: [] }
  let onExe = null
  let idle = 0
  let reply = options.reply || (() => ({ result: 'ok', focus: { state: 'FOCUS', updatedAt: '2026-11-26T03:01:00Z' } }))
  const collector = createFocusCollector({
    startProbe: cb => {
      log.probeStarts++
      onExe = cb
      return { kill: () => { log.kills++ } }
    },
    idleSeconds: () => { log.idleCalls++; return idle },
    send: async (sessionId, body) => {
      log.sends.push({ sessionId, body: JSON.parse(JSON.stringify(body)) })
      return reply(sessionId, body)
    },
    now: time.now,
    setInterval: time.setInterval,
    clearInterval: time.clearInterval,
    platform: 'win32',
    ...options.deps,
  })
  return {
    collector, time, log,
    emit: exe => onExe(exe),
    setIdle: value => { idle = value },
    setReply: fn => { reply = fn },
    /** One minute of probe samples: one per second, given as [exe, seconds] runs. */
    async minute(runs) {
      for (const [exe, seconds] of runs) {
        for (let i = 0; i < seconds; i++) {
          onExe(exe)
          await time.advance(1000)
        }
      }
    },
  }
}

// ---- AC-16 nothing runs before start ----

test('a new collector is off', () => {
  const { collector } = setup()
  assert.equal(collector.state().status, 'off')
})

test('before start: no probe, no idle query and no send even after 30 minutes', async () => {
  const { time, log } = setup()
  await time.advance(30 * MINUTE)
  assert.equal(log.probeStarts, 0)
  assert.equal(log.idleCalls, 0)
  assert.equal(log.sends.length, 0)
  assert.equal(time.active(), 0)
})

test('start begins collecting for that session and starts the probe once', () => {
  const { collector, log } = setup()
  collector.start(7)
  assert.equal(collector.state().status, 'collecting')
  assert.equal(collector.state().sessionId, 7)
  assert.equal(log.probeStarts, 1)
})

test('start with an invalid session id does nothing', () => {
  for (const bad of [0, -1, 1.5, '7', null, undefined, Number.MAX_SAFE_INTEGER + 1]) {
    const { collector, log } = setup()
    try { collector.start(bad) } catch { /* rejecting by throwing is fine too */ }
    assert.equal(log.probeStarts, 0, String(bad))
    assert.equal(collector.state().status, 'off', String(bad))
  }
})

test('non-Windows platform is unsupported and never starts the probe', () => {
  const { collector, log } = setup({ deps: { platform: 'linux' } })
  collector.start(7)
  assert.equal(collector.state().status, 'unsupported')
  assert.equal(log.probeStarts, 0)
})

test('a missing probe helper is unsupported', () => {
  const { collector, time } = setup({ deps: { startProbe: () => null } })
  collector.start(7)
  assert.equal(collector.state().status, 'unsupported')
  assert.equal(time.active(), 0)
})

// ---- AC-18 payload ----

test('after one minute the row is sent with exactly exeName, idleSec and switchCount', async () => {
  const s = setup()
  s.collector.start(7)
  s.setIdle(12)
  await s.minute([['code.exe', 40], ['chrome.exe', 20]])
  await s.time.advance(1000)
  assert.equal(s.log.sends.length, 1)
  const { sessionId, body } = s.log.sends[0]
  assert.equal(sessionId, 7)
  assert.deepEqual(Object.keys(body), ['rows'])
  assert.equal(body.rows.length, 1)
  assert.deepEqual(Object.keys(body.rows[0]).sort(), ['exeName', 'idleSec', 'switchCount'])
  assert.equal(body.rows[0].exeName, 'code.exe')
  assert.equal(body.rows[0].idleSec, 12)
})

test('exe of a minute is the most frequent one-second sample', async () => {
  const s = setup()
  s.collector.start(7)
  await s.minute([['chrome.exe', 15], ['code.exe', 35], ['chrome.exe', 10]])
  await s.time.advance(1000)
  assert.equal(s.log.sends[0].body.rows[0].exeName, 'code.exe')
})

// leader ruling (Round 1, F4): same as devdesk sampler.py, null never wins the minute
test('null samples do not win the minute: the readable exe does', async () => {
  const s = setup()
  s.collector.start(7)
  await s.minute([[null, 50], ['code.exe', 10]])
  await s.time.advance(1000)
  assert.equal(s.log.sends[0].body.rows[0].exeName, 'code.exe')
})

test('a minute with only null samples has a null exe', async () => {
  const s = setup()
  s.collector.start(7)
  await s.minute([[null, 60]])
  await s.time.advance(1000)
  assert.equal(s.log.sends[0].body.rows[0].exeName, null)
})

test('null between two different apps is one switch, not two', async () => {
  const s = setup()
  s.collector.start(7)
  await s.minute([['code.exe', 20], [null, 5], ['chrome.exe', 35]])
  await s.time.advance(1000)
  assert.equal(s.log.sends[0].body.rows[0].switchCount, 1)
})

test('null between the same app is no switch', async () => {
  const s = setup()
  s.collector.start(7)
  await s.minute([['code.exe', 10], [null, 5], ['code.exe', 45]])
  await s.time.advance(1000)
  assert.equal(s.log.sends[0].body.rows[0].switchCount, 0)
})

test('the last non-null exe of the previous minute counts for the switch at the minute edge', async () => {
  const s = setup()
  s.collector.start(7)
  await s.minute([['code.exe', 60]])
  await s.minute([[null, 5], ['chrome.exe', 55]])
  await s.time.advance(1000)
  const rows = s.log.sends.flatMap(send => send.body.rows)
  assert.equal(rows.at(-1).exeName, 'chrome.exe')
  assert.equal(rows.at(-1).switchCount, 1)
})

test('switchCount is the number of changes between consecutive samples in the minute', async () => {
  const s = setup()
  s.collector.start(7)
  // a a ... b b ... a a ... c c : 3 changes
  await s.minute([['code.exe', 20], ['chrome.exe', 10], ['code.exe', 20], ['kakaotalk.exe', 10]])
  await s.time.advance(1000)
  assert.equal(s.log.sends[0].body.rows[0].switchCount, 3)
})

test('switchCount is clamped to 120', async () => {
  const s = setup()
  s.collector.start(7)
  for (let i = 0; i < 130; i++) s.emit(i % 2 ? 'a.exe' : 'b.exe')  // burst inside the first second
  await s.minute([['b.exe', 59]])
  await s.time.advance(1000)
  assert.equal(s.log.sends[0].body.rows[0].switchCount, 120)
})

test('idleSec is clamped to 0..60', async () => {
  for (const [raw, expected] of [[75, 60], [3600, 60], [-3, 0], [0, 0], [60, 60]]) {
    const s = setup()
    s.collector.start(7)
    s.setIdle(raw)
    await s.minute([['code.exe', 60]])
    await s.time.advance(1000)
    assert.equal(s.log.sends[0].body.rows[0].idleSec, expected, `idle ${raw}`)
  }
})

test('retry keeps the rows and the next send carries them oldest first', async () => {
  const s = setup({ reply: () => ({ result: 'retry' }) })
  s.collector.start(7)
  s.setIdle(1)
  await s.minute([['code.exe', 60]])
  s.setIdle(2)
  await s.minute([['chrome.exe', 60]])
  await s.time.advance(1000)
  const last = s.log.sends.at(-1).body.rows
  assert.equal(last.length, 2)
  assert.deepEqual(last.map(r => r.idleSec), [1, 2])
  assert.deepEqual(last.map(r => r.exeName), ['code.exe', 'chrome.exe'])
})

test('the buffer keeps at most 15 rows, dropping the oldest', async () => {
  const s = setup({ reply: () => ({ result: 'retry' }) })
  s.collector.start(7)
  for (let i = 0; i < 17; i++) {
    s.setIdle(i)
    await s.minute([['code.exe', 60]])
  }
  await s.time.advance(1000)
  const last = s.log.sends.at(-1).body.rows
  assert.equal(last.length, 15)
  assert.deepEqual(last.map(r => r.idleSec), [2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16])
})

test('ok clears the sent rows and the state carries the server focus state', async () => {
  const s = setup()
  s.collector.start(7)
  await s.minute([['code.exe', 60]])
  await s.minute([['code.exe', 60]])
  await s.time.advance(1000)
  assert.equal(s.log.sends.length, 2)
  assert.equal(s.log.sends[1].body.rows.length, 1)
  assert.equal(s.collector.state().focus.state, 'FOCUS')
})

// ---- AC-16 / AC-17 stop ----

async function collectingWithBufferedRows() {
  const s = setup({ reply: () => ({ result: 'retry' }) })
  s.collector.start(7)
  await s.minute([['code.exe', 60]])
  await s.time.advance(1000)
  return s
}

test('stop kills the probe once, clears all timers and sends nothing more', async () => {
  const s = await collectingWithBufferedRows()
  const sent = s.log.sends.length
  s.collector.stop('user')
  s.collector.stop('user')
  assert.equal(s.log.kills, 1)
  assert.equal(s.time.active(), 0)
  assert.equal(s.collector.state().status, 'off')
  await s.time.advance(30 * MINUTE)
  assert.equal(s.log.sends.length, sent)
})

test('stop discards buffered rows: a later start sends only new rows', async () => {
  const s = await collectingWithBufferedRows()
  s.collector.stop('user')
  s.setReply(() => ({ result: 'ok', focus: { state: 'PENDING', updatedAt: null } }))
  s.collector.start(7)
  await s.minute([['chrome.exe', 60]])
  await s.time.advance(1000)
  const rows = s.log.sends.at(-1).body.rows
  assert.equal(rows.length, 1)
  assert.equal(rows[0].exeName, 'chrome.exe')
})

test('a send that finishes after stop does not restart anything', async () => {
  let release
  const s = setup({ reply: () => new Promise(resolve => { release = resolve }) })
  s.collector.start(7)
  await s.minute([['code.exe', 60]])
  await s.time.advance(1000)
  assert.equal(s.log.sends.length, 1)
  s.collector.stop('ended')
  release({ result: 'ok', focus: { state: 'FOCUS', updatedAt: '2026-11-26T03:01:00Z' } })
  await flush()
  assert.equal(s.collector.state().status, 'off')
  assert.equal(s.time.active(), 0)
  await s.time.advance(5 * MINUTE)
  assert.equal(s.log.sends.length, 1)
})

test('every stop reason ends collection and is reported in the state', async () => {
  const reasons = ['user', 'ended', 'removed', 'left', 'destroyed', 'render-process-gone', 'cookie',
    'quit', 'suspend', 'lock-screen']
  for (const reason of reasons) {
    const s = await collectingWithBufferedRows()
    s.collector.stop(reason)
    assert.equal(s.collector.state().status, 'off', reason)
    assert.equal(s.collector.state().reason, reason, reason)
    assert.equal(s.log.kills, 1, reason)
    assert.equal(s.time.active(), 0, reason)
  }
})

test('collection stops by itself after 3 hours', async () => {
  const s = setup()
  s.collector.start(7)
  await s.time.advance(3 * HOUR + 2 * MINUTE)
  assert.equal(s.collector.state().status, 'off')
  assert.equal(s.log.kills, 1)
  assert.equal(s.time.active(), 0)
  const sent = s.log.sends.length
  assert.ok(sent <= 181, `sent ${sent} times`)
  await s.time.advance(10 * MINUTE)
  assert.equal(s.log.sends.length, sent)
})

test('a stop answer from the server (401/403/404/410) ends collection', async () => {
  const s = setup({ reply: () => ({ result: 'stop' }) })
  s.collector.start(7)
  await s.minute([['code.exe', 60]])
  await s.time.advance(1000)
  assert.equal(s.collector.state().status, 'off')
  assert.equal(s.log.kills, 1)
  assert.equal(s.time.active(), 0)
  await s.time.advance(5 * MINUTE)
  assert.equal(s.log.sends.length, 1)
})

test('start with another session stops the current one and discards its rows', async () => {
  const s = await collectingWithBufferedRows()
  s.setReply(() => ({ result: 'ok', focus: { state: 'PENDING', updatedAt: null } }))
  s.collector.start(8)
  assert.equal(s.log.kills, 1)
  assert.equal(s.log.probeStarts, 2)
  assert.equal(s.collector.state().sessionId, 8)
  await s.minute([['chrome.exe', 60]])
  await s.time.advance(1000)
  const last = s.log.sends.at(-1)
  assert.equal(last.sessionId, 8)
  assert.equal(last.body.rows.length, 1)
})

test('onState reports collecting and off', () => {
  const { collector } = setup()
  const seen = []
  collector.onState(state => seen.push(state.status))
  collector.start(7)
  collector.stop('user')
  assert.deepEqual(seen.filter((s, i) => s !== seen[i - 1]), ['collecting', 'off'])
})

// ---- Round 1 review: one send at a time, abort on stop, failing idle query ----

/** Collector whose sends wait until released by hand; each call records its rows and abort signal. */
function manualSends() {
  const calls = []
  const send = (sessionId, body, options = {}) => new Promise(resolve => {
    calls.push({ sessionId, rows: JSON.parse(JSON.stringify(body.rows)), signal: options.signal, resolve })
  })
  return { calls, send }
}

test('while a send is running the next minute does not start a second send', async () => {
  const m = manualSends()
  const s = setup({ deps: { send: m.send } })
  s.collector.start(7)
  s.setIdle(1)
  await s.minute([['code.exe', 60]])
  s.setIdle(2)
  await s.minute([['chrome.exe', 60]])
  s.setIdle(3)
  await s.minute([['code.exe', 60]])
  assert.equal(m.calls.length, 1)
  assert.deepEqual(m.calls[0].rows.map(r => r.idleSec), [1])
})

test('after ok only the rows that were sent are removed; the next send carries the rest', async () => {
  const m = manualSends()
  const s = setup({ deps: { send: m.send } })
  s.collector.start(7)
  s.setIdle(1)
  await s.minute([['code.exe', 60]])
  s.setIdle(2)
  await s.minute([['chrome.exe', 60]])   // buffered while the first send runs
  m.calls[0].resolve({ result: 'ok', focus: { state: 'FOCUS', updatedAt: null } })
  await flush()
  s.setIdle(3)
  await s.minute([['code.exe', 60]])
  assert.equal(m.calls.length, 2)
  assert.deepEqual(m.calls[1].rows.map(r => r.idleSec), [2, 3])
})

test('every send gets an abort signal and stop aborts the running send', async () => {
  const m = manualSends()
  const s = setup({ deps: { send: m.send } })
  s.collector.start(7)
  await s.minute([['code.exe', 60]])
  assert.equal(m.calls.length, 1)
  assert.ok(m.calls[0].signal instanceof AbortSignal)
  assert.equal(m.calls[0].signal.aborted, false)
  s.collector.stop('user')
  assert.equal(m.calls[0].signal.aborted, true)
})

test('restart after stop: the old send is aborted and the new one is the only send in flight', async () => {
  const m = manualSends()
  const s = setup({ deps: { send: m.send } })
  s.collector.start(7)
  await s.minute([['code.exe', 60]])
  s.collector.stop('user')
  s.collector.start(7)
  await s.minute([['chrome.exe', 60]])
  assert.equal(m.calls.length, 2)
  const inFlight = m.calls.filter(c => !c.signal.aborted)
  assert.equal(inFlight.length, 1)
  assert.equal(inFlight[0].rows[0].exeName, 'chrome.exe')
})

test('a send that ends as retry (e.g. transport timeout) lets the next minute send again', async () => {
  const m = manualSends()
  const s = setup({ deps: { send: m.send } })
  s.collector.start(7)
  await s.minute([['code.exe', 60]])
  m.calls[0].resolve({ result: 'retry' })
  await flush()
  await s.minute([['chrome.exe', 60]])
  assert.equal(m.calls.length, 2)
  assert.equal(m.calls[1].rows.length, 2)
  assert.equal(s.collector.state().status, 'collecting')
})

test('a failing idle query counts as idle 0 and does not escape the timer', async () => {
  const s = setup({ deps: { idleSeconds: () => { throw new Error('powerMonitor gone') } } })
  s.collector.start(7)
  await s.minute([['code.exe', 60]])
  await s.time.advance(1000)
  assert.equal(s.log.sends.length, 1)
  assert.equal(s.log.sends[0].body.rows[0].idleSec, 0)
  assert.equal(s.collector.state().status, 'collecting')
})

test('the collecting state carries startedAt and each ok answer carries receivedAt (this PC clock)', async () => {
  const s = setup()
  s.collector.start(7)
  const startedAt = s.collector.state().startedAt
  assert.equal(startedAt, s.time.now())
  await s.minute([['code.exe', 60]])
  await s.time.advance(1000)
  const focus = s.collector.state().focus
  assert.equal(focus.state, 'FOCUS')
  assert.ok(Number.isFinite(focus.receivedAt))
  assert.ok(focus.receivedAt >= startedAt + MINUTE)
})
