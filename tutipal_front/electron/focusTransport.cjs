// PT-90 sends collected rows to Spring POST /api/sessions/{id}/focus through the Electron session (login cookie),
// with the CSRF header from GET /api/auth/csrf and the backend Origin (same as the bridge proxy).
// Result for the collector: ok (with the server state) | retry (rows kept) | stop (collection ends).
// One send (CSRF + POST + one CSRF retry + body) has a deadline of timeoutMs; a hung request ends as retry so the
// collector can send again. The caller's signal (collector stop) also ends it at once.
const STOP_STATUSES = new Set([401, 403, 404, 410])
const MAX_STATE_LENGTH = 32
const DEFAULT_TIMEOUT_MS = 15_000

/** Settles with the promise, or rejects as soon as the signal aborts (even if the promise never settles). */
function untilAborted(promise, signal) {
  if (signal.aborted) return Promise.reject(signal.reason)
  return new Promise((resolve, reject) => {
    const onAbort = () => reject(signal.reason)
    signal.addEventListener('abort', onAbort, { once: true })
    promise.then(
      value => { signal.removeEventListener('abort', onAbort); resolve(value) },
      error => { signal.removeEventListener('abort', onAbort); reject(error) },
    )
  })
}

function createFocusTransport({ fetch, backend, timeoutMs = DEFAULT_TIMEOUT_MS }) {
  let csrf = null

  async function token(signal) {
    const response = await fetch(new URL('/api/auth/csrf', backend).href, {
      method: 'GET', credentials: 'include', headers: { Accept: 'application/json', Origin: backend.origin }, signal,
    })
    if (!response.ok) return null
    const body = await response.json()
    if (!body || typeof body.headerName !== 'string' || typeof body.token !== 'string'
      || !/^[A-Za-z0-9-]{1,64}$/.test(body.headerName)) return null
    return { headerName: body.headerName, token: body.token }
  }

  async function post(sessionId, body, signal) {
    return fetch(new URL(`/api/sessions/${sessionId}/focus`, backend).href, {
      method: 'POST',
      credentials: 'include',
      redirect: 'manual',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'application/json',
        Origin: backend.origin,
        [csrf.headerName]: csrf.token,
      },
      body: JSON.stringify(body),
      signal,
    })
  }

  async function focusOf(response) {
    try {
      const body = await response.json()
      if (body && typeof body.state === 'string' && body.state.length <= MAX_STATE_LENGTH) {
        return { state: body.state, updatedAt: typeof body.updatedAt === 'string' ? body.updatedAt : null }
      }
    } catch { /* state is optional for the collector */ }
    return undefined
  }

  async function attempt(sessionId, body, signal) {
    if (!csrf) csrf = await token(signal)
    if (!csrf) return { result: 'retry' }
    let response = await post(sessionId, body, signal)
    if (response.status === 403) {
      // the CSRF token may have rotated (new login session): fetch it again and try once more
      csrf = await token(signal)
      if (!csrf) return { result: 'retry' }
      response = await post(sessionId, body, signal)
    }
    if (response.status === 200) return { result: 'ok', focus: await focusOf(response) }
    if (STOP_STATUSES.has(response.status)) {
      csrf = null
      return { result: 'stop' }
    }
    return { result: 'retry' }
  }

  async function send(sessionId, body, { signal } = {}) {
    if (!Number.isSafeInteger(sessionId) || sessionId <= 0) return { result: 'stop' }
    const deadline = new AbortController()
    const timer = setTimeout(() => deadline.abort(new Error('focus send timeout')), timeoutMs)
    const onCallerAbort = () => deadline.abort(signal.reason)
    if (signal) {
      if (signal.aborted) onCallerAbort()
      else signal.addEventListener('abort', onCallerAbort, { once: true })
    }
    try {
      return await untilAborted(attempt(sessionId, body, deadline.signal), deadline.signal)
    } catch {
      return { result: 'retry' }
    } finally {
      clearTimeout(timer)
      signal?.removeEventListener('abort', onCallerAbort)
    }
  }

  return { send }
}

module.exports = { createFocusTransport }
