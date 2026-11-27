// Actual React/dialog/SSE in sandboxed Electron. OS inputs and API responses are synthetic.
import { createServer } from 'vite'
import react from '@vitejs/plugin-react'
import { _electron } from 'playwright'
import { expect } from '@playwright/test'
import electronPath from 'electron'
import { mkdtemp, writeFile, rm } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import os from 'node:os'
import path from 'node:path'
const root = fileURLToPath(new URL('../', import.meta.url))
const temp = await mkdtemp(path.join(os.tmpdir(), 'tutipal-screen-detail-'))
const entry = `
import React from 'react';import{createRoot}from'react-dom/client';import{MemoryRouter}from'react-router-dom';import Widget from '/src/pages/WidgetPage.jsx';
import '/src/styles/global.css';import '/src/styles/app.css';
window.context={topic:'수학'};window.settings={enabled:true};window.target='ok';window.captures=0;
window.desktop={getWidgetChatContext:async()=>{if(window.contextGate)await window.contextGate;return {...window.context}},
 onWidgetChatContextChanged:cb=>{window.fireContext=cb;return()=>{}},captureScreen:async()=>{window.captures++;return{status:window.target,image:'SYNTHETIC_IMAGE',changed:true,sourceId:'window:A'}},
 getScreenTargetStatus:async()=>({status:window.target}),getScreenSettings:async()=>({...window.settings}),getReviewDeletionState:async()=>false,
 onReviewDeletionChanged:cb=>{window.fireDeletion=cb;return()=>{}},onScreenSettingsChanged:cb=>{window.fireSettings=cb;return()=>{}},openChat:()=>{window.openedChat=true}};
createRoot(document.getElementById('root')).render(React.createElement(MemoryRouter,null,React.createElement(Widget)));
`
const server = await createServer({ root, configFile:false, plugins:[react(), {
  name:'screen-detail-fixture', resolveId:id=>id==='/detail-entry.js'?'\0detail-entry':null,
  load:id=>id==='\0detail-entry'?entry:null,
  configureServer(s) { s.middlewares.use(async(req,res,next)=>{
    if (!req.url.startsWith('/detail.html')) return next()
    try { res.setHeader('Content-Type','text/html'); res.end(await s.transformIndexHtml('/detail.html','<div id="root"></div><script type="module" src="/detail-entry.js"></script>')) } catch(e) { next(e) }
  }) },
}], server:{host:'127.0.0.1',port:0}, optimizeDeps:{noDiscovery:true,include:['react','react-dom/client','react/jsx-dev-runtime','react-router-dom']} })
const deferred=()=>{let resolve;return{promise:new Promise(r=>{resolve=r}),resolve}}
let app, gate, screenGate, checks=0
const started=new Date().toISOString()
try {
  await server.listen()
  const main=path.join(temp,'main.cjs')
  await writeFile(main,`const{app,BrowserWindow}=require('electron');app.setPath('userData',${JSON.stringify(path.join(temp,'profile'))});app.whenReady().then(()=>new BrowserWindow({show:false,width:380,height:340,useContentSize:true,webPreferences:{sandbox:true,contextIsolation:true,nodeIntegration:false}}).loadURL('about:blank'));`)
  const env={...process.env};delete env.ELECTRON_RUN_AS_NODE
  app=await _electron.launch({executablePath:electronPath,args:[main],env})
  const page=await app.firstWindow(), errors=[], screens=[], chats=[]
  page.on('pageerror',e=>errors.push(e.message))
  let summary='분수의 통분을 공부하고 있어요.\n'+ '요약 문장 '.repeat(120), fail=false
  await page.route(url=>url.pathname.startsWith('/api/'),async route=>{
    const name=new URL(route.request().url()).pathname
    const json=(body,status=200)=>route.fulfill({status,contentType:'application/json',body:JSON.stringify(body)}).catch(()=>{})
    if(name==='/api/auth/csrf')return json({headerName:'X-CSRF',token:'synthetic'})
    if(name==='/api/review/observe')return json({})
    if(name==='/api/screen'){screens.push(route.request().postDataJSON());const own=summary;if(screenGate)await screenGate.promise;return json({text:own,detail:'UNCONFIRMED_DETAIL_DO_NOT_USE'})}
    if(name==='/api/tutor/chat'){
      chats.push(route.request().postDataJSON());const own=chats.length
      if(gate)await gate.promise
      if(fail)return json({message:'모의 연결 실패'},503)
      return route.fulfill({contentType:'text/event-stream',body:'event: delta\ndata: '+JSON.stringify({t:'답변 '+own})+'\n\nevent: done\ndata: {}\n\n'}).catch(()=>{})
    }
    throw Error('Unexpected API '+name)
  })
  await page.goto(`http://127.0.0.1:${server.httpServer.address().port}/detail.html`)
  const open=page.getByRole('button',{name:'자세히 보기',exact:true})
  const dialog=page.getByRole('dialog',{name:'화면 분석 상세'})
  await expect(open).toBeVisible();const captures=await page.evaluate(()=>window.captures), requests=screens.length
  await open.click();await expect(dialog).toBeVisible()
  await expect(dialog.getByText(summary,{exact:true})).toBeVisible()
  await expect(dialog.getByText('현재 분석은 요약만 제공해요. 별도의 상세 설명은 아직 제공되지 않아요.')).toBeVisible()
  await expect(page.getByText('UNCONFIRMED_DETAIL_DO_NOT_USE')).toHaveCount(0)
  expect(screens.length).toBe(requests);expect(await page.evaluate(()=>window.captures)).toBe(captures)
  expect(await dialog.evaluate(n=>n.scrollHeight>n.clientHeight && n.getBoundingClientRect().bottom<=innerHeight)).toBe(true)
  await dialog.getByRole('button',{name:'상세 닫기'}).scrollIntoViewIfNeeded()
  await expect(dialog.getByRole('button',{name:'상세 닫기'})).toBeFocused()
  await page.keyboard.press('Shift+Tab');expect(await dialog.evaluate(n=>n.contains(document.activeElement))).toBe(true)
  await page.keyboard.press('Escape');await expect(dialog).toHaveCount(0);await expect(open).toBeFocused()
  checks++;console.log('PASS open/close/focus/scroll/no extra requests/no invented detail')
  if(process.env.PT45_DETAIL_SCREENSHOT){
    await open.click();await page.screenshot({path:process.env.PT45_DETAIL_SCREENSHOT});await page.keyboard.press('Escape')
  }

  await open.click();await dialog.getByRole('textbox',{name:'화면 질문'}).fill('なぜ通分するの？')
  await page.evaluate(()=>{window.contextGate=new Promise(r=>{window.releaseContext=r});const form=document.querySelector('dialog form');for(let i=0;i<2;i++)form.dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}))})
  await expect(dialog.getByRole('button',{name:'보내기',exact:true})).toBeDisabled()
  await page.evaluate(()=>{window.releaseContext();window.contextGate=null})
  await expect(dialog.getByText('답변 1',{exact:true})).toBeVisible();expect(chats.length).toBe(1)
  expect(chats[0].screenSummary).toBe(summary.trim());expect(chats[0].messages).toEqual([{role:'user',content:'なぜ通分するの？'}])
  expect(Object.keys(chats[0]).sort()).toEqual(['messages','screenSummary','topic'])
  expect(JSON.stringify(chats)).not.toContain('UNCONFIRMED_DETAIL');expect(JSON.stringify(chats)).not.toContain('SYNTHETIC_IMAGE');expect(screens.length).toBe(requests)
  checks++;console.log('PASS dialog question one POST and summary only')

  fail=true;await dialog.getByRole('textbox').fill('다시 설명해 줘');await dialog.getByRole('button',{name:'보내기',exact:true}).click()
  await expect(dialog.getByRole('button',{name:'질문 다시 시도'})).toBeVisible();expect(chats.length).toBe(2)
  fail=false;await dialog.getByRole('button',{name:'질문 다시 시도'}).click();await expect(dialog.getByText('답변 3',{exact:true})).toBeVisible()
  checks++;console.log('PASS manual retry only')

  gate=deferred();await dialog.getByRole('textbox').fill('중지 질문');await dialog.getByRole('button',{name:'보내기',exact:true}).click()
  await expect.poll(()=>chats.length).toBe(4);await dialog.getByRole('button',{name:'답변 중지'}).click();gate.resolve();gate=null
  await expect(dialog.getByText('답변을 중지했어요.',{exact:true})).toBeVisible();await expect(page.getByText('답변 4',{exact:true})).toHaveCount(0)
  checks++;console.log('PASS stop rejects late chat')

  await page.keyboard.press('Escape');await page.getByRole('button',{name:'일시정지',exact:true}).click()
  await expect(open).toHaveCount(0);await expect(dialog).toHaveCount(0)
  await page.getByRole('button',{name:'다시 시작',exact:true}).click();await expect(open).toBeVisible();await open.click()
  screenGate=deferred();await page.evaluate(()=>window.fireContext());await expect(dialog).toHaveCount(0)
  await expect.poll(()=>screens.length).toBeGreaterThan(requests+1)
  await page.evaluate(()=>{window.settings={enabled:false};window.fireSettings()});screenGate.resolve();screenGate=null
  await expect(page.getByText('꺼짐',{exact:true})).toBeVisible();await expect(open).toHaveCount(0)
  checks++;console.log('PASS pause/context/off reject late analysis')

  summary='<img src=x onerror=alert(1)> 새 요약';await page.evaluate(()=>{window.settings={enabled:true};window.fireSettings()})
  await expect(open).toBeVisible();await open.click();await expect(dialog.getByText(summary,{exact:true})).toBeVisible()
  expect(await dialog.locator('img').count()).toBe(0)
  await page.evaluate(()=>window.fireDeletion(true));await expect(dialog).toHaveCount(0);await expect(open).toHaveCount(0)
  await page.evaluate(()=>window.fireDeletion(false));await page.evaluate(()=>window.fireContext());await expect(open).toBeVisible()
  await page.evaluate(()=>{window.target='gone'});await open.click();await expect(dialog).toHaveCount(0);await expect(page.getByText('창 종료',{exact:true})).toBeVisible()
  checks++;console.log('PASS deletion/closed target/text escaping')
  expect(errors).toEqual([])
  console.log(JSON.stringify({started,ended:new Date().toISOString(),scenarios:6,passed:checks,failed:0,rendererErrors:errors}))
} finally {
  gate?.resolve();screenGate?.resolve()
  await app?.close();await server.close()
  const resolved=path.resolve(temp)
  if(path.dirname(resolved)!==path.resolve(os.tmpdir())||!path.basename(resolved).startsWith('tutipal-screen-detail-'))throw Error('Unexpected temp path')
  await rm(resolved,{recursive:true,force:true})
}
