import { test } from 'node:test'
import assert from 'node:assert/strict'
import { EventEmitter } from 'node:events'
import { createLiveRoomSession } from '../src/utils/liveRoomSession.js'

const events = Object.fromEntries(['TrackSubscribed', 'TrackUnsubscribed', 'SignalReconnecting', 'Reconnecting', 'Reconnected', 'Disconnected'].map(k => [k, k]))
const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r }); return { promise, resolve } }
function setup(overrides = {}) {
  const log = { state: [], video: [], room: [], errors: [], calls: [] }
  const room = new EventEmitter()
  room.remoteParticipants = new Map()
  room.localParticipant = {
    trackPublications: new Map(),
    async setScreenShareEnabled(enabled) { log.calls.push(['share', enabled]) },
  }
  room.connect = async (...args) => { log.calls.push(['connect', ...args]) }
  room.disconnect = async () => { log.calls.push(['disconnect']); room.emit(events.Disconnected) }
  const make = () => createLiveRoomSession({
    room, events, getInfo: async () => { log.calls.push(['approval']); return { url: '/livekit/approved', token: 'session-bound' } },
    resolveUrl: url => 'ws://local' + url,
    onRoom: r => log.room.push(r), onVideo: v => log.video.push(v),
    onState: s => log.state.push(s), onError: e => log.errors.push(e), ...overrides,
  })
  return { room, log, make }
}

test('new room connects only after Spring approval and through the proxy', async () => {
  const { room, log, make } = setup()
  const session = make(); await session.done
  assert.deepEqual(log.calls, [['approval'], ['connect', 'ws://local/livekit/approved', 'session-bound']])
  assert.equal(log.room.at(-1), room)
  assert.deepEqual(log.state, ['connecting', 'connected'])
  await session.dispose()
})

test('disposing pending approval prevents late join and removes all listeners', async () => {
  const approval = deferred()
  const { room, log, make } = setup({ getInfo: () => approval.promise })
  const session = make(); await session.dispose()
  approval.resolve({ url: '/late', token: 'unused' }); await session.done
  assert.equal(log.calls.some(c => c[0] === 'connect'), false)
  assert.equal(room.eventNames().length, 0)
  assert.equal(log.errors.length, 0)
})

test('full reconnect stops capture immediately, clears video and restores subscriptions', async () => {
  const { room, log, make } = setup()
  let stopped = 0
  room.localParticipant.trackPublications.set('screen', { track: { stop() { stopped++ } } })
  const session = make(); await session.done
  const old = { kind: 'video' }; room.emit(events.TrackSubscribed, old)
  room.emit(events.Reconnecting)
  assert.equal(stopped, 1)
  assert.equal(log.room.at(-1), null)
  assert.equal(log.video.at(-1), null)
  assert.equal(log.state.at(-1), 'reconnecting')
  const fresh = { kind: 'video' }
  room.remoteParticipants.set('teacher', { trackPublications: new Map([['screen', { track: fresh }]]) })
  room.emit(events.Reconnected)
  assert.equal(log.room.at(-1), room)
  assert.equal(log.video.at(-1), fresh)
  assert.equal(log.calls.some(c => c[0] === 'share' && c[1] === true), false)
  await session.dispose()
})

test('signalling-only reconnect keeps active media and exposes status', async () => {
  const { room, log, make } = setup()
  const session = make(); await session.done
  room.emit(events.SignalReconnecting)
  assert.equal(log.state.at(-1), 'signal-reconnecting')
  assert.equal(log.room.at(-1), room)
  assert.equal(log.calls.some(c => c[0] === 'share'), false)
  room.emit(events.Reconnected)
  assert.equal(log.state.at(-1), 'connected')
  await session.dispose()
})

test('unsubscribe of an old video does not blank its replacement', async () => {
  const { room, log, make } = setup()
  const session = make(); await session.done
  const old = { kind: 'video' }, current = { kind: 'video' }
  room.emit(events.TrackSubscribed, old); room.emit(events.TrackSubscribed, current)
  room.emit(events.TrackUnsubscribed, old)
  assert.equal(log.video.at(-1), current)
  room.emit(events.TrackUnsubscribed, current)
  assert.equal(log.video.at(-1), null)
  await session.dispose()
})

test('terminal disconnect never rejoins automatically; explicit new attempt obtains approval', async () => {
  const { room, log, make } = setup()
  const first = make(); await first.done
  room.emit(events.Disconnected)
  assert.equal(log.state.at(-1), 'disconnected')
  room.emit(events.Reconnected)
  room.emit(events.TrackSubscribed, { kind: 'video' })
  assert.equal(log.room.at(-1), null)
  assert.equal(log.video.at(-1), null)
  assert.equal(log.calls.filter(c => c[0] === 'approval').length, 1)
  await first.dispose()
  const retry = make(); await retry.done
  assert.equal(log.calls.filter(c => c[0] === 'approval').length, 2)
  await retry.dispose()
})

test('denied/ended session approval does not open a LiveKit connection', async () => {
  for (const status of [401, 403, 404, 409, 410]) {
    const error = Object.assign(new Error('denied'), { status })
    const { log, make } = setup({ getInfo: async () => { throw error } })
    const session = make(); await session.done
    assert.equal(log.calls.some(c => c[0] === 'connect'), false)
    assert.equal(log.errors[0], error)
    assert.equal(log.state.at(-1), 'disconnected')
    await session.dispose()
  }
})
