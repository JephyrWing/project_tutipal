import { test } from 'node:test'
import assert from 'node:assert/strict'
import { activeLock, lockFromError, quotaWaitMs } from '../src/utils/quotaRetry.js'

const serverRetryAt = new Date('2026-11-25T00:10:00Z')
const quota = { status: 429, retryAt: serverRetryAt, retryAfterMs: 600_000 }

test('wait comes from the duration, not from the server retryAt, so a skewed local clock does not matter', () => {
  const fastClock = Date.parse('2026-11-25T00:15:00Z') // local clock 15 min ahead of server time 00:00
  const slowClock = Date.parse('2026-11-24T23:45:00Z') // local clock 15 min behind
  assert.equal(quotaWaitMs(quota, fastClock), 600_000)
  assert.equal(quotaWaitMs(quota, slowClock), 600_000)
})

test('without a duration the wait falls back to retryAt minus now', () => {
  assert.equal(quotaWaitMs({ status: 429, retryAt: new Date(61_000) }, 1000), 60_000)
  assert.equal(quotaWaitMs({ status: 429, retryAt: new Date(500) }, 1000), 0)
})

test('non-quota errors and quota errors without retry info give no wait', () => {
  assert.equal(quotaWaitMs({ status: 503, retryAfterMs: 1000 }, 0), null)
  assert.equal(quotaWaitMs({ status: 429 }, 0), null)
  assert.equal(quotaWaitMs(null, 0), null)
  assert.equal(lockFromError({ status: 500 }, 0), null)
})

test('lock lasts exactly the wait on the local clock and keeps the server time for display', () => {
  const now = Date.parse('2026-11-25T00:15:00Z')
  const lock = lockFromError(quota, now)
  assert.deepEqual(lock, { until: now + 600_000, retryAt: serverRetryAt })
  assert.equal(activeLock(lock, now), lock)
  assert.equal(activeLock(lock, now + 599_999), lock)
  assert.equal(activeLock(lock, now + 600_000), null)
  assert.equal(activeLock(null, now), null)
})
