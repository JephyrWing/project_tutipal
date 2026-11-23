import { spawn } from 'node:child_process'
import { randomBytes } from 'node:crypto'
import { mkdtemp, readdir, realpath, rm } from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = fileURLToPath(new URL('../../', import.meta.url))
const dbUrl = process.env.E2E_DB_URL ?? 'jdbc:postgresql://127.0.0.1:25432/tutipal_e2e'
if (!/^jdbc:postgresql:\/\/127\.0\.0\.1:\d+\/tutipal_e2e(?:_[a-z0-9]+)?$/.test(dbUrl)) {
  throw new Error('E2E_DB_URL must use loopback and a dedicated tutipal_e2e database')
}
if (!process.env.E2E_DB_PASSWORD) throw new Error('Set E2E_DB_PASSWORD for the disposable database; real .env files are never loaded')
const libs = path.join(root, 'tutipal_back/build/libs')
const jars = (await readdir(libs)).filter(name => name.endsWith('.jar') && !name.endsWith('-plain.jar'))
if (jars.length !== 1) throw new Error('Build exactly one Spring bootJar before running the Electron tests')
const temporary = await mkdtemp(path.join(os.tmpdir(), 'tutipal-e2e-server-'))
const java = process.env.JAVA_HOME
  ? path.join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : 'java'
const child = spawn(java, ['-jar', path.join(libs, jars[0]), '--server.address=127.0.0.1', '--server.port=28080'], {
  cwd: temporary,
  stdio: 'inherit',
  windowsHide: true,
  env: {
    ...process.env,
    SPRING_PROFILES_ACTIVE: 'local',
    SPRING_FLYWAY_ENABLED: 'true',
    SPRING_JPA_HIBERNATE_DDL_AUTO: 'validate',
    DB_URL: dbUrl,
    DB_USER: 'tutipal_e2e',
    DB_PW: process.env.E2E_DB_PASSWORD,
    DEV_LOGIN: 'true',
    SESSION_COOKIE_SECURE: 'false',
    FAST_API_BASE_URL: 'http://127.0.0.1:9',
    AI_INTERNAL_KEY: randomBytes(32).toString('hex'),
    LIVEKIT_INTERNAL_URL: 'http://127.0.0.1:9',
    LIVEKIT_API_KEY: 'disposable-e2e',
    LIVEKIT_API_SECRET: randomBytes(32).toString('hex'),
    MATERIAL_STORAGE: 'local',
    MATERIAL_LOCAL_PATH: path.join(temporary, 'materials'),
    GOOGLE_CLIENT_ID: '', GOOGLE_CLIENT_SECRET: '',
    NAVER_CLIENT_ID: '', NAVER_CLIENT_SECRET: '',
    KAKAO_CLIENT_ID: '', KAKAO_CLIENT_SECRET: '',
    OPENAI_API_KEY: '', AWS_ACCESS_KEY_ID: '', AWS_SECRET_ACCESS_KEY: '', AWS_SESSION_TOKEN: '',
  },
})
for (const signal of ['SIGTERM', 'SIGINT']) process.once(signal, () => child.kill())
child.once('error', error => { console.error(error.message); process.exitCode = 1 })
child.once('exit', async code => {
  const resolved = await realpath(temporary)
  const allowedRoot = await realpath(os.tmpdir())
  if (path.dirname(resolved) === allowedRoot && path.basename(resolved).startsWith('tutipal-e2e-server-')) {
    await rm(resolved, { recursive: true, force: true })
  }
  process.exitCode = code ?? 1
})
