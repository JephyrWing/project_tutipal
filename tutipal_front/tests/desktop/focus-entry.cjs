// Test process only: real Main, Preload, collector and HTTP transport. Never probe this PC.
const { app } = require('electron')
const fs = require('node:fs')
const path = require('node:path')
const os = require('node:os')
const Module = require('node:module')
const profile = process.env.TUTIPAL_E2E_PROFILE
const backend = new URL(process.env.TUTIPAL_SPRING_URL)
if (!profile || path.dirname(path.resolve(profile)) !== path.resolve(os.tmpdir())
    || !path.basename(profile).startsWith('tutipal-e2e-profile-')) throw new Error('Disposable profile required')
if (backend.protocol !== 'http:' || backend.hostname !== '127.0.0.1' || !backend.port)
  throw new Error('Disposable loopback provider required')
app.disableHardwareAcceleration()
fs.mkdirSync(path.join(profile, 'session'), { recursive: true })
app.setPath('userData', profile)
app.setPath('sessionData', path.join(profile, 'session'))
app.setAppPath(path.resolve(__dirname, '../..'))
app.setAsDefaultProtocolClient = () => false
delete process.env.TUTIPAL_RENDERER_URL
app.on('browser-window-created', (_event, win) => win.hide())

let clock = 0
let sample = null
let collector
const timers = new Set()
const stats = { starts: 0, kills: 0, idleReads: 0, aborts: 0 }
const load = Module._load
let probeReplaced = false
let collectorReplaced = false
Module._load = function (request, parent, ...rest) {
  if (parent?.filename === path.join(app.getAppPath(), 'electron/main.cjs')) {
    if (request === './focusProbe.cjs') {
      probeReplaced = true
      return {
        canProbe: () => true,
        startFocusProbe: onSample => {
          stats.starts++
          sample = onSample
          return { kill() { stats.kills++; sample = null } }
        },
      }
    }
    if (request === './focusCollector.cjs') {
      collectorReplaced = true
      const real = load.call(this, request, parent, ...rest)
      return { createFocusCollector(options) {
        collector = real.createFocusCollector({ ...options,
          idleSeconds: () => { stats.idleReads++; return 7 },
          now: () => clock,
          setInterval: callback => { timers.add(callback); return callback },
          clearInterval: callback => timers.delete(callback),
          send: (id, body, settings) => {
            settings.signal.addEventListener('abort', () => stats.aborts++, { once: true })
            return options.send(id, body, settings)
          },
        })
        return collector
      } }
    }
  }
  return load.call(this, request, parent, ...rest)
}
global.pt30Focus = {
  snapshot: () => ({ ...stats, timers: timers.size, state: collector.state() }),
  preloadPath: path.join(app.getAppPath(), 'electron/preload.cjs'),
  minute(exe = 'fixture.exe') {
    sample?.(exe)
    clock += 60_000
    for (const callback of [...timers]) callback()
  },
}
require(path.join(app.getAppPath(), 'electron/main.cjs'))
Module._load = load
// If instrumentation stops matching after a refactor, fail before any renderer can start a real probe.
app.whenReady().then(() => {
  if (!collectorReplaced || !probeReplaced) app.exit(1)
})
