/** Sharing permission on an old request is not fresh consent to capture a screen. */
export function createHelpScreenShare({ participant, canShare, chooseSource, onSharing, onError }) {
  let disposed = false
  let pending = false
  let sharing = false
  let requested = false
  let version = 0
  const disable = () => Promise.resolve().then(() => participant.setScreenShareEnabled(false))
    .catch(error => { if (!disposed) onError(error) })
  return {
    async start(consented) {
      if (disposed || !canShare || consented !== true || pending || sharing) return false
      pending = true
      const attempt = ++version
      try {
        chooseSource()
        requested = true
        await participant.setScreenShareEnabled(true, { audio: false })
        if (disposed || attempt !== version) {
          await disable()
          return false
        }
        sharing = true
        onSharing(true)
        return true
      } catch (error) {
        if (!disposed && attempt === version) onError(error)
        return false
      } finally {
        pending = false
      }
    },
    async stop() {
      version++
      sharing = false
      if (!disposed) onSharing(false)
      if (requested) await disable()
    },
    dispose() {
      disposed = true
      version++
      return requested ? disable() : Promise.resolve()
    },
  }
}
