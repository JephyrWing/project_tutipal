import { test, expect, login } from './fixtures.js'
import { writeFile } from 'node:fs/promises'

async function expiredScreen(app) {
  await expect(app.page).toHaveURL(/\/login$/)
  await expect(app.page.getByRole('alert')).toContainText('로그인이 만료됐어요')
  await expect(app.page.getByRole('navigation', { name: '메인 메뉴' })).toHaveCount(0)
}

async function expiryEvidence(testInfo, response, trigger) {
  const evidence = testInfo.outputPath('auth-expiry-boundary.json')
  await writeFile(evidence, JSON.stringify({ trigger, path: new URL(response.url()).pathname,
    method: response.request().method(), status: response.status() }))
  await testInfo.attach('auth-expiry-boundary.json', { path: evidence, contentType: 'application/json' })
}

test('서버 503은 인증 만료와 구분하고 다시 연결로 복구한다', async ({ apps }) => {
  const app = await apps.launch()
  let unavailable = true
  await app.page.route('**/api/users/me', route => unavailable
    ? route.fulfill({ status: 503, json: { message: 'synthetic unavailable' } }) : route.continue())
  await app.page.goto(new URL('/', app.page.url()).href)
  await expect(app.page.getByRole('heading', { name: '연결을 확인해 주세요' })).toBeVisible()
  await expect(app.page.getByRole('alert')).toContainText('서버가 잠시 응답하지 않아요')
  await expect(app.page.getByText('로그인이 만료됐어요', { exact: false })).toHaveCount(0)
  unavailable = false
  await app.page.getByRole('button', { name: '다시 연결', exact: true }).click()
  await expect(app.page.getByRole('button', { name: '개발용 로그인 (내 PC 전용)', exact: true })).toBeVisible()
})

test('연결 실패는 재로그인 안내 대신 연결 확인과 재시도를 제공한다', async ({ apps }) => {
  const app = await apps.launch()
  let offline = true
  await app.page.route('**/api/users/me', route => offline ? route.abort('failed') : route.continue())
  await app.page.goto(new URL('/', app.page.url()).href)
  await expect(app.page.getByRole('alert')).toContainText('인터넷 연결을 확인')
  offline = false
  await app.page.getByRole('button', { name: '다시 연결', exact: true }).click()
  await expect(app.page.getByRole('button', { name: '개발용 로그인 (내 PC 전용)', exact: true })).toBeVisible()
})

test('로그인 설정 실패는 로그인 방법 없음으로 오인하지 않고 재시도한다', async ({ apps }) => {
  const app = await apps.launch()
  let unavailable = true
  await app.page.route('**/api/auth/config', route => unavailable
    ? route.fulfill({ status: 503, json: {} }) : route.continue())
  await app.page.reload()
  await expect(app.page.getByRole('alert')).toContainText('서버가 잠시 응답하지 않아요')
  await expect(app.page.getByText('현재 서버에서 사용할 수 있는 로그인 방법이 없어요', { exact: false })).toHaveCount(0)
  unavailable = false
  await app.page.getByRole('button', { name: '다시 연결', exact: true }).click()
  await expect(app.page.getByRole('button', { name: '개발용 로그인 (내 PC 전용)', exact: true })).toBeVisible()
})

test('서버 로그아웃 실패를 완료로 숨기지 않고 같은 로그인에서 다시 시도한다', async ({ apps }) => {
  const app = await apps.launch()
  await login(app, 1)
  let unavailable = true
  await app.page.route('**/logout', route => unavailable ? route.fulfill({ status: 503, json: {} }) : route.continue())
  await app.page.getByRole('button', { name: '로그아웃', exact: true }).click()
  await expect(app.page.getByRole('alert')).toContainText('로그아웃을 완료하지 못했어요')
  await expect(app.page.getByRole('heading', { name: '오늘은 무엇을 공부할까요?' })).toBeVisible()
  expect(await app.page.evaluate(async () => (await fetch('/api/users/me')).status)).toBe(200)
  unavailable = false
  await app.page.getByRole('button', { name: '다시 로그아웃', exact: true }).click()
  await expect(app.page).toHaveURL(/\/login$/)
  expect(await app.page.evaluate(async () => (await fetch('/api/users/me')).status)).toBe(401)
})

test('만료된 테스트 쿠키의 참여 요청 401은 화면을 정리하고 재로그인을 안내한다', async ({ apps }, testInfo) => {
  const app = await apps.launch()
  // Keep background home reads from consuming the expiry before the deliberate join request.
  await app.page.route('**/api/sessions', route => route.fulfill({ status: 200, json: [] }))
  await app.page.route('**/api/sessions/ended', route => route.fulfill({ status: 200, json: [] }))
  await app.page.route('**/api/review/items', route => route.fulfill({ status: 200, json: [] }))
  await login(app, 2)
  // Only the disposable test profile is touched; no credential value is read.
  await app.application.evaluate(({ session }) => session.defaultSession.cookies.remove('http://127.0.0.1:28080', 'TUTIPAL_SESSION'))
  await app.page.getByRole('link', { name: /방 참여하기/ }).click()
  await app.page.getByRole('textbox', { name: '초대 코드 6자리' }).fill('ZZZZZZ')
  const denied = app.page.waitForResponse(response => new URL(response.url()).pathname === '/api/sessions/join'
    && response.request().method() === 'POST')
  await app.page.getByRole('button', { name: '입장', exact: true }).click()
  const response = await denied
  expect(response.status()).toBe(401)
  await expiredScreen(app)
  await expiryEvidence(testInfo, response, 'explicit-join')
})

test('지연된 홈 조회가 쿠키 삭제 뒤 401을 받으면 클릭 없이 재로그인 화면으로 이동한다', async ({ apps }, testInfo) => {
  const app = await apps.launch()
  await app.page.route('**/api/sessions', route => route.fulfill({ status: 200, json: [] }))
  await app.page.route('**/api/review/items', route => route.fulfill({ status: 200, json: [] }))
  let release, requested
  const gate = new Promise(resolve => { release = resolve })
  const entered = new Promise(resolve => { requested = resolve })
  await app.page.route('**/api/sessions/ended', async route => {
    requested()
    await gate
    await route.continue()
  })
  try {
    await login(app, 2)
    await entered
    // Forward through the real bridge/Spring only after its test cookie is gone.
    await app.application.evaluate(({ session }) => session.defaultSession.cookies.remove('http://127.0.0.1:28080', 'TUTIPAL_SESSION'))
    await expect(app.page.getByRole('link', { name: /방 참여하기/ })).toBeVisible()
    const denied = app.page.waitForResponse(response => new URL(response.url()).pathname === '/api/sessions/ended')
    release()
    const response = await denied
    expect(response.status()).toBe(401)
    await expiredScreen(app)
    await expiryEvidence(testInfo, response, 'delayed-home-read')
  } finally { release() }
})

test('개발 로그인 요청 503은 성공 화면으로 이동하지 않는다', async ({ apps }) => {
  const app = await apps.launch()
  await app.page.route('**/dev/login?*', route => route.fulfill({ status: 503, json: {} }))
  await app.page.getByRole('button', { name: '개발용 로그인 (내 PC 전용)', exact: true }).click()
  await expect(app.page).toHaveURL(/\/login$/)
  await expect(app.page.getByRole('alert')).toContainText('서버가 잠시 응답하지 않아요')
  await expect(app.page.getByRole('heading', { name: '오늘은 무엇을 공부할까요?' })).toHaveCount(0)
})
