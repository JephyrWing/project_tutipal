import { test, expect } from './fixtures.js'

test('메인·위젯의 sandbox와 격리 설정을 실제 renderer에서 유지한다', async ({ apps }) => {
  const app = await apps.launch()
  await expect.poll(async () => app.application.evaluate(({ BrowserWindow }) =>
    BrowserWindow.getAllWindows().filter(window => !window.isDestroyed()).length)).toBe(2)
  const windows = await app.application.evaluate(({ BrowserWindow }) =>
    BrowserWindow.getAllWindows().map(window => {
      const settings = window.webContents.getLastWebPreferences()
      return { path: new URL(window.webContents.getURL()).pathname,
        contextIsolation: settings.contextIsolation, sandbox: settings.sandbox,
        nodeIntegration: settings.nodeIntegration, webSecurity: settings.webSecurity }
    }))
  expect(windows.map(window => window.path).sort()).toEqual(['/login', '/widget'])
  for (const window of windows) expect(window).toMatchObject({
    contextIsolation: true, sandbox: true, nodeIntegration: false, webSecurity: true,
  })
  for (const page of app.application.windows()) {
    expect(await page.evaluate(() => ({ require: typeof require, process: typeof process })))
      .toEqual({ require: 'undefined', process: 'undefined' })
  }
})

test('카메라·마이크·위치·알림 요청은 사용자 장치를 열지 않고 거절한다', async ({ apps }) => {
  const app = await apps.launch({ fakeMedia: true })
  const results = await app.page.evaluate(async () => {
    const media = async constraints => {
      try {
        const stream = await navigator.mediaDevices.getUserMedia(constraints)
        // Always release a stream if a regression unexpectedly grants access.
        stream.getTracks().forEach(track => track.stop())
        return 'unexpectedly-granted'
      } catch (error) { return error.name }
    }
    const location = await new Promise(resolve => navigator.geolocation.getCurrentPosition(
      () => resolve('unexpectedly-granted'), error => resolve(error.code), { timeout: 3000 }))
    return { camera: await media({ video: true }), microphone: await media({ audio: true }),
      location, notifications: await Notification.requestPermission() }
  })
  expect(results).toEqual({ camera: 'NotAllowedError', microphone: 'NotAllowedError',
    location: 1, notifications: 'denied' })
})

test('외부 이동과 새창은 앱 안에서 차단하고 내부 이동은 허용한다', async ({ apps }) => {
  const app = await apps.launch()
  // Intercept only OS browser launch, preserving the real navigation handlers.
  await app.application.evaluate(({ shell }) => {
    globalThis.pt30ExternalRequests = []
    shell.openExternal = async url => { globalThis.pt30ExternalRequests.push(url) }
  })
  const original = app.page.url()
  const outside = 'https://security-check.invalid/PT30'
  const network = []
  app.page.on('request', request => {
    if (request.url().startsWith('https://security-check.invalid/')) network.push(request.url())
  })
  await app.page.evaluate(url => { location.href = url }, outside)
  await expect.poll(() => app.application.evaluate(() => globalThis.pt30ExternalRequests))
    .toEqual([outside])
  expect(app.page.url()).toBe(original)
  await app.page.evaluate(url => window.open(url, '_blank'), outside + '/popup')
  await expect.poll(() => app.application.evaluate(() => globalThis.pt30ExternalRequests))
    .toEqual([outside, outside + '/popup'])
  await app.page.evaluate(() => {
    window.open('file:///PT30-do-not-open', '_blank')
    window.open('tutipal-security-probe:blocked', '_blank')
  })
  expect(await app.application.evaluate(({ BrowserWindow }) => BrowserWindow.getAllWindows().length)).toBe(2)
  expect(await app.application.evaluate(() => globalThis.pt30ExternalRequests))
    .toEqual([outside, outside + '/popup'])
  expect(network).toEqual([])
  await app.page.evaluate(() => { location.href = '/login?security=internal' })
  await expect(app.page).toHaveURL(/\/login\?security=internal$/)
  await expect(app.page.getByRole('button', { name: '개발용 로그인 (내 PC 전용)', exact: true })).toBeVisible()
})
