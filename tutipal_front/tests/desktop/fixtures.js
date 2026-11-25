import { test as base, expect, _electron } from '@playwright/test'
import electronPath from 'electron'
import { createServer } from 'node:http'
import { mkdtemp, realpath, rm } from 'node:fs/promises'
import { once } from 'node:events'
import path from 'node:path'
import os from 'node:os'
import { fileURLToPath } from 'node:url'

export { expect }
export const front = fileURLToPath(new URL('../../', import.meta.url))
export const entry = path.join(front, 'tests/desktop/entry.cjs')
export const environment = profile => ({ ...process.env, TUTIPAL_E2E_PROFILE: profile, TUTIPAL_SPRING_URL: 'http://127.0.0.1:28080' })
export const test = base.extend({
  provider: [async ({}, use) => {
    // UI initialization only: this is NOT Spring, an authentication test or a DB.
    const server = createServer((req, res) => {
      let body
      const route = new URL(req.url, 'http://127.0.0.1').pathname
      if (req.method !== 'GET') return res.writeHead(403).end()
      if (route === '/api/users/me') body = { id: 1, name: 'PT30 synthetic user', role: 'USER', providers: [] }
      else if (route === '/api/auth/csrf') body = { token: 'synthetic-only', headerName: 'X-CSRF-TOKEN' }
      else if (route === '/api/auth/config') body = { devLogin: false, providers: [] }
      else if (route === '/api/auth/providers') body = []
      else if (route === '/api/sessions' || route === '/api/chat/sessions' || route.startsWith('/api/review')) body = []
      else return res.writeHead(404).end()
      res.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' }).end(JSON.stringify(body))
    })
    const listening = once(server, 'listening')
    server.listen(28080, '127.0.0.1')
    await listening
    try { await use() } finally {
      server.closeAllConnections()
      await new Promise(resolve => server.close(resolve))
    }
  }, { scope: 'worker', auto: true }],
  desktop: async ({ provider }, use) => {
    void provider
    const profile = await mkdtemp(path.join(os.tmpdir(), 'tutipal-e2e-profile-'))
    let application
    try {
      application = await _electron.launch({ executablePath: electronPath, args: [entry], cwd: front,
        chromiumSandbox: true, env: environment(profile) })
      let page
      await expect.poll(() => {
        page = application.windows().find(p => {
          const u = new URL(p.url())
          return u.protocol === 'http:' && !['/widget', '/overlay'].includes(u.pathname)
        })
        return !!page
      }).toBe(true)
      await expect(page.getByRole('heading', { name: '오늘은 무엇을 공부할까요?' })).toBeVisible()
      await expect.poll(() => application.windows().some(p => new URL(p.url()).pathname === '/widget')).toBe(true)
      await use({ application, page, profile })
    } finally {
      await application?.close().catch(() => {})
      const resolved = await realpath(profile)
      if (path.dirname(resolved) === await realpath(os.tmpdir()) && path.basename(resolved).startsWith('tutipal-e2e-profile-')) {
        await rm(resolved, { recursive: true, force: true })
      }
    }
  },
})
