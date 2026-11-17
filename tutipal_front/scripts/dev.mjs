import { createServer, loadEnv } from 'vite'
import { spawn } from 'node:child_process'
import electronPath from 'electron'

const server = await createServer()
await server.listen()

// 개발 모드에서도 Electron에 내장되는 것과 같은 React 소스를 사용한다.
const childEnv = { ...process.env, TUTIPAL_RENDERER_URL: 'http://127.0.0.1:5173' }
const local = loadEnv('development', process.cwd(), 'TUTIPAL_')
if (!childEnv.TUTIPAL_SPRING_URL && local.TUTIPAL_SPRING_URL) childEnv.TUTIPAL_SPRING_URL = local.TUTIPAL_SPRING_URL
delete childEnv.ELECTRON_RUN_AS_NODE
const child = spawn(electronPath, ['.'], { stdio: 'inherit', env: childEnv })

const close = async (code = 0) => {
  await server.close()
  process.exit(code)
}
child.once('error', async (error) => {
  console.error(error.message)
  await close(1)
})
child.once('exit', (code) => close(code ?? 1))
for (const signal of ['SIGINT', 'SIGTERM']) {
  process.once(signal, () => child.kill())
}
