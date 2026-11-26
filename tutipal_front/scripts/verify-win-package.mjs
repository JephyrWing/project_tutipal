import { stat } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { getCurrentFuseWire, FuseVersion, FuseV1Options } from '@electron/fuses'

// Read the produced executable, independently of the builder configuration.
// Never flip fuses on an installed binary or change the user's cookie format.
const expected = {
  RunAsNode: false,
  EnableCookieEncryption: true,
  EnableNodeOptionsEnvironmentVariable: false,
  EnableNodeCliInspectArguments: false,
  EnableEmbeddedAsarIntegrityValidation: true,
  OnlyLoadAppFromAsar: true,
  GrantFileProtocolExtraPrivileges: false,
}
const project = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const root = path.resolve(process.argv[2] || path.join(project, 'release/win-unpacked'))
try {
  const wire = await getCurrentFuseWire(path.join(root, 'TUTIPAL.exe'))
  if (wire.version !== FuseVersion.V1) throw new Error('Unsupported fuse wire version')
  const checks = Object.entries(expected).map(([name, enabled]) => ({
    name, expected: enabled ? 49 : 48, actual: wire[FuseV1Options[name]],
    passed: wire[FuseV1Options[name]] === (enabled ? 49 : 48),
  }))
  for (const relative of ['resources/app.asar', 'resources/app.asar.unpacked/electron/native/bin/window-target.exe',
    'resources/app.asar.unpacked/electron/native/bin/focus-probe.exe']) {
    const info = await stat(path.join(root, relative)).catch(() => null)
    checks.push({ name: relative, passed: !!info?.isFile() && info.size > 0 })
  }
  const failed = checks.filter(check => !check.passed).length
  console.log(JSON.stringify({ root, checks, passed: checks.length - failed, failed,
    note: 'Artifact checks only; installer, runtime permissions and user flows require separate verification.' }, null, 2))
  if (failed) process.exitCode = 1
} catch (error) {
  console.error('Windows package verification failed: ' + error.message)
  process.exitCode = 1
}
