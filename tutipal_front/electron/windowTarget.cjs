const { spawn } = require('node:child_process')
const { existsSync } = require('node:fs')
const path = require('node:path')
const { validRect } = require('./shareGeometry.cjs')
const helperPath = path.join(__dirname, 'native/bin/window-target.exe').replace('app.asar' + path.sep, 'app.asar.unpacked' + path.sep)
function windowHandle(sourceId) {
  const match = /^window:([1-9][0-9]{0,18}):[0-9]+$/.exec(sourceId)
  return match && BigInt(match[1]) <= 9223372036854775807n ? match[1] : null
}
function canTrackWindow() { return process.platform === 'win32' && existsSync(helperPath) }
function watchWindow(sourceId, onState, options = {}) {
  const handle = windowHandle(sourceId)
  const launch = options.spawn || spawn
  if (!handle || (!options.spawn && !canTrackWindow())) throw new Error('이 환경에서는 창 공유를 지원하지 않아요')
  const child = launch(helperPath, [handle], { windowsHide: true, shell: false, stdio: ['ignore', 'pipe', 'ignore'] })
  let disposed = false, buffer = '', received = false, resolveFirst, rejectFirst
  const ready = new Promise((resolve, reject) => { resolveFirst = resolve; rejectFirst = reject })
  const fail = () => {
    if (disposed) return
    if (!received) rejectFirst(new Error('공유할 창의 위치를 확인하지 못했어요'))
    onState({ state: 'gone' })
  }
  const timer = setTimeout(() => { fail(); child.kill() }, 4000)
  child.stdout.on('data', data => {
    if (disposed) return
    buffer += data.toString('utf8')
    if (buffer.length > 16000) { fail(); child.kill(); return }
    let newline
    while ((newline = buffer.indexOf('\n')) !== -1) {
      const line = buffer.slice(0, newline); buffer = buffer.slice(newline + 1)
      try {
        const state = JSON.parse(line)
        if (state.state === 'ok' && (!validRect(state.window) || !validRect(state.frame) || !validRect(state.client))) throw new Error('invalid geometry')
        if (!['ok', 'gone', 'hidden'].includes(state.state)) throw new Error('invalid state')
        clearTimeout(timer)
        if (!received) {
          received = true
          if (state.state === 'ok') resolveFirst(state)
          else rejectFirst(new Error('공유할 창이 닫혔거나 최소화됐어요'))
        }
        onState(state)
      } catch { fail(); child.kill(); return }
    }
  })
  child.once('error', fail)
  child.once('exit', () => { clearTimeout(timer); fail() })
  return { ready, dispose() { disposed = true; clearTimeout(timer); rejectFirst(new Error('창 선택이 취소됐어요')); child.kill() } }
}
module.exports = { windowHandle, watchWindow, canTrackWindow }
