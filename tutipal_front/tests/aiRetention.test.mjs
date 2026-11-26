// PT-106 / PT-13: AI conversation retention helpers (session create / settings, conversation notice) and the
// save toggle API call.
import { test, beforeEach, afterEach } from 'node:test'
import assert from 'node:assert/strict'
import { setConversationSaved } from '../src/api/conversations.js'
import {
  AI_RETENTION_MAX_DAYS,
  AI_RETENTION_QUICK_PICKS,
  PERSONAL_RETENTION_DAYS,
  aiRetentionError,
  aiRetentionLabel,
  applySavedState,
  parseAiRetentionDays,
  retentionNotice,
} from '../src/utils/aiRetention.js'

const jsonResponse = (status, body) => new Response(JSON.stringify(body), {
  status, headers: { 'Content-Type': 'application/json' },
})

let calls
let realFetch
beforeEach(() => { calls = []; realFetch = globalThis.fetch })
afterEach(() => { globalThis.fetch = realFetch })

test('retention limits match the server (0..90, personal 30) and every quick pick is valid', () => {
  assert.equal(AI_RETENTION_MAX_DAYS, 90)
  assert.equal(PERSONAL_RETENTION_DAYS, 30)
  assert.ok(AI_RETENTION_QUICK_PICKS.includes(0))
  assert.ok(AI_RETENTION_QUICK_PICKS.includes(90))
  for (const days of AI_RETENTION_QUICK_PICKS) assert.equal(parseAiRetentionDays(String(days)), days)
})

// Codex P2: the teacher can set any whole number of days, not only the quick picks.
test('every whole number of days from 0 to 90 is accepted, typed or as a number', () => {
  for (let days = 0; days <= 90; days++) {
    assert.equal(parseAiRetentionDays(String(days)), days)
    assert.equal(parseAiRetentionDays(days), days)
    assert.equal(aiRetentionError(String(days)), '')
  }
  assert.equal(parseAiRetentionDays(' 13 '), 13)
  assert.ok(!AI_RETENTION_QUICK_PICKS.includes(13))
})

test('parseAiRetentionDays has no implicit default and rejects out-of-range input', () => {
  for (const bad of ['', null, undefined, '-1', '91', '100', '1.5', '1e1', 'abc', ' ']) {
    assert.equal(parseAiRetentionDays(bad), null, `"${bad}"`)
  }
})

test('aiRetentionError stays quiet while empty and explains invalid input', () => {
  assert.equal(aiRetentionError(''), '')
  assert.equal(aiRetentionError(null), '')
  for (const bad of ['-1', '91', '2.5', 'abc']) {
    assert.match(aiRetentionError(bad), /0부터 90까지의 정수/, bad)
  }
})

test('aiRetentionLabel reads 0 as "right after the session ends"', () => {
  assert.equal(aiRetentionLabel(0), '수업 종료 직후 삭제')
  assert.equal(aiRetentionLabel(30), '수업 종료 후 30일')
  assert.equal(aiRetentionLabel(null), '정해지지 않음')
})

test('retentionNotice explains class (N days after the end) and personal (30 days) retention', () => {
  assert.match(retentionNotice({}), /개인 대화는 마지막 대화 후 30일/)
  assert.match(retentionNotice({ sessionId: '10', aiRetentionDays: 7 }), /수업이 끝나고 7일이 지나면/)
  assert.match(retentionNotice({ sessionId: '10', aiRetentionDays: 0 }), /수업이 끝나면 바로/)
  assert.match(retentionNotice({ sessionId: '10', aiRetentionDays: null }), /최대 90일/)
  for (const text of [retentionNotice({}), retentionNotice({ sessionId: '10', aiRetentionDays: 7 })]) {
    assert.match(text, /저장/)
  }
})

test('applySavedState updates only the matching conversation', () => {
  const conversation = { id: '7', topic: '수학', saved: false, savedAt: null }
  assert.deepEqual(applySavedState(conversation, { id: '7', saved: true, savedAt: '2026-11-26T00:00:00Z' }),
    { id: '7', topic: '수학', saved: true, savedAt: '2026-11-26T00:00:00Z' })
  assert.equal(applySavedState(conversation, { id: '8', saved: true }), conversation)
  assert.deepEqual(applySavedState({ ...conversation, saved: true, savedAt: 'x' }, { id: '7', saved: false, savedAt: null }),
    { ...conversation, saved: false, savedAt: null })
})

test('setConversationSaved sends PUT {saved} with the CSRF header and returns the state', async () => {
  globalThis.fetch = async (url, init = {}) => {
    const path = String(url)
    if (path === '/api/auth/csrf') return jsonResponse(200, { headerName: 'X-XSRF-TOKEN', token: 'csrf-1' })
    calls.push({ path, init })
    return jsonResponse(200, { id: '7', saved: true, savedAt: '2026-11-26T00:00:00Z' })
  }

  const state = await setConversationSaved('7', true)

  assert.deepEqual(state, { id: '7', saved: true, savedAt: '2026-11-26T00:00:00Z' })
  assert.equal(calls.length, 1)
  assert.equal(calls[0].path, '/api/conversations/7/saved')
  assert.equal(calls[0].init.method, 'PUT')
  assert.deepEqual(JSON.parse(calls[0].init.body), { saved: true })
})

test('setConversationSaved surfaces a 404 for someone else\'s conversation', async () => {
  globalThis.fetch = async (url) => {
    if (String(url) === '/api/auth/csrf') return jsonResponse(200, { headerName: 'X-XSRF-TOKEN', token: 'csrf-1' })
    return jsonResponse(404, { status: 404, message: '대화를 찾을 수 없어요' })
  }

  await assert.rejects(setConversationSaved('9', false), (e) => e.status === 404)
})
