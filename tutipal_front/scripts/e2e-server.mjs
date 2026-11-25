import { spawn } from 'node:child_process'
import { randomBytes } from 'node:crypto'
import { mkdtemp, readdir, realpath, rm } from 'node:fs/promises'
import { createServer } from 'node:http'
import { once } from 'node:events'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = fileURLToPath(new URL('../../', import.meta.url))
const dbUrl = process.env.E2E_DB_URL ?? 'jdbc:postgresql://127.0.0.1:25432/tutipal_e2e'
if (!/^jdbc:postgresql:\/\/127\.0\.0\.1:\d+\/tutipal_e2e(?:_[a-z0-9]+)?$/.test(dbUrl)) {
  throw new Error('E2E_DB_URL must use loopback and a dedicated tutipal_e2e database')
}
if (!process.env.E2E_DB_PASSWORD) throw new Error('Set E2E_DB_PASSWORD for the disposable database; real .env files are never loaded')
const helpAccess = process.env.E2E_HELP_ACCESS === 'true'
if (helpAccess && !/^[a-f0-9]{64}$/.test(process.env.E2E_LIVEKIT_SECRET ?? '')) {
  throw new Error('Help access checks require a fresh 64-character hexadecimal test secret')
}
const libs = path.join(root, 'tutipal_back/build/libs')
const jars = (await readdir(libs)).filter(name => name.endsWith('.jar') && !name.endsWith('-plain.jar'))
if (jars.length !== 1) throw new Error('Build exactly one Spring bootJar before running the Electron tests')
const temporary = await mkdtemp(path.join(os.tmpdir(), 'tutipal-e2e-server-'))
const java = process.env.JAVA_HOME
  ? path.join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : 'java'
// Only the material tests' synthetic input reaches this loopback stand-in.
const materialVector = [1, ...Array(1023).fill(0)]
const indexModel = (name) => 'e2e:' + name + ':1024'
const held = new Set()
let documentCalls = 0
let lastDocumentCallAt = null
let indexReply = { outcome: 'success', model: 'old', allowThrough: 0 }
let child

const materialAi = createServer(async (request, response) => {
  if (request.method !== 'POST' || !['/v1/embed', '/v1/chat', '/_test/material-index'].includes(request.url)) {
    response.writeHead(503).end()
    return
  }
  try {
    const chunks = []
    for await (const chunk of request) chunks.push(chunk)
    const body = JSON.parse(Buffer.concat(chunks).toString('utf8'))
    if (request.url === '/_test/material-index') {
      if (body.restart) {
        const previous = child
        const exited = once(previous, 'exit')
        child = null
        previous.kill('SIGKILL')
        await exited
        startBackend()
      }
      if (body.outcome) {
        indexReply = { outcome: body.outcome, model: body.model ?? 'old', allowThrough: documentCalls + (body.after ?? 0) }
        held.forEach(resume => resume())
      }
      sendJson(response, { documentCalls, lastDocumentCallAt, held: held.size })
    } else if (request.url === '/v1/embed' && body.texts?.length &&
        body.texts.every(text => text.startsWith('PT70MATERIAL'))) {
      sendJson(response, { model: 'e2e:pt70', vectors: body.texts.map(() => materialVector) })
    } else if (request.url === '/v1/embed' && body.texts?.length &&
        body.texts.every(text => text.startsWith('PT99MATERIAL'))) {
      documentCalls++
      lastDocumentCallAt = Date.now()
      if (indexReply.outcome === 'hold' && documentCalls > indexReply.allowThrough) {
        await new Promise(resolve => {
          const resume = () => {
            held.delete(resume)
            response.off('close', resume)
            resolve()
          }
          held.add(resume)
          response.once('close', resume)
        })
      }
      if (response.destroyed) return
      if (indexReply.outcome === 'temporary') response.writeHead(503, { 'X-Embedding-Failure': 'transient' }).end()
      else if (indexReply.outcome === 'fail') response.writeHead(503).end()
      else sendJson(response, { model: indexModel(indexReply.model), vectors: body.texts.map(() => materialVector) })
    } else if (request.url === '/v1/embed' && body.texts?.[0]?.startsWith('PT99QUERY ')) {
      sendJson(response, { model: indexModel(body.texts[0].split(' ')[1]), vectors: [materialVector] })
    } else if (request.url === '/v1/chat' && /^PT(70MATERIAL|99QUERY)/.test(body.messages?.at(-1)?.content ?? '')) {
      response.writeHead(200, { 'Content-Type': 'application/x-ndjson' })
      response.end(JSON.stringify({ delta: 'PT70 test response' }) + '\n')
    } else {
      response.writeHead(503).end()
    }
  } catch {
    response.writeHead(400).end()
  }
})

function sendJson(response, body) {
  response.writeHead(200, { 'Content-Type': 'application/json' })
  response.end(JSON.stringify(body))
}

materialAi.listen(28081, '127.0.0.1')
await once(materialAi, 'listening')
materialAi.unref()

const backendOptions = {
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
    FAST_API_BASE_URL: `http://127.0.0.1:${materialAi.address().port}`,
    AI_INTERNAL_KEY: randomBytes(32).toString('hex'),
    LIVEKIT_INTERNAL_URL: helpAccess ? 'http://127.0.0.1:28082' : 'http://127.0.0.1:9',
    LIVEKIT_API_KEY: 'disposable-e2e',
    LIVEKIT_API_SECRET: helpAccess ? process.env.E2E_LIVEKIT_SECRET : randomBytes(32).toString('hex'),
    MATERIAL_STORAGE: 'local',
    MATERIAL_LOCAL_PATH: path.join(temporary, 'materials'),
    GOOGLE_CLIENT_ID: '', GOOGLE_CLIENT_SECRET: '',
    NAVER_CLIENT_ID: '', NAVER_CLIENT_SECRET: '',
    KAKAO_CLIENT_ID: '', KAKAO_CLIENT_SECRET: '',
    OPENAI_API_KEY: '', AWS_ACCESS_KEY_ID: '', AWS_SECRET_ACCESS_KEY: '', AWS_SESSION_TOKEN: '',
  },
}

function startBackend() {
  const backend = spawn(java, ['-jar', path.join(libs, jars[0]), '--server.address=127.0.0.1', '--server.port=28080'], backendOptions)
  child = backend
  backend.once('error', error => {
    console.error(error.message)
    void cleanup(1)
  })
  backend.once('exit', code => {
    if (child === backend) void cleanup(code ?? 1)
  })
}

async function cleanup(code) {
  materialAi.close()
  const resolved = await realpath(temporary)
  const allowedRoot = await realpath(os.tmpdir())
  if (path.dirname(resolved) === allowedRoot && path.basename(resolved).startsWith('tutipal-e2e-server-')) {
    await rm(resolved, { recursive: true, force: true })
  }
  process.exitCode = code
}

for (const signal of ['SIGTERM', 'SIGINT']) process.once(signal, () => child?.kill())
startBackend()
