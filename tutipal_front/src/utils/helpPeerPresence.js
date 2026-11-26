/** In a student's approved help room, remote participants are viewing teachers. */
export function watchHelpPeers({ room, events, onChange, onLastPeerLeft }) {
  const peers = new Set([...room.remoteParticipants.values()].map(peer => peer.identity))
  let disposed = false
  const joined = peer => {
    if (disposed) return
    peers.add(peer.identity)
    onChange(peers.size > 0)
  }
  const left = peer => {
    if (disposed || !peers.delete(peer.identity)) return
    if (peers.size === 0) onLastPeerLeft()
    onChange(peers.size > 0)
  }
  room.on(events.ParticipantConnected, joined)
  room.on(events.ParticipantDisconnected, left)
  onChange(peers.size > 0)
  return {
    hasPeer: () => !disposed && peers.size > 0,
    dispose() {
      disposed = true
      room.off(events.ParticipantConnected, joined)
      room.off(events.ParticipantDisconnected, left)
    },
  }
}
