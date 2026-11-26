// Real Electron display capture of a separate synthetic window, never a monitor/user window.
const { app, BrowserWindow, session, desktopCapturer } = require('electron')
const { spawn } = require('node:child_process')
const { mkdtempSync, mkdirSync, rmSync, writeFileSync } = require('node:fs')
const http = require('node:http')
const os = require('node:os')
const path = require('node:path')
const assert = require('node:assert/strict')
const temp = mkdtempSync(path.join(os.tmpdir(), 'tutipal-capture-test-'))
const mainProfile = path.join(temp, 'main'), targetProfile = path.join(temp, 'target')
mkdirSync(mainProfile); mkdirSync(targetProfile)
app.setPath('userData', mainProfile)
const sharing = require('../electron/share.cjs')
const { windowHandle } = require('../electron/windowTarget.cjs')
let target, main, server
const command = value => writeFileSync(path.join(targetProfile, 'fixture-command'), value)
const watchdog = setTimeout(() => { target?.kill(); app.exit(1) }, 45000)
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
async function until(check) {
  const end = Date.now() + 8000
  while (Date.now() < end) { const value = await check(); if (value) return value; await sleep(100) }
  throw new Error('Synthetic capture observation timed out')
}
app.on('window-all-closed', () => {})
app.whenReady().then(async () => {
  let scenarios = 0
  let stage = 'target enumeration'
  try {
    const env = { ...process.env }; delete env.ELECTRON_RUN_AS_NODE
    target = spawn(process.execPath, [path.join(__dirname, 'synthetic-window.cjs'), targetProfile],
      { env, windowsHide: true, stdio: ['pipe', 'pipe', 'ignore'] })
    const fixtureId = await new Promise((resolve, reject) => {
      let buffer = ''
      const timer = setTimeout(() => reject(new Error('Synthetic target did not start')), 10000)
      target.once('error', reject)
      target.stdout.on('data', chunk => {
        buffer += chunk.toString()
        for (const line of buffer.split('\n')) {
          try { const result = JSON.parse(line); if (result.sourceId) { clearTimeout(timer); resolve(result.sourceId) } } catch {}
        }
      })
    })
    const fixture = await until(async () => (await desktopCapturer.getSources({ types: ['window'], thumbnailSize: { width: 0, height: 0 } })).find(s => windowHandle(s.id) === windowHandle(fixtureId)))
    const sourceId = fixture.id
    stage = 'display capture'
    server = http.createServer((request, response) => {
      response.setHeader('Content-Type', 'text/html')
      response.end(request.url === '/overlay' ? '<style>html,body{margin:0;background:transparent}.ink{position:absolute;left:20%;top:20%;width:50px;height:50px;background:lime}</style><div class="ink"></div>' : '<video muted autoplay></video>')
    })
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
    const origin = 'http://127.0.0.1:' + server.address().port
    const preferences = { preload: path.resolve(__dirname, '../electron/preload.cjs'), sandbox: true, contextIsolation: true, nodeIntegration: false, webSecurity: true, backgroundThrottling: false }
    session.defaultSession.setPermissionRequestHandler((_wc, permission, callback) => callback(permission === 'display-capture'))
    session.defaultSession.setPermissionCheckHandler((_wc, permission) => permission === 'display-capture')
    sharing.installDisplayMediaHandler(url => new URL(url).origin === origin)
    main = new BrowserWindow({ show: false, webPreferences: preferences })
    await main.loadURL(origin)
    const ownHandle = windowHandle(main.getMediaSourceId())
    assert.equal((await sharing.helpShareSources()).some(s => windowHandle(s.id) === ownHandle), false)
    await sharing.prepareHelpShare(sourceId, main.webContents)
    const size = await main.webContents.executeJavaScript(`(async()=>{
      window.stream=await navigator.mediaDevices.getDisplayMedia({video:true,audio:false});
      window.desktop.onHelpShareEnded(()=>{window.targetEnded=true;window.stream.getTracks().forEach(track=>track.stop())});
      const video=document.querySelector('video');video.srcObject=window.stream;await video.play();
      if(video.readyState<2)await new Promise(resolve=>video.addEventListener('loadeddata',resolve,{once:true}));
      return {width:video.videoWidth,height:video.videoHeight};
    })()`, true)
    assert.ok(size.width > 0 && size.height > 0)
    stage = 'overlay geometry'
    sharing.setHelpShareVideoSize(main.webContents, sourceId, size.width, size.height)
    sharing.showOverlay(true, new URL(origin), preferences, () => {}, () => {})
    const overlay = await until(() => BrowserWindow.getAllWindows().find(w => w !== main && new URL(w.webContents.getURL() || origin).pathname === '/overlay'))
    await until(() => overlay.getBounds().width >= 580 && overlay.isVisible())
    const sample = await until(() => main.webContents.executeJavaScript(`(()=>{const v=document.querySelector('video'),c=document.createElement('canvas');c.width=v.videoWidth;c.height=v.videoHeight;const x=c.getContext('2d');x.drawImage(v,0,0);const p=x.getImageData(c.width/4,c.height/4,1,1).data;return p[2]>150&&p[0]<100})()`))
    assert.equal(sample, true); scenarios++
    stage = 'owner isolation'
    const wrongOwnerAllowed = await overlay.webContents.executeJavaScript(`navigator.mediaDevices.getDisplayMedia({video:true,audio:false}).then(s=>{s.getTracks().forEach(t=>t.stop());return true},()=>false)`, true)
    assert.equal(wrongOwnerAllowed, false); scenarios++
    stage = 'resize'
    command('resize')
    const resized = await until(() => main.webContents.executeJavaScript(`(()=>{const v=document.querySelector('video');return v.videoWidth!==${size.width}||v.videoHeight!==${size.height}?{width:v.videoWidth,height:v.videoHeight}:null})()`))
    sharing.setHelpShareVideoSize(main.webContents, sourceId, resized.width, resized.height)
    await until(() => overlay.getBounds().width >= 780 && overlay.getBounds().height >= 480); scenarios++
    stage = 'minimize'
    command('minimize')
    await until(() => main.webContents.executeJavaScript('window.targetEnded===true'))
    const ended = await main.webContents.executeJavaScript('window.stream.getVideoTracks().every(t=>t.readyState==="ended")')
    assert.equal(ended, true)
    assert.equal(BrowserWindow.getAllWindows().some(w => w !== main), false); scenarios++
    console.log(`Native synthetic capture: scenarios=${scenarios} passed=${scenarios} failed=0`)
    app.exitCode = 0
  } catch (error) { console.error(stage + ': ' + error.message); app.exitCode = 1 }
  finally {
    clearTimeout(watchdog)
    sharing.showOverlay(false)
    command('close')
    await sleep(300); target?.kill()
    if (main && !main.isDestroyed()) main.destroy()
    server?.close()
    if (path.dirname(temp) === os.tmpdir() && path.basename(temp).startsWith('tutipal-capture-test-')) {
      try { rmSync(temp, { recursive: true, force: true }) } catch {}
    }
    app.exit(app.exitCode || 0)
  }
})
