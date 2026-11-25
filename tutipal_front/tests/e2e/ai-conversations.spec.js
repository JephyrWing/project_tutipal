import { test, expect, login, createRoom, joinRoom } from './fixtures.js'

// PT-106 stored AI conversations against the real PostgreSQL schema (V21) and the Spring jar.
// app.ai.agent.enabled is false in e2e, so a turn goes through the legacy /v1/chat path of the fake AI server,
// which answers questions starting with "PT106" with `PT106 reply n=<messages received>`.

async function api(app, path, method = 'GET', body, extraHeaders = {}) {
  return app.page.evaluate(async ({ path, method, body, extraHeaders }) => {
    const headers = { ...extraHeaders }
    if (method !== 'GET') {
      const csrf = await (await fetch('/api/auth/csrf')).json()
      headers[csrf.headerName] = csrf.token
      if (body !== undefined) headers['Content-Type'] = 'application/json'
    }
    const response = await fetch(path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) })
    const text = await response.text()
    let parsed = null
    try { parsed = text ? JSON.parse(text) : null } catch { parsed = text }
    return { status: response.status, body: parsed }
  }, { path, method, body, extraHeaders })
}

const sse = { Accept: 'text/event-stream' }

async function openConversations(app, query = '') {
  await app.page.goto(new URL(`/conversations${query}`, app.page.url()).href)
  await expect(app.page.getByRole('heading', { name: 'AI 대화 기록', level: 1 })).toBeVisible()
}

async function ask(page, text) {
  await page.getByPlaceholder(/이어서 질문하세요/).fill(text)
  await page.getByRole('button', { name: '보내기', exact: true }).click()
}

test('PT106 personal conversation: save, reload, continue with stored history, isolation and delete', async ({ apps }) => {
  const owner = await apps.launch()
  const stranger = await apps.launch()
  await login(owner, 1)
  await login(stranger, 2)

  // Create through the screen
  await openConversations(owner)
  await owner.page.getByRole('button', { name: /새 대화/ }).click()
  await expect(owner.page).toHaveURL(/\/conversations\/\d+$/)
  const id = new URL(owner.page.url()).pathname.split('/').at(-1)
  const log = owner.page.locator('.chat-list')

  // First turn: only the question reaches the model
  await ask(owner.page, 'PT106 첫 질문')
  await expect(log).toContainText('PT106 reply n=1')
  await expect(owner.page.getByRole('button', { name: '보내기', exact: true })).toBeVisible()
  await expect.poll(async () => (await api(owner, `/api/conversations/${id}/messages`)).body?.items?.length).toBe(2)

  await owner.page.reload()
  await expect(owner.page.getByRole('heading', { name: 'PT106 첫 질문', level: 1 })).toBeVisible()
  await expect(log).toContainText('PT106 첫 질문')
  await expect(log).toContainText('PT106 reply n=1')

  // Second turn: 2 stored messages + the new question are sent; markup in the question stays text
  const xss = 'PT106 <img src=x onerror=alert(1)> 두 번째 질문'
  await ask(owner.page, xss)
  await expect(log).toContainText('PT106 reply n=3')
  await expect(owner.page.getByRole('button', { name: '보내기', exact: true })).toBeVisible()
  await expect.poll(async () => (await api(owner, `/api/conversations/${id}/messages`)).body?.items?.length).toBe(4)

  await owner.page.reload()
  await expect(log).toContainText(xss)
  await expect(log).toContainText('PT106 reply n=3')
  await expect(log.locator('.bubble')).toHaveCount(4)
  await expect(log.locator('img')).toHaveCount(0)

  // Detail and message paging on the real database
  const detail = await api(owner, `/api/conversations/${id}`)
  expect(detail.status).toBe(200)
  expect(detail.body).toMatchObject({ id, title: 'PT106 첫 질문', sessionId: null, messageCount: 4, summary: null, memories: [] })

  const all = await api(owner, `/api/conversations/${id}/messages`)
  expect(all.status).toBe(200)
  expect(all.body.items.map(m => m.role)).toEqual(['user', 'assistant', 'user', 'assistant'])
  expect(all.body.items.map(m => m.content)).toEqual(['PT106 첫 질문', 'PT106 reply n=1', xss, 'PT106 reply n=3'])
  expect(all.body.items.every(m => typeof m.id === 'string')).toBe(true)
  expect(all.body).toMatchObject({ hasMore: false, nextBefore: null })

  const newest = await api(owner, `/api/conversations/${id}/messages?limit=2`)
  expect(newest.body.items.map(m => m.content)).toEqual([xss, 'PT106 reply n=3'])
  expect(newest.body.hasMore).toBe(true)
  expect(typeof newest.body.nextBefore).toBe('string')
  const older = await api(owner, `/api/conversations/${id}/messages?limit=2&before=${encodeURIComponent(newest.body.nextBefore)}`)
  expect(older.body.items.map(m => m.content)).toEqual(['PT106 첫 질문', 'PT106 reply n=1'])
  expect(older.body).toMatchObject({ hasMore: false, nextBefore: null })

  // Keyset list: a second (empty) conversation, one item per page
  const empty = await api(owner, '/api/conversations', 'POST', { topic: '수학' })
  expect(empty.status).toBe(201)
  expect(typeof empty.body.id).toBe('string')
  const first = await api(owner, '/api/conversations?limit=1')
  expect(first.status).toBe(200)
  expect(first.body.items).toHaveLength(1)
  expect(first.body.hasMore).toBe(true)
  expect(typeof first.body.nextCursor).toBe('string')
  const rest = await api(owner, `/api/conversations?limit=50&cursor=${encodeURIComponent(first.body.nextCursor)}`)
  expect(rest.status).toBe(200)
  const restIds = rest.body.items.map(c => c.id)
  expect(restIds).not.toContain(first.body.items[0].id)
  expect([first.body.items[0].id, ...restIds]).toEqual(expect.arrayContaining([id, empty.body.id]))
  expect((await api(owner, '/api/conversations?cursor=not-a-cursor')).status).toBe(400)
  const listed = [...first.body.items, ...rest.body.items].find(c => c.id === id)
  expect(listed).toMatchObject({ title: 'PT106 첫 질문', sessionId: null, messageCount: 4 })

  // Memory save / delete in the personal scope
  const memory = await api(owner, `/api/conversations/${id}/memories`, 'POST', { text: 'PT106 개인 기억' })
  expect(memory.status).toBe(201)
  expect(typeof memory.body.id).toBe('string')

  // Another user sees nothing of it: every route answers 404
  const base = `/api/conversations/${id}`
  expect((await api(stranger, base)).status).toBe(404)
  expect((await api(stranger, `${base}/messages`)).status).toBe(404)
  expect((await api(stranger, `${base}/memories`, 'POST', { text: '남의 대화 기억' })).status).toBe(404)
  expect((await api(stranger, `${base}/memories/${memory.body.id}`, 'DELETE')).status).toBe(404)
  const continued = await api(stranger, `${base}/messages`, 'POST', { content: 'PT106 남의 대화 이어가기' }, sse)
  expect(continued.status).toBe(404)
  expect(continued.body).toMatchObject({ status: 404 })
  expect((await api(stranger, base, 'DELETE')).status).toBe(404)
  expect((await api(stranger, '/api/conversations')).body.items.map(c => c.id)).not.toContain(id)
  expect((await api(owner, `${base}/messages`)).body.items).toHaveLength(4)

  expect((await api(owner, `${base}/memories/${memory.body.id}`, 'DELETE')).status).toBe(204)
  expect((await api(owner, base)).body.memories).toEqual([])

  // Resend after a lost saved event: the same clientMessageId replays the stored pair (no second row, no model
  // call); a different text under that key is 409. Polled because the turn slot frees just after the stream ends.
  const key = '00000000-0000-4000-8000-000000000106'
  const keyed = { content: 'PT106 같은 질문', clientMessageId: key }
  await expect.poll(async () => (await api(owner, `${base}/messages`, 'POST', keyed, sse)).status).toBe(200)
  await expect.poll(async () => (await api(owner, `${base}/messages`)).body?.items?.length).toBe(6)
  let replayed
  await expect.poll(async () => (replayed = await api(owner, `${base}/messages`, 'POST', keyed, sse)).status).toBe(200)
  expect(String(replayed.body)).toContain('PT106 reply n=5')
  expect((await api(owner, `${base}/messages`)).body.items).toHaveLength(6)
  await expect.poll(async () => (await api(owner, `${base}/messages`, 'POST',
    { content: 'PT106 다른 질문', clientMessageId: key }, sse)).status).toBe(409)

  // Delete through the screen: messages go with it (ON DELETE CASCADE)
  await owner.page.reload()
  owner.page.once('dialog', dialog => void dialog.accept())
  await owner.page.getByRole('button', { name: /대화 삭제/ }).click()
  await expect(owner.page).toHaveURL(/\/conversations$/)
  expect((await api(owner, base)).status).toBe(404)
  expect((await api(owner, `${base}/messages`)).status).toBe(404)
  expect((await api(owner, '/api/conversations?limit=50')).body.items.map(c => c.id)).not.toContain(id)
  expect((await api(owner, `/api/conversations/${empty.body.id}`, 'DELETE')).status).toBe(204)
})

test('PT106 session conversations: student only, scoped list, 20 memory cap under concurrency, cascade', async ({ apps }) => {
  const teacher = await apps.launch()
  const student = await apps.launch()
  await login(teacher, 1)
  await login(student, 2)
  const room = await createRoom(teacher, 'PT106 수업 대화')
  await joinRoom(student, room.code)
  await expect(student.page).toHaveURL(new RegExp(`/sessions/${room.id}$`))

  const a = await api(student, '/api/conversations', 'POST', { sessionId: room.id })
  expect(a.status, JSON.stringify(a.body)).toBe(201)
  expect(a.body).toMatchObject({ sessionId: String(room.id), topic: '수학' })
  expect((await api(teacher, '/api/conversations', 'POST', { sessionId: room.id })).status).toBe(403)

  const scoped = await api(student, `/api/conversations?sessionId=${room.id}`)
  expect(scoped.status).toBe(200)
  expect(scoped.body.items.map(c => c.id)).toEqual([a.body.id])
  expect(scoped.body.items[0]).toMatchObject({ title: null, messageCount: 0, sessionId: String(room.id) })
  expect((await api(student, '/api/conversations?limit=50')).body.items.map(c => c.id)).not.toContain(a.body.id)

  // The session list on the screen shows the empty conversation as "새 대화"
  await openConversations(student, `?sessionId=${room.id}`)
  await expect(student.page.getByRole('list', { name: 'AI 대화 목록' }).getByRole('button')).toHaveCount(1)
  await expect(student.page.getByRole('list', { name: 'AI 대화 목록' })).toContainText('새 대화')

  // Two conversations in the same scope share one cap of 20, enforced by the scope advisory lock
  const b = await api(student, '/api/conversations', 'POST', { sessionId: room.id })
  expect(b.status).toBe(201)
  const saves = await Promise.all(Array.from({ length: 22 }, (_, i) =>
    api(student, `/api/conversations/${i % 2 ? b.body.id : a.body.id}/memories`, 'POST', { text: `PT106 기억 ${i}` })))
  expect(saves.filter(r => r.status === 201)).toHaveLength(20)
  expect(saves.filter(r => r.status === 409)).toHaveLength(2)
  const inA = (await api(student, `/api/conversations/${a.body.id}`)).body.memories
  const inB = (await api(student, `/api/conversations/${b.body.id}`)).body.memories
  expect(inA.length + inB.length).toBe(20)
  expect(inA.length).toBeGreaterThan(0)
  expect((await api(student, `/api/conversations/${b.body.id}/memories`, 'POST', { text: 'PT106 21번째' })).status).toBe(409)

  // Deleting one memory frees one place
  expect((await api(student, `/api/conversations/${a.body.id}/memories/${inA[0].id}`, 'DELETE')).status).toBe(204)
  expect((await api(student, `/api/conversations/${a.body.id}/memories`, 'POST', { text: 'PT106 다시 저장' })).status).toBe(201)
  expect((await api(student, `/api/conversations/${a.body.id}/memories`, 'POST', { text: 'PT106 넘침' })).status).toBe(409)

  // Deleting a conversation takes its memories with it, so the scope has room again
  expect((await api(teacher, `/api/conversations/${a.body.id}`, 'DELETE')).status).toBe(404)
  expect((await api(student, `/api/conversations/${a.body.id}`, 'DELETE')).status).toBe(204)
  expect((await api(student, `/api/conversations/${a.body.id}`)).status).toBe(404)
  expect((await api(student, `/api/conversations/${a.body.id}/messages`)).status).toBe(404)
  expect((await api(student, `/api/conversations/${b.body.id}/memories`, 'POST', { text: 'PT106 삭제 뒤 저장' })).status).toBe(201)
  expect((await api(student, `/api/conversations?sessionId=${room.id}`)).body.items.map(c => c.id)).toEqual([b.body.id])
})
