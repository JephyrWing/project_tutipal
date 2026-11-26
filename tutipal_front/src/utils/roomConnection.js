/** A disposed React effect must never start a late LiveKit connection. */
export function startRoomConnection({ room, getInfo, resolveUrl, onConnected, onError }) {
  let cancelled = false
  let disconnecting
  const disconnect = () => {
    disconnecting ??= Promise.resolve().then(() => room.disconnect()).catch(() => undefined)
    return disconnecting
  }
  const done = (async () => {
    try {
      const info = await getInfo()
      if (cancelled) return
      await room.connect(resolveUrl(info.url), info.token)
      if (cancelled) {
        await disconnect()
        return
      }
      onConnected(room)
    } catch (error) {
      if (!cancelled) onError(error)
    }
  })()
  return {
    done,
    cancel() {
      cancelled = true
      return disconnect()
    },
  }
}
