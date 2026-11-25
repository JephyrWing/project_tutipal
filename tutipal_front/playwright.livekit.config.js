import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: './tests/livekit',
  testMatch: '**/*.spec.js',
  timeout: 90000,
  expect: { timeout: 20000 },
  workers: 1,
  fullyParallel: false,
  retries: 0,
  forbidOnly: !!process.env.CI,
  reporter: [['list'], ['junit', { outputFile: 'test-results/livekit-media.xml' }]],
  use: { trace: 'off', video: 'off', screenshot: 'off' },
  webServer: {
    command: 'node scripts/e2e-server.mjs',
    url: 'http://127.0.0.1:28080/actuator/health',
    env: { E2E_LIVEKIT_MEDIA: 'true' },
    reuseExistingServer: false,
    timeout: 120000,
    stdout: 'ignore',
    stderr: 'ignore',
  },
})
