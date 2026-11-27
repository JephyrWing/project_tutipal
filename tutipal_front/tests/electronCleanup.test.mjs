import { test } from 'node:test'
import assert from 'node:assert/strict'
import { EventEmitter } from 'node:events'
import { mkdtemp, access, rm } from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import { cleanupElectronItems, releaseFixtureMedia } from './helpers/electronCleanup.mjs'

const pending = () => new Promise(() => {})
async function item(overrides = {}) {
  const profile = await mkdtemp(path.join(os.tmpdir(), 'tutipal-e2e-profile-'))
  const child = new EventEmitter()
  Object.assign(child, { exitCode: null, signalCode: null, kill() {
    this.signalCode = 'SIGTERM'; this.emit('exit', null, 'SIGTERM'); return true
  } })
  return { profile, page: { isClosed: () => false, evaluate: async () => ({ state: 'disconnected' }),
    url: () => 'http://127.0.0.1:12345/sessions/1', goto: async () => {} },
    application: { process: () => child, close: async () => { child.exitCode = 0; child.emit('exit', 0) } }, ...overrides }
}
test('already-revoked media stops tracks and removes video without reacquiring the SDK disconnect lock', async () => {
  let stopped = 0, removed = 0, disconnected = 0
  globalThis.pt65Media = { stream: { getTracks: () => [{ stop: () => stopped++ }] }, videos: [{ remove: () => removed++ }],
    room: { state: 'disconnected', disconnect: () => { disconnected++; return pending() } } }
  try {
    assert.deepEqual(await releaseFixtureMedia(), { before: 'disconnected', state: 'disconnected', disconnectCalled: false })
    assert.equal(stopped, 1); assert.equal(removed, 1); assert.equal(disconnected, 0)
  } finally { delete globalThis.pt65Media }
})
test('connected media disconnects after local tracks and videos are released', async () => {
  const calls = []
  globalThis.pt65Media = { stream: { getTracks: () => [{ stop: () => calls.push('stop') }] }, videos: [{ remove: () => calls.push('remove') }],
    room: { state: 'connected', disconnect: async function () { calls.push('disconnect'); this.state = 'disconnected' } } }
  try { await releaseFixtureMedia(); assert.deepEqual(calls, ['stop', 'remove', 'disconnect']) }
  finally { delete globalThis.pt65Media }
})
test('hung renderer cleanup fails explicitly but both Electron instances and profiles are still cleaned', async () => {
  const a = await item(), b = await item(); a.page.evaluate = pending
  await assert.rejects(cleanupElectronItems([a, b], { timeoutMs: 20 }), error => {
    assert.equal(error.results.filter(r => r.outcome === 'failed').length, 1)
    assert.equal(error.results.find(r => r.outcome === 'failed').stage, 'renderer-media')
    assert.equal(error.results.filter(r => r.stage === 'profile-remove' && r.outcome === 'passed').length, 2)
    return true
  })
  await assert.rejects(access(a.profile)); await assert.rejects(access(b.profile))
})
test('hung app close stops only its owned child, removes its exited profile, and retains the failure', async () => {
  const a = await item(); a.application.close = pending
  await assert.rejects(cleanupElectronItems([a], { timeoutMs: 20 }), error => {
    assert.equal(error.results.find(r => r.stage === 'electron-close').outcome, 'failed')
    assert.equal(error.results.find(r => r.stage === 'owned-process-exit').outcome, 'passed')
    return true
  })
  assert.equal(a.application.process().signalCode, 'SIGTERM'); await assert.rejects(access(a.profile))
})
test('a resolved close with a still-live process is detected before removing the profile', async () => {
  const a = await item(); a.application.close = async () => {}
  await assert.rejects(cleanupElectronItems([a], { timeoutMs: 20 }), error => {
    assert.equal(error.results.find(r => r.stage === 'electron-exit').outcome, 'failed')
    assert.equal(error.results.find(r => r.stage === 'owned-process-exit').outcome, 'passed')
    return true
  })
  await assert.rejects(access(a.profile))
})
test('a publisher document is reset before graceful Electron close', async () => {
  const a = await item(), order = []
  a.page.evaluate = async () => { order.push('media') }
  a.page.goto = async (url, options) => { order.push('reset'); assert.equal(url, 'http://127.0.0.1:12345/login'); assert.equal(options.waitUntil, 'domcontentloaded') }
  const close = a.application.close; a.application.close = async () => { order.push('close'); await close() }
  await cleanupElectronItems([a]); assert.deepEqual(order, ['media', 'reset', 'close'])
})
test('a child ignoring SIGTERM is killed by its owned handle, with both timeout failures retained', async () => {
  const a = await item(); a.application.close = pending
  const child = a.application.process(), signals = []
  child.kill = signal => { signals.push(signal ?? 'SIGTERM'); if (signal === 'SIGKILL') { child.signalCode = signal; child.emit('exit', null, signal) } return true }
  await assert.rejects(cleanupElectronItems([a], { timeoutMs: 20 }), error => {
    assert.equal(error.results.filter(r => r.outcome === 'failed').length, 2)
    assert.equal(error.results.find(r => r.stage === 'owned-process-kill').outcome, 'passed'); return true
  })
  assert.deepEqual(signals, ['SIGTERM', 'SIGKILL']); await assert.rejects(access(a.profile))
})
test('a profile outside the fixture prefix is preserved and reported as a cleanup failure', async () => {
  const profile = await mkdtemp(path.join(os.tmpdir(), 'pt45-preserved-'))
  const a = await item(); const ownProfile = a.profile; a.profile = profile
  try {
    await assert.rejects(cleanupElectronItems([a]), error => error.results.some(r => r.stage === 'profile-remove' && r.outcome === 'failed'))
    await access(profile)
  } finally {
    assert.equal(path.dirname(profile), os.tmpdir()); assert.ok(path.basename(profile).startsWith('pt45-preserved-'))
    await rm(profile, { recursive: true }); await rm(ownProfile, { recursive: true })
  }
})
