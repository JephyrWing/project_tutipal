import { spawnSync } from 'node:child_process'
import { mkdirSync, existsSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
if (process.platform === 'win32') {
  const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
  const compiler = path.join(process.env.WINDIR || 'C:\\Windows', 'Microsoft.NET/Framework64/v4.0.30319/csc.exe')
  if (!existsSync(compiler)) throw new Error('Windows .NET Framework C# compiler is required for window sharing')
  const output = path.join(root, 'electron/native/bin')
  mkdirSync(output, { recursive: true })
  const result = spawnSync(compiler, ['/nologo', '/target:exe', '/optimize+', '/platform:x64',
    '/out:' + path.join(output, 'window-target.exe'), path.join(root, 'electron/native/WindowTarget.cs')],
    { stdio: 'inherit', windowsHide: true })
  if (result.error) throw result.error
  if (result.status !== 0) process.exit(result.status || 1)
  console.log('Read-only Windows window tracker built')
}
