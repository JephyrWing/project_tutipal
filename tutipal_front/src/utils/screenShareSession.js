const participantQueues = new WeakMap()

/** Owns capture even when selection/publishing finishes after stop or unmount. */
export function createScreenShareSession({ participant, prepare, onSharing, onTrack, onError,
  requiresConsent = false, selectSource = () => {}, clearSource = () => {} }) {
  let disposed = false
  let pending = false
  let version = 0
  let track = null
  let requested = false
  let disableQueue = Promise.resolve()
  const enqueue = operation => {
    const next = (participantQueues.get(participant) ?? Promise.resolve()).then(operation)
    participantQueues.set(participant, next.catch(() => undefined))
    return next
  }
  const report = error => { if (!disposed) onError(error) }
  const ended = () => { void stop() }
  const clearTrack = () => {
    if (track) {
      track.mediaStreamTrack?.removeEventListener('ended', ended)
      track.stop()
      track = null
    }
    if (!disposed) { onTrack(null); onSharing(false) }
  }
  const disable = () => {
    if (!requested) return disableQueue
    requested = false
    disableQueue = enqueue(async () => {
      clearSource()
      await participant.setScreenShareEnabled(false)
    }).catch(report)
    return disableQueue
  }
  function stop() {
    version++
    clearTrack()
    return disable()
  }
  return {
    async start(consented) {
      if (disposed || pending || track || (requiresConsent && consented !== true)) return false
      pending = true
      const attempt = ++version
      try {
        await disableQueue
        if (disposed || attempt !== version || await prepare() === false) return false
        if (disposed || attempt !== version) return false
        requested = true
        const publication = await enqueue(() => {
          if (disposed || attempt !== version) return undefined
          selectSource()
          return participant.setScreenShareEnabled(true, { audio: false })
        })
        const next = publication?.track
        if (disposed || attempt !== version) {
          next?.stop()
          await disable()
          return false
        }
        if (!next || next.mediaStreamTrack?.readyState === 'ended') {
          next?.stop()
          throw new Error('공유할 화면을 가져오지 못했어요. 다시 선택해 주세요')
        }
        track = next
        track.mediaStreamTrack?.addEventListener('ended', ended, { once: true })
        onTrack(track)
        onSharing(true)
        return true
      } catch (error) {
        if (!disposed && attempt === version) report(error)
        clearTrack()
        await disable()
        return false
      } finally { pending = false }
    },
    stop,
    dispose() {
      disposed = true
      return stop()
    },
  }
}
