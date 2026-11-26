/**
 * PT-97: timing rules for an AI quota rejection (HTTP 429), measured on the local clock.
 * The wait comes from retryAfterMs (a duration from the moment the response arrived), so a PC clock that
 * differs from the server clock neither re-requests too early nor locks too long. retryAt is for display only;
 * it is used as a fallback wait (retryAt - now) only when no duration is available.
 */

/** Milliseconds to wait from `now`, or null when the error is not a quota rejection with retry information. */
export function quotaWaitMs(error, now = Date.now()) {
  if (error?.status !== 429) return null
  if (Number.isFinite(error.retryAfterMs)) return Math.max(0, error.retryAfterMs)
  if (error.retryAt instanceof Date && !Number.isNaN(error.retryAt.getTime())) {
    return Math.max(0, error.retryAt.getTime() - now)
  }
  return null
}

/** Lock state for a quota rejection: { until (local epoch ms), retryAt (Date to display) }, or null. */
export function lockFromError(error, now = Date.now()) {
  const wait = quotaWaitMs(error, now)
  if (wait === null) return null
  const until = now + wait
  return { until, retryAt: error.retryAt instanceof Date ? error.retryAt : new Date(until) }
}

/** The lock while it still applies at `now`, otherwise null. */
export function activeLock(lock, now = Date.now()) {
  return lock && lock.until > now ? lock : null
}
