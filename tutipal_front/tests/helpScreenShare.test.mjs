import test from 'node:test'
import assert from 'node:assert/strict'
import { createHelpScreenShare } from '../src/utils/helpScreenShare.js'

function fixture({ canShare = true, enable = async () => {} } = {}) {
  const calls = { enabled: [], source: 0, sharing: [], errors: [] }
  const control = createHelpScreenShare({
    participant: { async setScreenShareEnabled(value) { calls.enabled.push(value); if (value) await enable() } },
    canShare,
    chooseSource: () => calls.source++,
    onSharing: value => calls.sharing.push(value),
    onError: error => calls.errors.push(error),
  })
  return { calls, control }
}

test('mounting a previously permitted help session never starts capture', async () => {
  const { calls, control } = fixture()
  assert.equal(await control.start(false), false)
  assert.equal(await control.start(), false)
  assert.deepEqual(calls.enabled, [])
  assert.equal(calls.source, 0)
})

test('client consent cannot override a help request without sharing permission', async () => {
  const { calls, control } = fixture({ canShare: false })
  assert.equal(await control.start(true), false)
  assert.deepEqual(calls.enabled, [])
})

test('a new login controller does not inherit the previous capture consent', async () => {
  const previous = fixture()
  assert.equal(await previous.control.start(true), true)
  await previous.control.dispose()
  const current = fixture()
  assert.equal(await current.control.start(false), false)
  assert.deepEqual(current.calls.enabled, [])
  assert.deepEqual(previous.calls.enabled, [true, false])
})

test('explicit sharing can be stopped while keeping the help connection', async () => {
  const { calls, control } = fixture()
  assert.equal(await control.start(true), true)
  await control.stop()
  assert.deepEqual(calls.enabled, [true, false])
  assert.deepEqual(calls.sharing, [true, false])
})

test('logout while capture is pending also stops a late successful capture', async () => {
  let resolve
  const pending = new Promise(yes => { resolve = yes })
  const { calls, control } = fixture({ enable: () => pending })
  const start = control.start(true)
  await control.dispose()
  resolve()
  assert.equal(await start, false)
  assert.equal(calls.enabled.at(-1), false)
  assert.deepEqual(calls.sharing, [])
  assert.equal(await control.start(true), false)
})

test('a failed screen picker is reported without claiming that sharing started', async () => {
  const error = new Error('capture cancelled')
  const { calls, control } = fixture({ enable: async () => { throw error } })
  assert.equal(await control.start(true), false)
  assert.deepEqual(calls.errors, [error])
  assert.deepEqual(calls.sharing, [])
})
