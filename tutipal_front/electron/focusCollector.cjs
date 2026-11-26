// PT-90 focus collection state machine (pure: probe, idle time, transport, clock and timers are injected).
// Nothing runs before start(sessionId): no probe, no idle query, no timer, no send. Every stop kills the probe,
// clears the timer and discards buffered rows. A row per minute: { exeName, idleSec, switchCount }.
const MINUTE_MS = 60_000
const DEFAULT_MAX_DURATION_MS = 3 * 60 * MINUTE_MS
const MAX_SWITCHES = 120
const MAX_IDLE_SEC = 60
// The probe prints once a second; anything far above that is ignored rather than kept in memory.
const MAX_SAMPLES_PER_MINUTE = 600

const clamp = (value, low, high) => Math.max(low, Math.min(high, value))
const validSessionId = id => Number.isSafeInteger(id) && id > 0

function summarize(samples, switches) {
  const counts = new Map()
  // null (unreadable / in transition) never wins the minute, same as devdesk system/sampler.py
  for (const exe of samples) if (exe !== null) counts.set(exe, (counts.get(exe) || 0) + 1)
  let exeName = null
  let best = 0
  for (const [exe, count] of counts) {
    if (count > best) { best = count; exeName = exe }
  }
  return { exeName, switchCount: clamp(switches, 0, MAX_SWITCHES) }
}

function createFocusCollector({
  startProbe, idleSeconds, send, now, setInterval, clearInterval, platform,
  maxDurationMs = DEFAULT_MAX_DURATION_MS, bufferMax = 15,
}) {
  let state = { status: 'off', sessionId: null }
  const listeners = new Set()
  let probe = null
  let timer = null
  let startedAt = 0
  let generation = 0
  let samples = []
  let switches = 0
  let lastSample
  let buffer = []
  let sending = false
  let inFlight = null   // AbortController of the running send; stop() aborts it

  const publish = next => {
    state = next
    const copy = { ...state }
    for (const listener of listeners) {
      try { listener({ ...copy }) } catch { /* a listener error must not stop collection */ }
    }
  }

  const onSample = (run, exe) => {
    if (run !== generation || samples.length >= MAX_SAMPLES_PER_MINUTE) return
    samples.push(exe)
    // devdesk sampler.py: null is not a switch candidate; lastSample keeps the last non-null exe, also across the
    // minute edge (A, null, B = 1 switch; A, null, A = 0)
    if (exe === null) return
    if (lastSample !== undefined && exe !== lastSample) switches++
    lastSample = exe
  }

  function tick(run) {
    if (run !== generation) return
    if (now() - startedAt >= maxDurationMs) { stop('max-duration'); return }
    let idle = 0
    try { idle = Number(idleSeconds()) } catch { idle = 0 }   // a failing idle query must not escape the timer
    const row = { ...summarize(samples, switches), idleSec: Number.isFinite(idle) ? clamp(Math.round(idle), 0, MAX_IDLE_SEC) : 0 }
    samples = []
    switches = 0
    buffer.push({ exeName: row.exeName, idleSec: row.idleSec, switchCount: row.switchCount })
    while (buffer.length > bufferMax) buffer.shift()
    if (sending) return   // one request at a time; the rows go with the next minute
    const batch = buffer.slice()
    const sessionId = state.sessionId
    sending = true
    const controller = new AbortController()
    inFlight = controller
    Promise.resolve()
      .then(() => send(sessionId, { rows: batch }, { signal: controller.signal }))
      .catch(() => ({ result: 'retry' }))
      .then(answer => {
        if (run !== generation) return
        if (inFlight === controller) inFlight = null
        sending = false
        const result = answer && answer.result
        if (result === 'ok') {
          const sent = new Set(batch)
          buffer = buffer.filter(r => !sent.has(r))
          // receivedAt (this PC's clock): the screen stops trusting the state once it is older than stale-after
          const focus = answer.focus && typeof answer.focus === 'object' ? { ...answer.focus, receivedAt: now() } : undefined
          publish({ ...state, focus })
        } else if (result === 'stop') {
          stop('server')
        }
      })
  }

  function start(sessionId) {
    if (!validSessionId(sessionId)) return
    if (state.status === 'collecting') {
      if (state.sessionId === sessionId) return
      stop('switch')
    }
    if (platform !== 'win32') { publish({ status: 'unsupported', sessionId: null }); return }
    const run = ++generation
    const started = startProbe(exe => onSample(run, exe))
    if (!started) { publish({ status: 'unsupported', sessionId: null }); return }
    probe = started
    startedAt = now()
    samples = []
    switches = 0
    lastSample = undefined
    buffer = []
    sending = false
    timer = setInterval(() => tick(run), MINUTE_MS)
    publish({ status: 'collecting', sessionId, startedAt })
  }

  function stop(reason) {
    if (state.status !== 'collecting') return
    generation++
    if (timer !== null) clearInterval(timer)
    timer = null
    const current = probe
    probe = null
    samples = []
    buffer = []
    switches = 0
    lastSample = undefined
    sending = false
    const request = inFlight
    inFlight = null
    try { request?.abort() } catch { /* nothing to abort */ }
    try { current?.kill() } catch { /* already gone */ }
    publish({ status: 'off', sessionId: null, reason: typeof reason === 'string' ? reason : 'stopped' })
  }

  return {
    start,
    stop,
    state: () => ({ ...state }),
    onState(listener) {
      listeners.add(listener)
      return () => listeners.delete(listener)
    },
  }
}

module.exports = { createFocusCollector }
