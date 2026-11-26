// Synthetic Electron renderer checks. No real screens, accounts, models or servers.
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
const temp=await mkdtemp(path.join(os.tmpdir(),'tutipal-screen-lifecycle-'))
const entry=`
import React from 'react';import {createRoot} from 'react-dom/client';import {MemoryRouter} from 'react-router-dom';
import Widget from '/src/pages/WidgetPage.jsx';
window.calls=[];window.records=[];window.loads=[];window.holdSettings=false;window.target='ok';window.captures=0;
window.selected={enabled:true,sourceId:'window:A',sourceName:'Synthetic A'};
window.desktop={getWidgetChatContext:async()=>({topic:'수학'}),onWidgetChatContextChanged:()=>()=>{},captureScreen:async()=>{window.captures++;return{status:window.target,image:'synthetic',changed:true}},
 getScreenTargetStatus:async()=>({status:window.target}),
 getScreenSettings:()=>window.holdSettings?new Promise(resolve=>window.loads.push(resolve)):Promise.resolve({...window.selected}),
 getReviewDeletionState:async()=>false,onReviewDeletionChanged:()=>()=>{},
 onScreenSettingsChanged:cb=>{window.fireSettings=cb;return()=>{window.fireSettings=()=>{}}},openChat:()=>{window.chatOpened=true}};
window.root=createRoot(document.getElementById('root'));
window.root.render(React.createElement(MemoryRouter,null,React.createElement(Widget)));
`
const server=await createServer({root,configFile:false,plugins:[react(),{
 name:'screen-lifecycle-fixture',enforce:'pre',
 resolveId(id){if(id==='/lifecycle-entry.js')return '\0lifecycle-entry';if(id.endsWith('/api/screen'))return '\0screen-mock';if(id.endsWith('/api/review'))return '\0review-mock'},
 load(id){if(id==='\0lifecycle-entry')return entry;
 if(id==='\0screen-mock')return `export const screenApi={describe:(image,question,signal)=>new Promise((resolve,reject)=>window.calls.push({resolve: text=>resolve({text}),reject,signal,question}))}`;
 if(id==='\0review-mock')return `export const reviewApi={observe:async(summary,seconds)=>{window.records.push({summary,seconds});return{}}}`},
 configureServer(s){s.middlewares.use(async(req,res,next)=>{if(!req.url.startsWith('/lifecycle.html'))return next();try{res.setHeader('Content-Type','text/html');res.end(await s.transformIndexHtml('/lifecycle.html','<div id="root"></div><script type="module" src="/lifecycle-entry.js"></script>'))}catch(e){next(e)}})},
}],server:{host:'127.0.0.1',port:0},optimizeDeps:{noDiscovery:true,include:['react','react-dom/client','react/jsx-dev-runtime','react-router-dom']}})
let app,passed=0
try{
 await server.listen();const port=server.httpServer.address().port
 const main=path.join(temp,'main.cjs')
 await writeFile(main,`const {app,BrowserWindow}=require('electron');app.setPath('userData',${JSON.stringify(path.join(temp,'profile'))});app.whenReady().then(()=>new BrowserWindow({show:false,webPreferences:{sandbox:true,contextIsolation:true,nodeIntegration:false}}).loadURL('about:blank'));`)
 const env={...process.env};delete env.ELECTRON_RUN_AS_NODE
 app=await _electron.launch({executablePath:electronPath,args:[main],env})
 const page=await app.firstWindow();const errors=[];page.on('pageerror',e=>errors.push(e.message))
 await page.goto(`http://127.0.0.1:${port}/lifecycle.html`)
 const count=()=>page.evaluate(()=>window.calls.length)
 await expect.poll(count).toBe(1)
 await page.evaluate(()=>window.calls[0].resolve('OLD A SUMMARY'))
 await expect(page.getByText('OLD A SUMMARY',{exact:true})).toBeVisible()
 await page.getByRole('button',{name:'일시정지',exact:true}).click()
 await expect(page.getByText('OLD A SUMMARY',{exact:true})).toHaveCount(0)
 await page.getByRole('button',{name:'다시 시작',exact:true}).click()
 await expect.poll(count).toBe(2)
 await expect(page.getByText('OLD A SUMMARY',{exact:true})).toHaveCount(0);passed++

 // Ignore cancellation deliberately: a stale result must still be discarded.
 await page.evaluate(()=>{window.selected={enabled:true,sourceId:'window:B'};window.fireSettings()})
 await expect.poll(count).toBe(3)
 expect(await page.evaluate(()=>window.calls[1].signal.aborted)).toBe(true)
 await page.evaluate(()=>{window.calls[2].resolve('NEW B SUMMARY');window.calls[1].resolve('LATE A SUMMARY')})
 await expect(page.getByText('NEW B SUMMARY',{exact:true})).toBeVisible()
 await expect(page.getByText('LATE A SUMMARY',{exact:true})).toHaveCount(0)
 expect(await page.evaluate(()=>window.records.some(r=>r.summary==='LATE A SUMMARY'))).toBe(false);passed++

 await page.getByRole('button',{name:'질문하기',exact:true}).click()
 await page.getByRole('textbox',{name:'화면 질문'}).fill('old window draft')
 await page.evaluate(()=>{window.selected={enabled:false,sourceId:'window:B'};window.fireSettings()})
 await expect(page.getByRole('textbox',{name:'화면 질문'})).toHaveCount(0)
 await expect(page.getByText('NEW B SUMMARY',{exact:true})).toHaveCount(0)
 await page.getByRole('button',{name:'질문하기',exact:true}).click()
 expect(await page.evaluate(()=>window.chatOpened)).toBe(true);passed++

 // Two settings fetches complete in reverse order: the old enabled value must not restart capture.
 const before=await count()
 await page.evaluate(()=>{window.holdSettings=true;window.fireSettings();window.fireSettings()})
 await expect.poll(()=>page.evaluate(()=>window.loads.length)).toBe(2)
 await page.evaluate(()=>{window.loads[1]({enabled:false});window.loads[0]({enabled:true,sourceId:'window:OLD'})})
 await expect(page.getByText('꺼짐',{exact:true})).toBeVisible();expect(await count()).toBe(before);passed++

 await page.evaluate(()=>{window.holdSettings=false;window.selected={enabled:true,sourceId:'window:C'};window.fireSettings()})
 await expect.poll(count).toBe(before+1)
 await page.evaluate(()=>{window.target='gone';window.calls.at(-1).resolve('CLOSED TARGET SUMMARY')})
 await expect(page.getByText('창 종료',{exact:true})).toBeVisible()
 await expect(page.getByText('CLOSED TARGET SUMMARY',{exact:true})).toHaveCount(0)
 expect(await page.evaluate(()=>window.records.some(r=>r.summary==='CLOSED TARGET SUMMARY'))).toBe(false);passed++

 await page.evaluate(()=>{window.target='ok';window.selected={enabled:true,sourceId:'window:D'};window.fireSettings()})
 await expect.poll(count).toBe(before+2)
 await page.evaluate(()=>window.root.unmount())
 expect(await page.evaluate(()=>window.calls.at(-1).signal.aborted)).toBe(true)
 await page.evaluate(()=>window.calls.at(-1).resolve('UNMOUNTED SUMMARY'))
 expect(await page.evaluate(()=>window.records.some(r=>r.summary==='UNMOUNTED SUMMARY'))).toBe(false);passed++
 expect(errors).toEqual([])
 console.log(`PT45 synthetic Electron widget: scenarios=6 passed=${passed} failed=0 rendererErrors=${errors.length}`)
}finally{
 await app?.close();await server.close()
 const resolved=path.resolve(temp)
 if(path.dirname(resolved)!==path.resolve(os.tmpdir())||!path.basename(resolved).startsWith('tutipal-screen-lifecycle-'))throw Error('Unexpected temp path')
 await rm(resolved,{recursive:true,force:true})
}
