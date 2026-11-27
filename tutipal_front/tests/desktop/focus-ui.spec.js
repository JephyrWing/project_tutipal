import { test, expect } from './focus-fixtures.js'

const writes = f => f.requests.filter(r => r.route === '/api/focus/consent' && r.method !== 'GET')
const card = f => f.page.getByRole('region', { name: '집중도 체크', exact: true })
const dialog = f => f.page.getByRole('dialog', { name: '집중도 기록 저장 동의' })
async function enter(f) {
  // Match the collector fixture's epoch; change Date only, keeping real browser timers running.
  await f.page.clock.setSystemTime(new Date(0))
  await f.page.goto(new URL('/sessions/1', f.page.url()).href)
  await expect(f.page.getByRole('heading', { name: /PT30 집중도 시험 수업/ })).toBeVisible()
  await expect(card(f)).toBeVisible()
}
async function start(f) {
  await card(f).getByRole('button', { name: '시작', exact: true }).click()
  if (!f.control.consented) await dialog(f).getByRole('button', { name: '동의하고 시작' }).click()
  await expect(card(f).getByText('수집 중', { exact: true })).toBeVisible()
}

test('UI first consent cancel never starts a probe or stores consent', async ({ focus: f }) => {
  await enter(f)
  await card(f).getByRole('button', { name: '시작', exact: true }).click()
  await expect(dialog(f)).toContainText('창 제목, 화면 내용, 입력한 글자, 파일 경로는 모으지 않아요')
  await expect(dialog(f)).toContainText('30일')
  await expect(dialog(f)).toContainText('31일')
  await dialog(f).getByRole('button', { name: '취소', exact: true }).click()
  await expect(dialog(f)).toHaveCount(0)
  await f.minute()
  expect(writes(f)).toHaveLength(0)
  expect(f.posts).toHaveLength(0)
  expect(await f.snapshot()).toMatchObject({ starts: 0, idleReads: 0, timers: 0 })
})

test('UI waits for successful consent save before starting collection', async ({ focus: f }) => {
  f.control.holdConsent = true
  await enter(f)
  await card(f).getByRole('button', { name: '시작', exact: true }).click()
  await dialog(f).getByRole('button', { name: '동의하고 시작' }).click()
  await expect.poll(() => writes(f).length).toBe(1)
  await expect.poll(() => writes(f)[0]).toMatchObject({ method: 'PUT', body: { version: '2026-11-26' } })
  await expect(dialog(f).getByRole('button', { name: '동의하고 시작' })).toBeDisabled()
  expect(await f.snapshot()).toMatchObject({ starts: 0, timers: 0 })
  expect(f.posts).toHaveLength(0)
  f.releaseConsent()
  await expect(card(f).getByText('수집 중', { exact: true })).toBeVisible()
  expect(await f.snapshot()).toMatchObject({ starts: 1, state: { sessionId: 1, status: 'collecting' } })
})

test('UI consent save failure keeps collection off and allows an explicit retry', async ({ focus: f }) => {
  f.control.saveError = 503
  await enter(f)
  await card(f).getByRole('button', { name: '시작', exact: true }).click()
  await dialog(f).getByRole('button', { name: '동의하고 시작' }).click()
  await expect(card(f)).toContainText('시험 동의 저장 실패')
  expect(await f.snapshot()).toMatchObject({ starts: 0, timers: 0 })
  expect(f.posts).toHaveLength(0)
  f.control.saveError = 0
  await dialog(f).getByRole('button', { name: '동의하고 시작' }).click()
  await expect(card(f).getByText('수집 중', { exact: true })).toBeVisible()
  expect(writes(f).map(r => r.method)).toEqual(['PUT', 'PUT'])
})

test('UI stop preserves consent but restarting still requires the start button', async ({ focus: f }) => {
  f.control.focusState = 'FOCUS'
  await enter(f)
  await start(f)
  await f.minute('fixture.exe')
  await expect(card(f).getByText('집중', { exact: true })).toBeVisible()
  await card(f).getByRole('button', { name: '중지', exact: true }).click()
  await expect(card(f).getByRole('button', { name: '시작', exact: true })).toBeEnabled()
  expect(await f.snapshot()).toMatchObject({ starts: 1, kills: 1, timers: 0 })
  await f.minute('discarded.exe')
  expect(f.posts).toHaveLength(1)
  expect(writes(f).map(r => r.method)).toEqual(['PUT'])
  await start(f)
  expect(await f.snapshot()).toMatchObject({ starts: 2, state: { status: 'collecting' } })
  await expect(dialog(f)).toHaveCount(0)
})

test('UI withdrawal cancel keeps collection; a failed delete stops it and remains retryable', async ({ focus: f }) => {
  await enter(f)
  await start(f)
  f.page.once('dialog', d => d.dismiss())
  await card(f).getByRole('button', { name: '동의 철회', exact: true }).click()
  expect(writes(f).map(r => r.method)).toEqual(['PUT'])
  expect(await f.snapshot()).toMatchObject({ starts: 1, kills: 0, state: { status: 'collecting' } })
  f.control.withdrawError = 503
  f.page.once('dialog', d => d.accept())
  await card(f).getByRole('button', { name: '동의 철회', exact: true }).click()
  await expect(card(f)).toContainText('시험 동의 철회 실패')
  expect(await f.snapshot()).toMatchObject({ kills: 1, timers: 0, state: { status: 'off' } })
  expect(f.control.consented).toBe(true)
  f.control.withdrawError = 0
  f.page.once('dialog', d => d.accept())
  await card(f).getByRole('button', { name: '동의 철회', exact: true }).click()
  await expect(card(f)).toContainText('동의를 철회하고 기록을 지웠어요')
  await expect(card(f).getByRole('button', { name: '동의 철회', exact: true })).toHaveCount(0)
  expect(writes(f).map(r => r.method)).toEqual(['PUT', 'DELETE', 'DELETE'])
  await card(f).getByRole('button', { name: '시작', exact: true }).click()
  await expect(dialog(f)).toBeVisible()
  expect(await f.snapshot()).toMatchObject({ starts: 1, timers: 0 })
})

test('UI consent read failure leaves start disabled without probing', async ({ focus: f }) => {
  f.control.consentError = 503
  await enter(f)
  await expect(card(f).getByRole('button', { name: '시작', exact: true })).toBeDisabled()
  await expect.poll(() => f.requests.filter(r => r.route === '/api/focus/consent').length).toBe(1)
  await f.minute()
  expect(await f.snapshot()).toMatchObject({ starts: 0, timers: 0, idleReads: 0 })
  expect(writes(f)).toHaveLength(0)
})

test('UI leaving the class by SPA navigation stops collection; returning never auto-starts', async ({ focus: f }) => {
  await enter(f)
  await start(f)
  await f.page.getByRole('link', { name: '홈', exact: true }).click()
  await expect(f.page.getByRole('heading', { name: '오늘은 무엇을 공부할까요?' })).toBeVisible()
  await expect.poll(async () => (await f.snapshot()).state.status).toBe('off')
  expect(await f.snapshot()).toMatchObject({ starts: 1, kills: 1, timers: 0 })
  await f.minute()
  expect(f.posts).toHaveLength(0)
  await enter(f)
  await expect(card(f).getByRole('button', { name: '시작', exact: true })).toBeEnabled()
  expect(await f.snapshot()).toMatchObject({ starts: 1, state: { status: 'off' } })
  await expect(dialog(f)).toHaveCount(0)
})

test('UI expired consent requires accepting the current version again', async ({ focus: f }) => {
  f.control.version = 'old-test-version'
  await enter(f)
  await card(f).getByRole('button', { name: '시작', exact: true }).click()
  await expect(dialog(f)).toBeVisible()
  expect(await f.snapshot()).toMatchObject({ starts: 0, timers: 0 })
  await dialog(f).getByRole('button', { name: '동의하고 시작' }).click()
  await expect(card(f).getByText('수집 중', { exact: true })).toBeVisible()
  expect(writes(f)[0].body).toEqual({ version: '2026-11-26' })
})
