// PT-90 Round 1 review: consent withdrawal outside the class screen (settings), shared flow and summary text.
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { canWithdraw, consentSummary, withdrawFocusConsent } from '../src/focus/focusConsent.js'
import { CONSENT_TEXT } from '../src/focus/focusLabels.js'

function fakeApi(log, after = { consented: false, version: null, consentedAt: null, currentVersion: '2026-11-26' }) {
  return {
    withdrawConsent: async () => { log.push('DELETE /api/focus/consent') },
    consent: async () => { log.push('GET /api/focus/consent'); return after },
  }
}

test('withdraw in the desktop app stops collection first, then deletes on the server, then reads the consent again', async () => {
  const log = []
  const desktop = { focusStop: async () => { log.push('focusStop') } }

  const consent = await withdrawFocusConsent({ api: fakeApi(log), desktop })

  assert.deepEqual(log, ['focusStop', 'DELETE /api/focus/consent', 'GET /api/focus/consent'])
  assert.equal(consent.consented, false)
})

test('withdraw in a browser (no desktop bridge) still deletes on the server', async () => {
  const log = []

  await withdrawFocusConsent({ api: fakeApi(log), desktop: undefined })

  assert.deepEqual(log, ['DELETE /api/focus/consent', 'GET /api/focus/consent'])
})

test('a failing local stop does not block the server deletion', async () => {
  const log = []
  const desktop = { focusStop: async () => { throw new Error('ipc gone') } }

  await withdrawFocusConsent({ api: fakeApi(log), desktop })

  assert.ok(log.includes('DELETE /api/focus/consent'))
})

test('a failing server deletion is reported to the screen', async () => {
  const api = { withdrawConsent: async () => { throw new Error('서버 오류') }, consent: async () => ({}) }

  await assert.rejects(withdrawFocusConsent({ api, desktop: undefined }), /서버 오류/)
})

test('withdrawal is offered for a current or an old-version consent, not without one', () => {
  assert.equal(canWithdraw(null), false)
  assert.equal(canWithdraw({ consented: false, version: null }), false)
  assert.equal(canWithdraw({ consented: true, version: '2026-11-26' }), true)
  assert.equal(canWithdraw({ consented: false, version: '2020-01-01' }), true)
})

test('summary text for loading, consented, old version and none', () => {
  assert.match(consentSummary(null), /확인/)
  assert.match(consentSummary({ consented: true, version: '2026-11-26', consentedAt: '2026-11-26T03:00:00Z' }), /2026-11-26/)
  assert.match(consentSummary({ consented: false, version: '2020-01-01' }), /다시 동의/)
  assert.match(consentSummary({ consented: false, version: null }), /동의하지 않았어요/)
})

test('consent text states the age upper bound for classes that were never closed', () => {
  assert.ok(CONSENT_TEXT.includes('31일'))
})
