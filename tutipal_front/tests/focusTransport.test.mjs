// PT-90: electron/focusTransport.cjs posts collected rows to Spring with the session cookie (Electron session
// fetch), a CSRF header and our Origin, and turns the answer into ok / retry / stop for the collector (AC-17, AC-18).
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'

const require = createRequire(import.meta.url)
const { createFocusTransport } = require('../electron/focusTransport.cjs')

const backend = new URL('https://tutipal.example')
const BODY = { rows: [{ exeName: 'code.exe', idleSec: 3, switchCount: 1 }] }

function response(status, json) {
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: new Headers({ 'Content-Type': 'application/json' }),
    json: async () => json,
    text: async () => JSON.stringify(json ?? null),
  }
}

/** Fake fetch: CSRF endpoint answers with a new token each time; POSTs answer from the given status list. */
function fakeFetch(postStatuses, { csrfStatus = 200, throwOnPost = false } = {}) {
  const calls = []
  let tokens = 0
  let posts = 0
  const fetch = async (url, init = {}) => {
    const href = String(url)
    calls.push({ url: href, init })
    if (href.endsWith('/api/auth/csrf')) {
      tokens++
      return response(csrfStatus, { headerName: 'X-XSRF-TOKEN', token: `token-${tokens}` })
    }
    if (throwOnPost) throw new TypeError('network down')
    const status = postStatuses[Math.min(posts, postStatuses.length - 1)]
    posts++
    return response(status, status === 200 ? { state: 'FOCUS', updatedAt: '2026-11-26T03:01:00Z' } : { status, message: 'x' })
  }
  return { fetch, calls, posts: () => calls.filter(c => !c.url.endsWith('/api/auth/csrf')) }
}

const header = (init, name) => new Headers(init.headers).get(name)

test('posts the body to the session focus endpoint with CSRF header, Origin and JSON content type', async () => {
  const f = fakeFetch([200])
  const transport = createFocusTransport({ fetch: f.fetch, backend })

  const result = await transport.send(7, BODY)

  assert.equal(result.result, 'ok')
  const [post] = f.posts()
  assert.equal(post.url, 'https://tutipal.example/api/sessions/7/focus')
  assert.equal(post.init.method, 'POST')
  assert.equal(header(post.init, 'X-XSRF-TOKEN'), 'token-1')
  assert.equal(header(post.init, 'Origin'), 'https://tutipal.example')
  assert.match(header(post.init, 'Content-Type'), /^application\/json/)
  assert.deepEqual(JSON.parse(post.init.body), BODY)
  assert.ok(f.calls.some(c => c.url === 'https://tutipal.example/api/auth/csrf'))
})

test('200 returns ok with the server focus state', async () => {
  const f = fakeFetch([200])
  const result = await createFocusTransport({ fetch: f.fetch, backend }).send(7, BODY)
  assert.deepEqual(result.focus, { state: 'FOCUS', updatedAt: '2026-11-26T03:01:00Z' })
})

for (const status of [401, 404, 410]) {
  test(`${status} stops collection without retrying`, async () => {
    const f = fakeFetch([status])
    const result = await createFocusTransport({ fetch: f.fetch, backend }).send(7, BODY)
    assert.equal(result.result, 'stop')
    assert.equal(f.posts().length, 1)
  })
}

test('403 refreshes the CSRF token and retries once; success after that is ok', async () => {
  const f = fakeFetch([403, 200])
  const result = await createFocusTransport({ fetch: f.fetch, backend }).send(7, BODY)
  assert.equal(result.result, 'ok')
  const posts = f.posts()
  assert.equal(posts.length, 2)
  assert.equal(header(posts[1].init, 'X-XSRF-TOKEN'), 'token-2')
})

test('403 again after the token refresh stops collection', async () => {
  const f = fakeFetch([403, 403, 403])
  const result = await createFocusTransport({ fetch: f.fetch, backend }).send(7, BODY)
  assert.equal(result.result, 'stop')
  assert.equal(f.posts().length, 2)
})

for (const status of [429, 500, 502, 503]) {
  test(`${status} is retried later (rows kept)`, async () => {
    const f = fakeFetch([status])
    const result = await createFocusTransport({ fetch: f.fetch, backend }).send(7, BODY)
    assert.equal(result.result, 'retry')
    assert.equal(f.posts().length, 1)
  })
}

test('a network error is retried later', async () => {
  const f = fakeFetch([200], { throwOnPost: true })
  const result = await createFocusTransport({ fetch: f.fetch, backend }).send(7, BODY)
  assert.equal(result.result, 'retry')
})

test('a failing CSRF request is retried later without posting', async () => {
  const f = fakeFetch([200], { csrfStatus: 503 })
  const result = await createFocusTransport({ fetch: f.fetch, backend }).send(7, BODY)
  assert.equal(result.result, 'retry')
  assert.equal(f.posts().length, 0)
})

test('only the session focus path is used; the session id goes into the path as a number', async () => {
  const f = fakeFetch([200])
  await createFocusTransport({ fetch: f.fetch, backend }).send(123, BODY)
  assert.equal(f.posts()[0].url, 'https://tutipal.example/api/sessions/123/focus')
})

// ---- Round 1 review: deadline and caller abort ----

test('a hung request (fetch that never settles, even ignoring the signal) ends as retry after the deadline', async () => {
  const calls = []
  const fetch = (url, init) => { calls.push(init); return new Promise(() => {}) }
  const transport = createFocusTransport({ fetch, backend, timeoutMs: 50 })

  const started = Date.now()
  const result = await transport.send(7, BODY)

  assert.equal(result.result, 'retry')
  assert.ok(Date.now() - started < 2000)
  assert.ok(calls[0].signal instanceof AbortSignal, 'fetch gets an abort signal')
  assert.equal(calls[0].signal.aborted, true)
})

test('a hung POST after a good CSRF token also ends as retry, and the next send works', async () => {
  let hang = true
  const f = fakeFetch([200])
  const fetch = (url, init) => (hang && !String(url).endsWith('/api/auth/csrf') ? new Promise(() => {}) : f.fetch(url, init))
  const transport = createFocusTransport({ fetch, backend, timeoutMs: 50 })

  assert.equal((await transport.send(7, BODY)).result, 'retry')
  hang = false
  assert.equal((await transport.send(7, BODY)).result, 'ok')
})

test('the caller signal (collector stop) ends a running send at once', async () => {
  const fetch = () => new Promise(() => {})
  const transport = createFocusTransport({ fetch, backend, timeoutMs: 60_000 })
  const controller = new AbortController()

  const pending = transport.send(7, BODY, { signal: controller.signal })
  controller.abort()

  assert.equal((await pending).result, 'retry')
})

test('an already aborted caller signal sends nothing', async () => {
  const f = fakeFetch([200])
  const transport = createFocusTransport({ fetch: f.fetch, backend })
  const controller = new AbortController()
  controller.abort()

  assert.equal((await transport.send(7, BODY, { signal: controller.signal })).result, 'retry')
  assert.equal(f.posts().length, 0)
})
