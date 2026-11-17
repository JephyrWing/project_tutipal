const { BrowserWindow, desktopCapturer, screen, session } = require('electron')

/**
 * 화면공유(LiveKit) 를 위한 Electron 쪽 준비.
 * 1) 화면이 getDisplayMedia() 를 부르면, 미리 고른 창/화면을 허용한다 (Electron 은 기본 선택 창이 없음).
 *    도움 요청(학생 → 선생님)은 그림 위치가 맞도록 주 모니터 전체를 공유한다.
 * 2) 학생 PC 위의 투명 오버레이 창(목업 19)에 선생님 그림을 그린다. 클릭은 아래 프로그램으로 통과.
 */
let chosenSource = null
let overlay = null

/** 화면이 다음 getDisplayMedia 에서 공유할 창/화면 id (빈 값 = 주 모니터) */
function chooseShareSource(id) {
  chosenSource = typeof id === 'string' && id.length <= 200 ? id : null
}

function installDisplayMediaHandler(isOurPage) {
  session.defaultSession.setDisplayMediaRequestHandler(async (request, callback) => {
    if (!isOurPage(request.frame?.url ?? '')) {
      callback({})   // 우리 페이지가 아니면 거절
      return
    }
    const choice = chosenSource
    chosenSource = null
    if (choice === null) { callback({}); return }
    try {
      const sources = await desktopCapturer.getSources({ types: ['screen', 'window'] })
      const primary = String(screen.getPrimaryDisplay().id)
      // 선택한 창이 사라지면 거절한다. 전체 모니터는 명시적인 빈 선택(도움 요청 동의) 때만 허용한다.
      const pick = choice === ''
        ? sources.find((s) => s.id.startsWith('screen:') && s.display_id === primary)
        : sources.find((s) => s.id === choice)
      callback(pick ? { video: pick } : {})
    } catch { callback({}) }
  })
}

/** 오버레이 보이기/숨기기. 주 모니터 전체를 덮는 투명 · 클릭 통과 · 항상 위 창 */
function showOverlay(on, base, webPreferences, own, harden) {
  if (!on) {
    overlay?.close()
    overlay = null
    return
  }
  if (overlay) return
  const { bounds } = screen.getPrimaryDisplay()
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
    webPreferences,
  })
  own(overlay.webContents)
  harden(overlay.webContents)
  // Windows 는 새 창을 작업 표시줄 위(작업 영역)로 줄여서 만든다 → 공유 화면(모니터 전체)과 그림 위치가 어긋남. 만든 뒤 다시 맞춤
  overlay.setBounds(bounds)
  overlay.setAlwaysOnTop(true, 'screen-saver')
  overlay.setIgnoreMouseEvents(true)
  overlay.setContentProtection(true)   // 오버레이 자체는 공유 화면에 다시 찍히지 않게
  void overlay.loadURL(new URL('/overlay', base).href)
  overlay.on('closed', () => {
    overlay = null
  })
}

/** 학생 화면이 받은 그림 메시지를 오버레이 창으로 넘긴다 */
function forwardDraw(message) {
  if (typeof message !== 'string' || message.length > 200_000) return
  overlay?.webContents.send('overlay-draw', message)
}

module.exports = { chooseShareSource, installDisplayMediaHandler, showOverlay, forwardDraw }
