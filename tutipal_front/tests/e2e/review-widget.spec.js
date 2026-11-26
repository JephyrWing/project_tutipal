import { test, expect, login } from './fixtures.js'
import { installSyntheticAnalysis, startSyntheticAnalysis } from './synthetic-analysis.js'

async function widget(app) {
  await expect.poll(() => app.application.windows().some(p => p.url().endsWith('/widget'))).toBe(true)
  const page = app.application.windows().find(p => p.url().endsWith('/widget'))
  await expect(page.getByText('꺼짐', { exact: true })).toBeVisible()
  await page.clock.install()
  await installSyntheticAnalysis(app)
  // Synthetic capture only: this suite must never read the user's desktop.
  await app.application.evaluate(({ ipcMain }) => {
    globalThis.reviewCapture = { count: 0, gone: false }
    ipcMain.removeHandler('screen-capture')
    ipcMain.handle('screen-capture', () => {
      globalThis.reviewCapture.count++
      return globalThis.reviewCapture.gone ? { status: 'gone' }
        : { status: 'ok', changed: false, image: 'synthetic-no-real-screen' }
    })
    // The lifecycle check must use the same synthetic source, never enumerate the desktop.
    ipcMain.removeHandler('screen-target-status')
    ipcMain.handle('screen-target-status', () => ({ status: globalThis.reviewCapture.gone ? 'gone' : 'ok' }))
  })
  return page
}
async function start(app) {
  await startSyntheticAnalysis(app)
}
async function deleteAll(app) {
  await app.page.goto(new URL('/settings', app.page.url()).href)
  await app.page.getByRole('tab', { name: '복습 기록', exact: true }).click()
  await app.page.getByRole('button', { name: '기록 전체 삭제', exact: true }).click()
  await app.page.getByRole('button', { name: '삭제', exact: true }).click()
  await expect(app.page.getByText('모두 지웠어요', { exact: true })).toBeVisible()
}
const fulfill = (route, body, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })

test('창 종료 후 추가 캡처 중단·재선택 후 동일 화면의 503 재시도', async ({ apps }) => {
  const app = await apps.launch(); await login(app, 8)
  const page = await widget(app)
  await app.application.evaluate(() => { globalThis.reviewCapture.gone = true })
  await start(app)
  await expect(page.getByText('창 종료', { exact: true })).toBeVisible()
  await page.clock.fastForward(120_000)
  expect(await app.application.evaluate(() => globalThis.reviewCapture.count)).toBe(1)
  let descriptions = 0
  await page.route('**/api/screen', r => ++descriptions === 1 ? fulfill(r, { message: 'busy' }, 503) : fulfill(r, { text: '합성 요약' }))
  await page.route('**/api/review/observe', r => fulfill(r, { recorded: true, suggestion: null }))
  await app.application.evaluate(() => { globalThis.reviewCapture.gone = false })
  await start(app)
  await expect.poll(() => descriptions).toBe(1)
  await page.clock.fastForward(40_000)
  await expect(page.getByText('합성 요약', { exact: true })).toBeVisible()
  expect(descriptions).toBe(2)
})

test('위젯 제안을 일시정지·분석 끄기·실제 전체삭제 후 정리', async ({ apps }) => {
  const app = await apps.launch(); await login(app, 8)
  const page = await widget(app)
  await page.route('**/api/screen', r => fulfill(r, { text: '합성 요약' }))
  await page.route('**/api/review/observe', r => fulfill(r, { recorded: true, suggestion: { id: 123, summary: '합성 요약' } }))
  await start(app)
  await expect(page.getByRole('button', { name: '설명 듣기', exact: true })).toBeVisible()
  await page.getByRole('button', { name: '일시정지', exact: true }).click()
  await expect(page.getByRole('button', { name: '설명 듣기', exact: true })).toHaveCount(0)
  await page.getByRole('button', { name: '다시 시작', exact: true }).click()
  await expect(page.getByRole('button', { name: '설명 듣기', exact: true })).toBeVisible()
  await deleteAll(app) // Real Spring/DB DELETE and real Main/preload acknowledgement.
  await expect(page.getByRole('button', { name: '설명 듣기', exact: true })).toHaveCount(0)
  await page.clock.fastForward(40_000)
  await expect(page.getByRole('button', { name: '설명 듣기', exact: true })).toBeVisible()
  await app.page.evaluate(() => window.desktop.setScreenSettings({ enabled: false, sourceId: '', sourceName: '' }))
  await expect(page.getByText('꺼짐', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '설명 듣기', exact: true })).toHaveCount(0)
})

test('수집 응답이 삭제보다 늦어도 이전 제안·후속 요약 수집을 되살리지 않음', async ({ apps }) => {
  const app = await apps.launch(); await login(app, 9)
  const page = await widget(app)
  await page.route('**/api/screen', r => fulfill(r, { text: '합성 요약' }))
  let release
  const gate = new Promise(resolve => { release = resolve })
  let observations = 0
  let responded = false
  await page.route('**/api/review/observe', async r => {
    observations++; await gate
    await fulfill(r, { recorded: true, suggestion: { id: 999, summary: '늦은 제안' } })
    responded = true
  })
  await start(app)
  await expect.poll(() => observations).toBe(1)
  try { await deleteAll(app) } finally { release() }
  await expect.poll(() => responded).toBe(true)
  await expect(page.getByRole('button', { name: '설명 듣기', exact: true })).toHaveCount(0)
  // Let the pending response be processed before checking no second write was issued.
  await expect.poll(() => page.evaluate(() => document.querySelectorAll('.widget-suggest').length)).toBe(0)
  expect(observations).toBe(1)
})

test('삭제 실패도 이전 제안을 비우고 이후 새 분석을 허용', async ({ apps }) => {
  const app = await apps.launch(); await login(app, 9)
  const page = await widget(app)
  await page.route('**/api/screen', r => fulfill(r, { text: '합성 요약' }))
  await page.route('**/api/review/observe', r => fulfill(r, { recorded: true, suggestion: { id: 123, summary: '합성 요약' } }))
  await start(app)
  await expect(page.getByRole('button', { name: '설명 듣기', exact: true })).toBeVisible()
  await app.page.route('**/api/review/items', r => r.request().method() === 'DELETE'
    ? fulfill(r, { message: '합성 삭제 실패' }, 503) : r.continue())
  await app.page.goto(new URL('/settings', app.page.url()).href)
  await app.page.getByRole('tab', { name: '복습 기록', exact: true }).click()
  await app.page.getByRole('button', { name: '기록 전체 삭제', exact: true }).click()
  await app.page.getByRole('button', { name: '삭제', exact: true }).click()
  await expect(app.page.getByRole('alert')).toContainText('합성 삭제 실패')
  await expect(page.getByRole('button', { name: '설명 듣기', exact: true })).toHaveCount(0)
  await page.clock.fastForward(40_000)
  await expect(page.getByRole('button', { name: '설명 듣기', exact: true })).toBeVisible()
})
