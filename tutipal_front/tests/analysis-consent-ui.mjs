import {_electron} from 'playwright'
import electronPath from 'electron'
import {expect} from '@playwright/test'
import {mkdtemp,rm,mkdir} from 'node:fs/promises'
import {spawn} from 'node:child_process'
import path from 'node:path'
import os from 'node:os'
import {fileURLToPath} from 'node:url'
const front=fileURLToPath(new URL('../',import.meta.url))
const profile=await mkdtemp(path.join(os.tmpdir(),'tutipal-e2e-profile-'))
let app,child
const targetProfile=path.join(profile,'target');await mkdir(targetProfile)
const env={...process.env};delete env.ELECTRON_RUN_AS_NODE
try{
 app=await _electron.launch({executablePath:electronPath,args:[path.join(front,'tests/e2e/entry.cjs')],cwd:front,env:{...env,TUTIPAL_E2E_PROFILE:profile,TUTIPAL_SPRING_URL:'http://127.0.0.1:28080'},chromiumSandbox:true})
 let screenPosts=0
 await app.context().route(url=>url.pathname.startsWith('/api/'),async route=>{
  const p=new URL(route.request().url()).pathname
  if(p==='/api/screen')screenPosts++
  const body=p==='/api/users/me'?{id:1,name:'Synthetic student'}:p==='/api/auth/csrf'?{headerName:'X-CSRF',token:'synthetic'}:p==='/api/screen'?{text:'Synthetic summary'}:p.endsWith('/observe')?{}:[]
  await route.fulfill({contentType:'application/json',body:JSON.stringify(body)})
 })
 let page
 await expect.poll(()=>{page=app.windows().find(p=>p.url().startsWith('http:')&&!p.url().includes('/widget'));return !!page}).toBe(true)
 const errors=[];page.on('pageerror',e=>errors.push(e.message))
 child=spawn(electronPath,[path.join(front,'tests/analysis-target.cjs'),targetProfile,'rgb(210,40,40)','PT45 UI target'],{env,windowsHide:true,stdio:['ignore','pipe','pipe']})
 await new Promise((resolve,reject)=>{child.stdout.once('data',resolve);child.once('error',reject)})
 await expect.poll(()=>page.evaluate(()=>window.desktop.screenSources().then(list=>list.some(s=>s.name==='PT45 UI target')))).toBe(true)
 await page.goto(new URL('/screen-consent',page.url()).href)
 await expect(page.getByRole('heading',{name:'분석할 창 선택'})).toBeVisible()
 await expect(page.getByAltText('선택 창 미리보기')).toHaveCount(0)
 const chosen=page.locator('.source-card').filter({hasText:'PT45 UI target'})
 await expect(chosen).toHaveCount(1)
 await chosen.click();await expect(chosen.getByRole('radio')).toBeChecked()
 await expect(page.getByRole('button',{name:'분석 시작',exact:true})).toBeDisabled()
 expect(screenPosts).toBe(0)
 console.log('PASS actual Main/Preload UI: select alone has no preview or analysis')
 await page.getByRole('button',{name:'선택 창 미리보기',exact:true}).click()
 await expect(page.getByAltText('선택 창 미리보기')).toBeVisible({timeout:12000})
 expect(screenPosts).toBe(0)
 expect(await page.evaluate(()=>window.desktop.getScreenSettings())).toMatchObject({enabled:false})
 await expect(page.getByRole('button',{name:'분석 시작',exact:true})).toBeEnabled()
 console.log('PASS actual selected-window preview via UI does not invoke analysis')
 await page.getByRole('button',{name:'새로고침',exact:true}).click()
 await expect(page.getByAltText('선택 창 미리보기')).toHaveCount(0)
 await expect(page.getByRole('button',{name:'분석 시작',exact:true})).toBeDisabled()
 console.log('PASS refresh invalidates preview/start token')
 await page.getByRole('button',{name:'선택 창 미리보기',exact:true}).click()
 await expect(page.getByAltText('선택 창 미리보기')).toBeVisible({timeout:12000})
 await page.getByRole('button',{name:'분석 시작',exact:true}).click()
 await expect.poll(()=>page.evaluate(()=>window.desktop.getScreenSettings().then(s=>s.enabled))).toBe(true)
 await expect.poll(()=>screenPosts>0,{timeout:12000}).toBe(true)
 console.log('PASS explicit start enables widget analysis using mock API only')
 let widget=app.windows().find(p=>new URL(p.url()).pathname==='/widget')
 await expect(widget.getByRole('button',{name:'일시정지',exact:true})).toBeVisible()
 await widget.getByRole('button',{name:'일시정지',exact:true}).click()
 await expect(widget.getByRole('button',{name:'다시 시작',exact:true})).toBeVisible()
 expect(screenPosts).toBeGreaterThan(0);expect(errors).toEqual([])
 console.log('PASS widget pause via actual Main/Preload')
 console.log('PT45 actual consent UI: collected=5 executed=5 passed=5 failed=0 skipped=0; AI/API mocked; rendererErrors=0')
}catch(error){
 const w=app?.windows().find(p=>p.url().includes('/widget'));console.error('widget diagnostic',w?await w.locator('.widget-bubble').innerText().catch(()=> 'unavailable'):'missing');throw error
}finally{
 child?.kill();await app?.close();await rm(profile,{recursive:true,force:true,maxRetries:5,retryDelay:200})
}

