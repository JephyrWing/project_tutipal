import { createHmac } from 'node:crypto'

// Node-only diagnostic. No admin token or secret enters a renderer or report.
export async function participantCount(room) {
  if (!/^session-\d+-help-\d+$/.test(room)) throw Error('Unexpected diagnostic room')
  const secret = process.env.E2E_LIVEKIT_SECRET ?? ''
  if (!/^[a-f0-9]{64}$/.test(secret)) throw Error('Disposable diagnostic secret missing')
  const encode = value => Buffer.from(JSON.stringify(value)).toString('base64url')
  const now = Math.floor(Date.now() / 1000)
  const body = encode({ alg: 'HS256', typ: 'JWT' }) + '.' + encode({
    iss: 'disposable-e2e', nbf: now - 5, exp: now + 60, video: { room, roomAdmin: true },
  })
  const token = body + '.' + createHmac('sha256', secret).update(body).digest('base64url')
  const response = await fetch('http://127.0.0.1:28082/twirp/livekit.RoomService/ListParticipants', {
    method: 'POST', headers: { Authorization: 'Bearer ' + token, 'Content-Type': 'application/json' },
    body: JSON.stringify({ room }), signal: AbortSignal.timeout(5000),
  })
  if (response.status === 404) return 0
  if (!response.ok) throw Error('LiveKit participant diagnostic unavailable')
  const result = await response.json()
  // ProtoJSON may omit an empty repeated field in a successful empty response.
  if (result && typeof result === 'object' && Object.keys(result).length === 0) return 0
  if (!Array.isArray(result.participants)) throw Error('Invalid participant diagnostic response')
  return result.participants.length
}
