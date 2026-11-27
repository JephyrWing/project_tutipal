import {test} from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import {createHash,randomUUID} from 'node:crypto'
import vm from 'node:vm'

async function fixture(start=true) {
 let settings={enabled:false,sourceId:'window:chosen',sourceName:'synthetic'}
 let sources=[{id:'window:chosen',name:'synthetic'}],options,captures=0,gate=null
 const context={module:{exports:{}},Buffer,require(name){
  if(name==='electron')return {app:{getPath:()=>'/synthetic'},BrowserWindow:{getAllWindows:()=>[]},desktopCapturer:{getSources:async value=>{options=value;return sources}}}
  if(name==='node:fs')return {readFileSync:()=>JSON.stringify(settings),writeFileSync:(_,v)=>{settings=JSON.parse(v)}}
  if(name==='node:crypto')return {createHash,randomUUID}
  if(name==='node:path')return {join:(...v)=>v.join('/')}
  if(name==='./selectedWindowCapture.cjs')return {captureWindow:async()=>{captures++;if(gate)await gate;return'data:image/jpeg;base64,c3ludGhldGlj'},cancelCapture:()=>{}}
  throw Error(name)
 }}
 vm.runInNewContext(readFileSync(new URL('../electron/screen.cjs',import.meta.url),'utf8'),context)
 const api=context.module.exports
 if(start){const p=await api.previewSource('window:chosen');api.saveSettings({enabled:true,sourceId:p.sourceId,previewToken:p.previewToken})}
 options=undefined;captures=0
 return {api,status:api.targetStatus,settings:value=>{settings=value},sources:value=>{sources=value.map(s=>({name:'synthetic',...s}))},options:()=>options,captures:()=>captures,gate:value=>{gate=value}}
}
test('selected source is checked without requesting pixels',async()=>{
 const f=await fixture();assert.equal((await f.status()).status,'ok');assert.equal(f.options().thumbnailSize.width,0);assert.equal(f.options().thumbnailSize.height,0)
})

test('session boundary revokes selected source and previously issued preview permission',async()=>{
 const f=await fixture(false),p=await f.api.previewSource('window:chosen')
 f.api.stopAnalysis()
 assert.equal(f.api.loadSettings().enabled,false);assert.equal(f.api.loadSettings().sourceId,'')
 assert.throws(()=>f.api.saveSettings({enabled:true,sourceId:p.sourceId,previewToken:p.previewToken}))
 assert.equal((await f.api.capture()).status,'off')
})

test('session boundary discards an in-flight capture and requires a new preview',async()=>{
 const f=await fixture();let release;f.gate(new Promise(r=>{release=r}))
 const work=f.api.capture();while(!f.captures())await Promise.resolve()
 f.api.stopAnalysis();release();assert.equal((await work).status,'off')
 assert.equal(f.api.loadSettings().sourceId,'');assert.equal((await f.api.capture()).status,'off')
})
test('a different window with the same display name is never substituted',async()=>{
 const f=await fixture();f.sources([{id:'window:other'}]);assert.equal((await f.status()).status,'gone');assert.equal((await f.api.capture()).status,'gone');assert.equal(f.captures(),0)
})
test('disabled capture does not enumerate sources',async()=>{
 const f=await fixture(false);assert.equal((await f.status()).status,'off');assert.equal((await f.api.capture()).status,'off');assert.equal(f.options(),undefined)
})
test('selection changes during enumeration cannot confirm an old request',async()=>{
 const f=await fixture();const pending=f.status();f.api.saveSettings({enabled:false,sourceId:'window:new'});assert.equal((await pending).status,'off')
})
test('a captured source cannot be confirmed by another currently selected source',async()=>{
 const f=await fixture();assert.equal((await f.status('window:previous')).status,'changed');assert.equal(f.options(),undefined)
})
test('listing returns only metadata and rejects monitor preview',async()=>{
 const f=await fixture(false);f.sources([{id:'window:chosen'},{id:'screen:0'}]);const list=await f.api.listSources();assert.equal(list.length,1);assert.equal(list[0].thumbnail,undefined);await assert.rejects(f.api.previewSource('screen:0'));assert.equal(f.captures(),0)
})
test('start requires a fresh preview token for the exact window',async()=>{
 const f=await fixture(false);assert.throws(()=>f.api.saveSettings({enabled:true,sourceId:'window:chosen'}));const p=await f.api.previewSource('window:chosen');assert.equal(f.api.loadSettings().enabled,false);assert.throws(()=>f.api.saveSettings({enabled:true,sourceId:'window:other',previewToken:p.previewToken}));f.api.cancelPreview();assert.throws(()=>f.api.saveSettings({enabled:true,sourceId:p.sourceId,previewToken:p.previewToken}))
})
test('settings change while capturing discards the late pixels',async()=>{
 const f=await fixture();let release;f.gate(new Promise(r=>{release=r}));const work=f.api.capture();while(!f.captures())await Promise.resolve();f.api.saveSettings({...f.api.loadSettings(),enabled:false});release();assert.equal((await work).status,'off')
})
test('pause cancels only analysis and rejects its late result',async()=>{
 const f=await fixture();let release;f.gate(new Promise(r=>{release=r}));const work=f.api.capture();while(!f.captures())await Promise.resolve();f.api.cancelAnalysisCapture();release();assert.equal((await work).status,'off');assert.equal(f.api.loadSettings().enabled,true)
})
test('cancelled pending preview cannot grant permission after resolution',async()=>{
 const f=await fixture(false);let release;f.gate(new Promise(r=>{release=r}));const work=f.api.previewSource('window:chosen');while(!f.captures())await Promise.resolve();f.api.cancelPreview();release();await assert.rejects(work);assert.equal(f.api.loadSettings().enabled,false)
})
test('persisted enabled setting cannot automatically restart after launch',async()=>{
 const f=await fixture(false);f.settings({enabled:true,sourceId:'window:chosen'});assert.equal(f.api.loadSettings().enabled,false);assert.equal((await f.api.capture()).status,'off');assert.equal(f.captures(),0)
})
