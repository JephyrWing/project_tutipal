import {test} from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
const source=readFileSync(new URL('../src/api/screen.js',import.meta.url),'utf8').replace("'./client'",JSON.stringify(new URL('../src/api/client.js',import.meta.url).href))
const {screenApi}=await import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`)
const {advanceAuthGeneration}=await import('../src/api/client.js')
test('screen request passes AbortSignal through CSRF and the actual HTTP request',async t=>{
 const original=globalThis.fetch;t.after(()=>{globalThis.fetch=original;advanceAuthGeneration()});advanceAuthGeneration()
 const controller=new AbortController();const calls=[];let entered
 const started=new Promise(r=>{entered=r})
 globalThis.fetch=async(url,init)=>{
   calls.push({url,init})
   if(url.endsWith('/csrf'))return new Response(JSON.stringify({headerName:'X-CSRF',token:'synthetic'}))
   entered();return new Promise((_,reject)=>init.signal.addEventListener('abort',()=>reject(init.signal.reason),{once:true}))
 }
 const work=screenApi.describe('synthetic','question',controller.signal);await started;controller.abort()
 await assert.rejects(work,{name:'AbortError'})
 assert.equal(calls.length,2);assert.ok(calls.every(c=>c.init.signal===controller.signal))
 assert.deepEqual(JSON.parse(calls[1].init.body),{image:'synthetic',question:'question'})
})
