import test from 'node:test'
import assert from 'node:assert/strict'
import { EventEmitter } from 'node:events'
import { watchHelpPeers } from '../src/utils/helpPeerPresence.js'

const events = { ParticipantConnected: 'joined', ParticipantDisconnected: 'left' }
function setup(identities = []) {
  const room = new EventEmitter()
  room.remoteParticipants = new Map(identities.map(identity => [identity, { identity }]))
  const states = []
  let stopped = 0
  const watcher = watchHelpPeers({ room, events, onChange: state => states.push(state), onLastPeerLeft: () => stopped++ })
  return { room, states, watcher, stopped: () => stopped }
}

test('initial empty room waits without treating it as a departure', () => {
  const s = setup()
  assert.equal(s.watcher.hasPeer(), false)
  assert.deepEqual(s.states, [false])
  assert.equal(s.stopped(), 0)
})
test('already connected teacher is recognized and last departure stops capture', () => {
  const s = setup(['teacher'])
  assert.equal(s.watcher.hasPeer(), true)
  s.room.emit('left', { identity: 'teacher' })
  assert.equal(s.stopped(), 1)
  assert.equal(s.watcher.hasPeer(), false)
  assert.deepEqual(s.states, [true, false])
})
test('another teacher remains until the last teacher leaves', () => {
  const s = setup(['teacher-1', 'teacher-2'])
  s.room.emit('left', { identity: 'teacher-1' })
  assert.equal(s.stopped(), 0)
  assert.equal(s.watcher.hasPeer(), true)
  s.room.emit('left', { identity: 'teacher-2' })
  assert.equal(s.stopped(), 1)
})
test('duplicate and unknown departures cannot stop a new session', () => {
  const s = setup(['teacher'])
  s.room.emit('left', { identity: 'unknown' })
  s.room.emit('left', { identity: 'teacher' })
  s.room.emit('left', { identity: 'teacher' })
  assert.equal(s.stopped(), 1)
})
test('teacher can return but presence does not invoke a sharing start', () => {
  const s = setup(['teacher'])
  s.room.emit('left', { identity: 'teacher' })
  s.room.emit('joined', { identity: 'teacher' })
  assert.equal(s.watcher.hasPeer(), true)
  assert.deepEqual(s.states, [true, false, true])
  assert.equal(s.stopped(), 1)
})
test('disposing removes handlers and ignores previously queued events', () => {
  const s = setup(['teacher'])
  const late = s.room.listeners('left')[0]
  s.watcher.dispose()
  late({ identity: 'teacher' })
  s.room.emit('joined', { identity: 'another' })
  assert.equal(s.room.listenerCount('left'), 0)
  assert.equal(s.room.listenerCount('joined'), 0)
  assert.equal(s.watcher.hasPeer(), false)
  assert.equal(s.stopped(), 0)
  assert.deepEqual(s.states, [true])
})
