// Production Main/Preload/React + production screen controller, synthetic OS input only.
import { _electron } from 'playwright'
import electron from 'electron'
import { expect } from '@playwright/test'
import { mkdtemp, rm } from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { installSyntheticAnalysis } from './e2e/synthetic-analysis.js'

const front = fileURLToPath(new URL('../', import.meta.url))
const profile = await mkdtemp(path.join(os.tmpdir(), 'tutipal-e2e-profile-'))
const env = { ...process.env, TUTIPAL_E2E_PROFILE: profile, TUTIPAL_SPRING_URL: 'http://127.0.0.1:28080' }
delete env.ELECTRON_RUN_AS_NODE
let application
try {
  application = await _electron.launch({ executablePath: electron, args: [path.join(front, 'tests/e2e/entry.cjs')], cwd: front, env, chromiumSandbox: true })
  await application.context().route(url => url.pathname.startsWith('/api/'), route => route.fulfill({ contentType: 'application/json',
    body: JSON.stringify(new URL(route.request().url()).pathname === '/api/users/me' ? { id: 1, name: 'Synthetic student' } : []) }))
  let page
  await expect.poll(() => { page = application.windows().find(p => p.url().startsWith('http:') && !p.url().includes('/widget')); return !!page }).toBe(true)
  await installSyntheticAnalysis({ application, page })
  await expect(page.evaluate(() => window.desktop.setScreenSettings({ enabled: true, sourceId: 'window:synthetic' }))).rejects.toThrow(/미리보기/)
  await page.goto(new URL('/screen-consent', page.url()).href)
  await page.locator('.source-card').filter({ hasText: '합성 학습 창' }).click()
  await expect(page.getByRole('button', { name: '분석 시작', exact: true })).toBeDisabled()
  await page.getByRole('button', { name: '선택 창 미리보기', exact: true }).click()
  await expect(page.getByRole('button', { name: '분석 시작', exact: true })).toBeEnabled()
  await page.getByRole('button', { name: '분석 시작', exact: true }).click()
  await expect.poll(() => page.evaluate(() => window.desktop.getScreenSettings().then(s => s.enabled))).toBe(true)
  console.log('Synthetic analysis fixture: collected=3 executed=3 passed=3 failed=0 skipped=0 (no-preview rejection, preview gate, explicit start)')
} finally {
  await application?.close()
  await rm(profile, { recursive: true, force: true, maxRetries: 5, retryDelay: 200 })
}
