import { realpath, rm } from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'

export async function withDeadline(action, ms, stage) {
  let timer
  const controller = new AbortController()
  try {
    return await Promise.race([Promise.resolve().then(() => action(controller.signal)), new Promise((_, reject) => {
      timer = setTimeout(() => reject(Object.assign(new Error(`${stage} timed out after ${ms}ms`), { code: 'CLEANUP_TIMEOUT' })), ms)
    })])
  } finally { clearTimeout(timer); controller.abort() }
}

function waitForOwnedExit(child, signal, killSignal) {
  return new Promise((resolve, reject) => {
    const done = error => {
      child.removeListener('exit', onExit)
      signal.removeEventListener('abort', onAbort)
      if (error) reject(error); else resolve()
    }
    const onExit = () => done()
    const onAbort = () => done(new Error('Owned exit wait ended'))
    child.once('exit', onExit)
    signal.addEventListener('abort', onAbort, { once: true })
    try {
      if (child.exitCode !== null || child.signalCode !== null) done()
      else if (killSignal && !child.kill(killSignal) && child.exitCode === null && child.signalCode === null) {
        done(new Error('Could not stop owned Electron process'))
      }
    } catch (error) { done(error) }
  })
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
      const result = { index, stage: name, outcome: 'passed', ms: Date.now() - start,
        startedAt: new Date(start).toISOString(), finishedAt: new Date().toISOString(), detail }
      results.push(result); onStage(result); return true
    } catch (error) {
      // Playwright errors can contain request URLs, cookies or profile paths.
      const reason = error?.code === 'CLEANUP_TIMEOUT' ? 'timeout' : 'error'
      const result = { index, stage: name, outcome: 'failed', ms: Date.now() - start, error: reason,
        startedAt: new Date(start).toISOString(), finishedAt: new Date().toISOString() }
      results.push(result); onStage(result); errors.push(new Error(`app ${index} ${name}: ${reason}`)); return false
    }
  }
  for (const [index, item] of items.entries()) {
    try {
      if (item.page && !item.page.isClosed()) {
        await stage(index, 'renderer-media', () => item.page.evaluate(releaseFixtureMedia))
        // Logout cases already replace the publisher document; help-end cases
        // retain it. Release its WebRTC/canvas context before graceful shutdown.
        await stage(index, 'renderer-reset', async () => {
          const url = new URL(item.page.url())
          if (url.protocol !== 'http:' || url.hostname !== '127.0.0.1') throw new Error('Expected the isolated loopback page')
          await item.page.goto(new URL('/login', url).href, { waitUntil: 'domcontentloaded', timeout: timeoutMs })
          return { contextReleased: true }
        })
      }
    } finally {
      if (item.application) {
        const child = item.application.process()
        const closed = await stage(index, 'electron-close', () => item.application.close())
        if (closed && child.exitCode === null && child.signalCode === null) {
          await stage(index, 'electron-exit', signal => waitForOwnedExit(child, signal))
        }
        if (child.exitCode === null && child.signalCode === null) {
          // Only the process returned by this fixture's launch is eligible.
          // Recovery does not erase the failed close stage or make the test pass.
          await stage(index, 'owned-process-exit', signal => waitForOwnedExit(child, signal, 'SIGTERM'))
        }
        if (child.exitCode === null && child.signalCode === null) {
          await stage(index, 'owned-process-kill', signal => waitForOwnedExit(child, signal, 'SIGKILL'))
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
