// Local UI supplement; real Spring/PostgreSQL scenarios live in e2e/personal-stored-chat.spec.js.
import { _electron } from 'playwright'
import { expect } from '@playwright/test'
import electronPath from 'electron'
import { createServer } from 'node:http'
import { mkdtemp, rm } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import path from 'node:path'
import os from 'node:os'
const front = fileURLToPath(new URL('../', import.meta.url))
const profile = await mkdtemp(path.join(os.tmpdir(), 'tutipal-e2e-profile-'))
const control = { user: 1, posts: 0, creates: 0, fail: false, cut: false, hold: false, pending: [], keys: [] }
const conversations = new Map()
const server = createServer(async (req, res) => {
  const route = new URL(req.url, 'http://127.0.0.1').pathname
  const json = (body, status = 200) => res.writeHead(status, { 'Content-Type': 'application/json' }).end(JSON.stringify(body))
  if (route === '/api/users/me') return json({ id: control.user, name: 'synthetic' })
  if (route === '/api/auth/csrf') return json({ headerName: 'X-CSRF', token: 'synthetic' })
  if (route === '/api/auth/config') return json({ devLogin: true, providers: [] })
  let data = ''; for await (const chunk of req) data += chunk
  const body = data ? JSON.parse(data) : {}
  if (route === '/api/conversations' && req.method === 'POST') {
    control.creates++; const id = String(control.creates)
    const c = { id, user: control.user, topic: body.topic, title: '', sessionId: null, messageCount: 0, messages: [], keys: new Map(), memories: [], summary: null, personalRetentionDays: 30 }
    conversations.set(id, c); return json(c, 201)
  }
  const match = route.match(/^\/api\/conversations\/(\d+)(\/messages)?$/)
  if (match) {
    const c = conversations.get(match[1])
    if (!c || c.user !== control.user) return json({ message: 'not found' }, 404)
    if (!match[2]) return json(c)
    if (req.method === 'GET') return json({ items: c.messages, hasMore: false })
    control.posts++; control.keys.push(body.clientMessageId)
    if (control.fail) { control.fail = false; return json({ message: 'PT124 temporary failure' }, 503) }
    if (control.hold) { control.pending.push(res); return }
    let pair = c.keys.get(body.clientMessageId)
    if (!pair) {
      const n = c.messages.length
      pair = { userMessageId: String(n + 1), assistantMessageId: String(n + 2) }
      c.messages.push({ id: pair.userMessageId, role: 'user', content: body.content }, { id: pair.assistantMessageId, role: 'assistant', content: `reply ${n + 1}` })
      c.keys.set(body.clientMessageId, pair); c.title ||= body.content; c.messageCount += 2
    }
    const cut = control.cut; control.cut = false
    return res.writeHead(200, { 'Content-Type': 'text/event-stream' }).end(`event: delta\ndata: {"t":"${c.messages.find(m => m.id === pair.assistantMessageId).content}"}\n\n${cut ? '' : `event: saved\ndata: ${JSON.stringify(pair)}\n\nevent: done\ndata: {}\n\n`}`)
  }
  return json([])
})
let app
try {
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
  const env = { ...process.env, TUTIPAL_E2E_PROFILE: profile, TUTIPAL_SPRING_URL: `http://127.0.0.1:${server.address().port}` }; delete env.ELECTRON_RUN_AS_NODE
  app = await _electron.launch({ executablePath: electronPath, args: [path.join(front, 'tests/desktop/analysis-session-entry.cjs')], cwd: front, env, chromiumSandbox: true })
  let page
  await expect.poll(() => { page = app.windows().find(p => p.url().startsWith('http:') && !p.url().endsWith('/widget')); return !!page }).toBe(true)
  const errors = []; page.on('pageerror', e => { errors.push(e.message); console.error('renderer error', e.stack) })
  const open = () => page.goto(new URL('/chat', page.url()).href)
  const ask = async text => { await page.getByRole('textbox', { name: '질문', exact: true }).fill(text); await page.getByRole('button', { name: '보내기', exact: true }).click() }
  const saved = () => expect(page.getByRole('status')).toContainText('기록에 저장')
  await open()
  await page.getByRole('textbox', { name: '질문', exact: true }).fill('first question')
  await page.locator('form.chat-input').evaluate(form => { form.requestSubmit(); form.requestSubmit() }); await saved()
  expect(control.creates).toBe(1); expect(control.posts).toBe(1)
  await page.getByRole('link', { name: '이 대화 기록 열기' }).click()
  await expect(page.locator('.chat-list')).toContainText('first question')
  await page.getByPlaceholder(/이어서 질문하세요/).fill('continued question'); await page.getByRole('button', { name: '보내기', exact: true }).click()
  await expect.poll(() => conversations.get('1').messages.length).toBe(4)
  console.log('PASS same id persistence/record continuation and duplicate submission')
  await open(); control.cut = true; await ask('lost saved')
  await expect(page.getByRole('button', { name: '답변 다시 시도' })).toBeVisible()
  const key = control.keys.at(-1)
  await page.getByRole('button', { name: '답변 다시 시도' }).click(); await saved()
  expect(control.keys.at(-1)).toBe(key); expect(conversations.get('2').messages.length).toBe(2)
  console.log('PASS lost saved response uses same idempotency key')
  control.fail = true; await ask('temporary failure')
  await expect(page.getByRole('alert')).toContainText('temporary failure')
  await page.getByRole('button', { name: '답변 다시 시도' }).click(); await saved()
  console.log('PASS explicit failure retry')
  control.hold = true; await ask('stopped question')
  await expect.poll(() => control.pending.length).toBe(1)
  const beforeStop = control.posts
  await page.getByRole('button', { name: '중지', exact: true }).click()
  await expect(page.getByRole('textbox', { name: '질문', exact: true })).toHaveValue('stopped question')
  control.pending.splice(0).forEach(r => r.end()); expect(conversations.get('2').messages.length).toBe(4)
  expect(control.posts).toBe(beforeStop)
  console.log('PASS stop restores question without storing partial answer')
  control.hold = false; control.user = 2; await open()
  await expect(page.locator('.chat-list')).not.toContainText('lost saved')
  await expect(page.getByRole('link', { name: '이 대화 기록 열기' })).toHaveCount(0)
  expect(await page.evaluate(async () => (await fetch('/api/conversations/2')).status)).toBe(404)
  console.log('PASS account boundary and old record access denial')
  expect(errors).toEqual([])
  console.log(JSON.stringify({ collected: 5, executed: 5, passed: 5, failed: 0, skipped: 0, rendererErrors: errors }))
} finally {
  control.pending.forEach(r => r.end()); await app?.close(); await new Promise(resolve => server.close(resolve))
  if (path.dirname(path.resolve(profile)) !== path.resolve(os.tmpdir()) || !path.basename(profile).startsWith('tutipal-e2e-profile-')) throw Error('Unsafe temporary profile')
  await rm(profile, { recursive: true, force: true, maxRetries: 5, retryDelay: 200 })
}
