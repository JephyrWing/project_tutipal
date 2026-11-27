import { test, expect, login } from './fixtures.js'

async function open(app) {
  await app.page.goto(new URL('/chat', app.page.url()).href)
  await expect(app.page.getByRole('heading', { name: '도우미 챗봇' })).toBeVisible()
}
async function ask(page, text) {
  await page.getByRole('textbox', { name: '질문', exact: true }).fill(text)
  await page.getByRole('button', { name: '보내기', exact: true }).click()
}
async function rows(page, id) {
  return page.evaluate(async id => (await (await fetch(`/api/conversations/${id}/messages`)).json()).items, id)
}
async function idOf(page) {
  return (await page.getByRole('link', { name: '이 대화 기록 열기' }).getAttribute('href')).split('/').at(-1)
}

test('PT124 personal entry persists once and continues the same conversation in records', async ({ apps }) => {
  const app = await apps.launch(); await login(app, 1); await open(app)
  let creates = 0, posts = 0
  app.page.on('request', r => {
    if (r.method() !== 'POST') return
    if (new URL(r.url()).pathname === '/api/conversations') creates++
    if (/\/api\/conversations\/\d+\/messages$/.test(new URL(r.url()).pathname)) posts++
  })
  await app.page.getByRole('textbox', { name: '질문', exact: true }).fill('PT106 개인 진입 첫 질문')
  await app.page.locator('form.chat-input').evaluate(form => { form.requestSubmit(); form.requestSubmit() })
  await expect(app.page.getByRole('status')).toContainText('기록에 저장')
  const id = await idOf(app.page)
  expect(creates).toBe(1); expect(posts).toBe(1)
  expect((await rows(app.page, id)).length).toBe(2)
  await app.page.getByRole('link', { name: '이 대화 기록 열기' }).click()
  await expect(app.page).toHaveURL(new RegExp(`/conversations/${id}$`))
  await expect(app.page.locator('.chat-list')).toContainText('PT106 개인 진입 첫 질문')
  await app.page.getByPlaceholder(/이어서 질문하세요/).fill('PT106 기록에서 이어가기')
  await app.page.getByRole('button', { name: '보내기', exact: true }).click()
  await expect(app.page.locator('.chat-list')).toContainText('PT106 reply n=3')
  await expect.poll(async () => (await rows(app.page, id)).length).toBe(4)
  await app.page.reload()
  await expect(app.page.locator('.chat-list')).toContainText('PT106 기록에서 이어가기')
})

test('PT124 lost saved event retries the same key without a second stored pair', async ({ apps }) => {
  const app = await apps.launch(); await login(app, 1); await open(app)
  const keys = []; let first = true
  await app.page.route('**/api/conversations/*/messages', async route => {
    if (route.request().method() !== 'POST') return route.continue()
    keys.push(route.request().postDataJSON().clientMessageId)
    const response = await route.fetch()
    const body = await response.text()
    if (first) { first = false; await route.fulfill({ response, body: body.split('event: saved')[0] }); }
    else await route.fulfill({ response, body })
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
  const app = await apps.launch(); await login(app, 1); await open(app)
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
  await login(app, 2); release(); await open(app)
  await expect(app.page.locator('.chat-list')).not.toContainText('PT106 이전 계정 질문')
  await expect(app.page.getByRole('link', { name: '이 대화 기록 열기' })).toHaveCount(0)
  const status = await app.page.evaluate(async id => (await fetch(`/api/conversations/${id}`)).status, id)
  expect(status).toBe(404)
})

test('PT124 stop during conversation creation reuses the row and does not send automatically', async ({ apps }) => {
  const app = await apps.launch(); await login(app, 1); await open(app)
  let release, entered, creates = 0, posts = 0
  const waiting = new Promise(resolve => { entered = resolve })
  app.page.on('request', r => { if (r.method() === 'POST' && /\/api\/conversations\/\d+\/messages$/.test(new URL(r.url()).pathname)) posts++ })
  await app.page.route('**/api/conversations', async route => {
    if (route.request().method() !== 'POST') return route.continue()
    creates++; const response = await route.fetch()
    entered(); await new Promise(resolve => { release = resolve })
    await route.fulfill({ response })
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
