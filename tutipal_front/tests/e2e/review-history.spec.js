import { execFile } from 'node:child_process'
import { promisify } from 'node:util'
import { test, expect, login } from './fixtures.js'

const exec = promisify(execFile)

// Only synthetic rows in the isolated E2E database. No product-only seed API is introduced.
async function seed(app, count) {
  const container = process.env.E2E_DB_CONTAINER ?? `tutipal-pt24-${process.env.GITHUB_RUN_ID}`
  if (!/^(tutipal-pt24-\d+|tutipal-pt54-review-check)$/.test(container)) throw new Error('Disposable test container required')
  const id = await app.page.evaluate(async () => (await (await fetch('/api/users/me')).json()).id)
  if (!Number.isSafeInteger(id) || id < 1 || !Number.isInteger(count) || count < 0 || count > 110) throw new Error('Invalid synthetic seed')
  const sql = `DELETE FROM review_items WHERE user_id=${id};
    INSERT INTO review_items(user_id,summary,embedding,embedding_model,seen_count,seen_seconds,first_seen,last_seen)
    SELECT ${id}, '조회 검사 ' || lpad(n::text,3,'0'), ('[' || repeat('0,',1023) || '0]')::vector,
    'synthetic:e2e', 3, 125, '2026-11-20T00:00:00Z', '2026-11-20T00:00:00Z'::timestamptz + n * interval '1 second'
    FROM generate_series(1,${count}) n;`
  await exec('docker', ['exec', container, 'psql', '-U', 'tutipal_e2e', '-d', 'tutipal_e2e', '-v', 'ON_ERROR_STOP=1', '-c', sql], { windowsHide: true })
}

async function review(app) {
  await app.page.goto(new URL('/review', app.page.url()).href)
}

async function settings(app) {
  await app.page.goto(new URL('/settings', app.page.url()).href)
  await app.page.getByRole('tab', { name: '복습 기록', exact: true }).click()
}

test('100건 이후까지 본인 기록 페이지·전체개수·날짜를 조회하고 다른 사용자를 격리', async ({ apps }, testInfo) => {
  const owner = await apps.launch()
  const other = await apps.launch()
  await login(owner, 4)
  await login(other, 5)
  await seed(owner, 105)
  await seed(other, 3)
  await review(owner)
  await expect(owner.page.getByText('전체 105개 · 최근 본 순', { exact: true })).toBeVisible()
  await expect(owner.page.locator('.review-row')).toHaveCount(20)
  await expect(owner.page.locator('.review-text').first()).toContainText('조회 검사 105')
  await expect(owner.page.locator('time').first()).toHaveAttribute('datetime', '2026-11-20T00:01:45Z')
  await expect(owner.page.locator('.review-row').first()).toContainText('3번 봄 · 누적 2분 5초')
  await owner.page.screenshot({ path: testInfo.outputPath('review-history.png') })
  const ids = []
  for (let p = 0; p < 6; p++) {
    await expect(owner.page.getByText(`${p + 1} / 6 페이지`, { exact: true })).toBeVisible()
    await expect(owner.page.locator('.review-text').first()).toContainText(`조회 검사 ${String(105 - p * 20).padStart(3, '0')}`)
    ids.push(...await owner.page.locator('.review-text').allTextContents())
    if (p < 5) await owner.page.getByRole('button', { name: '다음', exact: true }).click()
  }
  expect(ids).toHaveLength(105)
  expect(new Set(ids).size).toBe(105)
  await expect(owner.page.getByRole('button', { name: '다음', exact: true })).toBeDisabled()
  await expect(owner.page.locator('.review-row')).toHaveCount(5)
  await expect(owner.page.locator('.review-text').last()).toContainText('조회 검사 001')
  const otherId = await other.page.evaluate(async () => (await (await fetch('/api/review/history')).json()).items[0].id)
  expect(await owner.page.evaluate(async id => (await fetch(`/api/review/items/${id}`)).status, otherId)).toBe(404)
  expect(await owner.page.evaluate(async () => (await (await fetch('/api/review/items')).json()).length)).toBe(100)
  await review(other)
  await expect(other.page.getByText('전체 3개 · 최근 본 순', { exact: true })).toBeVisible()
  await settings(owner)
  await expect(owner.page.getByText('모인 기록 105개', { exact: true })).toBeVisible()
})

test('목록과 설정의 조회 실패를 0개로 표시하지 않고 재시도', async ({ apps }) => {
  const owner = await apps.launch()
  await login(owner, 6)
  await seed(owner, 2)
  const failOnce = () => owner.page.route('**/api/review/history?*', route => route.fulfill({
    status: 503, contentType: 'application/json', body: JSON.stringify({ status: 503, message: '임시 조회 실패' }),
  }), { times: 1 })
  await failOnce()
  await review(owner)
  await expect(owner.page.getByRole('alert')).toContainText('임시 조회 실패')
  await expect(owner.page.getByText('아직 모인 내용이 없어요', { exact: true })).toHaveCount(0)
  await owner.page.getByRole('button', { name: '다시 불러오기', exact: true }).click()
  await expect(owner.page.getByText('전체 2개 · 최근 본 순', { exact: true })).toBeVisible()
  await expect(owner.page.getByRole('alert')).toHaveCount(0)
  await failOnce()
  await settings(owner)
  await expect(owner.page.getByRole('alert')).toContainText('임시 조회 실패')
  await expect(owner.page.getByText('모인 기록 0개', { exact: true })).toHaveCount(0)
  await owner.page.getByRole('button', { name: '다시 불러오기', exact: true }).click()
  await expect(owner.page.getByText('모인 기록 2개', { exact: true })).toBeVisible()
})

test('로딩 표시·삭제 실패 보존·마지막 페이지 삭제 복구·전체삭제 빈목록', async ({ apps }) => {
  const owner = await apps.launch()
  await login(owner, 7)
  await seed(owner, 21)
  let release
  const gate = new Promise(resolve => { release = resolve })
  await owner.page.route('**/api/review/history?*', async route => { await gate; await route.continue() }, { times: 1 })
  await review(owner)
  await expect(owner.page.getByRole('status')).toHaveText('복습 기록을 불러오는 중이에요…')
  release()
  await expect(owner.page.getByText('전체 21개 · 최근 본 순', { exact: true })).toBeVisible()
  await owner.page.getByRole('button', { name: '다음', exact: true }).click()
  await expect(owner.page.locator('.review-row')).toHaveCount(1)
  await owner.page.route('**/api/review/items/*', route => route.fulfill({
    status: 503, contentType: 'application/json', body: JSON.stringify({ status: 503, message: '임시 삭제 실패' }),
  }), { times: 1 })
  await owner.page.getByRole('button', { name: '이 기록 지우기', exact: true }).click()
  await expect(owner.page.getByRole('alert')).toContainText('임시 삭제 실패')
  await expect(owner.page.locator('.review-row')).toHaveCount(1)
  await owner.page.getByRole('button', { name: '이 기록 지우기', exact: true }).click()
  await expect(owner.page.getByText('1 / 1 페이지', { exact: true })).toBeVisible()
  await expect(owner.page.getByText('전체 20개 · 최근 본 순', { exact: true })).toBeVisible()
  await owner.page.getByRole('button', { name: /기록 삭제/ }).click()
  await owner.page.getByRole('button', { name: '삭제', exact: true }).click()
  await expect(owner.page.getByText('아직 모인 내용이 없어요', { exact: true })).toBeVisible()
  await expect(owner.page.getByText('전체 0개 · 최근 본 순', { exact: true })).toBeVisible()
  await expect(owner.page.getByRole('navigation', { name: '복습 기록 페이지' })).toHaveCount(0)
  await settings(owner)
  await expect(owner.page.getByText('모인 기록 0개', { exact: true })).toBeVisible()
})
