import { spawnSync } from 'node:child_process'
import { createRequire } from 'node:module'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import asar from '@electron/asar'
const require = createRequire(import.meta.url)
const { verifyReleaseMetadata } = require('../electron/serverConfig.cjs')
try {
  const [directory, origin, sourceSha] = process.argv.slice(2)
  if (!directory || !origin || !sourceSha) throw new Error('설치 폴더·기대 HTTPS 주소·전체 source SHA가 필요합니다')
  const root = path.resolve(directory)
  const metadata = JSON.parse(asar.extractFile(path.join(root, 'resources/app.asar'), 'package.json').toString('utf8'))
  const release = verifyReleaseMetadata(metadata, origin, sourceSha)
  const security = spawnSync(process.execPath, [fileURLToPath(new URL('./verify-win-package.mjs', import.meta.url)), root],
    { stdio: 'inherit', shell: false, windowsHide: true })
  if (security.error || security.status !== 0) throw new Error('운영 바이너리 보안 검사가 실패했습니다')
  console.log(JSON.stringify({ ...release, artifactMetadataVerified: true }, null, 2))
} catch (error) { console.error('운영 패키지 검사 실패: ' + error.message); process.exitCode = 1 }
