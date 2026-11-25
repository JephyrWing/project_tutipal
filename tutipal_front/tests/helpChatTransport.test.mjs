import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createHelpChatTransport } from '../src/api/helpChat.js'
const response=(items,nextCursor,hasMore=false)=>({items,nextCursor,hasMore,maxLength:1000})
test('transport drains exact string cursors across pages',async()=>{
  const calls=[];const t=createHelpChatTransport(async(path,init)=>{calls.push({path,init});return calls.length===1?response([{id:'first'}],'9007199254740993',true):response([{id:'second'}],'9007199254740994')})
  const result=await t.load({helpId:'7',afterSequence:'0'});assert.equal(result.items.length,2)
  assert.match(calls[1].path,/afterSequence=9007199254740993&limit=100$/);assert.equal(result.nextCursor,'9007199254740994');t.dispose()
})
test('nonprogressing page fails without an endless request loop',async()=>{
  let calls=0;const t=createHelpChatTransport(async()=>{calls++;return response([],'0',true)})
  await assert.rejects(t.load({helpId:'7',afterSequence:'0'}));assert.equal(calls,1);t.dispose()
})
test('send uses common POST and excludes forged sender fields',async()=>{
  let call;const t=createHelpChatTransport(async(path,init)=>{call={path,init};return {id:'9'}})
  await t.send({helpId:'7',clientMessageId:'same-uuid',text:'한글\n질문',senderId:'forged'})
  assert.equal(call.path,'/api/help/7/messages');assert.equal(call.init.method,'POST')
  assert.deepEqual(JSON.parse(call.init.body),{clientMessageId:'same-uuid',text:'한글\n질문'});t.dispose()
})
test('dispose aborts in-flight work and prevents new requests',async()=>{
  let signal;const t=createHelpChatTransport((_path,init)=>new Promise((_resolve,reject)=>{signal=init.signal;signal.addEventListener('abort',()=>reject(Error('aborted')))}))
  const pending=t.load({helpId:'7',afterSequence:'0'});t.dispose();assert.equal(signal.aborted,true)
  await assert.rejects(pending);await assert.rejects(t.load({helpId:'7',afterSequence:'0'}))
})
test('authorization failure mid-page does not return partial text',async()=>{
  let calls=0;const denied={status:403};const t=createHelpChatTransport(async()=>{if(++calls===2)throw denied;return response([{text:'private'}],'1',true)})
  await assert.rejects(t.load({helpId:'7',afterSequence:'0'}),error=>error===denied);t.dispose()
})
