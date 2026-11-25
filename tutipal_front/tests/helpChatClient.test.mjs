import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createHelpChatClient } from '../src/utils/helpChatClient.js'

const deferred = () => { let resolve, reject; const promise = new Promise((a,b) => { resolve=a; reject=b }); return { promise, resolve, reject } }
const row = (sequence='1', patch={}) => ({id:sequence,sequence,helpId:'7',senderId:'2',clientMessageId:`c${sequence}`,text:'안녕하세요',...patch})
const page = items => ({items,nextCursor:items.at(-1)?.sequence??'0',hasMore:false})
function setup(overrides={}) {
  let n=0
  const transport={load:async()=>page([]),send:async r=>row('1',{senderId:'1',clientMessageId:r.clientMessageId,text:r.text}),...overrides}
  return createHelpChatClient({helpId:'7',userId:'1',maxLength:10,transport,newId:()=>`uuid-${++n}`})
}
test('failed send preserves text and retry identity; a confirmed retry replaces its failed row',async()=>{
  const requests=[];let fail=true
  const c=setup({send:async r=>{requests.push(r);if(fail)throw Error('private server detail');return row('1',{senderId:'1',clientMessageId:r.clientMessageId,text:r.text})}})
  await c.refresh();await c.send('한글\n질문');assert.equal(c.getSnapshot().items[0].delivery,'failed')
  assert.ok(!c.getSnapshot().error.includes('private'));fail=false
  await c.retry(c.getSnapshot().items[0].clientMessageId)
  assert.deepEqual(requests[0],requests[1]);assert.equal(c.getSnapshot().items.length,1);assert.equal(c.getSnapshot().items[0].delivery,'sent')
})
test('double submit is blocked while a send is unresolved',async()=>{
  const d=deferred();let calls=0;const c=setup({send:r=>{calls++;return d.promise}})
  await c.refresh();const send=c.send('질문');assert.equal(await c.send('중복'),false);assert.equal(calls,1)
  d.resolve(row('1',{senderId:'1',clientMessageId:'uuid-1',text:'질문'}));await send
})
test('close clears transcript and ignores late send completion',async()=>{
  const d=deferred();const c=setup({send:()=>d.promise});await c.refresh();const send=c.send('질문');c.close()
  d.resolve(row('1',{senderId:'1',clientMessageId:'uuid-1',text:'질문'}));await send
  assert.equal(c.getSnapshot().status,'closed');assert.deepEqual(c.getSnapshot().items,[]);assert.equal(await c.send('다시'),false)
})
test('late refresh failure cannot override a newer successful reconnect',async()=>{
  const d=deferred();let count=0;const c=setup({load:()=>++count===1?d.promise:Promise.resolve(page([row()]))})
  const old=c.refresh();await c.refresh();d.reject({status:401});await old
  assert.equal(c.getSnapshot().status,'ready');assert.equal(c.getSnapshot().items.length,1)
})
test('permission loss closes and clears content even while a send is in flight',async()=>{
  let denied=false;const d=deferred();const c=setup({load:async()=>{if(denied)throw {status:403};return page([row()])},send:()=>d.promise})
  await c.refresh();const send=c.send('질문');denied=true;await c.refresh();d.reject(Error('late'));await send
  assert.equal(c.getSnapshot().status,'closed');assert.deepEqual(c.getSnapshot().items,[])
})
test('large sequence values keep exact order and cursor without Number rounding',async()=>{
  const cursors=[];let calls=0
  const c=setup({load:async r=>{
    cursors.push(r.afterSequence)
    return ++calls===1 ? {items:[row('9007199254740993'),row('9007199254740992')],nextCursor:'9007199254740993'}
      : {items:[],nextCursor:r.afterSequence}
  }})
  await c.refresh();await c.refresh()
  assert.deepEqual(c.getSnapshot().items.map(m=>m.sequence),['9007199254740992','9007199254740993']);assert.equal(cursors[1],'9007199254740993')
})
test('incomplete pagination does not advance cursor or expose partial content',async()=>{
  const cursors=[];let fail=true;const c=setup({load:async r=>{cursors.push(r.afterSequence);return {items:[row()],nextCursor:'1',hasMore:fail}}})
  await c.refresh();assert.equal(c.getSnapshot().status,'offline');assert.deepEqual(c.getSnapshot().items,[])
  fail=false;await c.refresh();assert.deepEqual(cursors,['0','0']);assert.equal(c.getSnapshot().items.length,1)
})
test('a malformed other-help row rejects the full page before any content is merged',async()=>{
  const c=setup({load:async()=>page([row(),row('2',{helpId:'8'})])});await c.refresh()
  assert.equal(c.getSnapshot().status,'offline');assert.deepEqual(c.getSnapshot().items,[])
})
test('ack does not advance load cursor; notification and POST ack merge once',async()=>{
  const d=deferred();const cursors=[];let sent=false
  const ack=row('1',{senderId:'1',clientMessageId:'uuid-1',text:'질문'})
  const c=setup({send:()=>d.promise,load:async r=>{cursors.push(r.afterSequence);return sent?page([ack]):page([])}})
  await c.refresh();const send=c.send('질문');sent=true;await c.refresh();d.resolve(ack);await send
  assert.deepEqual(cursors,['0','0']);assert.equal(c.getSnapshot().items.length,1);assert.equal(c.getSnapshot().items[0].delivery,'sent')
})
test('a lost POST response after load confirmation never recreates a failed duplicate',async()=>{
  const d=deferred();let sent=false;const c=setup({send:()=>d.promise,load:async()=>sent?page([row('1',{senderId:'1',clientMessageId:'uuid-1',text:'질문'})]):page([])})
  await c.refresh();const send=c.send('질문');sent=true;await c.refresh();d.reject(Error('response lost'));await send
  assert.equal(c.getSnapshot().items.length,1);assert.equal(c.getSnapshot().items[0].delivery,'sent');assert.equal(c.getSnapshot().error,'')
})
test('wrong acknowledgement identity is a retryable failure, never another user message',async()=>{
  const c=setup({send:async()=>row()});await c.refresh();await c.send('질문')
  assert.equal(c.getSnapshot().items.length,1);assert.equal(c.getSnapshot().items[0].delivery,'failed')
})
test('Unicode code points respect the supplied limit and whitespace is rejected',async()=>{
  let calls=0;const c=setup({send:async r=>{calls++;return row('1',{senderId:'1',clientMessageId:r.clientMessageId,text:r.text})}})
  await c.refresh();assert.equal(await c.send(' \n\t '),false);assert.equal(await c.send('😀'.repeat(11)),false)
  assert.equal(await c.send('😀'.repeat(10)),true);assert.equal(calls,1)
})
test('dispose fences pending loads and rejects further transport work',async()=>{
  const d=deferred();const c=setup({load:()=>d.promise});const load=c.refresh();c.dispose();d.resolve(page([row()]));await load
  assert.equal(await c.refresh(),false);assert.equal(c.getSnapshot().status,'closed');assert.deepEqual(c.getSnapshot().items,[])
})
