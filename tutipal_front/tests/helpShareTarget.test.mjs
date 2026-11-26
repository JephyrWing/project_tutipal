import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { selectedHelpSource } from '../src/utils/helpShareSelection.js'
const require = createRequire(import.meta.url)
const { createHelpTargetManager } = require('../electron/helpShareTarget.cjs')
const { captureBounds, validRect } = require('../electron/shareGeometry.cjs')
const { windowHandle, watchWindow } = require('../electron/windowTarget.cjs')
import { EventEmitter } from 'node:events'

const rect = { x: -100, y: 40, width: 800, height: 600 }
const state = { state: 'ok', foreground: true, window: rect, frame: { ...rect, width: 784, height: 592 }, client: { ...rect, width: 784, height: 562 } }
const deferred = () => { let resolve, reject; const promise = new Promise((a,b) => { resolve=a; reject=b }); return { promise, resolve, reject } }
function setup(options={}) {
  const owner = {}, log = { ended: [], geometry: 0 }
  let callback, disposed=0
  const sources=[{id:'window:123:0',name:'synthetic'},{id:'screen:4:0',display_id:'4'}]
  let displays=[{id:4,bounds:rect}]
  const manager=createHelpTargetManager({getSources:async()=>sources,getDisplays:()=>displays,
    canTrackWindow:()=>true,watchWindow:(_id,cb)=>{callback=cb; cb(state); return { ready:Promise.resolve(state),dispose(){disposed++} }},
    onGeometry:()=>log.geometry++,onEnd:(...args)=>log.ended.push(args),...options})
  return {manager,owner,log,sources,emit:s=>callback(s),disposed:()=>disposed,setDisplays:d=>{displays=d}}
}
test('preview selection rejects absent, unsupported and non-image sources',()=>{
  const good={id:'window:1:0',name:'synthetic',thumbnail:'data:image/png;base64,test',supported:true}
  assert.equal(selectedHelpSource([good],good.id),good)
  for(const list of [[],[{...good,supported:false}],[{...good,thumbnail:'https://remote/image'}]]) assert.equal(selectedHelpSource(list,good.id),null)
  assert.equal(selectedHelpSource([good],''),null)
})
test('HWND parsing allows only the numeric window form; rejects commands and overflow',()=>{
  assert.equal(windowHandle('window:123456:0'),'123456')
  assert.equal(windowHandle('window:123456:1'),'123456')
  for(const id of ['','screen:1:0','window:0:0','window:1;calc:0','window:-1:0','window:9999999999999999999:0']) assert.equal(windowHandle(id),null)
})
test('capture aspect chooses native geometry and rejects untrusted/unknown bounds',()=>{
  assert.deepEqual(captureBounds(state,{width:800,height:600}),rect)
  assert.deepEqual(captureBounds(state,{width:784,height:562}),state.client)
  assert.equal(captureBounds(state,{width:1000,height:100}),null)
  assert.equal(captureBounds(state,{width:Infinity,height:600}),null)
  assert.equal(validRect({...rect,width:0}),false)
  assert.equal(validRect({...rect,x:1e20}),false)
})
test('closed or fabricated IDs never become a fallback screen',async()=>{
  const {manager,owner}=setup()
  for(const id of ['', 'window:999:0','screen:999:0']){
    await assert.rejects(manager.prepare(id,owner))
    assert.equal(manager.sourceId,null)
  }
  manager.clear()
})
test('window geometry is hidden until actual video size arrives and is bound to sender/source',async()=>{
  const {manager,owner}=setup()
  await manager.prepare('window:123:0',owner)
  assert.equal(manager.geometry(),null)
  manager.setVideoSize({},'window:123:0',800,600)
  manager.setVideoSize(owner,'window:999:0',800,600)
  assert.equal(manager.geometry(),null)
  manager.setVideoSize(owner,'window:123:0',800,600)
  assert.deepEqual(manager.geometry(),{bounds:rect,physical:true,visible:true})
  manager.clear()
})
test('window move/resize updates geometry and background windows hide annotations',async()=>{
  const {manager,owner,emit}=setup()
  await manager.prepare('window:123:0',owner)
  manager.setVideoSize(owner,'window:123:0',800,600)
  emit({...state,foreground:false,window:{...rect,x:300,y:200}})
  assert.equal(manager.geometry().visible,false)
  assert.equal(manager.geometry().bounds.x,300)
  manager.clear()
})
test('minimize/close ends the selected target and clears the watcher',async()=>{
  for(const status of ['hidden','gone']){
    const {manager,owner,emit,log,disposed}=setup()
    await manager.prepare('window:123:0',owner)
    emit({state:status})
    assert.equal(manager.sourceId,null)
    assert.equal(manager.geometry(),null)
    assert.equal(log.ended.length,1)
    assert.equal(log.ended[0][0],owner)
    assert.equal(disposed(),1)
  }
})
test('unsupported OS refuses window geometry but allows a known display',async()=>{
  const {manager,owner}=setup({canTrackWindow:()=>false})
  await assert.rejects(manager.prepare('window:123:0',owner))
  await manager.prepare('screen:4:0',owner)
  assert.deepEqual(manager.geometry(),{bounds:rect,physical:false,visible:true})
  manager.clear()
})
test('a display disconnect terminates sharing instead of moving to another display',async()=>{
  const {manager,owner,setDisplays,log}=setup()
  await manager.prepare('screen:4:0',owner)
  setDisplays([{id:5,bounds:rect}])
  await new Promise(resolve=>setTimeout(resolve,300))
  assert.equal(manager.sourceId,null)
  assert.equal(log.ended.length,1)
})
test('cancelled preview lookup cannot overwrite a later choice',async()=>{
  const lookup=deferred()
  const {manager,owner}=setup({getSources:()=>lookup.promise})
  const pending=manager.prepare('window:123:0',owner)
  manager.clear()
  lookup.resolve([{id:'window:123:0'}])
  await assert.rejects(pending)
  assert.equal(manager.sourceId,null)
})
test('native tracker validates bounded output and does not accept malformed JSON',async()=>{
  const child=new EventEmitter(); child.stdout=new EventEmitter(); child.kill=()=>{}
  const events=[]
  const watcher=watchWindow('window:123:0',s=>events.push(s),{spawn:()=>child})
  child.stdout.emit('data',Buffer.from(JSON.stringify(state)+'\n'))
  assert.equal((await watcher.ready).state,'ok')
  child.stdout.emit('data',Buffer.from('{"state":"ok","window":{"width":999999}}\n'))
  assert.equal(events.at(-1).state,'gone')
  watcher.dispose()
})
test('native tracker disposal rejects a pending first sample and stops its process',async()=>{
  const child=new EventEmitter();child.stdout=new EventEmitter();let killed=0;child.kill=()=>killed++
  const watcher=watchWindow('window:123:0',()=>{}, {spawn:()=>child})
  const rejected=assert.rejects(watcher.ready)
  watcher.dispose();await rejected
  assert.equal(killed,1)
})
