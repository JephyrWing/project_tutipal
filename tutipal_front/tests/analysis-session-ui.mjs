import {_electron} from 'playwright'
import {expect} from '@playwright/test'
import electronPath from 'electron'
import {createServer} from 'node:http'
import {mkdtemp,rm} from 'node:fs/promises'
import {fileURLToPath} from 'node:url'
import os from 'node:os'
import path from 'node:path'
const front=fileURLToPath(new URL('../',import.meta.url))
const profile=await mkdtemp(path.join(os.tmpdir(),'tutipal-e2e-profile-'))
const control={user:1,expired:false,screenPosts:0,hold:false,pending:[],summary:'요약 1'}
const server=createServer((req,res)=>{
 const p=new URL(req.url,'http://127.0.0.1').pathname
 const json=(body,status=200)=>res.writeHead(status,{'Content-Type':'application/json'}).end(JSON.stringify(body))
 if(p==='/api/auth/csrf')return json({headerName:'X-CSRF',token:'synthetic'})
 if(p==='/api/users/me')return control.expired?json({message:'expired'},401):json({id:control.user,name:'synthetic'})
 if(p==='/logout')return res.writeHead(204).end()
 if(p==='/api/screen'){
  control.screenPosts++;const answer=control.summary
  if(control.hold){control.pending.push(()=>json({text:answer}));return}
  return json({text:answer})
 }
 if(p==='/api/review/observe')return json({})
 if(p==='/api/auth/config')return json({devLogin:true,providers:[]})
 return json([])
})
let app,passed=0
const started=new Date().toISOString()
try{
 await new Promise(r=>server.listen(0,'127.0.0.1',r))
 const backend='http://127.0.0.1:'+server.address().port
 const env={...process.env,TUTIPAL_E2E_PROFILE:profile,TUTIPAL_SPRING_URL:backend};delete env.ELECTRON_RUN_AS_NODE
 app=await _electron.launch({executablePath:electronPath,args:[path.join(front,'tests/desktop/analysis-session-entry.cjs')],cwd:front,env,chromiumSandbox:true})
 let main,widget;const errors=[]
 await expect.poll(()=>{main=app.windows().find(p=>p.url().startsWith('http:')&&!p.url().endsWith('/widget'));widget=app.windows().find(p=>p.url().endsWith('/widget'));return !!main&&!!widget}).toBe(true)
 main.on('pageerror',e=>errors.push(e.message));widget.on('pageerror',e=>errors.push(e.message))
 await widget.clock.install()
 const settings=()=>main.evaluate(()=>window.desktop.getScreenSettings())
 const start=async()=>main.evaluate(async()=>{const p=await window.desktop.previewScreenSource('window:999999999');return window.desktop.setScreenSettings({enabled:true,sourceId:p.sourceId,sourceName:'synthetic',previewToken:p.previewToken})})
 const captures=()=>app.evaluate(()=>global.pt119.captures)
 await start();await expect(widget.getByText('요약 1',{exact:true})).toBeVisible()
 await widget.getByRole('button',{name:'자세히 보기',exact:true}).click()
 const dialog=widget.getByRole('dialog',{name:'화면 분석 상세'}),input=dialog.getByRole('textbox',{name:'화면 질문'})
 await input.fill('작성 중인 질문')
 control.summary='요약 2';await app.evaluate(()=>{global.pt119.version++});await widget.clock.fastForward(40001)
 await expect(dialog.getByText('요약 2',{exact:true})).toBeVisible();await expect(input).toHaveValue('작성 중인 질문')
 passed++;console.log('PASS same selected window summary update preserves modal and question draft')

 control.hold=true;control.summary='OLD ACCOUNT RESPONSE';await app.evaluate(()=>{global.pt119.version++})
 const posts=control.screenPosts;await widget.clock.fastForward(40001);await expect.poll(()=>control.screenPosts).toBe(posts+1)
 await main.evaluate(()=>fetch('/logout',{method:'POST'}))
 await expect.poll(settings).toMatchObject({enabled:false,sourceId:''})
 await expect(dialog).toHaveCount(0)
 const before=captures();const count=await before
 control.pending.splice(0).forEach(r=>r());control.hold=false;await widget.clock.fastForward(80001)
 await expect(widget.getByText('OLD ACCOUNT RESPONSE',{exact:true})).toHaveCount(0);expect(await captures()).toBe(count)
 passed++;console.log('PASS actual bridge logout stops analysis and discards delayed response')

 control.user=2;await main.reload();await expect.poll(settings).toMatchObject({enabled:false,sourceId:''})
 expect(await captures()).toBe(count)
 await expect(main.evaluate(()=>window.desktop.setScreenSettings({enabled:true,sourceId:'window:999999999',previewToken:'old-token'}))).rejects.toThrow()
 control.summary='새 계정 요약';await start();await expect(widget.getByText('새 계정 요약',{exact:true})).toBeVisible()
 passed++;console.log('PASS relogin never restarts and requires a new explicit preview')

 control.expired=true;await main.evaluate(()=>fetch('/api/users/me'))
 await expect.poll(settings).toMatchObject({enabled:false,sourceId:''});control.expired=false
 passed++;console.log('PASS actual bridge HTTP 401 revokes selection')

 await app.evaluate(async({session},url)=>{await session.defaultSession.cookies.set({url,name:'TUTIPAL_SESSION',value:'synthetic-account-2'})},backend)
 await start();await app.evaluate(async({session},url)=>{await session.defaultSession.cookies.set({url,name:'TUTIPAL_SESSION',value:'synthetic-account-3'})},backend)
 await expect.poll(settings).toMatchObject({enabled:false,sourceId:''})
 passed++;console.log('PASS actual session cookie replacement stops analysis')

 // Trusted Main records the selected class before opening the picker.
 await main.goto(new URL('/sessions/7',main.url()).href)
 await main.evaluate(()=>window.desktop.openScreenConsent())
 await expect(main.getByRole('heading',{name:'분석할 창 선택'})).toBeVisible()
 await main.getByText('PT119 synthetic window',{exact:true}).click()
 await main.getByRole('button',{name:'선택 창 미리보기',exact:true}).click()
 await expect(main.getByAltText('선택 창 미리보기')).toBeVisible()
 await main.getByRole('button',{name:'분석 시작',exact:true}).click()
 await expect.poll(()=>new URL(main.url()).pathname).toBe('/sessions/7')
 passed++;console.log('PASS actual picker completion returns to original class')
 expect(errors).toEqual([])
 console.log(JSON.stringify({started,ended:new Date().toISOString(),collected:6,executed:6,passed,failed:0,skipped:0,rendererErrors:errors}))
}finally{
 control.pending.splice(0).forEach(r=>r())
 await app?.close();await new Promise(r=>server.close(r))
 const resolved=path.resolve(profile)
 if(path.dirname(resolved)!==path.resolve(os.tmpdir())||!path.basename(resolved).startsWith('tutipal-e2e-profile-'))throw Error('Unexpected profile')
 await rm(resolved,{recursive:true,force:true,maxRetries:5,retryDelay:200})
}
