// Load the real app with an isolated profile and preserve the user's protocol registration.
const { app } = require('electron')
const fs = require('node:fs')
const path = require('node:path')
const os = require('node:os')
const profile = process.env.TUTIPAL_E2E_PROFILE
if (!profile || path.dirname(path.resolve(profile)) !== path.resolve(os.tmpdir())
    || !path.basename(profile).startsWith('tutipal-e2e-profile-')) throw new Error('An isolated temporary E2E profile is required')
if (process.env.TUTIPAL_SPRING_URL !== 'http://127.0.0.1:28080') throw new Error('Only the disposable E2E server is allowed')
fs.mkdirSync(path.join(profile, 'session'), { recursive: true })
app.setPath('userData', profile)
app.setPath('sessionData', path.join(profile, 'session'))
app.setAppPath(path.resolve(__dirname, '../..'))
app.setAsDefaultProtocolClient = () => false
delete process.env.TUTIPAL_RENDERER_URL
app.on('browser-window-created', (_event, window) => window.hide())
require(path.join(app.getAppPath(), 'electron/main.cjs'))
