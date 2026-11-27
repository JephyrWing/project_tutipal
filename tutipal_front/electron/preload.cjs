const { contextBridge, ipcRenderer } = require('electron')

// 화면(frontend)에 공개하는 기능은 이것뿐. 실제로 할지 말지는 메인 프로세스가 보낸 쪽 주소를 확인하고 정한다.
contextBridge.exposeInMainWorld('desktop', {
  isDesktop: true,
  openChat: (topic) => ipcRenderer.send('open-chat', typeof topic === 'string' ? topic : ''),
  getWidgetChatContext: () => ipcRenderer.invoke('widget-chat-context'),
  onWidgetChatContextChanged: cb => {
    const listener = () => cb()
    ipcRenderer.on('widget-chat-context-changed', listener)
    return () => ipcRenderer.removeListener('widget-chat-context-changed', listener)
  },
  toggleWidget: () => ipcRenderer.send('toggle-widget'),
  // 목업 02: 시스템 브라우저로 소셜 로그인 → tutipal:// 딥링크로 돌아옴
  loginInBrowser: (provider) => ipcRenderer.send('login-in-browser', provider),
  // 목업 04 · 07 · 08: 화면 분석 (고른 창만, 이미지는 저장 안 함)
  openScreenConsent: () => ipcRenderer.send('open-screen-consent'),
  stopScreenAnalysis: () => ipcRenderer.invoke('screen-analysis-stop'),
  completeScreenConsent: () => ipcRenderer.invoke('screen-consent-complete'),
  openReview: (id) => ipcRenderer.send('open-review', id),
  screenSources: () => ipcRenderer.invoke('screen-sources'),
  previewScreenSource: id => ipcRenderer.invoke('screen-preview', id),
  cancelScreenPreview: () => ipcRenderer.invoke('screen-preview-cancel'),
  getScreenSettings: () => ipcRenderer.invoke('screen-settings-get'),
  setScreenSettings: (s) => ipcRenderer.invoke('screen-settings-set', s),
  captureScreen: () => ipcRenderer.invoke('screen-capture'),
  cancelScreenCapture: () => ipcRenderer.invoke('screen-capture-cancel'),
  getScreenTargetStatus: (sourceId) => ipcRenderer.invoke('screen-target-status', sourceId),
  getReviewDeletionState: () => ipcRenderer.invoke('review-deletion-state'),
  beginReviewDeletion: () => ipcRenderer.invoke('review-deletion-start'),
  endReviewDeletion: () => ipcRenderer.invoke('review-deletion-end'),
  onReviewDeletionChanged: cb => {
    const listener = (_event, deleting, ticket) => {
      if (typeof deleting !== 'boolean') return
      cb(deleting)
      if (Number.isSafeInteger(ticket)) ipcRenderer.send('review-deletion-ready', ticket)
    }
    ipcRenderer.on('review-deletion-changed', listener)
    return () => ipcRenderer.removeListener('review-deletion-changed', listener)
  },
  // 4단계 화면공유: 다음 getDisplayMedia 에서 공유할 창 (빈 값 = 주 모니터), 학생 PC 그림 오버레이(목업 19)
  setShareSource: (id) => ipcRenderer.send('share-source', id),
  helpShareSources: () => ipcRenderer.invoke('help-share-sources'),
  prepareHelpShare: id => ipcRenderer.invoke('help-share-prepare', id),
  setHelpShareVideoSize: (id, width, height) => ipcRenderer.send('help-share-size', id, width, height),
  onHelpShareEnded: cb => {
    const listener = (_event, reason) => { if (typeof reason === 'string') cb(reason) }
    ipcRenderer.on('help-share-ended', listener)
    return () => ipcRenderer.removeListener('help-share-ended', listener)
  },
  showOverlay: (on) => ipcRenderer.send('overlay-show', on === true),
  sendOverlayDraw: (message) => ipcRenderer.send('overlay-draw', message),
  onOverlayDraw: (cb) => {
    const listener = (_e, message) => {
      if (typeof message === 'string') cb(message)
    }
    ipcRenderer.on('overlay-draw', listener)
    return () => ipcRenderer.removeListener('overlay-draw', listener)
  },
  // PT-90 집중도 수집: 화면은 수업 id 로 시작 · 중지 · 상태 조회만 한다. 수집 값은 메인 프로세스가 직접 서버로 보낸다.
  focusStart: (sessionId) => ipcRenderer.invoke('focus-start', sessionId),
  focusStop: () => ipcRenderer.invoke('focus-stop'),
  focusState: () => ipcRenderer.invoke('focus-state'),
  onFocusState: (cb) => {
    const listener = (_event, state) => {
      if (state && typeof state === 'object' && !Array.isArray(state)) cb(state)
    }
    ipcRenderer.on('focus-state', listener)
    return () => ipcRenderer.removeListener('focus-state', listener)
  },
  onScreenSettingsChanged: (cb) => {
    const listener = () => cb()
    ipcRenderer.on('screen-settings-changed', listener)
    return () => ipcRenderer.removeListener('screen-settings-changed', listener)
  },
})
