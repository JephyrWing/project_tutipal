import { test, expect, _electron } from '@playwright/test'
import electronPath from 'electron'
import { mkdtemp, readFile, realpath, rm } from 'node:fs/promises'
import path from 'node:path'
import os from 'node:os'
import { fileURLToPath } from 'node:url'
import { login, createRoom, joinRoom } from '../e2e/fixtures.js'

const front = fileURLToPath(new URL('../../', import.meta.url))
const sdk = await readFile(path.join(front, 'node_modules/livekit-client/dist/livekit-client.umd.js'), 'utf8')
const opened = []
async function launch() {
  const profile = await mkdtemp(path.join(os.tmpdir(), 'tutipal-e2e-profile-'))
  const item = { profile, application: null, page: null }
  opened.push(item)
  item.application = await _electron.launch({
    executablePath: electronPath,
    args: ['--allow-loopback-in-peer-connection', '--disable-renderer-backgrounding', path.join(front, 'tests/e2e/entry.cjs')],
    cwd: front,
    chromiumSandbox: true,
    env: { ...process.env, TUTIPAL_E2E_PROFILE: profile, TUTIPAL_SPRING_URL: 'http://127.0.0.1:28080' },
  })
  await expect.poll(() => {
    item.page = item.application.windows().find(p => {
      const u = new URL(p.url())
      return u.protocol === 'http:' && !['/widget', '/overlay'].includes(u.pathname)
    })
    return !!item.page
  }).toBe(true)
  await expect(item.page.getByRole('button', { name: '개발용 로그인 (내 PC 전용)', exact: true })).toBeVisible()
  return item
}
test.afterEach(async () => {
  for (const item of opened.splice(0).reverse()) {
    if (item.page && !item.page.isClosed()) await item.page.evaluate(async () => {
      const m = window.pt65Media
      if (!m) return
      clearInterval(m.paint)
      m.stream?.getTracks().forEach(t => t.stop())
      await m.room.disconnect()
      m.videos.forEach(v => v.remove())
    }).catch(() => {})
    await item.application?.close().catch(() => {})
    const resolved = await realpath(item.profile)
    if (path.dirname(resolved) === await realpath(os.tmpdir()) && path.basename(resolved).startsWith('tutipal-e2e-profile-')) {
      await rm(resolved, { recursive: true, force: true })
    }
  }
})
async function api(app, route, method = 'GET', body) {
  return app.page.evaluate(async ({ route, method, body }) => {
    const headers = {}
    if (method !== 'GET') {
      const csrf = await (await fetch('/api/auth/csrf')).json()
      headers[csrf.headerName] = csrf.token
      if (body !== undefined) headers['Content-Type'] = 'application/json'
    }
    const r = await fetch(route, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) })
    return { status: r.status, body: await r.json().catch(() => null) }
  }, { route, method, body })
}
async function accepted(title) {
  const teacher = await launch(), student = await launch()
  await login(teacher, 1); await login(student, 2)
  const room = await createRoom(teacher, title)
  await joinRoom(student, room.code)
  await expect(student.page).toHaveURL(new RegExp(`/sessions/${room.id}$`))
  await expect.poll(async () => (await api(teacher, `/api/sessions/${room.id}/screenshare/token`, 'POST')).status).toBe(200)
  const request = await api(student, `/api/sessions/${room.id}/help`, 'POST', { message: 'PT65 generated canvas video', shareScreen: true })
  expect(request.status).toBe(201)
  const help = request.body
  expect((await api(teacher, `/api/help/${help.id}/accept`, 'POST')).status).toBe(200)
  return { teacher, student, room, help }
}
async function connect(app, help) {
  const response = await api(app, `/api/help/${help.id}/token`, 'POST')
  expect(response.status).toBe(200)
  const grant = response.body
  expect(grant.token === 'session-bound', 'No internal JWT is exposed to the renderer').toBe(true)
  await app.page.evaluate(sdk)
  const connected = await app.page.evaluate(async grant => {
    const L = window.LivekitClient
    L.setLogLevel('silent')
    const room = new L.Room({ adaptiveStream: false, dynacast: false })
    const m = window.pt65Media = { room, videos: [], tracks: [], disconnected: false }
    room.on(L.RoomEvent.Disconnected, () => { m.disconnected = true })
    room.on(L.RoomEvent.TrackSubscribed, track => {
      if (track.kind !== L.Track.Kind.Video) return
      m.tracks.push(track)
      const video = track.attach()
      video.muted = true
      video.autoplay = true
      document.body.append(video)
      m.videos.push(video)
      void video.play().catch(() => {})
    })
    try {
      const url = new URL(grant.url, location.href)
      url.protocol = 'ws:'
      await room.connect(url.href, grant.token, { rtcConfig: { iceServers: [] } })
      return room.state === 'connected'
    } catch { return false }
  }, grant)
  expect(connected, 'LiveKit SDK connects through the real Electron bridge and Spring proxy').toBe(true)
  return grant
}
async function publish(student) {
  const published = await student.page.evaluate(async () => {
    const m = window.pt65Media, L = window.LivekitClient
    const canvas = document.createElement('canvas')
    canvas.width = 320; canvas.height = 180
    const context = canvas.getContext('2d')
    let frame = 0
    const paint = () => {
      context.fillStyle = frame++ % 2 ? '#215caa' : '#f4a340'
      context.fillRect(0, 0, canvas.width, canvas.height)
      context.fillStyle = '#ffffff'
      context.fillRect((frame * 13) % 280, 40, 40, 60)
    }
    paint()
    m.paint = setInterval(paint, 100)
    m.stream = canvas.captureStream(10)
    try {
      await m.room.localParticipant.publishTrack(m.stream.getVideoTracks()[0], {
        source: L.Track.Source.ScreenShare, simulcast: false, videoCodec: 'vp8',
      })
      return true
    } catch { return false }
  })
  expect(published, 'Only a generated canvas video track is published').toBe(true)
}
async function decoded(teacher, testInfo) {
  let frames = 0
  await expect.poll(async () => {
    frames = await teacher.page.evaluate(async () => {
      let result = 0
      for (const t of window.pt65Media.tracks) result += (await t.getReceiverStats())?.framesDecoded ?? 0
      return result
    })
    return frames
  }).toBeGreaterThan(2)
  await testInfo.attach('synthetic-media-count.json', { body: Buffer.from(JSON.stringify({ framesDecoded: frames, actualScreenCaptured: false })), contentType: 'application/json' })
}
async function disconnected(app) {
  await expect.poll(() => app.page.evaluate(() => window.pt65Media.disconnected && window.pt65Media.room.state === 'disconnected')).toBe(true)
}

test('PT65 real LiveKit decodes generated video and help end removes both connections', async ({}, testInfo) => {
  const c = await accepted('PT65 actual media end')
  await connect(c.teacher, c.help)
  const old = await connect(c.student, c.help)
  await publish(c.student)
  await decoded(c.teacher, testInfo)
  expect((await api(c.teacher, `/api/help/${c.help.id}/end`, 'POST')).status).toBe(204)
  await disconnected(c.student); await disconnected(c.teacher)
  expect((await api(c.student, old.url + '/rtc/validate')).status).toBe(409)
  expect((await api(c.student, `/api/help/${c.help.id}/token`, 'POST')).status).toBe(409)
  expect((await api(c.teacher, `/api/sessions/${c.room.id}/end`, 'POST')).status).toBe(200)
})

test('PT65 logout removes the real publisher and re-login cannot reuse its old mapping', async ({}, testInfo) => {
  const c = await accepted('PT65 actual media logout')
  await connect(c.teacher, c.help)
  const old = await connect(c.student, c.help)
  await publish(c.student)
  await decoded(c.teacher, testInfo)
  expect((await api(c.student, '/logout', 'POST')).status).toBe(204)
  await disconnected(c.student)
  await expect.poll(() => c.teacher.page.evaluate(() => window.pt65Media.room.remoteParticipants.size)).toBe(0)
  await expect.poll(() => c.teacher.page.evaluate(() => window.pt65Media.tracks.every(t => t.mediaStreamTrack.readyState === 'ended'))).toBe(true)
  await login(c.student, 2)
  expect((await api(c.student, old.url + '/rtc/validate')).status).toBe(401)
  expect((await api(c.teacher, `/api/help/${c.help.id}/end`, 'POST')).status).toBe(204)
  await disconnected(c.teacher)
  expect((await api(c.teacher, `/api/sessions/${c.room.id}/end`, 'POST')).status).toBe(200)
})
