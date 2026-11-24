import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import os from 'node:os'
import path from 'node:path'
const require = createRequire(import.meta.url)
const { productionOrigin, releaseMetadata, verifyReleaseMetadata, resolveServerUrl, loadServerUrl } = require('../electron/serverConfig.cjs')
const origin = 'https://classroom.school.edu'
const sourceSha = 'a'.repeat(40)
const pkg = () => ({ tutipal: { serverUrl: origin, release: releaseMetadata(origin, sourceSha) } })
test('production origin normalizes DNS HTTPS without silently changing a base path', () => {
  assert.equal(productionOrigin('https://CLASSROOM.school.edu:443/'), origin)
  assert.equal(productionOrigin(origin + ':8443'), origin + ':8443')
})
test('missing, local, placeholder, numeric and non-HTTPS production origins are refused', () => {
  for (const input of [undefined, '', ' ' + origin, 'http://classroom.school.edu', 'https://localhost', 'https://a.localhost',
    'https://127.0.0.1', 'https://2130706433', 'https://192.168.0.1', 'https://[::1]', 'https://example.com',
    'https://CHANGE_ME_SPRING_DOMAIN', 'https://a.invalid', 'javascript:alert(1)']) assert.throws(() => productionOrigin(input))
})
test('release addresses reject credentials, query, fragments and paths', () => {
  for (const suffix of ['/api', '?token=test', '#part', '/?', '/#']) assert.throws(() => productionOrigin(origin + suffix))
  assert.throws(() => productionOrigin('https://user:password@classroom.school.edu'))
})
test('release provenance requires the full Git SHA', () => {
  for (const sha of ['', '1234567', undefined, 'z'.repeat(40)]) assert.throws(() => releaseMetadata(origin, sha))
})
test('production ignores inherited development server overrides', () => {
  assert.equal(resolveServerUrl(pkg(), { TUTIPAL_SPRING_URL: 'http://127.0.0.1:8080' }), origin)
  assert.equal(resolveServerUrl(pkg(), { TUTIPAL_SPRING_URL: 'https://different.school.edu' }), origin)
})
test('legacy local packages and development keep their existing address selection', () => {
  assert.equal(resolveServerUrl(), 'http://127.0.0.1:8080')
  assert.equal(resolveServerUrl({ tutipal: { serverUrl: 'http://127.0.0.1:18080' } }), 'http://127.0.0.1:18080')
  assert.equal(resolveServerUrl(pkgWithoutRelease(), { TUTIPAL_SPRING_URL: 'http://localhost:28080' }), 'http://localhost:28080')
  for (const value of ['file:///tmp/secret', 'https://user:pass@classroom.school.edu']) assert.throws(() => resolveServerUrl({}, { TUTIPAL_SPRING_URL: value }))
})
function pkgWithoutRelease() { return { tutipal: { serverUrl: origin } } }
test('release metadata mismatches never fall back to localhost', () => {
  for (const key of ['schemaVersion', 'channel', 'sourceSha', 'serverOrigin']) {
    const value = pkg(); value.tutipal.release[key] = null
    assert.throws(() => resolveServerUrl(value))
  }
  const value = pkg(); value.tutipal.serverUrl = 'http://127.0.0.1:8080'
  assert.throws(() => resolveServerUrl(value))
  assert.throws(() => resolveServerUrl({ tutipal: { release: null } }))
})
test('artifact verification rejects absent or different origin and source SHA', () => {
  assert.deepEqual(verifyReleaseMetadata(pkg(), origin, sourceSha), releaseMetadata(origin, sourceSha))
  assert.throws(() => verifyReleaseMetadata(pkgWithoutRelease(), origin, sourceSha))
  assert.throws(() => verifyReleaseMetadata(pkg(), 'https://other.school.edu', sourceSha))
  assert.throws(() => verifyReleaseMetadata(pkg(), origin, 'b'.repeat(40)))
})
test('unreadable packaged configuration fails closed while missing dev metadata preserves local startup', () => {
  const directory = mkdtempSync(path.join(os.tmpdir(), 'tutipal-config-test-'))
  try {
    assert.throws(() => loadServerUrl(directory, true, {}))
    assert.equal(loadServerUrl(directory, false, {}), 'http://127.0.0.1:8080')
    writeFileSync(path.join(directory, 'package.json'), '{broken')
    assert.throws(() => loadServerUrl(directory, true, {}))
    writeFileSync(path.join(directory, 'package.json'), JSON.stringify(pkg()))
    assert.equal(loadServerUrl(directory, true, {}), origin)
  } finally {
    if (path.dirname(directory) === os.tmpdir() && path.basename(directory).startsWith('tutipal-config-test-')) rmSync(directory, { recursive: true, force: true })
  }
})
