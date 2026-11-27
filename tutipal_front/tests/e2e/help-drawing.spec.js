import { Client } from '@stomp/stompjs'
import WebSocket from 'ws'
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

async function subscribeToDraws(app) {
  const { body: csrf } = await api(app, '/api/auth/csrf')
  const origin = new URL(app.page.url()).origin
  const cookies = await app.page.context().cookies(origin)
  const socketUrl = new URL('/ws', origin)
  socketUrl.protocol = 'ws:'
  const received = []
  const client = new Client({
    connectHeaders: { [csrf.headerName]: csrf.token },
    reconnectDelay: 0,
    webSocketFactory: () => new WebSocket(socketUrl, {
      headers: { Origin: origin, Cookie: cookies.map(cookie => cookie.name + '=' + cookie.value).join('; ') },
    }),
  })
  try {
    await new Promise((resolve, reject) => {
      const timeout = setTimeout(() => reject(new Error('Drawing subscription did not connect')), 5000)
      const failed = () => {
        clearTimeout(timeout)
        reject(new Error('Unable to open the drawing subscription'))
      }
      client.onStompError = failed
      client.onWebSocketError = failed
      client.onConnect = () => {
        client.subscribe('/user/queue/draw', (frame) => received.push(JSON.parse(frame.body)))
        clearTimeout(timeout)
        resolve()
      }
      client.activate()
    })
  } catch (error) {
    await client.deactivate({ force: true })
    throw error
  }
  return { client, received }
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
  const channels = []
  try {
    const studentChannel = await subscribeToDraws(student)
    channels.push(studentChannel)
    const otherChannel = await subscribeToDraws(other)
    channels.push(otherChannel)

    await expect.poll(async () => {
      expect((await draw(teacher, helpId, pen)).status).toBe(204)
      expect((await draw(teacher, otherHelpId, { k: 'clear' })).status).toBe(204)
      return studentChannel.received.length > 0 && otherChannel.received.length > 0
    }).toBe(true)
    expect(studentChannel.received.every(frame => frame.helpId === helpId)).toBe(true)
    expect(otherChannel.received.every(frame => frame.helpId === otherHelpId)).toBe(true)
    expect(studentChannel.received[0]).toMatchObject({ helpId, sequence: expect.any(Number), message: pen })

    expect((await draw(student, helpId, pen)).status).toBe(403)
    expect((await draw(teacher, helpId, { ...pen, tool: null })).status).toBe(400)
    expect((await api(teacher, '/api/help/' + helpId + '/end', 'POST')).status).toBe(204)
    expect((await draw(teacher, helpId, pen)).status).toBe(409)

    expect((await api(other, '/api/sessions/' + room.id + '/participants/me', 'DELETE')).status).toBe(204)
    expect((await draw(teacher, otherHelpId, pen)).status).toBe(409)
  } finally {
    await Promise.all(channels.map(({ client }) => client.deactivate({ force: true })))
  }
})
