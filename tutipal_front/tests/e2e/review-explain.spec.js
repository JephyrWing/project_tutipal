import { execFile } from 'node:child_process'
import { promisify } from 'node:util'
import { test, expect, login } from './fixtures.js'

const exec = promisify(execFile)
async function seed(app) {
  const container = process.env.E2E_DB_CONTAINER ?? `tutipal-pt24-${process.env.GITHUB_RUN_ID}`
  if (!/^(tutipal-pt24-\d+|tutipal-pt73-chat-check)$/.test(container)) throw new Error('Disposable database required')
  const uid = await app.page.evaluate(async () => (await (await fetch('/api/users/me')).json()).id)
  if (!Number.isSafeInteger(uid) || uid < 1) throw new Error('Invalid synthetic user')
  await exec('docker', ['exec', container, 'psql', '-U', 'tutipal_e2e', '-d', 'tutipal_e2e', '-v', 'ON_ERROR_STOP=1', '-c',
    `DELETE FROM review_items WHERE user_id=${uid}; INSERT INTO review_items(user_id,summary,embedding,embedding_model,seen_count,seen_seconds,first_seen,last_seen) VALUES (${uid}, '합성 요약: 한글 & ? # + <b>문자</b>', ('[' || repeat('0,',1023) || '0]')::vector, 'synthetic:e2e',3,600,now(),now());`], { windowsHide: true })
  return app.page.evaluate(async () => (await (await fetch('/api/review/history')).json()).items[0])
}
async function openReview(app, query = '') {
  await app.page.goto(new URL(`/review${query}`, app.page.url()).href)
}
async function remove(app, id) {
  return app.page.evaluate(async id => {
    const csrf = await (await fetch('/api/auth/csrf')).json()
    return (await fetch(`/api/review/items/${id}`, { method: 'DELETE', headers: { [csrf.headerName]: csrf.token } })).status
  }, id)
}
const done = text => `event: delta\ndata: ${JSON.stringify({ t: text })}\n\nevent: done\ndata: {}\n\n`
async function mockChat(app, reply = done('모의 설명 답변')) {
  const calls = []
  await app.page.route('**/api/tutor/chat', async route => {
    calls.push(route.request().postDataJSON())
    await route.fulfill({ contentType: 'text/event-stream', body: typeof reply === 'function' ? reply(calls.length) : reply })
  })
  return calls
}

test('목록에서 정확한 요약을 한 번 전송하고 특수문자를 글자로 표시·후속 질문', async ({ apps }) => {
  const app = await apps.launch(); await login(app, 1)
  const item = await seed(app); const calls = await mockChat(app)
  await openReview(app)
  await app.page.getByRole('button', { name: '▶ 설명 듣기', exact: true }).evaluate(b => { b.click(); b.click() })
  await expect(app.page.locator('.bubble-ai')).toHaveText('모의 설명 답변')
  expect(calls).toHaveLength(1)
  expect(calls[0].messages.at(-1).content).toContain(item.summary)
  expect(calls[0].sessionId).toBeUndefined()
  await expect(app.page.locator('.bubble-user b')).toHaveCount(0)
  await expect(app.page).not.toHaveURL(/ask=/)
  await app.page.getByRole('textbox').fill('예시도 알려줘')
  await app.page.getByRole('button', { name: '보내기', exact: true }).click()
  await expect.poll(() => calls.length).toBe(2)
})

test('다른 창에서 삭제된 오래된 목록 기록은 질문 전송 차단', async ({ apps }) => {
  const app = await apps.launch(); await login(app, 2)
  const item = await seed(app); const calls = await mockChat(app)
  await openReview(app); await expect(app.page.locator('.review-row')).toHaveCount(1)
  expect(await remove(app, item.id)).toBe(204)
  await app.page.getByRole('button', { name: '▶ 설명 듣기', exact: true }).click()
  await expect(app.page.getByRole('alert')).toContainText('복습 기록을 찾을 수 없어요')
  expect(calls).toHaveLength(0)
  await expect(app.page).toHaveURL(/\/review/)
})

test('위젯 IPC 본인 기록 진입·타인/삭제 기록 거절', async ({ apps }) => {
  const owner = await apps.launch(); await login(owner, 3)
  const item = await seed(owner); const calls = await mockChat(owner)
  await expect.poll(() => owner.application.windows().some(p => p.url().endsWith('/widget'))).toBe(true)
  const widget = owner.application.windows().find(p => p.url().endsWith('/widget'))
  await widget.evaluate(id => window.desktop.openReview(id), item.id)
  await expect(owner.page.locator('.bubble-ai')).toHaveText('모의 설명 답변')
  expect(calls).toHaveLength(1)
  const other = await apps.launch(); await login(other, 4)
  expect(await other.page.evaluate(async () => (await (await fetch('/api/users/me')).json()).id)).not.toBe(
    await owner.page.evaluate(async () => (await (await fetch('/api/users/me')).json()).id))
  const otherCalls = await mockChat(other)
  await openReview(other, `?explain=${item.id}`)
  await expect(other.page.getByRole('alert')).toContainText('복습 기록을 찾을 수 없어요')
  expect(otherCalls).toHaveLength(0)
  expect(await remove(owner, item.id)).toBe(204)
  await openReview(owner, `?explain=${item.id}`)
  await expect(owner.page.getByRole('alert')).toContainText('복습 기록을 찾을 수 없어요')
  expect(calls).toHaveLength(1)
})

test('HTTP 장애 재시도와 빠른 중복 전송 방지', async ({ apps }) => {
  const app = await apps.launch(); await login(app, 7)
  await seed(app)
  let count = 0
  await app.page.route('**/api/tutor/chat', r => {
    count++
    return r.fulfill(count === 1 ? { status: 503, contentType: 'application/json', body: '{"message":"합성 HTTP 장애"}' }
      : { contentType: 'text/event-stream', body: done('복구 답변') })
  })
  await openReview(app)
  await app.page.getByRole('button', { name: '▶ 설명 듣기', exact: true }).click()
  await expect(app.page.locator('.bubble-error')).toContainText('합성 HTTP 장애')
  await app.page.getByRole('button', { name: '답변 다시 시도', exact: true }).evaluate(b => { b.click(); b.click() })
  await expect(app.page.locator('.bubble-ai')).toHaveText('복구 답변')
  expect(count).toBe(2)
  await expect(app.page.locator('.bubble-user')).toHaveCount(1)
})

test('응답 대기 중 중지하면 늦은 답변이 화면을 덮지 않고 다음 질문 가능', async ({ apps }) => {
  const app = await apps.launch(); await login(app, 8)
  await seed(app)
  let release
  const gate = new Promise(resolve => { release = resolve })
  let count = 0
  await app.page.route('**/api/tutor/chat', async r => {
    count++
    if (count === 1) await gate
    await r.fulfill({ contentType: 'text/event-stream', body: done(count === 1 ? '늦은 답변' : '새 답변') }).catch(() => {})
  })
  await openReview(app)
  await app.page.getByRole('button', { name: '▶ 설명 듣기', exact: true }).click()
  await expect.poll(() => count).toBe(1)
  await app.page.getByRole('button', { name: '중지', exact: true }).click()
  await expect(app.page.locator('.bubble-error')).toHaveText('답변을 중지했어요')
  release()
  await app.page.getByRole('textbox').fill('새 질문')
  await app.page.getByRole('button', { name: '보내기', exact: true }).click()
  await expect(app.page.locator('.bubble-ai').last()).toHaveText('새 답변')
  await expect(app.page.getByText('늦은 답변', { exact: true })).toHaveCount(0)
})

test('기록 조회 일시 실패 후 같은 기록을 다시 조회해서 설명', async ({ apps }) => {
  const app = await apps.launch(); await login(app, 5)
  const item = await seed(app); const calls = await mockChat(app)
  await app.page.route(`**/api/review/items/${item.id}`, r => r.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ message: '합성 조회 실패' }) }), { times: 1 })
  await openReview(app, `?explain=${item.id}`)
  await expect(app.page.getByRole('alert')).toContainText('합성 조회 실패')
  expect(calls).toHaveLength(0)
  await app.page.getByRole('button', { name: '설명 다시 시도', exact: true }).click()
  await expect(app.page.locator('.bubble-ai')).toHaveText('모의 설명 답변')
  expect(calls).toHaveLength(1)
})

for (const [name, reply] of [
  ['AI 오류', 'event: error\ndata: {"message":"합성 AI 오류"}\n\n'],
  ['스트림 중단', 'event: delta\ndata: {"t":"미완성"}\n\n'],
]) test(`${name} 후 같은 질문 재시도·실패한 질문 중복 없음`, async ({ apps }) => {
  const app = await apps.launch(); await login(app, 6)
  await seed(app); const calls = await mockChat(app, n => n === 1 ? reply : done('재시도 성공'))
  await openReview(app)
  await app.page.getByRole('button', { name: '▶ 설명 듣기', exact: true }).click()
  await expect(app.page.locator('.bubble-error')).toBeVisible()
  await app.page.getByRole('button', { name: '답변 다시 시도', exact: true }).click()
  await expect(app.page.locator('.bubble-ai').last()).toHaveText('재시도 성공')
  expect(calls).toHaveLength(2)
  expect(calls[1].messages).toEqual(calls[0].messages)
  await expect(app.page.locator('.bubble-user')).toHaveCount(1)
})


