import { test, expect, login, createRoom, joinRoom } from './fixtures.js'

async function classroom(apps) {
  const teacher = await apps.launch(), student = await apps.launch()
  await login(teacher, 1); await login(student, 2)
  const room = await createRoom(teacher, 'PT124 수업 저장')
  await joinRoom(student, room.code)
  await expect(student.page.getByRole('heading', { name: '도우미 챗봇' })).toBeVisible()
  student.room = room
  return { teacher, student, room }
}
async function open(app) {
  await app.page.goto(new URL(`/sessions/${app.room.id}`, app.page.url()).href)
  await expect(app.page.getByRole('heading', { name: '도우미 챗봇' })).toBeVisible()
}async function ask(page, text) {
  await page.getByRole('textbox', { name: '질문', exact: true }).fill(text)
  await page.getByRole('button', { name: '보내기', exact: true }).click()
}
async function rows(page, id) {
  return page.evaluate(async id => (await (await fetch(`/api/conversations/${id}/messages`)).json()).items, id)
}

async function request(app, url, method = 'GET', body) {
  return app.page.evaluate(async ({ url, method, body }) => {
    const csrf = await (await fetch('/api/auth/csrf')).json()
    const res = await fetch(url, { method, headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token }, body: body === undefined ? undefined : JSON.stringify(body) })
    return { status: res.status, text: await res.text() }
  }, { url, method, body })
}
async function idOf(page) {
  return (await page.getByRole('link', { name: '이 대화 기록 열기' }).getAttribute('href')).split('/').at(-1)
}

// Node route.fetch bypasses Electron's private bridge header. Fetch inside the
// real page, skipping just this interceptor, so Main supplies its normal header.
async function forwarded(page, route) {
  const request = route.request()
  return page.evaluate(async ({ url, body }) => {
    const csrf = await (await fetch('/api/auth/csrf')).json()
    const response = await fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token, 'X-PT124-Forward': '1' }, body })
    return { status: response.status, body: await response.text() }
  }, { url: request.url(), body: request.postData() })
}

test('PT124 session entry persists once and continues the same conversation in records', async ({ apps }) => {
  const { student: app } = await classroom(apps)
  let creates = 0, posts = 0
  app.page.on('request', r => {
    if (r.method() !== 'POST') return
    if (new URL(r.url()).pathname === '/api/conversations') creates++
    if (/\/api\/conversations\/\d+\/messages$/.test(new URL(r.url()).pathname)) posts++
  })
  await app.page.getByRole('textbox', { name: '질문', exact: true }).fill('PT106 수업 진입 첫 질문')
  await app.page.locator('form.chat-input').evaluate(form => { form.requestSubmit(); form.requestSubmit() })
  await expect(app.page.getByRole('status')).toContainText('기록에 저장')
  const id = await idOf(app.page)
  expect(creates).toBe(1); expect(posts).toBe(1)
  expect((await rows(app.page, id)).length).toBe(2)
  await app.page.getByRole('link', { name: '이 대화 기록 열기' }).click()
  await expect(app.page).toHaveURL(new RegExp(`/conversations/${id}$`))
  await expect(app.page.locator('.chat-list')).toContainText('PT106 수업 진입 첫 질문')
  await app.page.getByPlaceholder(/이어서 질문하세요/).fill('PT106 기록에서 이어가기')
  await app.page.getByRole('button', { name: '보내기', exact: true }).click()
  await expect(app.page.locator('.chat-list')).toContainText('PT106 reply n=3')
  await expect.poll(async () => (await rows(app.page, id)).length).toBe(4)
  await app.page.reload()
  await expect(app.page.locator('.chat-list')).toContainText('PT106 기록에서 이어가기')
})

test('PT124 lost saved event retries the same key without a second stored pair', async ({ apps }) => {
  const { student: app } = await classroom(apps)
  const keys = []; let first = true
  await app.page.route('**/api/conversations/*/messages', async route => {
    if (route.request().method() !== 'POST') return route.continue()
    if (route.request().headers()['x-pt124-forward']) return route.continue()
    keys.push(route.request().postDataJSON().clientMessageId)
    const response = await forwarded(app.page, route)
    expect(response.status).toBe(200)
    if (first) { first = false; await route.fulfill({ status: response.status, contentType: 'text/event-stream', body: response.body.split(/event:\s*saved/)[0] }); }
    else await route.fulfill({ status: response.status, contentType: 'text/event-stream', body: response.body })
  })
  await ask(app.page, 'PT106 저장 응답 유실')
  await expect(app.page.getByRole('button', { name: '답변 다시 시도' })).toBeVisible()
  const id = await idOf(app.page)
  expect((await rows(app.page, id)).length).toBe(2)
  await app.page.getByRole('button', { name: '답변 다시 시도' }).click()
  await expect(app.page.getByRole('status')).toContainText('기록에 저장')
  expect(keys).toHaveLength(2); expect(keys[0]).toBeTruthy(); expect(keys[1]).toBe(keys[0])
  expect((await rows(app.page, id)).length).toBe(2)
  await expect(app.page.locator('.msg-user')).toHaveCount(1)
})

test('PT124 failure retry, stop, account transition and late response are isolated', async ({ apps }) => {
  const { student: app } = await classroom(apps)
  let failure = true, hold = false, release, entered
  let waiting = new Promise(resolve => { entered = resolve })
  await app.page.route('**/api/conversations/*/messages', async route => {
    if (route.request().method() !== 'POST') return route.continue()
    if (failure) { failure = false; return route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ message: 'PT124 일시 오류' }) }) }
    if (hold) {
      entered(); await new Promise(resolve => { release = resolve })
      return route.fulfill({ contentType: 'text/event-stream', body: 'event: delta\ndata: {"t":"OLD ACCOUNT RESPONSE"}\n\nevent: done\ndata: {}\n\n' }).catch(() => {})
    }
    return route.continue()
  })
  await ask(app.page, 'PT106 재시도할 질문')
  await expect(app.page.getByRole('alert')).toContainText('PT124 일시 오류')
  await app.page.getByRole('button', { name: '답변 다시 시도' }).click()
  await expect(app.page.getByRole('status')).toContainText('기록에 저장')
  const id = await idOf(app.page)
  hold = true
  await ask(app.page, 'PT106 중지할 질문'); await waiting
  await app.page.getByRole('button', { name: '중지', exact: true }).click()
  await expect(app.page.getByRole('textbox', { name: '질문', exact: true })).toHaveValue('PT106 중지할 질문')
  release(); hold = false
  expect((await rows(app.page, id)).length).toBe(2)
  await expect(app.page.locator('.chat-list')).not.toContainText('OLD ACCOUNT RESPONSE')
  hold = true; waiting = new Promise(resolve => { entered = resolve })
  await ask(app.page, 'PT106 이전 계정 질문'); await waiting
  await app.page.getByRole('button', { name: '로그아웃', exact: true }).click()
  await expect(app.page.getByRole('button', { name: '개발용 로그인 (내 PC 전용)', exact: true })).toBeVisible()
  await login(app, 3); release(); await joinRoom(app, app.room.code); await expect(app.page.getByRole('heading', { name: '도우미 챗봇' })).toBeVisible()
  await expect(app.page.locator('.chat-list')).not.toContainText('PT106 이전 계정 질문')
  await expect(app.page.getByRole('link', { name: '이 대화 기록 열기' })).toHaveCount(0)
  const status = await app.page.evaluate(async id => (await fetch(`/api/conversations/${id}`)).status, id)
  expect(status).toBe(404)
})

test('PT124 stop during conversation creation reuses the row and does not send automatically', async ({ apps }) => {
  const { student: app } = await classroom(apps)
  let release, entered, creates = 0, posts = 0
  const waiting = new Promise(resolve => { entered = resolve })
  app.page.on('request', r => { if (r.method() === 'POST' && /\/api\/conversations\/\d+\/messages$/.test(new URL(r.url()).pathname)) posts++ })
  await app.page.route('**/api/conversations', async route => {
    if (route.request().method() !== 'POST') return route.continue()
    if (route.request().headers()['x-pt124-forward']) return route.continue()
    creates++; const response = await forwarded(app.page, route)
    expect(response.status).toBe(201)
    entered(); await new Promise(resolve => { release = resolve })
    await route.fulfill({ status: response.status, contentType: 'application/json', body: response.body })
  })
  await ask(app.page, 'PT106 생성 중지 질문'); await waiting
  await app.page.getByRole('button', { name: '대화 준비 중지' }).click()
  await expect(app.page.getByRole('textbox', { name: '질문', exact: true })).toHaveValue('PT106 생성 중지 질문')
  expect(posts).toBe(0)
  release()
  await app.page.getByRole('button', { name: '답변 다시 시도' }).click()
  await expect(app.page.getByRole('status')).toContainText('기록에 저장')
  expect(creates).toBe(1); expect(posts).toBe(1)
})

for (const boundary of ['leave', 'remove', 'end']) {
  test(`PT124 session ${boundary}: no further turn and stored pair is retained`, async ({ apps }) => {
    const { teacher, student, room } = await classroom(apps)
    await ask(student.page, `PT106 before ${boundary}`)
    await expect(student.page.getByRole('status')).toContainText('기록에 저장')
    const id = await idOf(student.page)
    const before = await rows(student.page, id)
    expect(before).toHaveLength(2)
    expect((await request(teacher, `/api/conversations/${id}`)).status).toBe(404)
    let entered, release, held = true
    const waiting = new Promise(resolve => { entered = resolve })
    await student.page.route('**/api/conversations/*/messages', async route => {
      if (route.request().method() !== 'POST' || !held) return route.continue()
      entered(); await new Promise(resolve => { release = resolve })
      await route.fulfill({ contentType: 'text/event-stream', body: 'event: delta\ndata: {"t":"LATE REMOVED TURN"}\n\nevent: done\ndata: {}\n\n' }).catch(() => {})
    })
    await ask(student.page, 'PT106 pending boundary'); await waiting
    if (boundary === 'leave') {
      await student.page.getByRole('button', { name: '방 나가기…', exact: true }).click()
      await student.page.getByRole('button', { name: '나가기', exact: true }).click()
      await expect(student.page.getByRole('heading', { name: '오늘은 무엇을 공부할까요?' })).toBeVisible()
    } else if (boundary === 'remove') {
      const roster = JSON.parse((await request(teacher, `/api/sessions/${room.id}/participants`)).text)
      const pupil = roster.find(p => p.role === 'STUDENT')
      expect((await request(teacher, `/api/sessions/${room.id}/participants/${pupil.userId}`, 'DELETE')).status).toBe(204)
      await expect(student.page.getByText(/방에서 내보냈어요/)).toBeVisible()
    } else {
      expect((await request(teacher, `/api/sessions/${room.id}/end`, 'POST')).status).toBe(200)
      await expect(student.page.getByText(/방은 닫혔어요/)).toBeVisible()
    }
    held = false; release()
    await expect(student.page.locator('body')).not.toContainText('LATE REMOVED TURN')
    const denied = boundary === 'end' ? 410 : 404
    expect((await request(student, `/api/conversations/${id}/messages`, 'POST', { content: 'PT106 denied', clientMessageId: crypto.randomUUID() })).status).toBe(denied)
    expect((await request(student, '/api/conversations', 'POST', { sessionId: room.id })).status).toBe(denied)
    // Existing contract allows the owner to read the old conversation after leaving/ending.
    // Revoking further chat is not deletion, and this test introduces no new access policy.
    expect(await rows(student.page, id)).toEqual(before)
    await student.page.goto(new URL(`/conversations/${id}`, student.page.url()).href)
    await expect(student.page.locator('.chat-list')).toContainText(`PT106 before ${boundary}`)
    await student.page.getByPlaceholder(/이어서 질문하세요/).fill('PT106 denied from record')
    await student.page.getByRole('button', { name: '보내기', exact: true }).click()
    if (boundary === 'end') await expect(student.page.getByRole('alert')).toBeVisible()
    else await expect(student.page.getByText(/없는 대화예요/)).toBeVisible()
    expect(await rows(student.page, id)).toEqual(before)
  })
}
