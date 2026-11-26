// PT-90 AC-19: the foreground-app probe's output is validated line by line, bounded, and the helper source
// never reads window titles.
import { test, mock } from 'node:test'
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { EventEmitter } from 'node:events'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

const require = createRequire(import.meta.url)
const { parseProbeLine, startFocusProbe } = require('../electron/focusProbe.cjs')
const root = fileURLToPath(new URL('..', import.meta.url))

function fakeChild() {
  const child = new EventEmitter()
  child.stdout = new EventEmitter()
  child.kills = 0
  child.kill = () => { child.kills++; return true }
  return child
}

// ---- parseProbeLine ----

test('valid lines give the lowercase exe name or null', () => {
  assert.equal(parseProbeLine('{"exe":"chrome.exe"}'), 'chrome.exe')
  assert.equal(parseProbeLine('{"exe":"code.exe"}'), 'code.exe')
  assert.equal(parseProbeLine('{"exe":"sublime_text.exe"}'), 'sublime_text.exe')
  assert.equal(parseProbeLine('{"exe":"my-app.v2.exe"}'), 'my-app.v2.exe')
  assert.equal(parseProbeLine('{"exe":null}'), null)
  assert.equal(parseProbeLine(`{"exe":"${'a'.repeat(64)}.exe"}`), `${'a'.repeat(64)}.exe`)
})

test('paths, uppercase, wrong extension, extra keys and non-JSON are rejected as null', () => {
  const bad = [
    '{"exe":"C:\\\\Program Files\\\\chrome.exe"}',
    '{"exe":"/usr/bin/chrome.exe"}',
    '{"exe":"..\\\\chrome.exe"}',
    '{"exe":"Chrome.exe"}',
    '{"exe":"chrome.EXE"}',
    '{"exe":"chrome"}',
    '{"exe":"chrome.exe.txt"}',
    '{"exe":".exe"}',
    `{"exe":"${'a'.repeat(65)}.exe"}`,
    '{"exe":"chrome.exe","title":"secret document"}',
    '{"exe":"chrome.exe","pid":1}',
    '{"name":"chrome.exe"}',
    '{"exe":1}',
    '{"exe":["chrome.exe"]}',
    '["chrome.exe"]',
    'chrome.exe',
    '',
    'null',
    '{"exe":"chrome.exe"',
    '{"exe":"chr ome.exe"}',
    '{"exe":"notepad++.exe"}',
  ]
  for (const line of bad) assert.equal(parseProbeLine(line), null, line)
})

// ---- startFocusProbe ----

test('spawns the focus-probe helper without a shell, without arguments and with only stdout piped', () => {
  const child = fakeChild()
  const calls = []
  const probe = startFocusProbe(() => {}, { spawn: (...args) => { calls.push(args); return child } })
  assert.equal(calls.length, 1)
  const [file, args, options] = calls[0]
  assert.match(String(file), /focus-probe\.exe$/)
  assert.deepEqual(args, [])
  assert.equal(options.shell, false)
  assert.deepEqual(options.stdio, ['ignore', 'pipe', 'ignore'])
  assert.equal(options.windowsHide, true)
  probe.kill()
})

test('each valid line is passed on; invalid lines are passed on as null', () => {
  const child = fakeChild()
  const seen = []
  const probe = startFocusProbe(exe => seen.push(exe), { spawn: () => child })
  child.stdout.emit('data', Buffer.from('{"exe":"chrome.exe"}\n{"exe":null}\n'))
  child.stdout.emit('data', Buffer.from('{"exe":"C:\\\\x\\\\code.exe"}\n{"exe":"co'))
  child.stdout.emit('data', Buffer.from('de.exe"}\n'))
  assert.deepEqual(seen, ['chrome.exe', null, null, 'code.exe'])
  probe.kill()
})

test('more than 16 KB without a newline kills the helper and stops reporting', () => {
  const child = fakeChild()
  const seen = []
  startFocusProbe(exe => seen.push(exe), { spawn: () => child })
  child.stdout.emit('data', Buffer.from('x'.repeat(16 * 1024 + 1)))
  assert.equal(child.kills, 1)
  child.stdout.emit('data', Buffer.from('\n{"exe":"chrome.exe"}\n'))
  assert.deepEqual(seen, [])
})

test('4 seconds without output kills the helper', () => {
  mock.timers.enable({ apis: ['setTimeout', 'setInterval'] })
  try {
    const child = fakeChild()
    startFocusProbe(() => {}, { spawn: () => child })
    mock.timers.tick(3900)
    assert.equal(child.kills, 0)
    mock.timers.tick(200)
    assert.ok(child.kills >= 1)
  } finally {
    mock.timers.reset()
  }
})

test('a helper that keeps printing once a second is not killed', () => {
  mock.timers.enable({ apis: ['setTimeout', 'setInterval'] })
  try {
    const child = fakeChild()
    const probe = startFocusProbe(() => {}, { spawn: () => child })
    for (let i = 0; i < 20; i++) {
      mock.timers.tick(1000)
      child.stdout.emit('data', Buffer.from('{"exe":"code.exe"}\n'))
    }
    assert.equal(child.kills, 0)
    probe.kill()
    assert.equal(child.kills, 1)
  } finally {
    mock.timers.reset()
  }
})

test('after kill no more samples are reported', () => {
  const child = fakeChild()
  const seen = []
  const probe = startFocusProbe(exe => seen.push(exe), { spawn: () => child })
  probe.kill()
  child.stdout.emit('data', Buffer.from('{"exe":"chrome.exe"}\n'))
  assert.deepEqual(seen, [])
})

// ---- helper source and build ----

test('FocusProbe.cs reads only the process image name, never window titles', () => {
  const source = readFileSync(`${root}electron/native/FocusProbe.cs`, 'utf8')
  assert.doesNotMatch(source, /GetWindowText|WM_GETTEXT|UIAutomation/)
  assert.match(source, /GetForegroundWindow/)
  assert.match(source, /QueryFullProcessImageNameW?/)
})

test('the native build script also builds focus-probe.exe', () => {
  const script = readFileSync(`${root}scripts/build-window-target.mjs`, 'utf8')
  assert.match(script, /FocusProbe\.cs/)
  assert.match(script, /focus-probe\.exe/)
})

// ---- Round 1 review ----

test('bytes without a newline do not keep the helper alive: one byte a second is killed after 4 seconds', () => {
  mock.timers.enable({ apis: ['setTimeout', 'setInterval'] })
  try {
    const child = fakeChild()
    startFocusProbe(() => {}, { spawn: () => child })
    for (let i = 0; i < 5; i++) {
      mock.timers.tick(1000)
      child.stdout.emit('data', Buffer.from('x'))
    }
    assert.ok(child.kills >= 1)
  } finally {
    mock.timers.reset()
  }
})

test('FocusProbe.cs exits when a stdout write fails (raw WriteFile, not Console.Out which ignores a broken pipe)', () => {
  const source = readFileSync(`${root}electron/native/FocusProbe.cs`, 'utf8')
  assert.match(source, /WriteFile\(/)
  assert.doesNotMatch(source, /\.WriteLine\(/)
})

test('the Windows package check also requires the unpacked focus-probe.exe', () => {
  const script = readFileSync(`${root}scripts/verify-win-package.mjs`, 'utf8')
  assert.match(script, /app\.asar\.unpacked\/electron\/native\/bin\/focus-probe\.exe/)
})

test('a complete line longer than 16 KB also kills the helper and is not reported', () => {
  const child = fakeChild()
  const seen = []
  startFocusProbe(exe => seen.push(exe), { spawn: () => child })
  child.stdout.emit('data', Buffer.from('{"exe":"' + 'a'.repeat(17 * 1024) + '.exe"}\n'))
  assert.ok(child.kills >= 1)
  assert.deepEqual(seen, [])
  child.stdout.emit('data', Buffer.from('{"exe":"code.exe"}\n'))
  assert.deepEqual(seen, [])
})
