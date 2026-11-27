import { realpath, rm } from 'node:fs/promises'
import path from 'node:path'
import os from 'node:os'

const exited = child => child.exitCode !== null || child.signalCode !== null

// Only receives applications returned by this test's own _electron.launch().
// Never look up processes by name/PID or delete profiles while an app is alive.
export async function cleanupApplications(items, { stepTimeoutMs = 5000, onPhase = () => {} } = {}) {
  return Promise.all(items.map(async (item, index) => {
    const phases = []
    async function step(stage, action) {
      const record = { application: index, stage, startedAt: new Date().toISOString(), status: 'running' }
      phases.push(record)
      onPhase({ ...record })
      let timer
      const controller = new AbortController()
      try {
        await Promise.race([
          Promise.resolve().then(() => action(controller.signal)),
          new Promise((_, reject) => {
            timer = setTimeout(() => reject(Object.assign(new Error('Cleanup deadline'), { cleanupTimeout: true })), stepTimeoutMs)
          }),
        ])
        record.status = 'passed'
      } catch (error) {
        // SDK/Playwright errors may contain URLs or credentials. Keep only our stage/status.
        record.status = error?.cleanupTimeout ? 'timeout' : 'failed'
      } finally {
        clearTimeout(timer)
        controller.abort()
        record.finishedAt = new Date().toISOString()
        onPhase({ ...record })
      }
      return record.status === 'passed'
    }
    const child = item.application?.process()
    if (item.page && !item.page.isClosed()) {
      await step('renderer-disconnect', () => item.page.evaluate(async () => {
        const m = window.pt65Media
        if (!m) return
        clearInterval(m.paint)
        m.stream?.getTracks().forEach(t => t.stop())
        m.videos.forEach(v => v.remove())
        await m.room.disconnect()
      }))
    }
    if (item.application) await step('application-close', () => item.application.close())
    if (child && !exited(child)) {
      await step('owned-process-exit', async signal => {
        // Register before killing, so an immediate exit cannot be missed.
        let listener, abort
        const ended = new Promise((resolve, reject) => {
          listener = resolve
          abort = () => reject(Error('Exit wait cancelled'))
          child.once('exit', listener)
          signal.addEventListener('abort', abort, { once: true })
        })
        try {
          if (!child.kill('SIGKILL') && !exited(child)) throw Error('Owned child could not be stopped')
          if (!exited(child)) await ended
        } finally {
          child.removeListener('exit', listener)
          signal.removeEventListener('abort', abort)
        }
      })
    }
    await step('profile-remove', async () => {
      if (item.application && !child) throw Error('Missing owned child')
      if (child && !exited(child)) throw Error('Owned child is still running')
      const resolved = await realpath(item.profile)
      if (path.dirname(resolved) !== await realpath(os.tmpdir())
          || !path.basename(resolved).startsWith('tutipal-e2e-profile-')) throw Error('Unsafe profile path')
      await rm(resolved, { recursive: true, force: true })
    })
    return { application: index, phases }
  }))
}
