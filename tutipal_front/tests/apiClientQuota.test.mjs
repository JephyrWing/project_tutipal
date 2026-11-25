import { test } from 'node:test'
import assert from 'node:assert/strict'
import { ApiRequestError, isQuotaError, toApiError } from '../src/api/client.js'

const problem = {
  type: 'about:blank', title: 'AI 사용 한도 초과', status: 429,
  detail: '챗봇 질문 한도를 다 썼어요. 약 60분 뒤에 다시 시도해 주세요',
  message: '챗봇 질문 한도를 다 썼어요. 약 60분 뒤에 다시 시도해 주세요',
  feature: 'CHAT', retryAt: '2026-11-25T01:00:00Z', retryAfterSeconds: 3600,
}
const response = (status, body, headers = {}) => new Response(body === null ? '' : JSON.stringify(body), { status, headers })

test('429 problem body gives retryAt from the body and the feature', async () => {
  const error = await toApiError(response(429, problem, { 'Retry-After': '10' }))
  assert.ok(error instanceof ApiRequestError)
  assert.equal(error.status, 429)
  assert.equal(error.message, problem.message)
  assert.equal(error.feature, 'CHAT')
  assert.equal(error.retryAt.toISOString(), '2026-11-25T01:00:00.000Z') // body wins over the header
  assert.ok(isQuotaError(error))
})

test('429 without a body retryAt falls back to the Retry-After seconds header', async () => {
  const before = Date.now()
  const error = await toApiError(response(429, null, { 'Retry-After': '120' }))
  assert.ok(error.retryAt.getTime() >= before + 120_000)
  assert.ok(error.retryAt.getTime() <= Date.now() + 120_000)
  assert.equal(error.feature, null)
  assert.ok(isQuotaError(error))
})

test('existing {status, message} errors keep their shape and have no retry time', async () => {
  const error = await toApiError(response(503, { status: 503, message: '지금 질문이 많아요' }))
  assert.equal(error.status, 503)
  assert.equal(error.message, '지금 질문이 많아요')
  assert.equal(error.retryAt, null)
  assert.equal(error.feature, null)
  assert.ok(!isQuotaError(error))
})

test('429 wait is a duration from the body retryAfterSeconds, independent of the local clock', async () => {
  const error = await toApiError(response(429, problem, { 'Retry-After': '10' }))
  assert.equal(error.retryAfterMs, 3_600_000)
})

test('429 wait falls back to the Retry-After seconds header; a blank header gives no retry info', async () => {
  assert.equal((await toApiError(response(429, null, { 'Retry-After': '120' }))).retryAfterMs, 120_000)
  const blank = await toApiError(response(429, null, { 'Retry-After': ' ' }))
  assert.equal(blank.retryAt, null)
  assert.equal(blank.retryAfterMs, null)
  assert.ok(!isQuotaError(blank))
})

test('a non-429 error with Retry-After gets no retry information', async () => {
  const error = await toApiError(response(503, { status: 503, message: '지금 질문이 많아요' }, { 'Retry-After': '30' }))
  assert.equal(error.retryAt, null)
  assert.equal(error.retryAfterMs, null)
  assert.ok(!isQuotaError(error))
})
