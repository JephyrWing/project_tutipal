import { test, expect } from './focus-fixtures.js'

test('focus IPC rejects invalid ids and exposes no raw collection API before start', async ({ focus: f }) => {
  const view = await f.page.evaluate(async () => ({
    keys: Object.keys(window.desktop).filter(k => /focus/i.test(k)).sort(),
    require: typeof require, process: typeof process,
    states: await Promise.all([0, -1, 1.5, '1', null, {}, Number.MAX_SAFE_INTEGER + 1]
      .map(id => window.desktop.focusStart(id))),
  }))
  expect(view.keys).toEqual(['focusStart', 'focusState', 'focusStop', 'onFocusState'])
  expect(view.require).toBe('undefined')
  expect(view.process).toBe('undefined')
  expect(view.states.every(s => s.status === 'off')).toBe(true)
  await f.minute()
  expect(await f.snapshot()).toMatchObject({ starts: 0, idleReads: 0, timers: 0 })
  expect(f.posts).toHaveLength(0)
})

test('untrusted renderer cannot change another renderer collection through IPC', async ({ focus: f }) => {
  await f.page.evaluate(() => window.desktop.focusStart(1))
  const id = await f.application.evaluate(async ({ app, BrowserWindow }) => {
    const w = new BrowserWindow({ show: false, webPreferences: {
      preload: global.pt30Focus.preloadPath,
      contextIsolation: true, sandbox: true, nodeIntegration: false, webSecurity: true,
    } })
    await w.loadURL('data:text/html,<title>PT30 untrusted fixture</title>')
    return w.webContents.id
  })
  await expect.poll(() => f.application.windows().some(p => p.url().startsWith('data:'))).toBe(true)
  const outsider = f.application.windows().find(p => p.url().startsWith('data:'))
  expect(await outsider.evaluate(async () => ({
    start: await window.desktop.focusStart(2), state: await window.desktop.focusState(), stop: await window.desktop.focusStop(),
  }))).toMatchObject({ start: { status: 'collecting', sessionId: 1 }, state: null, stop: null })
  expect(await f.snapshot()).toMatchObject({ starts: 1, kills: 0, state: { status: 'collecting', sessionId: 1 } })
  await f.application.evaluate(({ webContents }, target) => webContents.fromId(target).close(), id)
})

test('real transport sends only summary rows with CSRF and HttpOnly session, stop drops old rows', async ({ focus: f }) => {
  await f.page.evaluate(() => {
    globalThis.focusEvents = []
    globalThis.unsubscribeFocus = window.desktop.onFocusState(s => globalThis.focusEvents.push(s.status))
    return window.desktop.focusStart(1)
  })
  await f.minute('fixture.exe')
  await expect.poll(() => f.posts.length).toBe(1)
  expect(f.posts[0]).toEqual({ route: '/api/sessions/1/focus', csrf: 'synthetic-only', origin: f.backend,
    cookie: 'TUTIPAL_SESSION=synthetic-focus-session', body: { rows: [{ exeName: 'fixture.exe', idleSec: 7, switchCount: 0 }] } })
  await expect.poll(async () => (await f.snapshot()).state.focus?.state).toBe('FOCUSED')
  expect(await f.page.evaluate(() => document.cookie)).not.toContain('TUTIPAL_SESSION')
  await f.page.evaluate(() => window.desktop.focusStop())
  await expect.poll(() => f.page.evaluate(() => globalThis.focusEvents)).toEqual(['collecting', 'collecting', 'off'])
  expect(await f.snapshot()).toMatchObject({ kills: 1, timers: 0 })
  await f.minute('discarded.exe')
  expect(f.posts).toHaveLength(1)
  await f.page.evaluate(() => { globalThis.unsubscribeFocus(); return window.desktop.focusStart(2) })
  await f.minute('new.exe')
  await expect.poll(() => f.posts.length).toBe(2)
  expect(f.posts[1].body.rows).toEqual([{ exeName: 'new.exe', idleSec: 7, switchCount: 0 }])
  expect(f.posts[1].route).toBe('/api/sessions/2/focus')
})

test('stop aborts pending HTTP and a late response cannot restart collection', async ({ focus: f }) => {
  f.control.hold = true
  await f.page.evaluate(() => window.desktop.focusStart(1))
  await f.minute()
  await expect.poll(() => f.posts.length).toBe(1)
  await f.page.evaluate(() => window.desktop.focusStop())
  f.release()
  expect(await f.snapshot()).toMatchObject({ aborts: 1, kills: 1, timers: 0, state: { status: 'off', reason: 'user' } })
  await f.minute()
  expect(f.posts).toHaveLength(1)
  expect(await f.page.evaluate(() => window.desktop.focusState())).toMatchObject({ status: 'off', reason: 'user' })
})

for (const status of [401, 403, 404, 410]) {
  test(`server ${status} ends collection through real HTTP`, async ({ focus: f }) => {
    f.control.status = status
    await f.page.evaluate(() => window.desktop.focusStart(1))
    await f.minute()
    await expect.poll(async () => (await f.snapshot()).state).toMatchObject({ status: 'off', reason: 'server' })
    expect(f.posts).toHaveLength(status === 403 ? 2 : 1)
    expect(await f.snapshot()).toMatchObject({ kills: 1, timers: 0 })
  })
}

test('session cookie removal stops collection without automatic restart', async ({ focus: f }) => {
  await f.page.evaluate(() => window.desktop.focusStart(1))
  await f.application.evaluate(async ({ session }, url) => session.defaultSession.cookies.remove(url, 'TUTIPAL_SESSION'), f.backend)
  await expect.poll(async () => (await f.snapshot()).state).toMatchObject({ status: 'off', reason: 'cookie' })
  expect(await f.snapshot()).toMatchObject({ kills: 1, timers: 0 })
  await f.minute()
  expect(f.posts).toHaveLength(0)
})

for (const event of ['navigate', 'destroy', 'crash']) {
  test(`owner ${event} stops collection and another window cannot take ownership`, async ({ focus: f }) => {
    let widget
    await expect.poll(() => {
      widget = f.application.windows().find(p => new URL(p.url()).pathname === '/widget')
      return !!widget
    }).toBe(true)
    await widget.evaluate(() => window.desktop.focusStart(1))
    await f.page.evaluate(() => window.desktop.focusStart(1))
    expect(await f.snapshot()).toMatchObject({ starts: 1, kills: 0 })
    if (event === 'navigate') await widget.goto(new URL('/widget?focus=left', f.page.url()).href)
    else await f.application.evaluate(({ BrowserWindow }, kind) => {
      const w = BrowserWindow.getAllWindows().find(w => new URL(w.webContents.getURL()).pathname === '/widget')
      if (kind === 'destroy') w.destroy()
      else w.webContents.forcefullyCrashRenderer()
    }, event)
    await expect.poll(async () => (await f.snapshot()).state).toMatchObject({ status: 'off',
      reason: { navigate: 'left', destroy: 'destroyed', crash: 'render-process-gone' }[event] })
    expect(await f.snapshot()).toMatchObject({ kills: 1, timers: 0 })
    await f.minute()
    expect(f.posts).toHaveLength(0)
  })
}
