const { readFileSync } = require('node:fs')
const path = require('node:path')
const {
  app, BrowserWindow, ipcMain, Menu, nativeImage, screen, session, shell, Tray,
} = require('electron')
const { finishBrowserLogin, startBrowserLogin } = require('./applogin.cjs')
const { capture, listSources, loadSettings, saveSettings } = require('./screen.cjs')
const { chooseShareSource, forwardDraw, installDisplayMediaHandler, showOverlay,
  helpShareSources, prepareHelpShare, setHelpShareVideoSize } = require('./share.cjs')
const { startBridge } = require('./bridge.cjs')

/**
 * Spring 업무 서버 주소. React 화면은 설치 패키지의 dist에 내장한다.
 * 개발은 TUTIPAL_SPRING_URL, 설치 앱은 package.json의 tutipal.serverUrl 또는 실행 환경변수를 사용한다.
 */
function serverUrl() {
  if (process.env.TUTIPAL_SPRING_URL) return process.env.TUTIPAL_SPRING_URL
  try {
    const pkg = JSON.parse(readFileSync(path.join(app.getAppPath(), 'package.json'), 'utf8'))
    if (pkg.tutipal?.serverUrl) return pkg.tutipal.serverUrl
  } catch {
    // 기본값
  }
  return 'http://127.0.0.1:8080'
}
const BACKEND = new URL(serverUrl())
if (!['http:', 'https:'].includes(BACKEND.protocol) || BACKEND.username || BACKEND.password) throw new Error('Invalid Spring URL')
let BASE
let bridge
const TOPICS = new Set(['', 'general', 'coding', 'english', 'math'])
const ASSETS = path.join(__dirname, 'assets')
const PROTOCOL = 'tutipal'

let mainWindow = null
let widgetWindow = null
let tray = null
let pendingDeepLink = process.argv.find((value) => value.startsWith(`${PROTOCOL}://`))

function isOurPage(url) {
  try {
    return new URL(url).origin === BASE.origin
  } catch {
    return false
  }
}

function openOutside(url) {
  if (/^https?:\/\//.test(url)) void shell.openExternal(url)
}

/**
 * 우리 서버 밖으로 나가지 못하게, 바깥 주소는 기본 브라우저로.
 * 소셜 로그인도 앱 안에서 열지 않고 브라우저 + 딥링크로 (구글이 앱 안 로그인을 막아서 전부 같은 방식으로 통일)
 */
function harden(contents) {
  const guard = (event, url) => {
    if (!isOurPage(url)) {
      event.preventDefault()
      openOutside(url)
    }
  }
  contents.on('will-navigate', guard)
  contents.on('will-redirect', guard)
  contents.setWindowOpenHandler(({ url }) => {
    openOutside(url)
    return { action: 'deny' }
  })
}

const webPreferences = {
  preload: path.join(__dirname, 'preload.cjs'),
  contextIsolation: true,
  sandbox: true,
  nodeIntegration: false,
  webSecurity: true,
}

function createMainWindow() {
  const win = new BrowserWindow({
    width: 1280,
    height: 820,
    minWidth: 900,
    minHeight: 640,
    title: 'TUTIPAL',
    backgroundColor: '#eef3fa',
    icon: path.join(ASSETS, 'icon.png'),
    autoHideMenuBar: true,
    webPreferences,
  })
  bridge.own(win.webContents)
  harden(win.webContents)
  void win.loadURL(BASE.href)
  win.on('closed', () => {
    mainWindow = null
    app.quit()
  })
  return win
}

function createWidgetWindow() {
  const width = 380
  const height = 340
  const { workArea } = screen.getPrimaryDisplay()
  const win = new BrowserWindow({
    width,
    height,
    x: workArea.x + workArea.width - width - 16,
    y: workArea.y + workArea.height - height - 8,
    frame: false,
    transparent: true,
    resizable: false,
    alwaysOnTop: true,
    skipTaskbar: true,
    hasShadow: false,
    webPreferences,
  })
  bridge.own(win.webContents)
  win.setAlwaysOnTop(true, 'floating')
  harden(win.webContents)
  void win.loadURL(new URL('/widget', BASE).href)
  win.on('closed', () => {
    widgetWindow = null
  })
  return win
}

function showMain(urlPath) {
  if (!mainWindow) mainWindow = createMainWindow()
  if (urlPath) void mainWindow.loadURL(new URL(urlPath, BASE).href)
  if (mainWindow.isMinimized()) mainWindow.restore()
  mainWindow.show()
  mainWindow.focus()
}

function toggleWidget() {
  if (!widgetWindow) widgetWindow = createWidgetWindow()
  else if (widgetWindow.isVisible()) widgetWindow.hide()
  else widgetWindow.show()
}

/** 화면이 보낸 요청은 우리 서버 페이지에서 온 것만 받는다 */
function fromOurPage(event) {
  return isOurPage(event.senderFrame?.url ?? '')
}

/** tutipal://auth?code= 로 돌아왔을 때: 세션 쿠키로 교환하고 두 창을 새로 읽음 */
async function onDeepLink(url) {
  if (!url?.startsWith(`${PROTOCOL}://`)) return
  if (!BASE) { pendingDeepLink = url; return }
  showMain()
  const ok = await finishBrowserLogin(BACKEND, url).catch(() => false)
  if (ok) {
    void mainWindow?.loadURL(BASE.href)
    widgetWindow?.reload()
  }
}

function createTray() {
  tray = new Tray(nativeImage.createFromPath(path.join(ASSETS, 'tray.png')))
  tray.setToolTip('TUTIPAL · AI 학습 도우미')
  tray.setContextMenu(Menu.buildFromTemplate([
    { label: '열기', click: () => showMain() },
    { label: '캐릭터 위젯 보이기/숨기기', click: () => toggleWidget() },
    { label: '화면 분석 설정', click: () => showMain('/screen-consent') },
    { type: 'separator' },
    { label: '종료', click: () => app.quit() },
  ]))
  tray.on('click', () => showMain())
}

// tutipal:// 을 이 앱으로 연결 (Windows: 사용자 레지스트리 HKCU 에 등록). 개발 모드는 electron.exe + 앱 폴더로 등록해야 함.
// argv[1] 을 쓰면 `electron --inspect .` 처럼 옵션이 앞에 올 때 옵션이 앱 경로로 등록돼 버림 (실제로 겪음) → getAppPath
if (process.defaultApp) {
  app.setAsDefaultProtocolClient(PROTOCOL, process.execPath, [app.getAppPath()])
} else {
  app.setAsDefaultProtocolClient(PROTOCOL)
}

if (!app.requestSingleInstanceLock()) {
  app.quit()
} else {
  // Windows: 딥링크는 "두 번째 실행" 의 명령줄 인자로 들어온다
  app.on('second-instance', (_event, argv) => {
    showMain()
    void onDeepLink(argv.find((a) => a.startsWith(`${PROTOCOL}://`)))
  })
  // macOS
  app.on('open-url', (event, url) => {
    event.preventDefault()
    void onDeepLink(url)
  })

  app.whenReady().then(async () => {
    bridge = await startBridge(BACKEND)
    BASE = bridge.base
    // 권한은 화면 공유(display-capture, 우리 페이지에서만)만 허용. 카메라 · 마이크 · 알림 등은 모두 거절.
    // 실제로 어떤 창을 공유할지는 share.ts 의 setDisplayMediaRequestHandler 가 정한다
    session.defaultSession.setPermissionRequestHandler((wc, permission, callback) =>
      callback(permission === 'display-capture' && isOurPage(wc.getURL())))
    session.defaultSession.setPermissionCheckHandler((wc, permission, requestingOrigin) =>
      permission === 'display-capture' && isOurPage(requestingOrigin || wc?.getURL() || ''))

    ipcMain.on('open-chat', (event, topic) => {
      if (!fromOurPage(event)) return
      const t = typeof topic === 'string' && TOPICS.has(topic) ? topic : ''
      showMain(t ? `/chat?topic=${t}` : '/chat')
    })
    ipcMain.on('toggle-widget', (event) => {
      if (fromOurPage(event)) toggleWidget()
    })
    ipcMain.on('open-screen-consent', (event) => {
      if (fromOurPage(event)) showMain('/screen-consent')
    })
    ipcMain.on('open-review', (event, id) => {
      if (fromOurPage(event) && Number.isSafeInteger(id) && (id) > 0) showMain(`/review?explain=${id}`)
    })
    ipcMain.on('login-in-browser', (event, provider) => {
      if (fromOurPage(event)) startBrowserLogin(BACKEND, provider)
    })
    ipcMain.handle('screen-sources', (event) => (fromOurPage(event) ? listSources() : []))
    ipcMain.handle('screen-settings-get', (event) => (fromOurPage(event) ? loadSettings() : null))
    ipcMain.handle('screen-settings-set', (event, settings) => {
      if (!fromOurPage(event)) return null
      const saved = saveSettings(settings)
      widgetWindow?.webContents.send('screen-settings-changed')
      return saved
    })
    ipcMain.handle('screen-capture', (event) => (fromOurPage(event) ? capture() : { status: 'off' }))
    // Only trusted pages can suspend collection around record deletion.
    const reviewDeletions = new Map()
    const reviewDeletionSenders = new Set()
    let reviewDeletionTicket = 0
    const notifyReviewDeletion = () => widgetWindow?.webContents.send('review-deletion-changed', reviewDeletions.size > 0)
    const endReviewDeletion = senderId => {
      const count = reviewDeletions.get(senderId) ?? 0
      if (count > 1) reviewDeletions.set(senderId, count - 1)
      else reviewDeletions.delete(senderId)
      notifyReviewDeletion()
    }
    ipcMain.handle('review-deletion-state', event => fromOurPage(event) ? reviewDeletions.size > 0 : false)
    ipcMain.handle('review-deletion-start', async event => {
      if (!fromOurPage(event)) throw new Error('복습 기록 요청을 허용하지 않아요')
      const sender = event.sender
      reviewDeletions.set(sender.id, (reviewDeletions.get(sender.id) ?? 0) + 1)
      if (!reviewDeletionSenders.has(sender.id)) {
        reviewDeletionSenders.add(sender.id)
        sender.once('destroyed', () => {
          reviewDeletionSenders.delete(sender.id)
          reviewDeletions.delete(sender.id)
          notifyReviewDeletion()
        })
      }
      const target = widgetWindow?.webContents
      if (!target || target.isDestroyed()) return
      const ticket = ++reviewDeletionTicket
      // Do not issue DELETE until the widget has invalidated its old analysis.
      try {
        await new Promise((resolve, reject) => {
          const cleanup = () => {
            clearTimeout(timer)
            ipcMain.removeListener('review-deletion-ready', ready)
            target.removeListener('destroyed', closed)
          }
          const ready = (response, value) => {
            if (response.sender === target && value === ticket && fromOurPage(response)) { cleanup(); resolve() }
          }
          const closed = () => { cleanup(); resolve() }
          const timer = setTimeout(() => { cleanup(); reject(new Error('위젯 준비를 기다리지 못했어요. 다시 삭제해 주세요.')) }, 5000)
          ipcMain.on('review-deletion-ready', ready)
          target.once('destroyed', closed)
          target.send('review-deletion-changed', true, ticket)
        })
      } catch (error) {
        endReviewDeletion(sender.id)
        throw error
      }
    })
    ipcMain.handle('review-deletion-end', event => {
      if (!fromOurPage(event)) return
      endReviewDeletion(event.sender.id)
    })
    // 4단계 화면공유: 공유할 창 고르기 → getDisplayMedia 허용, 학생 PC 그림 오버레이
    installDisplayMediaHandler(isOurPage)
    ipcMain.handle('help-share-sources', event => fromOurPage(event) ? helpShareSources() : [])
    ipcMain.handle('help-share-prepare', (event, sourceId) => {
      if (!fromOurPage(event)) throw new Error('공유 요청을 허용하지 않아요')
      return prepareHelpShare(sourceId, event.sender)
    })
    ipcMain.on('help-share-size', (event, sourceId, width, height) => {
      if (fromOurPage(event)) setHelpShareVideoSize(event.sender, sourceId, width, height)
    })
    ipcMain.on('share-source', (event, id) => {
      if (fromOurPage(event)) chooseShareSource(id)
    })
    ipcMain.on('overlay-show', (event, on) => {
      if (fromOurPage(event)) showOverlay(on === true, BASE, webPreferences, bridge.own, harden)
    })
    ipcMain.on('overlay-draw', (event, message) => {
      if (fromOurPage(event)) forwardDraw(message)
    })

    mainWindow = createMainWindow()
    widgetWindow = createWidgetWindow()
    createTray()
    if (pendingDeepLink) void onDeepLink(pendingDeepLink)
  })
}

app.on('before-quit', () => bridge?.close())
