// Real Main/Preload/screen controller. Only the OS window source/pixels are synthetic.
const { app } = require('electron')
const fs = require('node:fs')
const path = require('node:path')
const os = require('node:os')
const Module = require('node:module')
const profile = process.env.TUTIPAL_E2E_PROFILE
const backend = new URL(process.env.TUTIPAL_SPRING_URL)
if (!profile || path.dirname(path.resolve(profile)) !== path.resolve(os.tmpdir()) || !path.basename(profile).startsWith('tutipal-e2e-profile-')) throw Error('Disposable profile required')
if (backend.hostname !== '127.0.0.1' || backend.protocol !== 'http:') throw Error('Loopback only')
fs.mkdirSync(path.join(profile,'session'),{recursive:true})
app.setPath('userData',profile);app.setPath('sessionData',path.join(profile,'session'))
app.setAppPath(path.resolve(__dirname,'../..'));app.setAsDefaultProtocolClient=()=>false
delete process.env.TUTIPAL_RENDERER_URL
app.on('browser-window-created',(_event,win)=>win.hide())
const load=Module._load
global.pt119={captures:0,version:0,cancels:0}
Module._load=function(request,parent,...rest){
 if(parent?.filename===path.join(app.getAppPath(),'electron/screen.cjs')){
  if(request==='electron')return {...load.call(this,request,parent,...rest),desktopCapturer:{getSources:async()=>[{id:'window:999999999',name:'PT119 synthetic window'}]}}
  if(request==='./selectedWindowCapture.cjs')return {
   captureWindow:async()=>{global.pt119.captures++;return 'data:image/png;base64,'+Buffer.from('SYNTHETIC '+global.pt119.version).toString('base64')},
   cancelCapture:()=>{global.pt119.cancels++},
  }
 }
 return load.call(this,request,parent,...rest)
}
require(path.join(app.getAppPath(),'electron/main.cjs'))
