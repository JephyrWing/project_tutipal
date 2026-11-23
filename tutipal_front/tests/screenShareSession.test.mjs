import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createScreenShareSession } from '../src/utils/screenShareSession.js'

const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r }); return { promise, resolve } }
function setup(overrides = {}) {
  const log = { enabled: [], sharing: [], tracks: [], errors: [], stopped: 0 }
  const media = new EventTarget(); media.readyState = 'live'
  const track = { mediaStreamTrack: media, stop() { log.stopped++; media.readyState = 'ended' } }
  const participant = { async setScreenShareEnabled(enabled, options) {
    log.enabled.push([enabled, options]); return enabled ? { track } : undefined
  } }
  const control = createScreenShareSession({ participant, prepare: async () => true,
    onSharing: s => log.sharing.push(s), onTrack: t => log.tracks.push(t),
    onError: e => log.errors.push(e), ...overrides })
  return { log, media, track, control }
}

test('capture begins only after preparation and never requests audio', async () => {
  const { log, track, control } = setup()
  assert.equal(await control.start(), true)
  assert.deepEqual(log.enabled[0], [true, { audio: false }])
  assert.equal(log.tracks.at(-1), track)
  assert.equal(log.sharing.at(-1), true)
  await control.stop()
  assert.equal(log.sharing.at(-1), false)
  assert.equal(log.stopped, 1)
})

test('student capture requires fresh explicit consent for each new controller', async () => {
  for (let login = 0; login < 2; login++) {
    const { control, log } = setup({ requiresConsent: true })
    assert.equal(await control.start(), false)
    assert.equal(await control.start(false), false)
    assert.equal(log.enabled.length, 0)
    assert.equal(await control.start(true), true)
    await control.dispose()
  }
})

test('closed selection or denied preparation never invokes capture', async () => {
  for (const prepare of [async () => false, async () => { throw new Error('closed window') }]) {
    const { control, log } = setup({ prepare })
    assert.equal(await control.start(), false)
    assert.equal(log.enabled.length, 0)
  }
})

test('stop while source refresh is pending cancels capture before it starts', async () => {
  const selection = deferred()
  const { control, log } = setup({ prepare: () => selection.promise })
  const start = control.start(); await Promise.resolve(); await control.stop()
  selection.resolve(true)
  assert.equal(await start, false)
  assert.equal(log.enabled.length, 0)
})

test('late publication after unmount is stopped without updating the removed view', async () => {
  const publication = deferred()
  const calls = []
  const { control, log, track } = setup({ participant: {
    async setScreenShareEnabled(on) { calls.push(on); return on ? publication.promise : undefined },
  } })
  const start = control.start()
  await new Promise(resolve => setImmediate(resolve))
  const disposed = control.dispose()
  const before = { sharing: log.sharing.length, tracks: log.tracks.length }
  publication.resolve({ track }); assert.equal(await start, false)
  await disposed
  assert.equal(log.stopped, 1)
  assert.equal(calls.at(-1), false)
  assert.equal(log.sharing.length, before.sharing)
  assert.equal(log.tracks.length, before.tracks)
})

test('stop during publication prevents overlapping restart and disables the late result', async () => {
  const publication = deferred(), calls = []
  const { control, track, log } = setup({ participant: {
    async setScreenShareEnabled(on) { calls.push(on); return on ? publication.promise : undefined },
  } })
  const start = control.start(); await new Promise(resolve => setImmediate(resolve))
  const stopped = control.stop()
  assert.equal(await control.start(), false)
  publication.resolve({ track }); assert.equal(await start, false)
  await stopped
  assert.deepEqual(calls, [true, false])
  assert.equal(log.sharing.includes(true), false)
  assert.equal(log.stopped, 1)
})

test('operating-system capture end clears sharing and unpublishes', async () => {
  const { control, log, media } = setup()
  await control.start()
  media.dispatchEvent(new Event('ended'))
  assert.equal(log.sharing.at(-1), false)
  assert.equal(log.tracks.at(-1), null)
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(log.enabled.at(-1)[0], false)
})

test('missing or ended tracks cannot produce a false sharing badge', async () => {
  for (const result of [undefined, { track: { mediaStreamTrack: { readyState: 'ended' }, stop() {} } }]) {
    const { control, log } = setup({ participant: { async setScreenShareEnabled(on) { return on ? result : undefined } } })
    assert.equal(await control.start(), false)
    assert.equal(log.sharing.includes(true), false)
    assert.equal(log.errors.length, 1)
  }
})

test('capture cancellation and disable failure are reported without unhandled rejection', async () => {
  const { control, log } = setup({ participant: { async setScreenShareEnabled(on) {
    throw new Error(on ? 'permission denied' : 'signalling unavailable')
  } } })
  assert.equal(await control.start(), false)
  assert.equal(log.sharing.includes(true), false)
  assert.equal(log.errors.length, 2)
  await control.dispose()
})

test('a replacement controller cannot publish before the previous late capture is cleaned up', async () => {
  const pending = deferred(), calls = []
  const oldTrack = { stop() { calls.push('old-stop') } }
  const freshTrack = { stop() { calls.push('fresh-stop') } }
  let enables = 0
  const participant = { async setScreenShareEnabled(on) {
    calls.push(on)
    return on ? (++enables === 1 ? pending.promise : { track: freshTrack }) : undefined
  } }
  const noop = () => {}
  const options = { participant, prepare: async () => true, onTrack: noop, onSharing: noop, onError: e => { throw e } }
  const old = createScreenShareSession(options)
  const first = old.start(); await new Promise(resolve => setImmediate(resolve))
  const disposed = old.dispose()
  const fresh = createScreenShareSession(options)
  const second = fresh.start(); await new Promise(resolve => setImmediate(resolve))
  assert.deepEqual(calls, [true])
  pending.resolve({ track: oldTrack })
  await first; await disposed; assert.equal(await second, true)
  assert.deepEqual(calls, [true, 'old-stop', false, true])
  await fresh.dispose()
})
