import { createHash } from 'node:crypto'
import { createReadStream } from 'node:fs'
import { cp, mkdir, mkdtemp, readFile, realpath, rm, stat, writeFile } from 'node:fs/promises'
import { createRequire } from 'node:module'
import { spawnSync } from 'node:child_process'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { flipFuses, getCurrentFuseWire, FuseVersion, FuseV1Options } from '@electron/fuses'
const require = createRequire(import.meta.url)
const asar = require('@electron/asar')
const front = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const source = path.resolve(process.argv[2] || path.join(front, 'release/win-unpacked'))
const report = { startUtc: new Date().toISOString(), source, checks: [], expected: 5 }
let temporary
let original

async function hash(file) {
  const sum = createHash('sha256')
  for await (const chunk of createReadStream(file)) sum.update(chunk)
  return sum.digest('hex')
}
async function sourceHashes() {
  return { exe: await hash(path.join(source, 'TUTIPAL.exe')), asar: await hash(path.join(source, 'resources/app.asar')) }
}
function check(name, passed, detail) {
  report.checks.push({ name, passed, detail })
  if (!passed) throw new Error(name)
}
function payload(marker, profile) {
  // No original application imports, windows, protocol registration or network calls.
  return `const {app}=require('electron');const fs=require('node:fs');app.disableHardwareAcceleration();fs.mkdirSync(${JSON.stringify(profile)},{recursive:true});app.setPath('userData',${JSON.stringify(profile)});app.setPath('sessionData',${JSON.stringify(profile)});app.whenReady().then(()=>{fs.writeFileSync(${JSON.stringify(marker)},'safe-probe');setTimeout(()=>app.quit(),250)});`
}
async function run(root, marker, profile) {
  const child = spawnSync(path.join(root, 'TUTIPAL.exe'), ['--no-error-dialogs', '--noerrdialogs',
    '--disable-crash-reporter', '--disable-breakpad', '--enable-logging=stderr', `--user-data-dir=${profile}`], {
    cwd: root, windowsHide: true, timeout: 15000, encoding: 'utf8', maxBuffer: 1024 * 1024,
    env: { ...process.env, ELECTRON_RUN_AS_NODE: '', NODE_OPTIONS: '' },
  })
  const log = (child.stderr || '') + (child.stdout || '')
  return { code: child.status, signal: child.signal, error: child.error?.code,
    marker: !!(await stat(marker).catch(() => null)),
    integrityError: /Integrity check failed|Failed to validate integrity|ASAR.*integrity|integrity.*ASAR|hash.*mismatch/i.test(log),
    log: log.slice(-4000) }
}

try {
  if (process.platform !== 'win32') throw new Error('Windows artifact runtime check requires Windows')
  if (path.basename(source) !== 'win-unpacked' || path.basename(path.dirname(source)) !== 'release')
    throw new Error('Only a release/win-unpacked build artifact is accepted')
  const wire = await getCurrentFuseWire(path.join(source, 'TUTIPAL.exe'))
  if (wire.version !== FuseVersion.V1 || wire[FuseV1Options.EnableEmbeddedAsarIntegrityValidation] !== 49
      || wire[FuseV1Options.OnlyLoadAppFromAsar] !== 49) throw new Error('Source integrity fuses must already be enabled')
  original = await sourceHashes()
  report.before = original
  temporary = await mkdtemp(path.join(os.tmpdir(), 'tutipal-asar-check-'))
  const protectedRoot = path.join(temporary, 'protected')
  const controlRoot = path.join(temporary, 'control')
  await cp(source, protectedRoot, { recursive: true, errorOnExist: true, force: false })
  await cp(source, controlRoot, { recursive: true, errorOnExist: true, force: false })
  // The negative control is a private disposable copy, never the source or installed executable.
  await flipFuses(path.join(controlRoot, 'TUTIPAL.exe'), {
    version: FuseVersion.V1, [FuseV1Options.EnableEmbeddedAsarIntegrityValidation]: false,
  })
  for (const kind of ['header', 'content']) {
    const protectedMarker = path.join(temporary, kind + '-protected.txt')
    const profile = path.join(temporary, kind + '-profile')
    await mkdir(profile)
    let changed
    let expectedEntry
    let entryName
    if (kind === 'header') {
      const benign = path.join(temporary, 'benign')
      await mkdir(benign)
      await writeFile(path.join(benign, 'package.json'), JSON.stringify({ name: 'tutipal-integrity-probe', version: '1.0.0', main: 'main.cjs' }))
      // Both copies use the same harmless entry. The environment chooses only this private marker.
      expectedEntry = Buffer.from(payload(protectedMarker, profile))
      entryName = 'main.cjs'
      await writeFile(path.join(benign, entryName), expectedEntry)
      const archive = path.join(temporary, 'benign.asar')
      await asar.createPackage(benign, archive)
      changed = await readFile(archive)
    } else {
      changed = await readFile(path.join(source, 'resources/app.asar'))
      const header = asar.getRawHeader(path.join(source, 'resources/app.asar'))
      const entry = asar.statFile(path.join(source, 'resources/app.asar'), 'electron/main.cjs')
      const code = Buffer.from(payload(protectedMarker, profile))
      if (entry.unpacked || entry.link || code.length > entry.size) throw new Error('Unsafe probe entry')
      const start = 8 + header.headerSize + Number(entry.offset)
      if (!Number.isSafeInteger(start) || start < 8 || start + entry.size > changed.length) throw new Error('Invalid archive offset')
      changed.fill(32, start, start + entry.size)
      code.copy(changed, start)
      entryName = 'electron/main.cjs'
      expectedEntry = Buffer.alloc(entry.size, 32)
      code.copy(expectedEntry)
    }
    await writeFile(path.join(protectedRoot, 'resources/app.asar'), changed)
    await writeFile(path.join(controlRoot, 'resources/app.asar'), changed)
    // Independently parse the written archive before any executable starts.
    // A bad offset must never let the original application entry run.
    for (const root of [protectedRoot, controlRoot]) {
      const archive = path.join(root, 'resources/app.asar')
      asar.uncache(archive)
      const pkg = JSON.parse(asar.extractFile(archive, 'package.json'))
      if (pkg.main !== entryName || !asar.extractFile(archive, entryName).equals(expectedEntry))
        throw new Error('Refusing launch: benign probe entry was not verified')
    }
    const control = await run(controlRoot, protectedMarker, profile)
    check(kind + ': harmless payload runs only in control copy', control.code === 0 && control.marker && !control.error, control)
    // Clear only our own marker. The protected copy must not recreate it.
    await rm(protectedMarker)
    const rejected = await run(protectedRoot, protectedMarker, profile)
    check(kind + ': protected executable rejects changed ASAR', rejected.code !== 0 && rejected.code !== null
      && !rejected.error && !rejected.marker && rejected.integrityError, rejected)
  }
} catch (error) {
  report.error = error.message
} finally {
  if (original) {
    const after = await sourceHashes()
    report.after = after
    report.checks.push({ name: 'source executable and ASAR remain unchanged', passed: after.exe === original.exe && after.asar === original.asar })
  }
  report.endUtc = new Date().toISOString()
  report.ran = report.checks.length
  report.passed = report.checks.filter(check => check.passed).length
  report.failed = report.checks.filter(check => !check.passed).length
  report.skipped = Math.max(0, report.expected - report.ran)
  await mkdir(path.join(front, 'test-results'), { recursive: true })
  await writeFile(path.join(front, 'test-results/win-integrity.json'), JSON.stringify(report, null, 2))
  console.log(JSON.stringify(report, null, 2))
  if (temporary) {
    const resolved = await realpath(temporary)
    if (path.dirname(resolved) !== await realpath(os.tmpdir()) || !path.basename(resolved).startsWith('tutipal-asar-check-'))
      throw new Error('Refusing cleanup outside the private probe directory')
    await rm(resolved, { recursive: true, force: false })
  }
  if (report.error || report.failed || report.ran !== report.expected) process.exitCode = 1
}
