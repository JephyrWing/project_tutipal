import {test,expect,login,createRoom,joinRoom} from './fixtures.js'
import {installSyntheticAnalysis,startSyntheticAnalysis} from './synthetic-analysis.js'

async function prepare(app) {
  await expect.poll(()=>app.application.windows().some(p=>p.url().endsWith('/widget'))).toBe(true)
  const widget=app.application.windows().find(p=>p.url().endsWith('/widget'))
  await expect(widget.getByText('꺼짐',{exact:true})).toBeVisible()
  const screens=[]
  await installSyntheticAnalysis(app)
  await app.application.evaluate(({ipcMain})=>{
    ipcMain.removeHandler('screen-capture')
    ipcMain.handle('screen-capture',()=>({status:'ok',changed:false,image:'SYNTHETIC_IMAGE',sourceId:'window:synthetic'}))
    ipcMain.removeHandler('screen-target-status')
    ipcMain.handle('screen-target-status',()=>({status:'ok'}))
  })
  await widget.route('**/api/screen',route=>{
    screens.push(route.request().postDataJSON())
    return route.fulfill({contentType:'application/json',body:JSON.stringify({summary:'가'.repeat(4001),detail:'PRIVATE_DETAIL'})})
  })
  await widget.route('**/api/review/observe',route=>route.fulfill({contentType:'application/json',body:'{}'}))
  await startSyntheticAnalysis(app)
  await expect(widget.getByText('가'.repeat(4001),{exact:true})).toBeVisible()
  return {widget,screens}
}
const answer=route=>route.fulfill({contentType:'text/event-stream',body:'event: delta\ndata: {"t":"공통 채팅 답변"}\n\nevent: done\ndata: {}\n\n'})
async function ask(widget) {
 await widget.getByRole('button',{name:'질문하기',exact:true}).click()
 await widget.getByRole('textbox',{name:'화면 질문'}).fill('이 요약을 설명해 줘')
 await widget.evaluate(()=>{
   const form=document.querySelector('form')
   form.dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}))
   form.dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}))
 })
}

test('화면 상세 모달은 추가 분석 없이 열고 요약만 질문으로 전송하며 중지 시 닫힘',async({apps})=>{
 const app=await apps.launch();await login(app,8)
 const {widget,screens}=await prepare(app),posts=[]
 await widget.route('**/api/tutor/chat',route=>{posts.push(route.request().postDataJSON());return answer(route)})
 const count=screens.length
 await widget.getByRole('button',{name:'자세히 보기',exact:true}).click()
 const dialog=widget.getByRole('dialog',{name:'화면 분석 상세'})
 await expect(dialog).toBeVisible()
 await expect(dialog.getByText('현재 분석은 요약만 제공해요. 별도의 상세 설명은 아직 제공되지 않아요.')).toBeVisible()
 expect(screens.length).toBe(count)
 await dialog.getByRole('textbox',{name:'화면 질문'}).fill('모달에서 질문')
 await widget.evaluate(()=>{const form=document.querySelector('dialog form');for(let i=0;i<2;i++)form.dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}))})
 await expect(dialog.getByText('공통 채팅 답변',{exact:true})).toBeVisible()
 expect(posts.length).toBe(1);expect(posts[0].screenSummary.length).toBe(4000)
 expect(posts[0].messages).toEqual([{role:'user',content:'모달에서 질문'}])
 expect(JSON.stringify(posts)).not.toContain('PRIVATE_DETAIL');expect(JSON.stringify(posts)).not.toContain('SYNTHETIC_IMAGE')
 expect(screens.length).toBe(count)
 await widget.keyboard.press('Escape');await expect(dialog).toHaveCount(0)
 await expect(widget.getByRole('button',{name:'자세히 보기',exact:true})).toBeFocused()
 await widget.getByRole('button',{name:'일시정지',exact:true}).click()
 await expect(widget.getByRole('button',{name:'자세히 보기',exact:true})).toHaveCount(0)
})
test('위젯 개인 질문은 실제 브리지에서 요약만 공통 채팅 한 번 전송',async({apps})=>{
 const app=await apps.launch();await login(app,8)
 const {widget,screens}=await prepare(app),posts=[]
 await widget.route('**/api/tutor/chat',route=>{posts.push(route.request().postDataJSON());return answer(route)})
 await ask(widget)
 await expect(widget.getByText('공통 채팅 답변',{exact:true})).toBeVisible()
 expect(posts.length).toBe(1);expect(posts[0].screenSummary.length).toBe(4000)
 expect(Object.keys(posts[0]).sort()).toEqual(['messages','screenSummary','topic'])
 expect(JSON.stringify(posts)).not.toContain('PRIVATE_DETAIL');expect(JSON.stringify(posts)).not.toContain('SYNTHETIC_IMAGE')
 expect(screens.every(s=>s.question===undefined)).toBe(true)
 expect(app.page.url()).not.toContain('가')
})
test('위젯 수업 질문은 실제 메인 수업 ID를 사용하고 새 맥락 이동 시 늦은 응답 폐기',async({apps})=>{
 const teacher=await apps.launch(),student=await apps.launch()
 await login(teacher,7);const room=await createRoom(teacher,'PT45 위젯 합성 수업')
 await login(student,8);await joinRoom(student,room.code)
 const {widget,screens}=await prepare(student),posts=[]
 let release;const gate=new Promise(r=>{release=r})
 await widget.route('**/api/tutor/chat',async route=>{
  posts.push(route.request().postDataJSON());await gate;await answer(route).catch(()=>undefined)
 })
 try{
  await ask(widget);await expect.poll(()=>posts.length).toBe(1)
  expect(posts[0].sessionId).toBe(room.id);expect(posts[0].screenSummary.length).toBe(4000)
  await student.page.goto(new URL('/settings',student.page.url()).href)
  await expect(widget.getByRole('button',{name:'답변 중지',exact:true})).toHaveCount(0)
 }finally{release()}
 await expect(widget.getByText('공통 채팅 답변',{exact:true})).toHaveCount(0)
 expect(screens.every(s=>s.question===undefined)).toBe(true)
 expect(posts.length).toBe(1)
})
