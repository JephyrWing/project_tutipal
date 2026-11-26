import { createServer } from 'node:http'
import { createHmac, timingSafeEqual } from 'node:crypto'
import { WebSocketServer } from 'ws'

// Synthetic control plane: never receives media or real credentials.
export function fakeLiveKit(secret) {
  const grants = [], removals = [], sockets = new Set()
  function verify(token) {
    const [head, body, signature] = (token ?? '').split('.')
    const expected = createHmac('sha256', secret).update(`${head}.${body}`).digest()
    const actual = Buffer.from(signature ?? '', 'base64url')
    if (actual.length !== expected.length || !timingSafeEqual(actual, expected)) throw Error('Invalid signature')
    const header = JSON.parse(Buffer.from(head, 'base64url'))
    const claims = JSON.parse(Buffer.from(body, 'base64url'))
    const now = Date.now() / 1000
    if (header.alg !== 'HS256' || claims.iss !== 'disposable-e2e' || !(claims.exp > now) || claims.nbf > now) throw Error('Invalid claims')
    return claims
  }
  function remember(c) {
    const v = c.video
    if (!c.sub || !v?.roomJoin || !v.room) throw Error('Missing grant')
    grants.push({ identity: c.sub, room: v.room, canPublish: v.canPublish,
      canSubscribe: v.canSubscribe, canPublishData: v.canPublishData, sources: v.canPublishSources ?? [] })
  }
  const json = (res, value) => res.writeHead(200, { 'Content-Type': 'application/json' }).end(JSON.stringify(value))
  // Java HttpClient offers h2c on validation GETs. Keep those as HTTP/1 requests;
  // only actual WebSocket handshakes belong to the WS upgrade handler.
  const server = createServer({ shouldUpgradeCallback: req => req.headers.upgrade?.toLowerCase() === 'websocket' }, async (req, res) => {
    try {
      const url = new URL(req.url, 'http://127.0.0.1')
      if (req.method === 'GET' && url.pathname === '/rtc/validate') {
        remember(verify(url.searchParams.get('access_token')))
        return json(res, { valid: true })
      }
      const c = verify(req.headers.authorization?.replace(/^Bearer /, ''))
      if (req.method !== 'POST') return res.writeHead(404).end()
      const chunks = []
      for await (const chunk of req) chunks.push(chunk)
      const body = JSON.parse(Buffer.concat(chunks).toString())
      if (url.pathname.endsWith('/ListRooms') && c.video?.roomList) return json(res, { rooms: [] })
      if (url.pathname.endsWith('/ListParticipants') && c.video?.roomAdmin) return json(res, { participants: [] })
      if (url.pathname.endsWith('/RemoveParticipant') && c.video?.roomAdmin && c.video.room === body.room) {
        removals.push({ room: body.room, identity: body.identity })
        // Spring must close the proxy; this stand-in deliberately keeps it open.
        return json(res, {})
      }
      res.writeHead(403).end()
    } catch { res.writeHead(403).end() }
  })
  const wss = new WebSocketServer({ noServer: true })
  server.on('upgrade', (req, socket, head) => {
    try {
      const url = new URL(req.url, 'http://127.0.0.1')
      if (url.pathname !== '/rtc') throw Error('Unexpected path')
      remember(verify(url.searchParams.get('access_token')))
      wss.handleUpgrade(req, socket, head, ws => {
        sockets.add(ws)
        ws.on('error', () => {})
        ws.on('close', () => sockets.delete(ws))
        ws.on('message', (data, binary) => ws.send(data, { binary }))
      })
    } catch { socket.end('HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n') }
  })
  return { grants, removals,
    start: () => new Promise((resolve, reject) => {
      server.once('error', reject)
      server.listen(28082, '127.0.0.1', resolve)
    }),
    close: async () => {
      sockets.forEach(ws => ws.terminate())
      await new Promise(resolve => wss.close(resolve))
      if (server.listening) await new Promise(resolve => server.close(resolve))
    },
  }
}
