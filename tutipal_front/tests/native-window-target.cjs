const assert = require('node:assert/strict')
const { app, BrowserWindow } = require('electron')
const { watchWindow } = require('../electron/windowTarget.cjs')
const { captureBounds } = require('../electron/shareGeometry.cjs')
let window, watcher
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
async function waitFor(predicate) {
  const end = Date.now() + 5000
  while (Date.now() < end) { if (predicate()) return; await sleep(50) }
  throw new Error('Native geometry observation timed out')
}
app.on('window-all-closed', () => {})
app.whenReady().then(async () => {
  let last, samples = []
  try {
    window = new BrowserWindow({ x: 100, y: 100, width: 600, height: 400, frame: false, show: false,
      webPreferences: { sandbox: true, contextIsolation: true, nodeIntegration: false } })
    await window.loadURL('data:text/html,<title>PT92 synthetic target</title><body style="background:cornflowerblue">Synthetic target only</body>')
    window.showInactive()
    watcher = watchWindow(window.getMediaSourceId(), state => { last = state; samples.push(state) })
    const first = await watcher.ready
    assert.equal(first.state, 'ok')
    assert.ok(captureBounds(first, { width: first.window.width, height: first.window.height }))
    window.setBounds({ x: 170, y: 180, width: 720, height: 480 })
    await waitFor(() => last.state === 'ok' && last.window.width !== first.window.width && last.window.x !== first.window.x)
    assert.ok(last.window.width > first.window.width)
    const moved = last
    assert.ok(captureBounds(moved, { width: moved.window.width, height: moved.window.height }))
    window.minimize()
    await waitFor(() => samples.some(s => s.state === 'hidden'))
    watcher.dispose(); watcher = null
    window.restore(); window.showInactive()
    samples = []
    watcher = watchWindow(window.getMediaSourceId(), state => { samples.push(state) })
    await watcher.ready
    window.destroy(); window = null
    await waitFor(() => samples.some(s => s.state === 'gone'))
    console.log('Native window tracker: scenarios=3 passed=3 failed=0 (geometry, move/resize, minimize/close)')
    app.exit(0)
  } catch (error) {
    console.error(error.message)
    app.exit(1)
  } finally { watcher?.dispose(); if (window && !window.isDestroyed()) window.destroy() }
})
