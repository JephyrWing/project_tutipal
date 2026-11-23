import test from 'node:test'
import assert from 'node:assert/strict'
import { startRoomConnection } from '../src/utils/roomConnection.js'

function deferred() {
  let resolve, reject
  const promise = new Promise((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}
const info = { url: '/livekit/test', token: 'test-only' }
function fixture(getInfo, connect = async () => {}) {
  const calls = { connects: [], connected: [], errors: [], disconnects: 0 }
  const room = {
    connect(...args) { calls.connects.push(args); return connect() },
    async disconnect() { calls.disconnects++ },
  }
  const attempt = startRoomConnection({ room, getInfo, resolveUrl: url => `ws://example.invalid${url}`,
    onConnected: value => calls.connected.push(value), onError: error => calls.errors.push(error) })
  return { calls, room, attempt }
}

test('StrictMode cleanup before token response prevents an obsolete duplicate join', async () => {
  const firstToken = deferred()
  const old = fixture(() => firstToken.promise)
  await old.attempt.cancel()
  const current = fixture(async () => info)
  await current.attempt.done
  firstToken.resolve(info)
  await old.attempt.done
  assert.equal(old.calls.connects.length, 0)
  assert.equal(old.calls.connected.length, 0)
  assert.equal(current.calls.connects.length, 1)
  assert.deepEqual(current.calls.connected, [current.room])
})

test('cancellation during connect suppresses its later failure', async () => {
  const connection = deferred()
  const pending = fixture(async () => info, () => connection.promise)
  await Promise.resolve()
  assert.equal(pending.calls.connects.length, 1)
  await pending.attempt.cancel()
  connection.reject(new Error('room connection has been cancelled'))
  await pending.attempt.done
  assert.deepEqual(pending.calls.errors, [])
  assert.deepEqual(pending.calls.connected, [])
})

test('late successful connection after cancellation cannot publish connected state', async () => {
  const connection = deferred()
  const pending = fixture(async () => info, () => connection.promise)
  await Promise.resolve()
  await pending.attempt.cancel()
  connection.resolve()
  await pending.attempt.done
  assert.equal(pending.calls.disconnects, 1)
  assert.deepEqual(pending.calls.connected, [])
})

test('active attempt reports a genuine connection failure', async () => {
  const error = new Error('network unavailable')
  const pending = fixture(async () => info, async () => { throw error })
  await pending.attempt.done
  assert.deepEqual(pending.calls.errors, [error])
  assert.deepEqual(pending.calls.connected, [])
})

test('disposed token failure is ignored and cancellation is idempotent', async () => {
  const token = deferred()
  const pending = fixture(() => token.promise)
  await pending.attempt.cancel()
  await pending.attempt.cancel()
  token.reject(new Error('session expired'))
  await pending.attempt.done
  assert.equal(pending.calls.disconnects, 1)
  assert.deepEqual(pending.calls.errors, [])
})

test('active successful connection receives the resolved URL and token', async () => {
  const pending = fixture(async () => info)
  await pending.attempt.done
  assert.deepEqual(pending.calls.connects, [['ws://example.invalid/livekit/test', 'test-only']])
  assert.deepEqual(pending.calls.connected, [pending.room])
})
