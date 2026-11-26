import {test} from 'node:test'
import assert from 'node:assert/strict'
import {createWidgetChat} from '../src/utils/widgetChat.js'
import {boundedScreenSummary,screenSummaryText} from '../src/utils/screenSummary.js'
import {widgetChatContext} from '../electron/widgetChatContext.cjs'
const deferred=()=>{let resolve,reject;const promise=new Promise((a,b)=>{resolve=a;reject=b});return{promise,resolve,reject}}
function setup(overrides={}){
 const seen={calls:[],deltas:[],done:[],errors:[],busy:[]}
 const chat=createWidgetChat({getContext:async()=>({topic:'수학',sessionId:7}),getSummary:async()=> 'SUMMARY',
  stream:async(...args)=>{seen.calls.push(args);args[2]('answer')},onStart:()=>{},
  onDelta:v=>seen.deltas.push(v),onDone:v=>seen.done.push(v),onError:e=>seen.errors.push(e),onBusy:v=>seen.busy.push(v),...overrides})
 return {chat,seen}
}
test('4000 UTF16 units maximum, with complete surrogate pairs',()=>{
 assert.equal(boundedScreenSummary('가'.repeat(4001)).length,4000)
 assert.equal(boundedScreenSummary('a'.repeat(3999)+'😀'), 'a'.repeat(3999))
 assert.equal(boundedScreenSummary('😀'.repeat(2001)).length,4000)
 assert.equal(boundedScreenSummary('  '),undefined)
})
test('summary is explicit, legacy text compatible, detail never a fallback',()=>{
 assert.equal(screenSummaryText({summary:'summary',text:'legacy',detail:'PRIVATE'}),'summary')
 assert.equal(screenSummaryText({text:'legacy',detail:'PRIVATE'}),'legacy')
 assert.equal(screenSummaryText({detail:'PRIVATE'}),'')
 assert.equal(boundedScreenSummary({summary:'wrong type'}),undefined)
})
test('one common stream call carries only summary and the current question',async()=>{
 const {chat,seen}=setup();await chat.ask('question')
 assert.equal(seen.calls.length,1);assert.equal(seen.calls[0][4],7)
 assert.deepEqual(seen.calls[0][1],[{role:'user',content:'question'}])
 assert.deepEqual(seen.calls[0][7],{screenSummary:'SUMMARY'});assert.deepEqual(seen.done,['answer'])
})
test('rapid submit and Enter while context is pending starts one request',async()=>{
 const context=deferred();const {chat,seen}=setup({getContext:()=>context.promise})
 const first=chat.ask('question');assert.equal(await chat.ask('question'),false)
 context.resolve({topic:'개인'});await first;assert.equal(seen.calls.length,1)
})
test('reset during summary lookup never starts a POST',async()=>{
 const summary=deferred(),started=deferred();const {chat,seen}=setup({getSummary:()=>{started.resolve();return summary.promise}})
 const first=chat.ask('question');await started.promise;chat.reset();summary.resolve('old');await first
 assert.equal(seen.calls.length,0);assert.deepEqual(seen.done,[])
})
test('late delta, done and finally cannot overwrite or unlock the new question',async()=>{
 const old=deferred(),fresh=deferred();const calls=[]
 const {chat,seen}=setup({stream:(...args)=>{calls.push(args);return calls.length===1?old.promise:fresh.promise}})
 const first=chat.ask('old');while(calls.length<1)await Promise.resolve()
 chat.reset();assert.equal(calls[0][3].aborted,true)
 const second=chat.ask('fresh');while(calls.length<2)await Promise.resolve()
 calls[0][2]('STALE');old.resolve();await first
 assert.equal(chat.busy(),true);assert.equal(await chat.ask('duplicate'),false)
 calls[1][2]('FRESH');fresh.resolve();await second
 assert.deepEqual(seen.deltas,['FRESH']);assert.deepEqual(seen.done,['FRESH'])
})
test('failure never automatically retries; explicit retry uses fresh context once',async()=>{
 let summary='first',count=0;const calls=[]
 const {chat,seen}=setup({getSummary:async()=>summary,stream:async(...args)=>{calls.push(args);if(++count===1)throw Error('failure')}})
 await chat.ask('q');assert.equal(count,1);assert.equal(seen.errors.length,1)
 summary='second';await chat.ask('q');assert.equal(count,2)
 assert.deepEqual(calls[1][1],[{role:'user',content:'q'}]);assert.equal(calls[1][7].screenSummary,'second')
})
test('unknown target or context errors fail before stream without fallback requests',async()=>{
 const {chat,seen}=setup({getSummary:async()=>{throw Error('unknown')}})
 await chat.ask('q');assert.equal(seen.calls.length,0);assert.equal(seen.errors.length,1)
})
test('personal mode and no valid summary send text only',async()=>{
 const {chat,seen}=setup({getContext:async()=>({topic:'자유 주제'}),getSummary:async()=>undefined})
 await chat.ask('q');assert.equal(seen.calls[0][4],undefined);assert.equal(seen.calls[0][7].screenSummary,undefined)
})
test('main route determines selected class, never arbitrary joined classes',()=>{
 const base='http://127.0.0.1:1234'
 assert.deepEqual(widgetChatContext(base+'/sessions/42',base),{topic:'자유 주제',sessionId:42})
 assert.deepEqual(widgetChatContext(base+'/chat?topic=math',base),{topic:'수학'})
 assert.deepEqual(widgetChatContext(base+'/settings',base),{topic:'자유 주제'})
 assert.deepEqual(widgetChatContext(base+'/chat?topic=constructor',base),{topic:'constructor'})
 assert.throws(()=>widgetChatContext('https://outside.invalid/sessions/42',base))
 assert.throws(()=>widgetChatContext(base+'/sessions/99999999999999999',base))
})
