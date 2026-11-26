// PT-90 Codex round 1: the student's own badge stops showing an old state when sends keep failing, and the consent
// text is built from the retention settings the server reports.
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { CONSENT_TEXT, FOCUS_STALE_MS, consentText, staleAfterMs, studentFocusView } from '../src/focus/focusLabels.js'

const T = Date.parse('2026-11-26T03:00:00Z')
const MINUTE = 60_000

test('stale window is the server stale-after (3 minutes)', () => {
  assert.equal(FOCUS_STALE_MS, 3 * MINUTE)
})

test('a fresh answer is shown as is', () => {
  const collector = { status: 'collecting', startedAt: T, focus: { state: 'FOCUS', receivedAt: T + MINUTE } }
  assert.deepEqual(studentFocusView(collector, T + 2 * MINUTE), { state: 'FOCUS', stale: false })
  assert.deepEqual(studentFocusView(collector, T + 4 * MINUTE), { state: 'FOCUS', stale: false })
})

test('an answer older than 3 minutes becomes UNKNOWN and stale', () => {
  const collector = { status: 'collecting', startedAt: T, focus: { state: 'FOCUS', receivedAt: T + MINUTE } }
  assert.deepEqual(studentFocusView(collector, T + MINUTE + FOCUS_STALE_MS + 1), { state: 'UNKNOWN', stale: true })
  assert.deepEqual(studentFocusView(collector, T + 3 * 60 * MINUTE), { state: 'UNKNOWN', stale: true })
})

test('an answer without receivedAt is not trusted', () => {
  assert.deepEqual(studentFocusView({ focus: { state: 'FOCUS' } }, T), { state: 'UNKNOWN', stale: true })
})

test('before the first answer it is PENDING, until the first send plus 3 minutes passed without any answer', () => {
  const collector = { status: 'collecting', startedAt: T }
  assert.deepEqual(studentFocusView(collector, T + 30_000), { state: 'PENDING', stale: false })
  assert.deepEqual(studentFocusView(collector, T + MINUTE + FOCUS_STALE_MS), { state: 'PENDING', stale: false })
  assert.deepEqual(studentFocusView(collector, T + MINUTE + FOCUS_STALE_MS + 1), { state: 'UNKNOWN', stale: true })
  assert.deepEqual(studentFocusView({ status: 'off' }, T), { state: 'PENDING', stale: false })
  assert.deepEqual(studentFocusView(undefined, T), { state: 'PENDING', stale: false })
})

test('consent text uses the server retention values', () => {
  const text = consentText({ retentionDays: 14, maxSampleAgeDays: 20 })
  assert.ok(text.includes('수업이 끝나고 14일 뒤'))
  assert.ok(text.includes('20일이 지난 기록'))
  assert.ok(!text.includes('30일'))
  assert.ok(!text.includes('31일'))
})

test('missing or invalid values fall back to 30 / 31 days and CONSENT_TEXT is that default text', () => {
  for (const values of [undefined, {}, { retentionDays: 0, maxSampleAgeDays: -1 }, { retentionDays: '30', maxSampleAgeDays: 1.5 }]) {
    assert.equal(consentText(values), CONSENT_TEXT)
  }
  assert.ok(CONSENT_TEXT.includes('수업이 끝나고 30일 뒤'))
  assert.ok(CONSENT_TEXT.includes('31일이 지난 기록'))
})

test('a consent response object can be passed directly', () => {
  const response = { consented: true, version: '2026-11-26', consentedAt: null, currentVersion: '2026-11-26', retentionDays: 7, maxSampleAgeDays: 8 }
  assert.ok(consentText(response).includes('7일 뒤'))
})

// Codex round 2: the stale window comes from the server (app.focus.stale-after via staleAfterSeconds)

test('staleAfterMs uses the server staleAfterSeconds, else 180 seconds', () => {
  assert.equal(staleAfterMs({ staleAfterSeconds: 300 }), 300_000)
  for (const consent of [null, undefined, {}, { staleAfterSeconds: 0 }, { staleAfterSeconds: -5 }, { staleAfterSeconds: '300' }, { staleAfterSeconds: 1.5 }]) {
    assert.equal(staleAfterMs(consent), 180_000)
  }
})

test('with a 5-minute server stale-after a 4-minute-old answer is still shown', () => {
  const collector = { status: 'collecting', startedAt: T, focus: { state: 'BREAK', receivedAt: T + MINUTE } }
  const staleMs = staleAfterMs({ staleAfterSeconds: 300 })
  assert.deepEqual(studentFocusView(collector, T + 5 * MINUTE, staleMs), { state: 'BREAK', stale: false })
  assert.deepEqual(studentFocusView(collector, T + 6 * MINUTE + 1, staleMs), { state: 'UNKNOWN', stale: true })
})
