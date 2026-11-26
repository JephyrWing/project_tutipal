// PT-90 AC-20: the preload exposes only focusStart / focusStop / focusState / onFocusState for focus collection.
// The renderer can only send a session id (start) or nothing (stop / state); there is no channel that carries
// collected values from the page. Main-side checks (fromOurPage, safe integer id, platform) are reviewed in main.cjs.
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

const require = createRequire(import.meta.url)
const Module = require('node:module')
const root = fileURLToPath(new URL('..', import.meta.url))

function loadPreload() {
  const calls = []
  const listeners = new Map()
  const ipcRenderer = {
    send: (channel, ...args) => { calls.push({ kind: 'send', channel, args }) },
    invoke: async (channel, ...args) => { calls.push({ kind: 'invoke', channel, args }); return { status: 'off' } },
    on: (channel, listener) => { listeners.set(channel, listener); calls.push({ kind: 'on', channel }) },
    removeListener: (channel, listener) => {
      if (listeners.get(channel) === listener) listeners.delete(channel)
      calls.push({ kind: 'removeListener', channel })
    },
  }
  let exposed = null
  const electron = { ipcRenderer, contextBridge: { exposeInMainWorld: (key, api) => { if (key === 'desktop') exposed = api } } }
  const original = Module._load
  Module._load = function (request, ...rest) {
    if (request === 'electron') return electron
    return original.call(this, request, ...rest)
  }
  try {
    const path = require.resolve('../electron/preload.cjs')
    delete require.cache[path]
    require(path)
  } finally {
    Module._load = original
  }
  return { api: exposed, calls, listeners }
}

test('the focus keys on window.desktop are exactly focusStart, focusStop, focusState, onFocusState', () => {
  const { api } = loadPreload()
  const focusKeys = Object.keys(api).filter(k => /focus/i.test(k)).sort()
  assert.deepEqual(focusKeys, ['focusStart', 'focusState', 'focusStop', 'onFocusState'])
  for (const key of focusKeys) assert.equal(typeof api[key], 'function', key)
})

test('focusStart sends only the session id on focus-start', async () => {
  const { api, calls } = loadPreload()
  await api.focusStart(7, { exeName: 'chrome.exe' }, 'extra')
  const call = calls.find(c => c.channel === 'focus-start')
  assert.ok(call, 'focus-start channel used')
  assert.deepEqual(call.args, [7])
})

test('focusStop and focusState send no values', async () => {
  const { api, calls } = loadPreload()
  await api.focusStop({ rows: [1, 2, 3] })
  await api.focusState('anything')
  const stop = calls.find(c => c.channel === 'focus-stop')
  const state = calls.find(c => c.channel === 'focus-state' && c.kind !== 'on')
  assert.ok(stop, 'focus-stop channel used')
  assert.ok(state, 'focus-state channel used')
  assert.deepEqual(stop.args, [])
  assert.deepEqual(state.args, [])
})

test('onFocusState subscribes to focus-state pushes, ignores non-object payloads and can unsubscribe', () => {
  const { api, listeners } = loadPreload()
  const seen = []
  const unsubscribe = api.onFocusState(state => seen.push(state))
  const listener = listeners.get('focus-state')
  assert.equal(typeof listener, 'function')
  listener({}, { status: 'collecting', sessionId: 7 })
  listener({}, 'not an object')
  listener({}, null)
  assert.deepEqual(seen, [{ status: 'collecting', sessionId: 7 }])
  unsubscribe()
  assert.equal(listeners.has('focus-state'), false)
})

test('no preload channel name lets the page push focus samples', () => {
  const source = readFileSync(`${root}electron/preload.cjs`, 'utf8')
  const channels = [...source.matchAll(/ipcRenderer\.(?:send|invoke|on)\(\s*'([^']+)'/g)].map(m => m[1])
  const focusChannels = [...new Set(channels.filter(c => c.startsWith('focus')))].sort()
  assert.deepEqual(focusChannels, ['focus-start', 'focus-state', 'focus-stop'])
})

test('main.cjs guards every focus handler with fromOurPage and a positive safe integer session id', () => {
  const source = readFileSync(`${root}electron/main.cjs`, 'utf8')
  for (const channel of ['focus-start', 'focus-stop', 'focus-state']) {
    const at = source.indexOf(`'${channel}'`)
    assert.ok(at !== -1, `${channel} handler registered`)
    assert.match(source.slice(at, at + 400), /fromOurPage\(/, `${channel} checks fromOurPage`)
  }
  const start = source.slice(source.indexOf(`'focus-start'`), source.indexOf(`'focus-start'`) + 400)
  assert.match(start, /Number\.isSafeInteger\(/)
})

test('focus-start from another window for the session already collecting keeps the original owner', () => {
  const source = readFileSync(`${root}electron/main.cjs`, 'utf8')
  const start = source.slice(source.indexOf(`'focus-start'`), source.indexOf(`'focus-stop'`))
  assert.match(start, /before\.status === 'collecting' && before\.sessionId === sessionId/)
  assert.match(start, /!continued\) focusOwner = event\.sender/)
})
