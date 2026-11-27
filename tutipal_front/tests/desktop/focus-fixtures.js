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
    const control = { hold: false, status: 200, focusState: 'FOCUSED',
      consented: false, version: null, consentError: 0, saveError: 0, withdrawError: 0, holdConsent: false }
    const pendingConsents = []
    const consent = () => ({ consented: control.consented, version: control.version,
      consentedAt: control.consented ? '2026-11-27T00:00:00Z' : null, currentVersion: '2026-11-26',
      retentionDays: 30, maxSampleAgeDays: 31, staleAfterSeconds: 180 })
    const server = createServer(async (req, res) => {
      const route = new URL(req.url, 'http://127.0.0.1').pathname
      const requestRecord = { method: req.method, route }
      requests.push(requestRecord)
      const json = body => res.writeHead(200, { 'Content-Type': 'application/json' }).end(JSON.stringify(body))
      const error = (status, message) => res.writeHead(status, { 'Content-Type': 'application/json' })
        .end(JSON.stringify({ status, message }))
      if (route === '/api/auth/csrf') return json({ token: 'synthetic-only', headerName: 'X-CSRF-TOKEN' })
      if (route === '/api/focus/consent') {
        if (req.method === 'GET') return control.consentError
          ? error(control.consentError, '시험 동의 조회 실패') : json(consent())
        if (req.method === 'PUT') {
          let raw = ''
          for await (const chunk of req) raw += chunk
          const value = JSON.parse(raw)
          requestRecord.body = value
          const finish = () => {
            if (res.destroyed) return
            if (control.saveError) return error(control.saveError, '시험 동의 저장 실패')
            if (value.version !== '2026-11-26') return error(409, '시험 동의 버전 불일치')
            control.consented = true
            control.version = value.version
            json(consent())
          }
          if (control.holdConsent) pendingConsents.push(finish)
          else finish()
          return
        }
        if (req.method === 'DELETE') {
          if (control.withdrawError) return error(control.withdrawError, '시험 동의 철회 실패')
          control.consented = false
          control.version = null
          return res.writeHead(204).end()
        }
      }
      if (req.method === 'POST' && /^\/api\/sessions\/\d+\/focus$/.test(route)) {
        let raw = ''
        for await (const chunk of req) raw += chunk
        posts.push({ route, body: JSON.parse(raw), csrf: req.headers['x-csrf-token'], origin: req.headers.origin,
          cookie: req.headers.cookie ?? '' })
        if (control.hold) { pending.add(res); res.on('close', () => pending.delete(res)); return }
        if (control.status !== 200) return res.writeHead(control.status).end()
        return json({ state: control.focusState, updatedAt: '2026-11-27T00:00:00Z' })
      }
      if (req.method !== 'GET') return res.writeHead(403).end()
      if (route === '/api/users/me') return json({ id: 1, name: 'PT30 synthetic user', role: 'USER', providers: [] })
      if (route === '/api/auth/config') return json({ devLogin: false, providers: [] })
      if (route === '/api/sessions/1') return json({ id: 1, title: 'PT30 집중도 시험 수업', topic: '수학',
        myRole: 'STUDENT', inviteCode: 'PT30UI', difficulty: 2, participantCount: 2, ended: false, aiRetentionDays: 14 })
      if (route === '/api/sessions/1/help' || route === '/api/sessions/1/quizzes/open') return json([])
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
      const releaseConsent = () => pendingConsents.splice(0).forEach(finish => finish())
      await use({ application, page, backend, posts, requests, control, snapshot, minute, release, releaseConsent })
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
