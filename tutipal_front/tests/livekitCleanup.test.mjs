import test from 'node:test'
import assert from 'node:assert/strict'
import { EventEmitter } from 'node:events'
import { spawn } from 'node:child_process'
import { mkdtemp, access, rm } from 'node:fs/promises'
import path from 'node:path'
import os from 'node:os'
import { cleanupApplications } from './livekit/cleanup.js'

const pending = () => new Promise(() => {})
const status = reports => reports.flatMap(r => r.phases).map(p => [p.stage, p.status])
async function fixture(options = {}) {
  const profile = await mkdtemp(path.join(os.tmpdir(), 'tutipal-e2e-profile-'))
  const child = new EventEmitter()
  Object.assign(child, { exitCode: null, signalCode: null, kill() {
    child.signalCode = 'SIGKILL'; child.emit('exit'); return true
  } })
  const application = { process: () => child, close: async () => { child.exitCode = 0 } }
  const page = { isClosed: () => false, evaluate: async () => {} }
  Object.assign(page, options.page)
  Object.assign(application, options.application)
  return { profile, child, application, page }
}

test('successful cleanup removes only its own temporary profile and reports all stages', async () => {
  const item = await fixture()
  const records = []
  const report = await cleanupApplications([item], { onPhase: r => records.push(r) })
  assert.deepEqual(status(report), [['renderer-disconnect', 'passed'], ['application-close', 'passed'], ['profile-remove', 'passed']])
  await assert.rejects(access(item.profile))
  assert.equal(records.length, 6)
  assert.ok(records.every(r => r.startedAt && !JSON.stringify(r).includes(item.profile)))
})

test('hung renderer fails its stage but both owned apps still close and profiles are removed', async () => {
  const stuck = await fixture({ page: { evaluate: pending } })
  const other = await fixture()
  const report = await cleanupApplications([stuck, other], { stepTimeoutMs: 30 })
  assert.equal(report[0].phases[0].status, 'timeout')
  assert.equal(stuck.child.exitCode, 0)
  assert.equal(other.child.exitCode, 0)
  await assert.rejects(access(stuck.profile))
  await assert.rejects(access(other.profile))
})

test('app close failure stays failed even after its owned process is stopped', async () => {
  const item = await fixture({ application: { close: async () => { throw Error('https://secret.invalid/token') } } })
  const report = await cleanupApplications([item])
  assert.deepEqual(status(report), [['renderer-disconnect', 'passed'], ['application-close', 'failed'], ['owned-process-exit', 'passed'], ['profile-remove', 'passed']])
  assert.equal(item.child.signalCode, 'SIGKILL')
  assert.ok(!JSON.stringify(report).includes('secret'))
})

test('a child that cannot exit retains its profile and removes its exit listener', async () => {
  const item = await fixture({ application: { close: pending } })
  item.child.kill = () => true
  try {
    const report = await cleanupApplications([item], { stepTimeoutMs: 30 })
    assert.deepEqual(status(report), [['renderer-disconnect', 'passed'], ['application-close', 'timeout'], ['owned-process-exit', 'timeout'], ['profile-remove', 'failed']])
    await access(item.profile)
    assert.equal(item.child.listenerCount('exit'), 0)
  } finally { await rm(item.profile, { recursive: true }) }
})

test('a path outside the test profile naming contract is retained', async () => {
  const profile = await mkdtemp(path.join(os.tmpdir(), 'tutipal-protected-'))
  try {
    const report = await cleanupApplications([{ profile }])
    assert.deepEqual(status(report), [['profile-remove', 'failed']])
    await access(profile)
  } finally { await rm(profile, { recursive: true }) }
})

test('an actual child stuck after close is stopped before its temporary profile is removed', async () => {
  const item = await fixture()
  const child = spawn(process.execPath, ['-e', 'setInterval(() => {}, 1000)'], { windowsHide: true, stdio: 'ignore' })
  await new Promise((resolve, reject) => { child.once('spawn', resolve); child.once('error', reject) })
  item.application = { process: () => child, close: pending }
  try {
    const report = await cleanupApplications([item], { stepTimeoutMs: 500 })
    assert.equal(report[0].phases.find(p => p.stage === 'application-close').status, 'timeout')
    assert.equal(report[0].phases.find(p => p.stage === 'owned-process-exit').status, 'passed')
    assert.ok(child.exitCode !== null || child.signalCode !== null)
    await assert.rejects(access(item.profile))
  } finally {
    if (child.exitCode === null && child.signalCode === null) child.kill('SIGKILL')
  }
})
