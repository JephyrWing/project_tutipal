import { test, expect, login, createRoom, joinRoom } from './fixtures.js'

async function api(app, path, method = 'GET', body) {
  return app.page.evaluate(async ({ path, method, body }) => {
    const headers = {}
    if (method !== 'GET') {
      const csrf = await (await fetch('/api/auth/csrf')).json()
      headers[csrf.headerName] = csrf.token
    }
    if (body !== undefined) headers['Content-Type'] = 'application/json'
    const response = await fetch(path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) })
    return { status: response.status, body: await response.json().catch(() => null) }
  }, { path, method, body })
}

function observeDraws(app) {
  const received = []
  app.page.on('websocket', (socket) => {
    if (new URL(socket.url()).pathname !== '/ws') return
    socket.on('framereceived', ({ payload }) => {
      for (const frame of payload.toString().split('\0')) {
        const boundary = frame.indexOf('\n\n')
        if (!frame.startsWith('MESSAGE\n') || boundary === -1) continue
        const headers = frame.slice(0, boundary)
        if (!/(?:^|\n)destination:[^\n]*\/queue\/draw(?:[-\n]|$)/.test(headers)) continue
        received.push(JSON.parse(frame.slice(boundary + 2)))
      }
    })
  })
  return received
}

async function acceptHelp(teacher, student, room) {
  const requested = await api(student, '/api/sessions/' + room.id + '/help', 'POST',
    { message: '판서 합성 검사', shareScreen: true })
  expect(requested.status).toBe(201)
  const helpId = requested.body.id
  expect((await api(teacher, '/api/help/' + helpId + '/accept', 'POST')).status).toBe(200)
  return helpId
}

test('PT48 validated pen strokes reach only the request owner and stop after end or removal', async ({ apps }) => {
  const teacher = await apps.launch()
  const student = await apps.launch()
  const other = await apps.launch()
  await login(teacher, 7)
  await login(student, 8)
  await login(other, 9)
  const room = await createRoom(teacher, 'PT48 판서 전달')
  await joinRoom(student, room.code)
  await joinRoom(other, room.code)
  await expect(student.page).toHaveURL(new RegExp('/sessions/' + room.id + '$'))
  await expect(other.page).toHaveURL(new RegExp('/sessions/' + room.id + '$'))

  const helpId = await acceptHelp(teacher, student, room)
  const otherHelpId = await acceptHelp(teacher, other, room)
  const draw = (app, id, message) => api(app, '/api/help/' + id + '/draw', 'POST', { message })
  const pen = { k: 'add', id: 'synthetic-stroke', tool: 'pen', c: '#ef4444', p: [[0, 0], [1, 1]] }
  const studentDraws = observeDraws(student)
  const otherDraws = observeDraws(other)
  await Promise.all([student.page.reload(), other.page.reload()])

  await expect.poll(async () => {
    expect((await draw(teacher, helpId, pen)).status).toBe(204)
    expect((await draw(teacher, otherHelpId, { k: 'clear' })).status).toBe(204)
    return studentDraws.length > 0 && otherDraws.length > 0
  }).toBe(true)
  expect(studentDraws.every(frame => frame.helpId === helpId)).toBe(true)
  expect(otherDraws.every(frame => frame.helpId === otherHelpId)).toBe(true)
  expect(studentDraws[0]).toMatchObject({ helpId, sequence: expect.any(Number), message: pen })

  expect((await draw(student, helpId, pen)).status).toBe(403)
  expect((await draw(teacher, helpId, { ...pen, tool: null })).status).toBe(400)
  expect((await api(teacher, '/api/help/' + helpId + '/end', 'POST')).status).toBe(204)
  expect((await draw(teacher, helpId, pen)).status).toBe(409)

  expect((await api(other, '/api/sessions/' + room.id + '/participants/me', 'DELETE')).status).toBe(204)
  expect((await draw(teacher, otherHelpId, pen)).status).toBe(409)
})

test('PT66 reconnecting the drawing socket sends clear before new strokes to the overlay IPC', async ({ apps }) => {
  const teacher = await apps.launch()
  const student = await apps.launch()
  await student.page.addInitScript(() => {
    const nativeSend = WebSocket.prototype.send
    const drawingSockets = []
    window.pt66DrawingSockets = drawingSockets
    WebSocket.prototype.send = function (frame) {
      nativeSend.call(this, frame)
      if (typeof frame === 'string' && frame.startsWith('SUBSCRIBE\n')
          && frame.includes('\ndestination:/user/queue/draw\n')) {
        drawingSockets.push(this)
      }
    }
  })
  await student.application.evaluate(({ ipcMain }) => {
    globalThis.pt66OverlayMessages = []
    ipcMain.on('overlay-draw', (_event, message) => globalThis.pt66OverlayMessages.push(JSON.parse(message)))
  })
  const overlayMessages = () => student.application.evaluate(() => globalThis.pt66OverlayMessages)
  const subscriptionCount = () => student.page.evaluate(() => window.pt66DrawingSockets.length)

  await login(teacher, 7)
  await login(student, 8)
  const room = await createRoom(teacher, 'PT66 판서 재연결')
  await joinRoom(student, room.code)
  await expect(student.page).toHaveURL(new RegExp('/sessions/' + room.id + '$'))
  const helpId = await acceptHelp(teacher, student, room)
  await student.page.reload()
  await expect.poll(subscriptionCount).toBe(1)

  const pen = { k: 'add', id: 'before-reconnect', tool: 'pen', c: '#ef4444', p: [[0.2, 0.3], [0.4, 0.5]] }
  const draw = (message) => api(teacher, '/api/help/' + helpId + '/draw', 'POST', { message })
  await expect.poll(async () => {
    expect((await draw(pen)).status).toBe(204)
    return (await overlayMessages()).some(message => message.id === pen.id)
  }).toBe(true)
  expect(await overlayMessages()).toContainEqual(pen)

  const reconnectStart = (await overlayMessages()).length
  await student.page.evaluate(() => window.pt66DrawingSockets[0].close())
  await expect.poll(async () => (await overlayMessages()).slice(reconnectStart)).toContainEqual({ k: 'clear' })
  await expect.poll(subscriptionCount).toBe(2)

  const newPen = { ...pen, id: 'after-reconnect' }
  await expect.poll(async () => {
    expect((await draw(newPen)).status).toBe(204)
    return (await overlayMessages()).some(message => message.id === newPen.id)
  }).toBe(true)
  const afterReconnect = (await overlayMessages()).slice(reconnectStart)
  const clearIndex = afterReconnect.findIndex(message => message.k === 'clear')
  expect(afterReconnect.slice(clearIndex + 1)).toContainEqual(newPen)
  expect(afterReconnect.slice(clearIndex + 1)).not.toContainEqual(pen)
})
