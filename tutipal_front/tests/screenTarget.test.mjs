import {test} from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import vm from 'node:vm'

function fixture() {
  let settings={enabled:true,sourceId:'window:chosen',sourceName:'synthetic'}
  let sources=[{id:'window:chosen'}], options
  const context={module:{exports:{}},require(name){
    if(name==='electron')return {app:{getPath:()=>'/synthetic'},desktopCapturer:{getSources:async value=>{options=value;return sources}}}
    if(name==='node:fs')return {readFileSync:()=>JSON.stringify(settings)}
    if(name==='node:crypto')return {}
    if(name==='node:path')return {join:(...v)=>v.join('/')}
    throw Error(name)
  }}
  vm.runInNewContext(readFileSync(new URL('../electron/screen.cjs',import.meta.url),'utf8'),context)
  return {status:context.module.exports.targetStatus,settings:value=>{settings=value},sources:value=>{sources=value},options:()=>options}
}
test('selected source is checked without requesting pixels',async()=>{
  const f=fixture(); assert.equal((await f.status()).status,'ok')
  assert.equal(f.options().thumbnailSize.width,0); assert.equal(f.options().thumbnailSize.height,0)
})
test('a different window with the same display name is never substituted',async()=>{
  const f=fixture(); f.sources([{id:'window:other',name:'synthetic'}]); assert.equal((await f.status()).status,'gone')
})
test('disabled capture does not enumerate sources',async()=>{
  const f=fixture();f.settings({enabled:false});assert.equal((await f.status()).status,'off');assert.equal(f.options(),undefined)
})
test('selection changes during enumeration cannot confirm the new window for an old request',async()=>{
  const f=fixture();const pending=f.status();f.settings({enabled:true,sourceId:'window:new'});
  assert.equal((await pending).status,'changed')
})
test('a captured source cannot be confirmed by another currently selected source',async()=>{
 const f=fixture(); assert.equal((await f.status('window:previous')).status,'changed');assert.equal(f.options(),undefined)
})
