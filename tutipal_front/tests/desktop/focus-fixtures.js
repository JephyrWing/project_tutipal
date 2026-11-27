import { test as base, expect, _electron } from '@playwright/test'
import electronPath from 'electron'
import { createServer } from 'node:http'
import { mkdtemp, realpath, rm } from 'node:fs/promises'
import { once } from 'node:events'
import path from 'node:path'
import os from 'node:os'
import { fileURLToPath } from 'node:url'

export { expect }
const front = fileURLToPath(new URL('../../', import.meta.url))
export const test = base.extend({
  focus: async ({}, use, testInfo) => {
    const posts = []
    const requests = []
    const pending = new Set()
    const control = { hold: false, status: 200 }
    const server = createServer(async (req, res) => {
      const route = new URL(req.url, 'http://127.0.0.1').pathname
      requests.push({ method: req.method, route })
      const json = body => res.writeHead(200, { 'Content-Type': 'application/json' }).end(JSON.stringify(body))
      if (route === '/api/auth/csrf') return json({ token: 'synthetic-only', headerName: 'X-CSRF-TOKEN' })
      if (req.method === 'POST' && /^\/api\/sessions\/\d+\/focus$/.test(route)) {
        let raw = ''
        for await (const chunk of req) raw += chunk
        posts.push({ route, body: JSON.parse(raw), csrf: req.headers['x-csrf-token'], origin: req.headers.origin,
          cookie: req.headers.cookie ?? '' })
        if (control.hold) { pending.add(res); res.on('close', () => pending.delete(res)); return }
        if (control.status !== 200) return res.writeHead(control.status).end()
        return json({ state: 'FOCUSED', updatedAt: '2026-11-27T00:00:00Z' })
      }
      if (req.method !== 'GET') return res.writeHead(403).end()
      if (route === '/api/users/me') return json({ id: 1, name: 'PT30 synthetic user', role: 'USER', providers: [] })
      if (route === '/api/auth/config') return json({ devLogin: false, providers: [] })
      if (route === '/api/auth/providers' || route === '/api/sessions' || route.startsWith('/api/review')) return json([])
      res.writeHead(404).end()
    })
    server.listen(0, '127.0.0.1')
    await once(server, 'listening')
    const backend = `http://127.0.0.1:${server.address().port}`
    const profile = await mkdtemp(path.join(os.tmpdir(), 'tutipal-e2e-profile-'))
    let application
    try {
      application = await _electron.launch({ executablePath: electronPath,
        args: [path.join(front, 'tests/desktop/focus-entry.cjs')], cwd: front, chromiumSandbox: true,
        env: { ...process.env, TUTIPAL_E2E_PROFILE: profile, TUTIPAL_SPRING_URL: backend } })
      let page
      await expect.poll(() => {
        page = application.windows().find(p => p.url().startsWith('http:') && new URL(p.url()).pathname === '/')
        return !!page
      }).toBe(true)
      await expect(page.getByRole('heading', { name: '오늘은 무엇을 공부할까요?' })).toBeVisible()
      // Synthetic HttpOnly cookie: verify the real session transport, never read a user's cookie.
      await application.evaluate(async ({ session }, url) => session.defaultSession.cookies.set({
        url, name: 'TUTIPAL_SESSION', value: 'synthetic-focus-session', httpOnly: true,
      }), backend)
      const snapshot = () => application.evaluate(() => global.pt30Focus.snapshot())
      const minute = exe => application.evaluate((_electron, value) => global.pt30Focus.minute(value), exe)
      const release = () => {
        for (const res of pending) res.writeHead(200, { 'Content-Type': 'application/json' })
          .end(JSON.stringify({ state: 'FOCUSED', updatedAt: '2026-11-27T00:00:00Z' }))
        pending.clear()
      }
      await use({ application, page, backend, posts, control, snapshot, minute, release })
    } finally {
      if (application && testInfo.status !== testInfo.expectedStatus) {
        const snapshot = await application.evaluate(() => global.pt30Focus.snapshot()).catch(() => ({}))
        console.log('Focus fixture diagnostic:', JSON.stringify({ snapshot, requests }))
      }
      await application?.close().catch(() => {})
      server.closeAllConnections()
      await new Promise(resolve => server.close(resolve))
      const resolved = await realpath(profile)
      if (path.dirname(resolved) === await realpath(os.tmpdir()) && path.basename(resolved).startsWith('tutipal-e2e-profile-'))
        await rm(resolved, { recursive: true, force: true })
    }
  },
})
