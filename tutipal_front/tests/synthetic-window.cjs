const { app, BrowserWindow } = require('electron')
const { readFileSync } = require('node:fs')
const path = require('node:path')
app.setPath('userData', process.argv[2])
setTimeout(() => app.exit(1), 60000).unref()
app.whenReady().then(async () => {
  const win = new BrowserWindow({ x: 90, y: 90, width: 600, height: 400, frame: false, show: false,
    webPreferences: { sandbox: true, contextIsolation: true, nodeIntegration: false, backgroundThrottling: false } })
  await win.loadURL('data:text/html,<title>PT92 synthetic share target</title><style>html,body{margin:0;width:100%;height:100%;background:rgb(40,100,210)}</style>')
  win.showInactive()
  win.show()
  console.log(JSON.stringify({ sourceId: win.getMediaSourceId(), visible: win.isVisible(), title: win.getTitle() }))
  let previous = ''
  setInterval(() => {
    try {
      const command = readFileSync(path.join(process.argv[2], 'fixture-command'), 'utf8')
      if (command === previous) return
      previous = command
      if (command === 'resize') win.setBounds({ x: 170, y: 150, width: 800, height: 500 })
      if (command === 'minimize') win.minimize()
      if (command === 'close') app.quit()
    } catch {}
  }, 100)
})
