const { BrowserWindow, desktopCapturer, session, webContents } = require('electron')
const path = require('node:path')

// Separate in-memory session: never replace the help-sharing display-media handler.
let captureSession
let active = null
function cancelCapture(kind) {
  if (!kind || active?.kind === kind) active?.cancel()
}

async function captureWindow(sourceId, kind = 'analysis') {
  if (active) throw new Error('화면을 확인하고 있어요. 잠시 후 다시 시도해 주세요.')
  if (typeof sourceId !== 'string' || !sourceId.startsWith('window:')) throw new Error('창을 다시 선택해 주세요.')
  captureSession ??= session.fromPartition('screen-analysis-capture', { cache: false })
  const win = new BrowserWindow({ show: false, width: 160, height: 120,
    webPreferences: { session: captureSession, sandbox: true, contextIsolation: true,
      nodeIntegration: false, webSecurity: true, backgroundThrottling: false } })
  let rejectCancel, timer
  const cancelled = new Promise((_, reject) => { rejectCancel = reject })
  const task = { kind, cancel: () => { rejectCancel(new Error('화면 확인이 취소됐어요.')); if (!win.isDestroyed()) win.destroy() } }
  active = task
  const allowed = wc => wc === win.webContents && !win.isDestroyed()
  captureSession.setPermissionCheckHandler((wc, permission) => allowed(wc) && permission === 'display-capture')
  captureSession.setPermissionRequestHandler((wc, permission, callback) => callback(allowed(wc) && permission === 'display-capture'))
  captureSession.setDisplayMediaRequestHandler(async (request, callback) => {
    const respond = value => { try { callback(value) } catch { /* cancelled frame */ } }
    if (!request.frame || !allowed(webContents.fromFrame(request.frame)) || request.frame !== win.webContents.mainFrame) return respond({})
    try {
      // Metadata only. No other window or monitor thumbnail is ever requested.
      const sources = await desktopCapturer.getSources({ types: ['window'], thumbnailSize: { width: 0, height: 0 }, fetchWindowIcons: false })
      const own = new Set(BrowserWindow.getAllWindows().map(w => w.getMediaSourceId().split(':')[1]))
      const chosen = sources.find(s => s.id === sourceId && !own.has(s.id.split(':')[1]))
      respond(active === task && chosen ? { video: chosen } : {})
    } catch { respond({}) }
  }, { useSystemPicker: false })
  win.webContents.setWindowOpenHandler(() => ({ action: 'deny' }))
  win.webContents.on('will-navigate', event => event.preventDefault())
  try {
    timer = setTimeout(task.cancel, 10000)
    return await Promise.race([cancelled, (async () => {
      await win.loadFile(path.join(__dirname, 'screenCapture.html'))
      return await win.webContents.executeJavaScript('captureOneFrame()', true)
    })()])
  } finally {
    clearTimeout(timer)
    if (!win.isDestroyed()) win.destroy() // also stops any pending/active media tracks
    if (active === task) active = null
    captureSession.setDisplayMediaRequestHandler(null)
  }
}

module.exports = { captureWindow, cancelCapture }
