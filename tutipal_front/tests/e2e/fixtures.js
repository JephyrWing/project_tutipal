import { test as base, expect } from '@playwright/test'
import { _electron } from 'playwright'
import electronPath from 'electron'
import { mkdtemp, realpath, rm } from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const front = fileURLToPath(new URL('../../', import.meta.url))
export { expect }
export const test = base.extend({
  apps: async ({}, use, testInfo) => {
    const opened = []
    const launch = async ({ fakeMedia = false } = {}) => {
      const profile = await mkdtemp(path.join(os.tmpdir(), 'tutipal-e2e-profile-'))
      const application = await _electron.launch({
        executablePath: electronPath,
        args: [...(fakeMedia ? ['--use-fake-device-for-media-stream'] : []), path.join(front, 'tests/e2e/entry.cjs')],
        cwd: front,
        chromiumSandbox: true,
        env: { ...process.env, TUTIPAL_E2E_PROFILE: profile, TUTIPAL_SPRING_URL: 'http://127.0.0.1:28080' },
      })
      const item = { application, profile, page: null }
      opened.push(item)
      // Electron may report the widget target before the main window finishes loading.
      await expect.poll(() => {
        item.page = application.windows().find(page => {
          const url = new URL(page.url())
          return url.protocol === 'http:' && !['/widget', '/overlay'].includes(url.pathname)
        })
        return !!item.page
      }).toBe(true)
      await expect(item.page.getByRole('button', { name: '개발용 로그인 (내 PC 전용)', exact: true })).toBeVisible()
      return item
    }
    try {
      await use({ launch })
    } finally {
      for (const item of opened.reverse()) {
        if (testInfo.status !== testInfo.expectedStatus && item.page && !item.page.isClosed()) {
          await item.page.screenshot({ path: testInfo.outputPath(`failure-${opened.indexOf(item)}.png`) }).catch(() => undefined)
        }
        await item.application.close().catch(() => undefined)
        const resolved = await realpath(item.profile)
        if (path.dirname(resolved) === await realpath(os.tmpdir()) && path.basename(resolved).startsWith('tutipal-e2e-profile-')) {
          await rm(resolved, { recursive: true, force: true })
        }
      }
    }
  },
})

export async function login(app, number) {
  await app.page.goto(new URL(`/login?dev=${number}`, app.page.url()).href)
  await app.page.getByRole('button', { name: '개발용 로그인 (내 PC 전용)', exact: true }).click()
  await expect(app.page.getByRole('heading', { name: '오늘은 무엇을 공부할까요?' })).toBeVisible()
}

export async function createRoom(app, title) {
  await app.page.getByRole('link', { name: /방 만들기/ }).click()
  await app.page.getByPlaceholder('예: 자바 기초 3주차').fill(title)
  await app.page.getByRole('combobox', { name: '공부할 과목' }).selectOption('수학')
  await app.page.getByRole('spinbutton', { name: 'AI 대화 보관 기간 (일)' }).fill('14')
  await app.page.getByRole('button', { name: '만들기', exact: true }).click()
  const code = await app.page.locator('.invite-code').innerText()
  await app.page.getByRole('link', { name: '선생님 모드로 들어가기' }).click()
  await expect(app.page.getByText('선생님 모드', { exact: true })).toBeVisible()
  const id = Number(new URL(app.page.url()).pathname.split('/').at(-1))
  return { id, code }
}

export async function joinRoom(app, code) {
  await app.page.getByRole('link', { name: /방 참여하기/ }).click()
  await app.page.getByRole('textbox', { name: '초대 코드 6자리' }).fill(code)
  await app.page.getByRole('button', { name: '입장', exact: true }).click()
}
