const { BrowserWindow, desktopCapturer, screen, session, webContents } = require('electron')
const { createHelpTargetManager } = require('./helpShareTarget.cjs')
const { watchWindow, canTrackWindow, windowHandle } = require('./windowTarget.cjs')

/**
 * 화면공유(LiveKit) 를 위한 Electron 쪽 준비.
 * 1) 화면이 getDisplayMedia() 를 부르면, 미리 고른 창/화면을 허용한다 (Electron 은 기본 선택 창이 없음).
 *    도움 요청은 Main이 검증한 선택 대상에만 캡처와 판서를 연결한다.
 * 2) 학생 PC 위의 투명 오버레이 창(목업 19)에 선생님 그림을 그린다. 클릭은 아래 프로그램으로 통과.
 */
let chosenSource = null
let overlay = null
let overlayWanted = false
let selectionVersion = 0

async function availableSources(thumbnailSize = { width: 0, height: 0 }) {
  // Electron's own media IDs can have a different suffix from desktopCapturer.
  const own = new Set(BrowserWindow.getAllWindows().map(w => windowHandle(w.getMediaSourceId())))
  return (await desktopCapturer.getSources({ types: ['screen', 'window'], thumbnailSize }))
    .filter(s => !(s.id.startsWith('window:') && own.has(windowHandle(s.id))) && s.name.trim())
}

function placeOverlay() {
  if (!overlay || overlay.isDestroyed()) return
  const geometry = targets.geometry()
  if (!overlayWanted || !geometry) { overlay.hide(); return }
  const bounds = geometry.physical ? screen.screenToDipRect(null, geometry.bounds) : geometry.bounds
  overlay.setBounds(bounds)
  if (geometry.visible) overlay.showInactive()
  else overlay.hide()
}
const targets = createHelpTargetManager({
  getSources: availableSources, getDisplays: () => screen.getAllDisplays(), watchWindow, canTrackWindow,
  onGeometry: placeOverlay,
  onEnd: (owner, reason) => {
    chosenSource = null
    overlayWanted = false
    overlay?.close(); overlay = null
    if (!owner.isDestroyed()) owner.send('help-share-ended', reason)
  },
})

async function helpShareSources() {
  return (await availableSources({ width: 480, height: 300 })).map(s => ({
    id: s.id, name: s.id.startsWith('screen:') ? `전체 화면 (${s.name})` : s.name,
    thumbnail: s.thumbnail.toDataURL(),
    supported: s.id.startsWith('screen:') ? screen.getAllDisplays().some(d => String(d.id) === String(s.display_id)) : canTrackWindow(),
  }))
}

async function prepareHelpShare(sourceId, owner) {
  const version = ++selectionVersion
  chosenSource = null
  const result = await targets.prepare(sourceId, owner)
  if (version !== selectionVersion) throw new Error('창 선택이 취소됐어요')
  chosenSource = result.id
  return result
}

function setHelpShareVideoSize(owner, sourceId, width, height) {
  targets.setVideoSize(owner, sourceId, width, height)
}

/** 화면이 다음 getDisplayMedia 에서 공유할 창/화면 id (빈 값 = 주 모니터) */
function chooseShareSource(id) {
  selectionVersion++
  targets.clear()
  overlayWanted = false
  overlay?.close(); overlay = null
  chosenSource = typeof id === 'string' && id.length <= 200 ? id : null
}

function installDisplayMediaHandler(isOurPage) {
  session.defaultSession.setDisplayMediaRequestHandler(async (request, callback) => {
    // Electron can synchronously throw while delivering a denied media request.
    const respond = result => { try { callback(result) } catch {} }
    if (!isOurPage(request.frame?.url ?? '')) {
      respond({})   // 우리 페이지가 아니면 거절
      return
    }
    if (targets.sourceId && !targets.owns(webContents.fromFrame(request.frame))) { respond({}); return }
    const choice = chosenSource
    const version = selectionVersion
    chosenSource = null
    if (choice === null) { respond({}); return }
    try {
      const sources = await availableSources()
      const primary = String(screen.getPrimaryDisplay().id)
      // 선택한 창이 사라지면 거절한다. 전체 모니터는 명시적인 빈 선택(도움 요청 동의) 때만 허용한다.
      const pick = choice === ''
        ? sources.find((s) => s.id.startsWith('screen:') && s.display_id === primary)
        : sources.find((s) => s.id === choice)
      // A selected help target cannot be replaced by a different window or monitor.
      respond(pick && version === selectionVersion && (!targets.sourceId || targets.sourceId === choice) ? { video: pick } : {})
    } catch { respond({}) }
  })
}

/** 오버레이 보이기/숨기기. 주 모니터 전체를 덮는 투명 · 클릭 통과 · 항상 위 창 */
function showOverlay(on, base, webPreferences, own, harden) {
  overlayWanted = on === true
  if (!on) {
    selectionVersion++
    chosenSource = null
    targets.clear()
    overlay?.close()
    overlay = null
    return
  }
  if (overlay) { placeOverlay(); return }
  if (!targets.sourceId) return
  const bounds = { x: 0, y: 0, width: 1, height: 1 }
  overlay = new BrowserWindow({
    ...bounds,
    frame: false,
    transparent: true,
    resizable: false,
    movable: false,
    focusable: false,
    skipTaskbar: true,
    hasShadow: false,
    alwaysOnTop: true,
    show: false,
    webPreferences,
  })
  own(overlay.webContents)
  harden(overlay.webContents)
  // Windows 는 새 창을 작업 표시줄 위(작업 영역)로 줄여서 만든다 → 공유 화면(모니터 전체)과 그림 위치가 어긋남. 만든 뒤 다시 맞춤
  overlay.setAlwaysOnTop(true, 'screen-saver')
  overlay.setIgnoreMouseEvents(true)
  overlay.setContentProtection(true)   // 오버레이 자체는 공유 화면에 다시 찍히지 않게
  void overlay.loadURL(new URL('/overlay', base).href)
  overlay.webContents.once('did-finish-load', placeOverlay)
  overlay.on('closed', () => {
    overlay = null
  })
}

/** 학생 화면이 받은 그림 메시지를 오버레이 창으로 넘긴다 */
function forwardDraw(message) {
  if (typeof message !== 'string' || message.length > 200_000) return
  overlay?.webContents.send('overlay-draw', message)
}

module.exports = { chooseShareSource, installDisplayMediaHandler, showOverlay, forwardDraw,
  helpShareSources, prepareHelpShare, setHelpShareVideoSize }
