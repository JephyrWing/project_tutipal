const { readFileSync } = require('node:fs')
const { isIP } = require('node:net')
const path = require('node:path')

const SHA = /^[a-f0-9]{40}$/
function productionOrigin(value) {
  if (typeof value !== 'string' || !value || value !== value.trim()) throw new Error('확정된 운영 HTTPS 주소가 필요합니다')
  let url
  try { url = new URL(value) } catch { throw new Error('운영 HTTPS 주소가 올바르지 않습니다') }
  const host = url.hostname.toLowerCase()
  const dnsName = host.includes('.') && host.split('.').every(label => /^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/.test(label))
  const reserved = /(?:^|\.)(?:localhost|local|internal|invalid|test|example)$/.test(host) || /(?:^|\.)example\.(?:com|org|net)$/.test(host)
  if (!/^https:\/\//i.test(value) || url.protocol !== 'https:' || url.username || url.password || url.pathname !== '/' || value.includes('?') || value.includes('#') ||
      !dnsName || reserved || isIP(host) || host.startsWith('[')) throw new Error('운영 서버는 경로·인증정보 없는 공개 DNS HTTPS 주소여야 합니다')
  return url.origin
}
function releaseMetadata(origin, sourceSha) {
  if (typeof sourceSha !== 'string' || !SHA.test(sourceSha)) throw new Error('전체 Git source SHA가 필요합니다')
  return { schemaVersion: 1, channel: 'production', serverOrigin: productionOrigin(origin), sourceSha }
}
function verifyReleaseMetadata(pkg, expectedOrigin, expectedSha) {
  const expected = releaseMetadata(expectedOrigin, expectedSha)
  const actual = pkg?.tutipal?.release
  if (!actual || actual.schemaVersion !== expected.schemaVersion || actual.channel !== expected.channel ||
      actual.sourceSha !== expected.sourceSha || actual.serverOrigin !== expected.serverOrigin ||
      pkg.tutipal.serverUrl !== expected.serverOrigin) throw new Error('운영 패키지의 서버 주소·채널·source SHA가 일치하지 않습니다')
  return expected
}
function resolveServerUrl(pkg = {}, env = {}) {
  const release = pkg.tutipal?.release
  if (release !== undefined) {
    verifyReleaseMetadata(pkg, release?.serverOrigin, release?.sourceSha)
    // An inherited development variable must never redirect a production installation.
    return release.serverOrigin
  }
  const value = env.TUTIPAL_SPRING_URL || pkg.tutipal?.serverUrl || 'http://127.0.0.1:8080'
  let url
  try { url = new URL(value) } catch { throw new Error('Spring 주소가 올바르지 않습니다') }
  if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password) throw new Error('Spring 주소가 올바르지 않습니다')
  return value
}
function loadServerUrl(appPath, isPackaged, env) {
  let pkg = {}
  try { pkg = JSON.parse(readFileSync(path.join(appPath, 'package.json'), 'utf8')) }
  catch { if (isPackaged) throw new Error('설치 정보가 올바르지 않습니다. 배포된 설치 파일로 다시 설치해 주세요') }
  return resolveServerUrl(pkg, env)
}
module.exports = { productionOrigin, releaseMetadata, verifyReleaseMetadata, resolveServerUrl, loadServerUrl }
