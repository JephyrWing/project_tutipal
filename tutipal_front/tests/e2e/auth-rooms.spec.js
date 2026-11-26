import { test, expect, login, createRoom, joinRoom } from './fixtures.js'

test('익명 보호경로 차단과 Electron 보안·브리지 경계', async ({ apps, request }) => {
  const guest = await apps.launch()
  const origin = new URL(guest.page.url()).origin
  await guest.page.goto(origin + '/sessions/999999')
  await expect(guest.page).toHaveURL(/\/login$/)
  expect((await request.get(origin + '/api/users/me')).status()).toBe(403)
  const preferences = await guest.application.evaluate(({ BrowserWindow, app }) => ({
    noSandbox: app.commandLine.hasSwitch('no-sandbox'),
    windows: BrowserWindow.getAllWindows().map(window => {
      const p = window.webContents.getLastWebPreferences()
      return { sandbox: p.sandbox, contextIsolation: p.contextIsolation, nodeIntegration: p.nodeIntegration, webSecurity: p.webSecurity }
    }),
  }))
  expect(preferences.noSandbox).toBe(false)
  expect(preferences.windows.length).toBeGreaterThan(0)
  for (const preferencesOfWindow of preferences.windows) {
    expect(preferencesOfWindow).toEqual({ sandbox: true, contextIsolation: true, nodeIntegration: false, webSecurity: true })
  }
})

test('실제 로그인·교사 방 생성·학생 참여·권한 거절·로그아웃', async ({ apps }) => {
  const teacher = await apps.launch()
  const student = await apps.launch()
  await login(teacher, 1)
  await login(student, 2)
  const room = await createRoom(teacher, 'E2E 역할·로그아웃 검사')
  expect(await student.page.evaluate(async id => (await fetch(`/api/sessions/${id}`)).status, room.id)).toBe(404)
  await joinRoom(student, room.code)
  await expect(student.page.getByRole('heading', { name: /E2E 역할·로그아웃 검사/ })).toBeVisible()
  await expect(teacher.page.getByRole('tab', { name: '주제 설정', exact: true })).toBeVisible()
  await expect(student.page.getByRole('tab', { name: '주제 설정', exact: true })).toHaveCount(0)
  const forbidden = await student.page.evaluate(async id => {
    const csrf = await (await fetch('/api/auth/csrf')).json()
    return (await fetch(`/api/sessions/${id}/settings`, {
      method: 'PUT', headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token },
      body: JSON.stringify({ topic: '수학', difficulty: 2, hideAnswers: true, preferMaterials: true }),
    })).status
  }, room.id)
  expect(forbidden).toBe(403)
  expect(await student.page.evaluate(() => document.cookie)).not.toContain('TUTIPAL_SESSION')
  const cookieMetadata = await student.application.evaluate(async ({ session }) =>
    (await session.defaultSession.cookies.get({ url: 'http://127.0.0.1:28080' }))
      .filter(cookie => cookie.name === 'TUTIPAL_SESSION').map(cookie => ({ httpOnly: cookie.httpOnly })))
  expect(cookieMetadata).toEqual([{ httpOnly: true }])
  await student.page.getByRole('button', { name: '로그아웃', exact: true }).click()
  await expect(student.page).toHaveURL(/\/login$/)
  expect(await student.page.evaluate(async () => (await fetch('/api/users/me')).status)).toBe(401)
  await student.page.goto(new URL(`/sessions/${room.id}`, student.page.url()).href)
  await expect(student.page).toHaveURL(/\/login$/)
  expect(await teacher.page.evaluate(async () => (await fetch('/api/users/me')).status)).toBe(200)
})

test('없는 초대 코드 거절과 닫힌 방 재참여 차단', async ({ apps }) => {
  const teacher = await apps.launch()
  const student = await apps.launch()
  await login(teacher, 3)
  await login(student, 4)
  await joinRoom(student, 'ZZZZZZ')
  await expect(student.page.getByText('코드에 맞는 방이 없어요', { exact: true })).toBeVisible()
  const room = await createRoom(teacher, 'E2E 종료된 방 검사')
  await student.page.goto(new URL('/sessions/join', student.page.url()).href)
  await student.page.getByRole('textbox', { name: '초대 코드 6자리' }).fill(room.code)
  await student.page.getByRole('button', { name: '입장', exact: true }).click()
  await expect(student.page.getByRole('heading', { name: /E2E 종료된 방 검사/ })).toBeVisible()
  await teacher.page.getByRole('button', { name: '방 닫기…', exact: true }).click()
  await teacher.page.getByRole('button', { name: '방 닫기', exact: true }).click()
  await expect(teacher.page.getByRole('heading', { name: '오늘은 무엇을 공부할까요?' })).toBeVisible()
  await student.page.reload()
  await expect(student.page.getByText('「E2E 종료된 방 검사」 방은 닫혔어요', { exact: true })).toBeVisible()
  await student.page.getByRole('link', { name: '홈으로', exact: true }).click()
  await joinRoom(student, room.code)
  await expect(student.page.getByText('이미 닫힌 방이에요', { exact: true })).toBeVisible()
})
