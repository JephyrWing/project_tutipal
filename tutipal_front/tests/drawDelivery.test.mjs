import assert from 'node:assert/strict'
import test from 'node:test'
import { applyDraw, createDrawReceiver } from '../src/draw.js'

const line = { k: 'add', id: 'stroke', tool: 'pen', c: '#ef4444', p: [[0, 0], [1, 1]] }
const clear = { k: 'clear' }
const event = (sequence, message, helpId = 2) => JSON.stringify({ helpId, sequence, message })

test('duplicate and delayed drawing events cannot append points or restore an erased stroke', () => {
  const strokes = new Map()
  const receiver = createDrawReceiver(2, (message) => applyDraw(strokes, message))
  const receive = receiver.connect()
  receive(event(1, line))
  receive(event(1, line))
  assert.deepEqual(strokes.get('stroke').pts, line.p)

  receive(event(3, clear))
  receive(event(2, line))
  assert.equal(strokes.size, 0)
})

test('disconnect clears the canvas and invalidates frames from the previous subscription', () => {
  const strokes = new Map()
  const receiver = createDrawReceiver(2, (message) => applyDraw(strokes, message))
  const previous = receiver.connect()
  previous(event(6, line))
  receiver.disconnect()

  previous(event(7, line))
  assert.equal(strokes.size, 0)
  const next = receiver.connect()
  previous(event(8, clear))
  next(event(1, line))
  assert.deepEqual(strokes.get('stroke').pts, line.p)
})

test('drawings from another help are ignored and older servers still deliver live messages', () => {
  const messages = []
  const receive = createDrawReceiver(2, (message) => messages.push(message)).connect()
  receive(event(50, line, 3))
  receive('{')
  receive(event('1', line))
  receive(event(undefined, line))
  receive(event(1, clear))
  receive(event(undefined, line))
  assert.deepEqual(messages, [clear, line, clear])
})
