import { realpath, rm } from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'

export async function withDeadline(action, ms, stage) {
  let timer
  try {
    return await Promise.race([Promise.resolve().then(action), new Promise((_, reject) => {
      timer = setTimeout(() => reject(new Error(`${stage} timed out after ${ms}ms`)), ms)
    })])
  } finally { clearTimeout(timer) }
}

// Self-contained because Playwright serializes this function into the renderer.
export async function releaseFixtureMedia() {
  const media = globalThis.pt65Media
  if (!media) return { state: 'no-fixture' }
  clearInterval(media.paint)
  media.stream?.getTracks().forEach(track => track.stop())
  media.videos.forEach(video => video.remove())
  const before = media.room.state
  // Revocation was asserted before teardown. Calling disconnect again acquires
  // the SDK disconnect mutex even when it is already disconnected.
  if (before !== 'disconnected') await media.room.disconnect()
  return { before, state: media.room.state, disconnectCalled: before !== 'disconnected' }
}

export async function cleanupElectronItems(items, { timeoutMs = 5000, onStage = () => {} } = {}) {
  const results = [], errors = []
  async function stage(index, name, action) {
    const start = Date.now()
    try {
      const detail = await withDeadline(action, timeoutMs, name)
      const result = { index, stage: name, outcome: 'passed', ms: Date.now() - start, detail }
      results.push(result); onStage(result); return true
    } catch (error) {
      const result = { index, stage: name, outcome: 'failed', ms: Date.now() - start, error: error.message }
      results.push(result); onStage(result); errors.push(new Error(`app ${index} ${name}: ${error.message}`)); return false
    }
  }
  for (const [index, item] of items.entries()) {
    try {
      if (item.page && !item.page.isClosed()) await stage(index, 'renderer-media', () => item.page.evaluate(releaseFixtureMedia))
    } finally {
      if (item.application) {
        const child = item.application.process()
        const closed = await stage(index, 'electron-close', () => item.application.close())
        if (closed && child.exitCode === null && child.signalCode === null) {
          await stage(index, 'electron-exit', () => new Promise(resolve => child.once('exit', resolve)))
        }
        if (child.exitCode === null && child.signalCode === null) {
          // Only the process returned by this fixture's launch is eligible.
          // Recovery does not erase the failed close stage or make the test pass.
          await stage(index, 'owned-process-exit', () => new Promise((resolve, reject) => {
            child.once('exit', resolve)
            if (!child.kill()) reject(new Error('Could not stop owned Electron process'))
          }))
        }
        if (child.exitCode === null && child.signalCode === null) continue // never delete a live profile
      }
      await stage(index, 'profile-remove', async () => {
        const resolved = await realpath(item.profile)
        if (path.dirname(resolved) !== await realpath(os.tmpdir()) || !path.basename(resolved).startsWith('tutipal-e2e-profile-')) {
          throw new Error('Refusing to remove a non-fixture profile')
        }
        await rm(resolved, { recursive: true, force: true, maxRetries: 3, retryDelay: 200 })
      })
    }
  }
  if (errors.length) {
    const error = new AggregateError(errors, 'Electron fixture cleanup failed')
    error.results = results
    throw error
  }
  return results
}
