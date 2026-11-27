const http = require('node:http')
const { readFile } = require('node:fs/promises')
const path = require('node:path')
const { Readable } = require('node:stream')
const { randomBytes, timingSafeEqual } = require('node:crypto')
const { session, app } = require('electron')
const { WebSocket, WebSocketServer } = require('ws')
const { createAuthBoundary } = require('./authBoundary.cjs')

const BACKEND_PATH = /^(?:\/api(?:\/|$)|\/livekit(?:\/|$)|\/dev\/login$|\/logout$|\/health$|\/actuator\/health(?:\/|$))/
const TYPES = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.json': 'application/json', '.png': 'image/png', '.svg': 'image/svg+xml', '.webp': 'image/webp', '.woff2': 'font/woff2' }

/** 내장 화면만 loopback으로 제공한다. Spring의 쿠키는 Electron 세션에 보관한다. */
async function startBridge(backend, { onAuthBoundary = () => {} } = {}) {
  const auth = createAuthBoundary(onAuthBoundary)
  const secret = randomBytes(32).toString('hex')
  const owned = new Set()
  const sockets = new Set()
  let origin
  const dev = !app.isPackaged && process.env.TUTIPAL_RENDERER_URL === 'http://127.0.0.1:5173'
  const root = path.resolve(__dirname, '../dist')
  const validSecret = (value) => typeof value === 'string' && /^[a-f0-9]{64}$/.test(value) && timingSafeEqual(Buffer.from(value), Buffer.from(secret))
  const authorized = (req) => req.headers.host === new URL(origin).host && validSecret(req.headers['x-tutipal-bridge']) && (!req.headers.origin || req.headers.origin === origin)
  const csp = () => `default-src 'self'; script-src 'self'${dev ? " 'unsafe-inline'" : ''}; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; font-src 'self'; connect-src 'self' ${origin.replace(/^http:/, 'ws:')}; media-src 'self' blob:; object-src 'none'; base-uri 'self'; frame-src 'none'`

  async function proxy(req, res, target) {
    const backendRequest = target.origin === backend.origin
    if (backendRequest && (target.pathname === '/logout' || target.pathname === '/dev/login'
        || target.pathname === '/api/users/me' && req.method === 'DELETE')) auth.revoke()
    const ownAuth = auth.revision()
    const abort = new AbortController()
    res.on('close', () => abort.abort())
    const headers = {}
    for (const [name, value] of Object.entries(req.headers)) {
      if (!['host', 'cookie', 'origin', 'referer', 'connection', 'content-length', 'x-tutipal-bridge', 'accept-encoding'].includes(name) && value !== undefined) headers[name] = value
    }
    // Spring은 실제 서버 출처를 검사한다. 화면은 자기 loopback 출처만 호출한다.
    headers.Origin = backend.origin
    const options = { method: req.method, headers, credentials: 'include', signal: abort.signal, redirect: 'manual' }
    if (!['GET', 'HEAD'].includes(req.method)) {
      const chunks = []
      let size = 0
      for await (const part of req) {
        size += part.length
        if (size > 55 * 1024 * 1024) { res.writeHead(413).end(); return }
        chunks.push(part)
      }
      options.body = Buffer.concat(chunks)
    }
    // Vite 자원은 인증 쿠키가 필요 없다. Chromium의 모듈 CORS 문맥을 이어받지 않는 Node fetch로 읽는다.
    const response = target.origin === backend.origin
      ? await session.defaultSession.fetch(target.href, options)
      : await fetch(target.href, { method: req.method, signal: abort.signal })
    if (backendRequest && response.status === 401) auth.unauthorized(ownAuth)
    res.statusCode = response.status
    for (const [name, value] of response.headers) {
      if (!['set-cookie', 'content-encoding', 'content-length', 'transfer-encoding', 'connection', 'access-control-allow-origin', 'content-security-policy'].includes(name)) res.setHeader(name, value)
    }
    res.setHeader('Cache-Control', 'no-store')
    res.setHeader('Content-Security-Policy', csp())
    if (response.body && req.method !== 'HEAD') Readable.fromWeb(response.body).on('error', () => res.destroy()).pipe(res)
    else res.end()
  }

  const server = http.createServer(async (req, res) => {
    res.setHeader('X-Content-Type-Options', 'nosniff')
    res.setHeader('Referrer-Policy', 'no-referrer')
    if (!authorized(req)) { res.writeHead(403).end(); return }
    try {
      const url = new URL(req.url, origin)
      if (BACKEND_PATH.test(url.pathname)) return await proxy(req, res, new URL(url.pathname + url.search, backend))
      if (!['GET', 'HEAD'].includes(req.method)) { res.writeHead(405).end(); return }
      if (dev) return await proxy(req, res, new URL(url.pathname + url.search, process.env.TUTIPAL_RENDERER_URL))
      let file = path.resolve(root, `.${decodeURIComponent(url.pathname)}`)
      const relative = path.relative(root, file)
      if (relative.startsWith('..') || path.isAbsolute(relative)) { res.writeHead(403).end(); return }
      if (!path.extname(file)) file = path.join(root, 'index.html')
      const body = await readFile(file)
      res.setHeader('Content-Type', TYPES[path.extname(file)] ?? 'application/octet-stream')
      res.setHeader('Content-Security-Policy', csp())
      res.setHeader('Cache-Control', 'no-store')
      res.end(req.method === 'HEAD' ? undefined : body)
    } catch (error) {
      if (dev && !BACKEND_PATH.test(req.url)) console.error('Development renderer request failed:', error.message)
      if (!res.headersSent) res.writeHead(BACKEND_PATH.test(req.url) ? 502 : 404, { 'Content-Type': 'application/json' })
      res.end(JSON.stringify({ message: '서버 연결 또는 내장 파일을 확인해 주세요' }))
    }
  })
  const wss = new WebSocketServer({ noServer: true, maxPayload: 2 * 1024 * 1024 })
  server.on('upgrade', async (req, socket, head) => {
    if (!authorized(req)) { socket.destroy(); return }
    try {
      const url = new URL(req.url, origin)
      const hmr = dev && req.headers['sec-websocket-protocol'] === 'vite-hmr'
      if (url.pathname !== '/ws' && !url.pathname.startsWith('/livekit/') && !hmr) { socket.destroy(); return }
      const target = hmr ? new URL(req.url, process.env.TUTIPAL_RENDERER_URL) : new URL(req.url, backend)
      target.protocol = target.protocol === 'https:' ? 'wss:' : 'ws:'
      const cookies = await session.defaultSession.cookies.get({ url: backend.href })
      const headers = { Origin: backend.origin, Cookie: cookies.map(c => `${c.name}=${c.value}`).join('; ') }
      const protocols = (req.headers['sec-websocket-protocol'] ?? '').split(',').map(v => v.trim()).filter(Boolean)
      const upstream = new WebSocket(target.href, protocols, { headers, maxPayload: 2 * 1024 * 1024, handshakeTimeout: 15000 })
      sockets.add(upstream)
      const fail = () => { socket.destroy(); upstream.terminate(); sockets.delete(upstream) }
      upstream.on('error', fail)
      socket.on('error', fail)
      upstream.once('open', () => wss.handleUpgrade(req, socket, head, (client) => {
        sockets.add(client)
        client.on('message', (data, isBinary) => { if (upstream.readyState === WebSocket.OPEN) upstream.send(data, { binary: isBinary }) })
        upstream.on('message', (data, isBinary) => { if (client.readyState === WebSocket.OPEN) client.send(data, { binary: isBinary }) })
        client.on('close', () => { upstream.close(); sockets.delete(client) })
        upstream.on('close', (code) => { client.close(code === 1005 || code === 1006 ? 1000 : code); sockets.delete(upstream) })
        client.on('error', () => upstream.close())
      }))
    } catch { socket.destroy() }
  })
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolve) })
  origin = `http://127.0.0.1:${server.address().port}`
  session.defaultSession.webRequest.onBeforeSendHeaders((details, callback) => {
    const headers = { ...details.requestHeaders }
    if (owned.has(details.webContentsId) && new URL(details.url).origin.replace(/^ws:/, 'http:') === origin) headers['X-Tutipal-Bridge'] = secret
    callback({ requestHeaders: headers })
  })
  return {
    base: new URL(origin),
    invalidateAuth: () => auth.revoke(),
    own(contents) { owned.add(contents.id); contents.once('destroyed', () => owned.delete(contents.id)) },
    close() { for (const socket of sockets) socket.terminate(); wss.close(); server.close() },
  }
}

module.exports = { startBridge }
