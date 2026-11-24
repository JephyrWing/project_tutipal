const { captureBounds } = require('./shareGeometry.cjs')

/** Main-only authority. A source ID must be in a fresh capturer snapshot. */
function createHelpTargetManager({ getSources, getDisplays, watchWindow, canTrackWindow, onGeometry, onEnd }) {
  let target = null, version = 0, watcher = null, monitor = null
  const clear = () => {
    version++
    watcher?.dispose(); watcher = null
    clearInterval(monitor); monitor = null
    target = null
    onGeometry()
  }
  const end = reason => {
    const owner = target?.owner
    clear()
    if (owner) onEnd(owner, reason)
  }
  const geometry = () => {
    if (!target) return null
    if (target.kind === 'screen') {
      const display = getDisplays().find(d => String(d.id) === target.displayId)
      return display ? { bounds: display.bounds, physical: false, visible: true } : null
    }
    const bounds = target.state && captureBounds(target.state, target.size)
    return bounds ? { bounds, physical: true, visible: target.state.foreground === true } : null
  }
  return {
    clear, geometry,
    get sourceId() { return target?.id ?? null },
    owns(owner) { return target?.owner === owner },
    async prepare(sourceId, owner) {
      clear()
      const attempt = version
      if (typeof sourceId !== 'string' || sourceId.length > 200 || !sourceId) throw new Error('공유할 창을 먼저 골라 주세요')
      const source = (await getSources()).find(s => s.id === sourceId)
      if (attempt !== version) throw new Error('창 선택이 취소됐어요')
      if (!source) throw new Error('선택한 창이 닫혔어요. 다시 골라 주세요')
      if (sourceId.startsWith('screen:')) {
        if (!getDisplays().some(d => String(d.id) === String(source.display_id))) throw new Error('이 화면의 위치를 확인할 수 없어요')
        target = { id: sourceId, kind: 'screen', displayId: String(source.display_id), owner }
        monitor = setInterval(() => {
          if (!geometry()) end('공유하던 화면이 연결 해제됐어요')
          else onGeometry()
        }, 250)
      } else {
        if (!canTrackWindow()) throw new Error('이 환경에서는 창 공유를 지원하지 않아요. 전체 화면을 선택해 주세요')
        target = { id: sourceId, kind: 'window', owner, state: null, size: null }
        watcher = watchWindow(sourceId, state => {
          if (attempt !== version) return
          if (state.state !== 'ok') { end('공유하던 창이 닫혔거나 최소화됐어요. 다시 선택해 주세요'); return }
          target.state = state
          onGeometry()
        })
        try { await watcher.ready } catch (error) { if (attempt === version) clear(); throw error }
      }
      if (attempt !== version) throw new Error('창 선택이 취소됐어요')
      return { id: sourceId, kind: target.kind }
    },
    setVideoSize(owner, id, width, height) {
      if (!target || target.owner !== owner || target.id !== id) return
      if (!Number.isInteger(width) || !Number.isInteger(height) || width < 1 || height < 1 || width > 32768 || height > 32768) return
      target.size = { width, height }
      onGeometry()
    },
  }
}
module.exports = { createHelpTargetManager }
