import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: './tests/desktop', testMatch: '**/*.spec.js',
  timeout: 45000, expect: { timeout: 10000 },
  workers: 1, fullyParallel: false, retries: 0, forbidOnly: !!process.env.CI,
  reporter: [['list'], ['junit', { outputFile: 'test-results/desktop-lifecycle.xml' }]],
  use: { trace: 'off', video: 'off', screenshot: 'off' },
})
