import assert from 'node:assert/strict'
import { before, after, test } from 'node:test'
import { randomBytes } from 'node:crypto'
import { spawn } from 'node:child_process'
import { setTimeout as delay } from 'node:timers/promises'
import { fileURLToPath } from 'node:url'
import WebSocket from 'ws'
import { fakeLiveKit } from './helpers/fakeLiveKit.mjs'

const base = 'http://127.0.0.1:28080'
const secret = randomBytes(32).toString('hex')
const provider = fakeLiveKit(secret), sockets = new Set()
let backend
async function until(check, timeout = 12000) {
  const deadline = Date.now() + timeout
  while (Date.now() < deadline) {
    if (await check()) return
    await delay(100)
  }
  throw Error('Integration condition did not occur before timeout')
}
class Client {
  cookies = new Map()
  cookie() { return [...this.cookies].map(([k, v]) => `${k}=${v}`).join('; ') }
  async request(path, method = 'GET', body) {
    const headers = { Origin: base }
    if (method !== 'GET') {
      const csrf = await this.request('/api/auth/csrf')
      assert.equal(csrf.status, 200)
      headers[csrf.body.headerName] = csrf.body.token
    }
    headers.Cookie = this.cookie()
    if (body !== undefined) headers['Content-Type'] = 'application/json'
    const response = await fetch(base + path, { method, headers, redirect: 'manual',
      signal: AbortSignal.timeout(10000), body: body === undefined ? undefined : JSON.stringify(body) })
    for (const value of response.headers.getSetCookie()) {
      const [pair] = value.split(';'), split = pair.indexOf('=')
      const name = pair.slice(0, split)
      if (/Max-Age=0/i.test(value)) this.cookies.delete(name)
      else this.cookies.set(name, pair.slice(split + 1))
    }
    return { status: response.status, body: await response.json().catch(() => null) }
  }
  async login(n) { assert.equal((await this.request(`/dev/login?n=${n}`, 'POST')).status, 200) }
}
async function context(shareScreen = true) {
  const teacher = new Client(), student = new Client(), peer = new Client()
  await teacher.login(1); await student.login(2); await peer.login(3)
  const created = await teacher.request('/api/sessions', 'POST', { title: 'PT65 isolated access', topic: '수학', aiRetentionDays: 30 })
  assert.equal(created.status, 201)
  const room = created.body
  for (const client of [student, peer]) assert.equal((await client.request('/api/sessions/join', 'POST', { code: room.inviteCode })).status, 200)
  await until(async () => (await teacher.request(`/api/sessions/${room.id}/screenshare/token`, 'POST')).status === 200)
  const requested = await student.request(`/api/sessions/${room.id}/help`, 'POST', { message: 'PT65 synthetic request', shareScreen })
  assert.equal(requested.status, 201)
  return { teacher, student, peer, room, help: requested.body }
}
async function token(client, help) {
  const result = await client.request(`/api/help/${help.id}/token`, 'POST')
  assert.equal(result.status, 200)
  assert.ok(result.body.token === 'session-bound', 'Expected session-bound placeholder')
  assert.ok(/^\/livekit\/[a-f0-9-]+$/.test(result.body.url), 'Expected opaque proxy URL')
  return result.body
}
async function connect(client, grant) {
  const ws = new WebSocket(base.replace('http:', 'ws:') + grant.url + '/rtc', {
    headers: { Cookie: client.cookie(), Origin: base }, handshakeTimeout: 5000,
  })
  sockets.add(ws)
  ws.on('error', () => {})
  ws.once('close', () => sockets.delete(ws))
  await new Promise((resolve, reject) => {
    ws.once('open', resolve)
    ws.once('error', () => reject(Error('Synthetic proxy connection failed')))
  })
  await new Promise((resolve, reject) => {
    const interval = setInterval(() => { if (ws.readyState === WebSocket.OPEN) ws.send('pt65-probe') }, 100)
    const timeout = setTimeout(() => { cleanup(); reject(Error('Proxy echo not received')) }, 5000)
    function cleanup() { clearInterval(interval); clearTimeout(timeout); ws.off('message', received) }
    function received(data) { if (data.toString() === 'pt65-probe') { cleanup(); resolve() } }
    ws.on('message', received)
  })
  return ws
}
async function revoked(ws, grant) {
  await until(() => ws.readyState === WebSocket.CLOSED && provider.removals.some(r => r.room === grant.room && r.identity === grant.identity))
}
const validate = (client, grant) => client.request(grant.url + '/rtc/validate')
const endClass = async ({ teacher, room }) => assert.equal((await teacher.request(`/api/sessions/${room.id}/end`, 'POST')).status, 200)

before(async () => {
  if (!/^jdbc:postgresql:\/\/127\.0\.0\.1:\d+\/tutipal_e2e(?:_[a-z0-9]+)?$/.test(process.env.E2E_DB_URL ?? '') || !process.env.E2E_DB_PASSWORD) {
    throw Error('Requires explicit disposable loopback E2E database; real .env files are never loaded')
  }
  await provider.start()
  backend = spawn(process.execPath, ['scripts/e2e-server.mjs'], {
    cwd: fileURLToPath(new URL('../', import.meta.url)), windowsHide: true, stdio: 'ignore',
    env: { ...process.env, E2E_HELP_ACCESS: 'true', E2E_LIVEKIT_SECRET: secret },
  })
  backend.on('error', () => {})
  await until(async () => {
    if (backend.exitCode !== null) throw Error('Disposable backend stopped before readiness')
    try { return (await fetch(base + '/actuator/health', { signal: AbortSignal.timeout(1000) })).ok } catch { return false }
  }, 90000)
}, { timeout: 100000 })
after(async () => {
  sockets.forEach(ws => ws.terminate())
  if (backend && backend.exitCode === null) {
    backend.kill('SIGTERM')
    try { await until(() => backend.exitCode !== null, 15000) } catch { backend.kill('SIGKILL') }
  }
  await provider.close()
})

test('accepted help restricts grants and ending revokes an established proxy', { timeout: 30000 }, async () => {
  const c = await context()
  assert.equal((await c.student.request(`/api/help/${c.help.id}/token`, 'POST')).status, 409)
  assert.equal((await c.teacher.request(`/api/help/${c.help.id}/accept`, 'POST')).status, 200)
  const grant = await token(c.student, c.help)
  assert.equal((await c.peer.request(`/api/help/${c.help.id}/token`, 'POST')).status, 403)
  assert.equal((await validate(c.peer, grant)).status, 401)
  assert.equal((await validate(c.student, grant)).status, 200)
  const ws = await connect(c.student, grant)
  assert.deepEqual(provider.grants.at(-1), { identity: grant.identity, room: grant.room,
    canPublish: true, canSubscribe: true, canPublishData: false, sources: ['screen_share'] })
  assert.equal((await c.teacher.request(`/api/help/${c.help.id}/end`, 'POST')).status, 204)
  await revoked(ws, grant)
  assert.equal((await validate(c.student, grant)).status, 409)
  assert.equal((await c.student.request(`/api/help/${c.help.id}/token`, 'POST')).status, 409)
  await endClass(c)
})
test('logout revokes the proxy and re-login cannot reuse the old session mapping', { timeout: 30000 }, async () => {
  const c = await context(false)
  assert.equal((await c.teacher.request(`/api/help/${c.help.id}/accept`, 'POST')).status, 200)
  const old = await token(c.student, c.help), ws = await connect(c.student, old)
  assert.equal((await c.student.request('/logout', 'POST')).status, 204)
  await revoked(ws, old)
  await c.student.login(2)
  assert.equal((await validate(c.student, old)).status, 401)
  const fresh = await token(c.student, c.help)
  assert.ok(fresh.url !== old.url, 'Re-login must create a new mapping')
  assert.equal((await validate(c.student, fresh)).status, 200)
  assert.equal(provider.grants.at(-1).canPublish, false)
  assert.equal(provider.grants.at(-1).canPublishData, false)
  assert.equal((await c.teacher.request(`/api/help/${c.help.id}/end`, 'POST')).status, 204)
  await endClass(c)
})
test('participant removal and class closure revoke existing help connections', { timeout: 40000 }, async () => {
  const c = await context()
  assert.equal((await c.teacher.request(`/api/help/${c.help.id}/accept`, 'POST')).status, 200)
  const grant = await token(c.student, c.help), ws = await connect(c.student, grant)
  const participants = await c.teacher.request(`/api/sessions/${c.room.id}/participants`)
  assert.equal(participants.status, 200)
  const studentId = Number(grant.identity.slice(1))
  assert.ok(participants.body.some(p => p.userId === studentId))
  assert.equal((await c.teacher.request(`/api/sessions/${c.room.id}/participants/${studentId}`, 'DELETE')).status, 204)
  await revoked(ws, grant)
  assert.equal((await validate(c.student, grant)).status, 404)
  assert.equal((await c.student.request(`/api/help/${c.help.id}/token`, 'POST')).status, 404)
  await endClass(c)
  const second = await context()
  assert.equal((await second.teacher.request(`/api/help/${second.help.id}/accept`, 'POST')).status, 200)
  const next = await token(second.student, second.help), nextWs = await connect(second.student, next)
  await endClass(second)
  await revoked(nextWs, next)
  assert.equal((await validate(second.student, next)).status, 410)
  assert.equal((await second.student.request(`/api/help/${second.help.id}/token`, 'POST')).status, 410)
})

const helpAction = (client, help, action) => client.request('/api/help/' + help.id + '/' + action, 'POST')
const openHelp = (client, room) => client.request('/api/sessions/' + room.id + '/help')
const requestHelp = (client, room) => client.request('/api/sessions/' + room.id + '/help', 'POST',
  { message: 'PT47 synthetic request', shareScreen: false })

test('four accepted requests are the limit even when two acceptance commands overlap', { timeout: 30000 }, async () => {
  const { teacher, peer, room, help } = await context(false)
  const additionalStudents = [new Client(), new Client(), new Client()]
  await Promise.all(additionalStudents.map(async (client, index) => {
    await client.login(index + 4)
    assert.equal((await client.request('/api/sessions/join', 'POST', { code: room.inviteCode })).status, 200)
  }))
  const pending = [help]
  for (const student of [peer, ...additionalStudents]) {
    const result = await requestHelp(student, room)
    assert.equal(result.status, 201)
    pending.push(result.body)
  }
  for (const request of pending.slice(0, 3)) assert.equal((await helpAction(teacher, request, 'accept')).status, 200)

  const contenders = pending.slice(3)
  const results = await Promise.all(contenders.map(request => helpAction(teacher, request, 'accept')))
  assert.deepEqual(results.map(result => result.status).sort(), [200, 409])
  const list = await openHelp(teacher, room)
  assert.equal(list.status, 200)
  assert.equal(list.body.filter(request => request.status === 'ACCEPTED').length, 4)
  assert.equal(list.body.filter(request => request.status === 'PENDING').length, 1)
  const waiting = contenders[results.findIndex(result => result.status === 409)]
  assert.equal((await helpAction(teacher, pending[0], 'end')).status, 204)
  assert.equal((await helpAction(teacher, waiting, 'accept')).status, 200)
  await endClass({ teacher, room })
})

test('student cancellation and teacher acceptance leave no active request when they overlap', { timeout: 30000 }, async () => {
  const { teacher, student, peer, room, help } = await context(false)
  assert.equal((await requestHelp(student, room)).status, 409)
  assert.deepEqual((await openHelp(peer, room)).body, [])
  assert.equal((await helpAction(peer, help, 'end')).status, 403)

  const [accepted, cancelled] = await Promise.all([
    helpAction(teacher, help, 'accept'),
    helpAction(student, help, 'end'),
  ])
  assert.ok([200, 409].includes(accepted.status), 'Acceptance may precede or follow cancellation')
  assert.equal(cancelled.status, 204)
  assert.deepEqual((await openHelp(teacher, room)).body, [])
  assert.deepEqual((await openHelp(student, room)).body, [])
  assert.equal((await helpAction(teacher, help, 'accept')).status, 409)

  const next = await requestHelp(student, room)
  assert.equal(next.status, 201)
  assert.equal((await helpAction(teacher, next.body, 'later')).status, 204)
  assert.deepEqual((await openHelp(student, room)).body, [])
  assert.equal((await requestHelp(student, room)).status, 201)
  await endClass({ teacher, room })
})

test('a closed class rejects accept, later, cancellation and end of existing requests', { timeout: 30000 }, async () => {
  const { teacher, student, peer, room, help } = await context(false)
  const second = await requestHelp(peer, room)
  assert.equal(second.status, 201)
  assert.equal((await helpAction(teacher, second.body, 'accept')).status, 200)
  await endClass({ teacher, room })

  assert.equal((await helpAction(teacher, help, 'accept')).status, 410)
  assert.equal((await helpAction(teacher, help, 'later')).status, 410)
  assert.equal((await helpAction(student, help, 'end')).status, 410)
  assert.equal((await helpAction(teacher, second.body, 'end')).status, 410)
  assert.equal((await helpAction(peer, second.body, 'end')).status, 410)
})
