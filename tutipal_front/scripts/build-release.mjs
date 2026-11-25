import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { spawnSync } from 'node:child_process'
import { createRequire } from 'node:module'
import { createReadStream } from 'node:fs'
import { createHash } from 'node:crypto'
import path from 'node:path'
import os from 'node:os'
import { fileURLToPath } from 'node:url'
const require = createRequire(import.meta.url)
const { releaseMetadata } = require('../electron/serverConfig.cjs')
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
let temporary
function run(executable, args, capture = false) {
  const result = spawnSync(executable, args, { cwd: root, shell: false, windowsHide: true,
    encoding: 'utf8', stdio: capture ? 'pipe' : 'inherit' })
  if (result.error || result.status !== 0) throw new Error(capture ? 'Git 기준 또는 소스 상태를 확인하지 못했습니다' : '운영 패키징/검사가 실패했습니다')
  return result.stdout?.trim()
}
async function sha256(file) {
  const hash = createHash('sha256')
  for await (const chunk of createReadStream(file)) hash.update(chunk)
  return hash.digest('hex')
}
try {
  const args = process.argv.slice(2)
  if (args.some(arg => arg !== '--check-only')) throw new Error('지원하는 옵션은 --check-only입니다. 주소는 TUTIPAL_RELEASE_SERVER_URL로 지정합니다')
  // Validate the origin first: an unset value cannot silently produce a localhost release.
  const sourceSha = run('git', ['rev-parse', 'HEAD'], true)
  const release = releaseMetadata(process.env.TUTIPAL_RELEASE_SERVER_URL, sourceSha)
  if (run('git', ['status', '--porcelain'], true)) throw new Error('커밋하지 않은 변경이 있어 운영 설치본을 만들지 않았습니다')
  const relativeOutput = `release/production/${sourceSha}`
  if (args.includes('--check-only')) {
    console.log(JSON.stringify({ ...release, output: relativeOutput, built: false }, null, 2))
  } else {
    if (process.platform !== 'win32') throw new Error('Windows에서 운영 NSIS 설치 파일을 생성해 주세요')
    const pkg = JSON.parse(await readFile(path.join(root, 'package.json'), 'utf8'))
    const config = { ...pkg.build,
      directories: { ...pkg.build.directories, output: relativeOutput },
      artifactName: `TUTIPAL-Setup-\${version}-production-${sourceSha.slice(0, 12)}.\${ext}`,
      extraMetadata: { ...pkg.build.extraMetadata, tutipal: { ...pkg.tutipal, serverUrl: release.serverOrigin, release } } }
    temporary = await mkdtemp(path.join(os.tmpdir(), 'tutipal-release-config-'))
    const configPath = path.join(temporary, 'builder.json')
    await writeFile(configPath, JSON.stringify(config))
    run(process.execPath, [path.join(root, 'scripts/build-window-target.mjs')])
    run(process.execPath, [path.join(root, 'node_modules/vite/bin/vite.js'), 'build'])
    run(process.execPath, [path.join(root, 'node_modules/electron-builder/cli.js'), '--config', configPath, '--win', 'nsis', '--x64', '--publish', 'never'])
    if (run('git', ['rev-parse', 'HEAD'], true) !== sourceSha || run('git', ['status', '--porcelain'], true)) throw new Error('빌드 중 소스가 변경되어 제출을 중단했습니다')
    const output = path.join(root, relativeOutput)
    run(process.execPath, [path.join(root, 'scripts/verify-release.mjs'), path.join(output, 'win-unpacked'), release.serverOrigin, sourceSha])
    const installer = `TUTIPAL-Setup-${pkg.version}-production-${sourceSha.slice(0, 12)}.exe`
    const hashes = {}
    for (const relative of [installer, 'win-unpacked/TUTIPAL.exe', 'win-unpacked/resources/app.asar']) hashes[relative] = await sha256(path.join(output, relative))
    await writeFile(path.join(output, 'release-manifest.json'), JSON.stringify({ ...release, builtAtUtc: new Date().toISOString(), sha256: hashes }, null, 2))
    console.log('운영 패키지 검사 완료: ' + relativeOutput)
  }
} catch (error) { console.error(error.message); process.exitCode = 1 }
finally {
  if (temporary && path.dirname(temporary) === os.tmpdir() && path.basename(temporary).startsWith('tutipal-release-config-')) await rm(temporary, { recursive: true, force: true })
}
