// Real OS windows + production screen.cjs; no AI, server, real user pixels, or saved images.
const {app,BrowserWindow,desktopCapturer,nativeImage}=require('electron')
const {spawn}=require('node:child_process')
const fs=require('node:fs'),os=require('node:os'),path=require('node:path'),assert=require('node:assert/strict')
const temp=process.env.PT45_CAPTURE_PROFILE || fs.mkdtempSync(path.join(os.tmpdir(),'pt45-selected-'))
if(path.dirname(temp)!==os.tmpdir()||!path.basename(temp).startsWith('pt45-selected-'))throw Error('Isolated synthetic profile required')
app.setPath('userData',temp)
app.on('window-all-closed',()=>{})
const children=[];let count=0,enumerations=0,pauseOnRequest=false
const original=desktopCapturer.getSources.bind(desktopCapturer)
desktopCapturer.getSources=async options=>{
 assert.deepEqual(options.types,['window']);assert.deepEqual(options.thumbnailSize,{width:0,height:0});enumerations++
 const sources=await original(options)
 if(pauseOnRequest&&BrowserWindow.getAllWindows().length){pauseOnRequest=false;screen.cancelAnalysisCapture()}
 return sources
}
const screen=require('../electron/screen.cjs')
const sleep=ms=>new Promise(r=>setTimeout(r,ms))
async function until(fn){const end=Date.now()+7000;while(Date.now()<end){if(await fn())return;await sleep(100)}throw Error('target timeout')}
async function target(color,label){
 const profile=path.join(temp,label);fs.mkdirSync(profile)
 const env={...process.env};delete env.ELECTRON_RUN_AS_NODE
 const child=spawn(process.execPath,[path.join(__dirname,'analysis-target.cjs'),profile,color,'PT45 same title'],{env,windowsHide:true,stdio:['ignore','pipe','pipe']});children.push({child,profile})
 return await new Promise((resolve,reject)=>{let text='';const timer=setTimeout(()=>reject(Error('fixture timeout')),8000);child.on('error',reject);child.stdout.on('data',c=>{text+=c;try{const data=JSON.parse(text.trim());clearTimeout(timer);resolve(data.sourceId)}catch{}})})
}
function color(image){const img=nativeImage.createFromDataURL(image.startsWith('data:')?image:'data:image/jpeg;base64,'+image);const size=img.getSize();assert.ok(size.width>0);const b=img.toBitmap();const i=(Math.floor(size.height/2)*size.width+Math.floor(size.width/2))*4;return{b:b[i],g:b[i+1],r:b[i+2]}}
app.whenReady().then(async()=>{
 const timer=setTimeout(()=>app.exit(1),50000)
 try{
  const aid=await target('rgb(210,40,40)','a'),bid=await target('rgb(40,40,210)','b')
  let a,b;await until(async()=>{const all=await screen.listSources();a=all.find(s=>s.id.split(':')[1]===aid.split(':')[1])?.id;b=all.find(s=>s.id.split(':')[1]===bid.split(':')[1])?.id;return a&&b})
  const items=await screen.listSources();assert.ok(items.some(s=>s.id===a));assert.ok(items.some(s=>s.id===b));assert.ok(items.every(s=>!('thumbnail'in s)));count++;console.log('PASS metadata-only window list')
  const before=enumerations;assert.equal((await screen.capture()).status,'off');assert.equal(enumerations,before);count++;console.log('PASS no capture before start')
  assert.throws(()=>screen.saveSettings({enabled:true,sourceId:a}),/미리보기/);count++;console.log('PASS start without preview denied')
  const preview=await screen.previewSource(a);assert.equal(screen.loadSettings().enabled,false);let pixel=color(preview.image);assert.ok(pixel.r>160&&pixel.b<90,JSON.stringify(pixel));count++;console.log('PASS explicit preview A only; analysis remains off')
  assert.throws(()=>screen.saveSettings({enabled:true,sourceId:b,previewToken:preview.previewToken}));count++;console.log('PASS preview token cannot start B')
  screen.saveSettings({enabled:true,sourceId:a,previewToken:preview.previewToken});const frame=await screen.capture();assert.equal(frame.status,'ok');assert.equal(frame.sourceId,a);pixel=color(frame.image);assert.ok(pixel.r>160&&pixel.b<90,JSON.stringify(pixel));count++;console.log('PASS started capture A; B excluded')
  pauseOnRequest=true;const pending=screen.capture();assert.equal((await pending).status,'off');assert.equal(pauseOnRequest,false);count++;console.log('PASS pause cancels pending OS capture')
  screen.saveSettings({...screen.loadSettings(),enabled:false});const stopped=enumerations;assert.equal((await screen.capture()).status,'off');assert.equal(enumerations,stopped);count++;console.log('PASS stop prevents enumeration/capture')
  const second=await screen.previewSource(a);screen.saveSettings({enabled:true,sourceId:a,previewToken:second.previewToken})
  fs.writeFileSync(path.join(children[0].profile,'close'),'close');await until(async()=>!(await screen.listSources()).some(s=>s.id===a))
  assert.equal((await screen.capture()).status,'gone');assert.equal((await screen.targetStatus()).status,'gone');assert.ok((await screen.listSources()).some(s=>s.id===b));count++;console.log('PASS closed A never substituted by same-title B')
  const fresh=await screen.previewSource(b);screen.cancelPreview();assert.throws(()=>screen.saveSettings({enabled:true,sourceId:b,previewToken:fresh.previewToken}));count++;console.log('PASS cancelled preview cannot start')
  console.log(`PT45 actual selected-window capture: collected=10 executed=${count} passed=${count} failed=0 skipped=0 metadataEnumerations=${enumerations}`)
  app.exitCode=0
 }catch(e){console.error(e.stack);app.exitCode=1}
 finally{clearTimeout(timer);for(const {child,profile}of children){try{fs.writeFileSync(path.join(profile,'close'),'close')}catch{};child.kill()}
  // Profiles contain only synthetic fixture settings. Keep no screen image files.
  await sleep(300);if(!process.env.PT45_CAPTURE_PROFILE&&path.dirname(temp)===os.tmpdir()&&path.basename(temp).startsWith('pt45-selected-')){try{fs.rmSync(temp,{recursive:true,force:true})}catch{console.log('Temporary synthetic profile cleanup deferred until process exit')}};app.exit(app.exitCode||0)}
})
