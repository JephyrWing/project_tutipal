import { test } from 'node:test'
import assert from 'node:assert/strict'
import { authFailure } from '../src/utils/authFailure.js'
import { createAuthSession } from '../src/utils/authSession.js'
import { advanceAuthGeneration, AUTH_EXPIRED_EVENT, request } from '../src/api/client.js'

const user = { id: 1, name: 'Synthetic user' }
const deferred = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
function setup(overrides = {}) {
  let state, boundaries = 0
  const api = { me: async () => user, logout: async () => {}, updateName: async name => ({ ...user, name }), ...overrides }
  const control = createAuthSession({ api, onChange: value => { state = value }, onBoundary: () => { boundaries++ } })
  return { api, control, state: () => state, boundaries: () => boundaries }
}
test('public error messages distinguish expired, forbidden, server, transport and timeout failures', () => {
  assert.match(authFailure({ status: 401 }), /만료/)
  assert.match(authFailure({ status: 403 }), /접근 권한/)
  assert.match(authFailure({ status: 503 }), /서버가 잠시/)
  assert.match(authFailure(new TypeError('private credential')), /인터넷 연결/)
  assert.match(authFailure({ name: 'TimeoutError' }), /응답이 늦어/)
  assert.match(authFailure({ name: 'AuthContextChangedError' }), /로그인 상태가 바뀌/)
  assert.equal(authFailure(new Error('private credential')).includes('private credential'), false)
})
test('startup transport failure is recoverable and is not reported as expired authentication', async () => {
  const { api, control, state } = setup({ me: async () => { throw new TypeError('offline') } })
  await control.refresh()
  assert.equal(state().me, null)
  assert.equal(state().sessionExpired, false)
  assert.match(state().authError, /인터넷 연결/)
  api.me = async () => user
  assert.deepEqual(await control.refresh(), user)
  assert.equal(state().authError, null)
})
test('server logout failure retains the signed-in state and allows an explicit retry', async () => {
  const { api, control, state } = setup({ logout: async () => { throw { status: 503 } } })
  await control.refresh()
  assert.equal(await control.logout(), false)
  assert.deepEqual(state().me, user)
  assert.match(state().logoutError, /로그아웃을 완료하지 못했어요/)
  assert.equal(state().loggingOut, false)
  api.logout = async () => {}
  assert.equal(await control.logout(), true)
  assert.equal(state().me, null)
  assert.equal(state().logoutError, null)
})
test('already unauthorized logout clears local authentication without a false server failure', async () => {
  const { control, state } = setup({ logout: async () => { throw { status: 401 } } })
  await control.refresh(); assert.equal(await control.logout(), true)
  assert.equal(state().me, null)
  assert.equal(state().logoutError, null)
})
test('an older me response cannot restore a user after logout', async () => {
  const delayed = deferred()
  const { api, control, state } = setup()
  await control.refresh()
  api.me = () => delayed.promise
  const pending = control.refresh()
  await control.logout()
  delayed.resolve(user); await pending
  assert.equal(state().me, null)
})
test('an obsolete refresh failure cannot erase a newer successful refresh', async () => {
  const delayed = deferred()
  const { api, control, state } = setup({ me: () => delayed.promise })
  const older = control.refresh()
  api.me = async () => ({ ...user, id: 2 })
  await control.refresh(); delayed.reject(new TypeError('offline')); await older
  assert.equal(state().me.id, 2)
  assert.equal(state().authError, null)
})
test('expiration clears protected state and invalidates an in-flight refresh', async () => {
  const delayed = deferred()
  const { api, control, state } = setup()
  await control.refresh(); api.me = () => delayed.promise
  const older = control.refresh()
  control.expire(); delayed.resolve(user); await older
  assert.equal(state().me, null)
  assert.equal(state().sessionExpired, true)
  assert.equal(state().authError, null)
})
test('a late name update does not revive signed-out state', async () => {
  const delayed = deferred()
  const { control, state } = setup({ updateName: () => delayed.promise })
  await control.refresh()
  const updating = control.updateName('Changed')
  await control.logout(); delayed.resolve({ ...user, name: 'Changed' }); await updating
  assert.equal(state().me, null)
})
test('refresh and duplicate logout do not race a pending logout', async () => {
  const delayed = deferred(); let calls = 0
  const { control, state } = setup({ logout: () => { calls++; return delayed.promise } })
  await control.refresh()
  const pending = control.logout()
  assert.equal(await control.refresh(), undefined)
  assert.equal(await control.logout(), false)
  assert.equal(calls, 1)
  delayed.resolve(); await pending
  assert.equal(state().me, null)
})
test('disposed providers discard pending responses', async () => {
  const delayed = deferred()
  const { control, state } = setup({ me: () => delayed.promise })
  const pending = control.refresh(); control.dispose(); delayed.resolve(user); await pending
  assert.equal(state(), undefined)
})

async function withClientMocks(fetchMock, run) {
  const previousFetch = globalThis.fetch, previousWindow = globalThis.window
  const events = []
  globalThis.fetch = fetchMock
  globalThis.window = { dispatchEvent: event => { events.push(event.type) } }
  advanceAuthGeneration()
  try { await run(events) } finally { globalThis.fetch = previousFetch; if (previousWindow === undefined) delete globalThis.window; else globalThis.window = previousWindow; advanceAuthGeneration() }
}
const response = (status, body = {}) => new Response(status === 204 ? null : JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
test('a fresh protected request emits one expiry notification on 401', async () => {
  await withClientMocks(async () => response(401), async events => {
    await assert.rejects(request('/api/protected'), error => error.status === 401)
    assert.deepEqual(events, [AUTH_EXPIRED_EVENT])
  })
})
test('a 401 from a previous auth generation cannot sign out a newly verified account', async () => {
  const delayed = deferred()
  await withClientMocks(() => delayed.promise, async events => {
    const pending = request('/api/protected')
    advanceAuthGeneration(); delayed.resolve(response(401))
    await assert.rejects(pending, error => error.status === 401)
    assert.deepEqual(events, [])
  })
})
test('mutating 403 retries the CSRF token once in the same auth generation', async () => {
  let csrfReads = 0, posts = 0
  await withClientMocks(async url => url === '/api/auth/csrf'
    ? response(200, { headerName: 'X-CSRF', token: String(++csrfReads) })
    : (++posts === 1 ? response(403) : response(204)), async () => {
    await request('/api/change', { method: 'POST' })
    assert.equal(csrfReads, 2); assert.equal(posts, 2)
  })
})
test('an old mutating 403 is not replayed under a new auth generation', async () => {
  const delayed = deferred(), posted = deferred(); let posts = 0
  await withClientMocks(async url => {
    if (url === '/api/auth/csrf') return response(200, { headerName: 'X-CSRF', token: 'synthetic' })
    posts++; posted.resolve(); return delayed.promise
  }, async () => {
    const pending = request('/api/change', { method: 'POST' })
    await posted.promise; advanceAuthGeneration(); delayed.resolve(response(403))
    await assert.rejects(pending, error => error.status === 403)
    assert.equal(posts, 1)
  })
})
test('old CSRF completion cannot send a mutation after the account boundary changes', async () => {
  const delayed = deferred(); let posts = 0
  await withClientMocks(async url => url === '/api/auth/csrf' ? delayed.promise : (posts++, response(204)), async () => {
    const pending = request('/api/change', { method: 'POST' })
    advanceAuthGeneration(); delayed.resolve(response(200, { headerName: 'X-CSRF', token: 'synthetic' }))
    await assert.rejects(pending, error => error.name === 'AuthContextChangedError')
    assert.equal(posts, 0)
  })
})
test('an auth boundary between CSRF retry completion and resending prevents a second mutation', async () => {
  let csrfReads = 0, posts = 0
  await withClientMocks(async url => {
    if (url !== '/api/auth/csrf') { posts++; return response(403) }
    if (++csrfReads === 1) return response(200, { headerName: 'X-CSRF', token: 'first' })
    return { ok: true, json() {
      queueMicrotask(() => queueMicrotask(advanceAuthGeneration))
      return Promise.resolve({ headerName: 'X-CSRF', token: 'retry' })
    } }
  }, async () => {
    await assert.rejects(request('/api/change', { method: 'POST' }), error => error.name === 'AuthContextChangedError')
    assert.equal(posts, 1)
  })
})
