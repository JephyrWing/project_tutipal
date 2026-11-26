import test from 'node:test'
import assert from 'node:assert/strict'
import { createShareSourceCatalog } from '../src/utils/shareSourceCatalog.js'

function deferred() {
  let resolve, reject
  const promise = new Promise((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}

test('a closed window disappears when the source snapshot is refreshed', async () => {
  let sources = [{ id: 'old-window', name: 'test.txt' }]
  let shown
  const catalog = createShareSourceCatalog(async () => sources, value => { shown = value }, assert.fail)
  await catalog.refresh()
  sources = [{ id: 'new-window', name: 'test.txt' }]
  const available = await catalog.refresh()
  assert.equal(shown.some(source => source.id === 'old-window'), false)
  assert.deepEqual(available, sources)
})

test('a late old response cannot restore a closed window into a newer list', async () => {
  const old = deferred(), current = deferred()
  const pending = [old.promise, current.promise]
  const updates = []
  const catalog = createShareSourceCatalog(() => pending.shift(), value => updates.push(value), assert.fail)
  const first = catalog.refresh(), second = catalog.refresh()
  current.resolve([])
  assert.deepEqual(await second, [])
  old.resolve([{ id: 'closed-window' }])
  assert.equal(await first, null)
  assert.deepEqual(updates, [[]])
})

test('a refresh failure clears stale choices instead of authorizing a fallback', async () => {
  const error = new Error('enumeration failed')
  const updates = [], errors = []
  const catalog = createShareSourceCatalog(async () => { throw error }, value => updates.push(value), value => errors.push(value))
  assert.equal(await catalog.refresh(), null)
  assert.deepEqual(updates, [[]])
  assert.deepEqual(errors, [error])
})

test('unmounted source picker ignores pending results and does not enumerate again', async () => {
  const pending = deferred()
  let loads = 0
  const catalog = createShareSourceCatalog(() => { loads++; return pending.promise }, assert.fail, assert.fail)
  const refresh = catalog.refresh()
  catalog.dispose()
  pending.resolve([{ id: 'late-window' }])
  assert.equal(await refresh, null)
  assert.equal(await catalog.refresh(), null)
  assert.equal(loads, 1)
})

test('an obsolete refresh error does not erase a newer valid list', async () => {
  const old = deferred()
  let calls = 0
  const updates = []
  const sources = [{ id: 'current-window' }]
  const catalog = createShareSourceCatalog(() => ++calls === 1 ? old.promise : Promise.resolve(sources), value => updates.push(value), assert.fail)
  const first = catalog.refresh()
  await catalog.refresh()
  old.reject(new Error('old request failed'))
  assert.equal(await first, null)
  assert.deepEqual(updates, [sources])
})
