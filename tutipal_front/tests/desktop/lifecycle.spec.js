import { spawn } from 'node:child_process'
import { once } from 'node:events'
import electronPath from 'electron'
import { test, expect, front, entry, environment } from './fixtures.js'

async function state(app) {
  return app.evaluate(({ BrowserWindow }) => {
    const windows = BrowserWindow.getAllWindows()
    const widget = windows.find(w => new URL(w.webContents.getURL()).pathname === '/widget')
    const main = windows.find(w => new URL(w.webContents.getURL()).pathname !== '/widget')
    return { count: windows.length, widget: !!widget, widgetVisible: widget?.isVisible(),
      widgetAlwaysOnTop: widget?.isAlwaysOnTop(), widgetResizable: widget?.isResizable(),
      mainVisible: main?.isVisible(), mainMinimized: main?.isMinimized() }
  })
}
async function menu(app, label) {
  await app.evaluate((_, label) => {
    const item = global.pt30TrayMenu?.items.find(i => i.label === label)
    if (!item?.click) throw Error('Expected native tray item is missing')
    item.click()
  }, label)
}

test('PT30 widget IPC toggles one native window and opens only supported chat topics', async ({ desktop }) => {
  const { application, page } = desktop
  await expect.poll(async () => (await state(application)).count).toBe(2)
  expect((await state(application)).widgetAlwaysOnTop).toBe(true)
  expect((await state(application)).widgetResizable).toBe(false)
  expect((await state(application)).widgetVisible).toBe(false)
  await page.evaluate(() => window.desktop.toggleWidget())
  await expect.poll(async () => (await state(application)).widgetVisible).toBe(true)
  await page.evaluate(() => window.desktop.toggleWidget())
  await expect.poll(async () => (await state(application)).widgetVisible).toBe(false)
  await page.evaluate(() => window.desktop.toggleWidget())
  await expect.poll(async () => (await state(application)).widgetVisible).toBe(true)
  expect((await state(application)).count).toBe(2)
  await page.evaluate(() => window.desktop.openChat('math'))
  await expect(page).toHaveURL(/\/chat\?topic=math$/)
  await page.evaluate(() => window.desktop.openChat('../../outside'))
  await expect(page).toHaveURL(/\/chat$/)
})

test('PT30 real second process restores the first window without duplicating or reloading it', async ({ desktop }) => {
  const { application, page, profile } = desktop
  await page.evaluate(() => { window.pt30RetainedDocument = 'same-document' })
  await application.evaluate(({ BrowserWindow }) => {
    const main = BrowserWindow.getAllWindows().find(w => new URL(w.webContents.getURL()).pathname !== '/widget')
    main.show(); main.minimize(); main.hide()
  })
  const child = spawn(electronPath, [entry, 'tutipal://auth?code=invalid&state=not-requested'], {
    cwd: front, env: environment(profile), windowsHide: true, stdio: 'ignore',
  })
  const exited = once(child, 'exit')
  try {
    const result = await Promise.race([exited, new Promise((_, reject) => {
      const timer = setTimeout(() => reject(Error('Second process did not exit')), 10000)
      timer.unref()
    })])
    expect(result[0]).toBe(0)
    await expect.poll(async () => {
      const s = await state(application)
      return s.mainVisible && !s.mainMinimized && s.count === 2
    }).toBe(true)
    expect(await page.evaluate(() => window.pt30RetainedDocument)).toBe('same-document')
    await expect(page.getByRole('heading', { name: '오늘은 무엇을 공부할까요?' })).toBeVisible()
  } finally {
    if (child.exitCode === null) { child.kill(); await exited }
  }
})

test('PT30 native tray handlers reopen, toggle and quit the app with its loopback bridge', async ({ desktop }) => {
  const { application, page } = desktop
  const origin = new URL(page.url()).origin
  await application.evaluate(({ BrowserWindow }) => {
    const main = BrowserWindow.getAllWindows().find(w => new URL(w.webContents.getURL()).pathname !== '/widget')
    main.hide()
  })
  await menu(application, '열기')
  await expect.poll(async () => (await state(application)).mainVisible).toBe(true)
  await menu(application, '캐릭터 위젯 보이기/숨기기')
  await expect.poll(async () => (await state(application)).widgetVisible).toBe(true)
  await menu(application, '캐릭터 위젯 보이기/숨기기')
  await expect.poll(async () => (await state(application)).widgetVisible).toBe(false)
  expect((await state(application)).count).toBe(2)
  const exited = once(application.process(), 'exit')
  await application.evaluate(() => {
    const quit = global.pt30TrayMenu.items.find(i => i.label === '종료')
    if (!quit?.click) throw Error('Native quit item missing')
    setTimeout(() => quit.click(), 100)
  })
  const [code] = await exited
  expect(code).toBe(0)
  await expect.poll(async () => {
    try { await fetch(origin, { signal: AbortSignal.timeout(500) }); return false } catch { return true }
  }).toBe(true)
})
