// Real Widget + common SSE client in sandboxed Electron; only synthetic inputs and API responses.
import { createServer } from 'vite'
import react from '@vitejs/plugin-react'
import { _electron } from 'playwright'
import { expect } from '@playwright/test'
import electronPath from 'electron'
import {mkdtemp,writeFile,rm} from 'node:fs/promises'
import {fileURLToPath} from 'node:url'
import os from 'node:os'
import path from 'node:path'
const root=fileURLToPath(new URL('../',import.meta.url))
const temp=await mkdtemp(path.join(os.tmpdir(),'tutipal-widget-chat-'))
const entry=`
import React from 'react';import{createRoot}from'react-dom/client';import{MemoryRouter}from'react-router-dom';import Widget from '/src/pages/WidgetPage.jsx';
window.context={topic:'수학'};window.selected={enabled:true,sourceId:'window:A'};window.target='ok';window.captures=0;
window.desktop={getWidgetChatContext:async()=>{if(window.contextGate)await window.contextGate;return {...window.context}},
 onWidgetChatContextChanged:cb=>{window.fireContext=cb;return()=>{}},captureScreen:async()=>{window.captures++;return{status:window.target,image:'SYNTHETIC_IMAGE',changed:false,sourceId:window.selected.sourceId}},
 getScreenTargetStatus:async()=>({status:window.target}),getScreenSettings:async()=>({...window.selected}),getReviewDeletionState:async()=>false,
 onReviewDeletionChanged:()=>()=>{},onScreenSettingsChanged:cb=>{window.fireSettings=cb;return()=>{}},openChat:()=>{window.openedChat=true}};
createRoot(document.getElementById('root')).render(React.createElement(MemoryRouter,null,React.createElement(Widget)));
`
const server=await createServer({root,configFile:false,plugins:[react(),{
 name:'widget-chat-fixture',enforce:'pre',resolveId:id=>id==='/widget-chat-entry.js'?'\0entry':null,load:id=>id==='\0entry'?entry:null,
 configureServer(s){s.middlewares.use(async(req,res,next)=>{if(!req.url.startsWith('/widget-chat.html'))return next();try{res.setHeader('Content-Type','text/html');res.end(await s.transformIndexHtml('/widget-chat.html','<div id="root"></div><script type="module" src="/widget-chat-entry.js"></script>'))}catch(e){next(e)}})},
}],server:{host:'127.0.0.1',port:0},optimizeDeps:{noDiscovery:true,include:['react','react-dom/client','react/jsx-dev-runtime','react-router-dom']}})
const deferred=()=>{let resolve;return{promise:new Promise(r=>{resolve=r}),resolve:()=>resolve()}}
let app,checks=0,gate=null
try{
 await server.listen();const port=server.httpServer.address().port
 const main=path.join(temp,'main.cjs');await writeFile(main,`const {app,BrowserWindow}=require('electron');app.setPath('userData',${JSON.stringify(path.join(temp,'profile'))});app.whenReady().then(()=>new BrowserWindow({show:false,webPreferences:{sandbox:true,contextIsolation:true,nodeIntegration:false}}).loadURL('about:blank'));`)
 const env={...process.env};delete env.ELECTRON_RUN_AS_NODE
 app=await _electron.launch({executablePath:electronPath,args:[main],env})
 const page=await app.firstWindow(),errors=[],screen=[],chats=[]
 page.on('pageerror',e=>errors.push(e.message))
 let fail=false
 const summary='가'.repeat(4001)
 let currentSummary=summary
 await page.route(url=>url.pathname.startsWith('/api/'),async route=>{
  const pathname=new URL(route.request().url()).pathname
  const json=(body,status=200)=>route.fulfill({status,contentType:'application/json',body:JSON.stringify(body)})
  if(pathname==='/api/auth/csrf')return json({headerName:'X-CSRF',token:'synthetic'})
  if(pathname==='/api/screen'){screen.push(route.request().postDataJSON());return json({summary:currentSummary,detail:'PRIVATE_DETAIL'})}
  if(pathname==='/api/review/observe')return json({})
  if(pathname==='/api/tutor/chat'){
   chats.push(route.request().postDataJSON());const own=chats.length
   if(gate)await gate.promise
   if(fail)return json({message:'synthetic failure'},503)
   return route.fulfill({contentType:'text/event-stream',body:`event: delta\ndata: ${JSON.stringify({t:'SSE ANSWER '+own})}\n\nevent: agent\ndata: {"variant":null}\n\nevent: done\ndata: {}\n\n`}).catch(()=>{})
  }
  throw Error('unexpected request '+pathname)
 })
 await page.goto(`http://127.0.0.1:${port}/widget-chat.html`)
 await expect(page.getByText(summary,{exact:true})).toBeVisible()
 const ask=async value=>{await page.getByRole('button',{name:'질문하기',exact:true}).click();await page.getByRole('textbox',{name:'화면 질문'}).fill(value)}
 await ask('first question')
 await page.evaluate(()=>{window.contextGate=new Promise(r=>{window.releaseContext=r});const form=document.querySelector('form');form.dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}));form.dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}))})
 await expect(page.getByRole('button',{name:'질문하기',exact:true})).toBeDisabled()
 await page.evaluate(()=>{window.releaseContext();window.contextGate=null})
 await expect(page.getByText('SSE ANSWER 1',{exact:true})).toBeVisible()
 expect(chats.length).toBe(1);expect(chats[0].screenSummary.length).toBe(4000)
 expect(Object.keys(chats[0]).sort()).toEqual(['messages','screenSummary','topic'])
 expect(JSON.stringify(chats)).not.toContain('PRIVATE_DETAIL');expect(JSON.stringify(chats)).not.toContain('SYNTHETIC_IMAGE')
 expect(screen.every(s=>s.question===undefined)).toBe(true);checks++;console.log('passed duplicate/payload')

 // A failed request does not resend itself; explicit retry is one new request with one question.
 fail=true;await ask('retry question');await page.getByRole('button',{name:'보내기',exact:true}).click()
 await expect(page.getByRole('button',{name:'질문 다시 시도',exact:true})).toBeVisible()
 expect(chats.length).toBe(2);fail=false
 await page.getByRole('button',{name:'질문 다시 시도',exact:true}).click()
 await expect(page.getByText('SSE ANSWER 3',{exact:true})).toBeVisible()
 expect(chats[2].messages).toEqual([{role:'user',content:'retry question'}]);checks++;console.log('passed explicit retry')

 // Stop aborts the connection; even an ignored abort cannot display its late response.
 gate=deferred();await ask('stop question');await page.getByRole('button',{name:'보내기',exact:true}).click()
 await expect.poll(()=>chats.length).toBe(4)
 await page.getByRole('button',{name:'답변 중지',exact:true}).click()
 gate.resolve();gate=null
 await expect(page.getByText('답변을 중지했어요.',{exact:true})).toBeVisible()
 await expect(page.getByText('SSE ANSWER 4',{exact:true})).toHaveCount(0);checks++;console.log('passed stop')

 await page.evaluate(()=>{window.context={topic:'수학',sessionId:7};window.selected={enabled:true,sourceId:'window:B'};window.fireSettings()})
 await expect(page.getByText(summary,{exact:true})).toBeVisible()
 await ask('student question');await page.getByRole('button',{name:'보내기',exact:true}).click()
 await expect(page.getByText('SSE ANSWER 5',{exact:true})).toBeVisible()
 expect(chats[4].sessionId).toBe(7);expect(chats[4].screenSummary.length).toBe(4000);checks++;console.log('passed student scope')

 gate=deferred();await ask('old context');await page.getByRole('button',{name:'보내기',exact:true}).click()
 await expect.poll(()=>chats.length).toBe(6)
 currentSummary='나'.repeat(4001)
 await page.evaluate(()=>{window.context={topic:'자유 주제'};window.fireContext()})
 gate.resolve();gate=null
 await expect(page.getByText('SSE ANSWER 6',{exact:true})).toHaveCount(0)
 await expect(page.getByRole('button',{name:'질문 다시 시도',exact:true})).toHaveCount(0)
 // Context reset immediately obtains a fresh automatic summary, never the old one.
 await expect(page.getByText(currentSummary,{exact:true})).toBeVisible()
 const capturesBeforeQuestion=await page.evaluate(()=>window.captures)
 await ask('fresh context');await page.getByRole('button',{name:'보내기',exact:true}).click()
 await expect(page.getByText('SSE ANSWER 7',{exact:true})).toBeVisible()
 expect(chats[6].screenSummary).toBe('나'.repeat(4000));expect(chats[6].sessionId).toBeUndefined();expect(await page.evaluate(()=>window.captures)).toBe(capturesBeforeQuestion);checks++;console.log('passed context reset')

 currentSummary=summary
 await page.evaluate(()=>{window.selected={enabled:true,sourceId:'window:C'};window.fireSettings()})
 await expect(page.getByText(summary,{exact:true})).toBeVisible()
 await ask('closed target');await page.evaluate(()=>{window.target='gone'})
 await page.getByRole('button',{name:'보내기',exact:true}).click()
 await expect(page.getByText('창 종료',{exact:true})).toBeVisible()
 expect(chats.length).toBe(7);checks++
 expect(errors).toEqual([])
 console.log(`PT45 widget common chat: scenarios=6 passed=${checks} failed=0 rendererErrors=${errors.length} chatPosts=${chats.length} screenQuestionPosts=${screen.filter(s=>s.question).length}`)
}finally{
 gate?.resolve()
 await app?.close();await server.close()
 const resolved=path.resolve(temp)
 if(path.dirname(resolved)!==path.resolve(os.tmpdir())||!path.basename(resolved).startsWith('tutipal-widget-chat-'))throw Error('Unexpected temp path')
 await rm(resolved,{recursive:true,force:true})
}
