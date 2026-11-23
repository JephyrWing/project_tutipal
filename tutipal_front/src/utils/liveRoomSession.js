import { startRoomConnection } from './roomConnection.js'

/** One mounted view owns one room. Every new attempt obtains Spring approval. */
export function createLiveRoomSession({ room, events, getInfo, resolveUrl, onRoom, onVideo, onState, onError }) {
  let disposed = false
  let terminal = false
  let video = null
  const listeners = []
  const listen = (event, callback) => {
    const guarded = (...args) => { if (!disposed && !terminal) callback(...args) }
    room.on(event, guarded)
    listeners.push([event, guarded])
  }
  const clearVideo = () => { video = null; onVideo(null) }
  const restore = () => {
    video = null
    onState('connected')
    onRoom(room)
    for (const participant of room.remoteParticipants.values()) {
      for (const publication of participant.trackPublications.values()) {
        if (publication.track?.kind === 'video') video = publication.track
      }
    }
    onVideo(video)
  }
  listen(events.TrackSubscribed, track => {
    if (track.kind === 'video') { video = track; onVideo(track) }
  })
  listen(events.TrackUnsubscribed, track => { if (track === video) clearVideo() })
  listen(events.SignalReconnecting, () => onState('signal-reconnecting'))
  listen(events.Reconnecting, () => {
    for (const publication of room.localParticipant.trackPublications.values()) publication.track?.stop()
    onState('reconnecting')
    onRoom(null)
    clearVideo()
  })
  listen(events.Reconnected, restore)
  listen(events.Disconnected, () => {
    terminal = true
    onRoom(null)
    clearVideo()
    onState('disconnected')
    onError(new Error('화면 공유 연결이 끊겼어요. 다시 연결해 주세요'))
    void attempt.cancel()
  })
  onState('connecting')
  const attempt = startRoomConnection({
    room, getInfo, resolveUrl,
    onConnected: () => { if (!terminal) restore() },
    onError: error => {
      onRoom(null)
      clearVideo()
      onState('disconnected')
      onError(error)
    },
  })
  return {
    done: attempt.done,
    dispose() {
      disposed = true
      for (const [event, callback] of listeners) room.off(event, callback)
      // Stop capture before waiting for the asynchronous signalling shutdown.
      for (const publication of room.localParticipant.trackPublications.values()) publication.track?.stop()
      return attempt.cancel()
    },
  }
}
