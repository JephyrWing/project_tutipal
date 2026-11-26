// PT-90 foreground-app probe. Runs native/bin/focus-probe.exe (FocusProbe.cs), which prints one line per second:
// {"exe":"chrome.exe"} or {"exe":null}. Only the lowercase exe basename is accepted; window titles are never read.
const { spawn } = require('node:child_process')
const { existsSync } = require('node:fs')
const path = require('node:path')

const helperPath = path.join(__dirname, 'native/bin/focus-probe.exe')
  .replace('app.asar' + path.sep, 'app.asar.unpacked' + path.sep)
const EXE_NAME = /^[a-z0-9._-]{1,64}\.exe$/
const MAX_PENDING = 16 * 1024
const SILENCE_MS = 4000

/** One probe line -> exe basename, or null for unknown / anything not exactly {"exe": "<name>.exe"}. */
function parseProbeLine(line) {
  let value
  try {
    value = JSON.parse(line)
  } catch {
    return null
  }
  if (!value || typeof value !== 'object' || Array.isArray(value)) return null
  const keys = Object.keys(value)
  if (keys.length !== 1 || keys[0] !== 'exe') return null
  return typeof value.exe === 'string' && EXE_NAME.test(value.exe) ? value.exe : null
}

function canProbe() { return process.platform === 'win32' && existsSync(helperPath) }

/**
 * Starts the helper and calls onExe(name | null) per line. The helper is killed when it prints a line (complete or not)
 * longer than 16 KB or prints no complete line for 4 seconds; after that (or kill()) nothing more is reported.
 * options.onExit is called once if the helper ends by itself or is killed by these guards (not after kill()).
 */
function startFocusProbe(onExe, options = {}) {
  const launch = options.spawn || spawn
  const onExit = typeof options.onExit === 'function' ? options.onExit : () => {}
  const child = launch(helperPath, [], { windowsHide: true, shell: false, stdio: ['ignore', 'pipe', 'ignore'] })
  let dead = false
  let pending = ''
  let watchdog = null
  const end = (notify) => {
    if (dead) return
    dead = true
    clearTimeout(watchdog)
    try { child.kill() } catch { /* already gone */ }
    if (notify) onExit()
  }
  const arm = () => {
    clearTimeout(watchdog)
    watchdog = setTimeout(() => end(true), SILENCE_MS)
  }
  arm()
  child.stdout.on('data', data => {
    if (dead) return
    pending += data.toString('utf8')
    let newline
    while (!dead && (newline = pending.indexOf('\n')) !== -1) {
      // a complete line longer than 16 KB is as wrong as 16 KB without a newline
      if (newline > MAX_PENDING) { end(true); return }
      const line = pending.slice(0, newline).replace(/\r$/, '')
      pending = pending.slice(newline + 1)
      arm()   // only a complete line counts as alive: bytes without a newline do not reset the 4 s guard
      onExe(parseProbeLine(line))
    }
    if (pending.length > MAX_PENDING) end(true)
  })
  if (typeof child.once === 'function') {
    child.once('error', () => end(true))
    child.once('exit', () => end(true))
  }
  return { kill: () => end(false) }
}

module.exports = { parseProbeLine, startFocusProbe, canProbe }
