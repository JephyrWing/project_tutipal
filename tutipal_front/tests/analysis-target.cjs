const {app,BrowserWindow}=require('electron')
const fs=require('node:fs'),path=require('node:path')
app.setPath('userData',process.argv[2])
app.whenReady().then(async()=>{
 const w=new BrowserWindow({width:600,height:400,frame:false,show:false,webPreferences:{sandbox:true,contextIsolation:true,nodeIntegration:false}})
 const color=process.argv[3],title=process.argv[4]
 await w.loadURL('data:text/html,'+encodeURIComponent(`<title>${title}</title><style>html,body{margin:0;width:100%;height:100%;background:${color}}</style>`))
 w.showInactive();console.log(JSON.stringify({sourceId:w.getMediaSourceId()}))
 setInterval(()=>{try{if(fs.readFileSync(path.join(process.argv[2],'close'),'utf8')==='close')app.quit()}catch{}},50)
 setTimeout(()=>app.quit(),55000).unref()
})
