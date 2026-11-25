import { test, expect, login, createRoom, joinRoom } from './fixtures.js'

async function api(app,path,method='GET',body) {
  return app.page.evaluate(async({path,method,body})=>{
    const headers={}
    if(method!=='GET') {
      const csrf=await(await fetch('/api/auth/csrf')).json();headers[csrf.headerName]=csrf.token
      if(body!==undefined)headers['Content-Type']='application/json'
    }
    const response=await fetch(path,{method,headers,body:body===undefined?undefined:JSON.stringify(body)})
    return {status:response.status,body:await response.json().catch(()=>null)}
  },{path,method,body})
}
async function accepted(apps,title,{third=false}={}) {
  const teacher=await apps.launch(),student=await apps.launch()
  await login(teacher,1);await login(student,2)
  const room=await createRoom(teacher,title)
  await joinRoom(student,room.code)
  await expect(student.page).toHaveURL(new RegExp(`/sessions/${room.id}$`))
  await expect(student.page.getByRole('heading',{name:title,exact:true})).toBeVisible()
  expect((await api(student,`/api/sessions/${room.id}`)).status).toBe(200)
  const requested=await api(student,`/api/sessions/${room.id}/help`,'POST',{message:'채팅만 도움 요청',shareScreen:false})
  expect(requested.status,JSON.stringify(requested.body)).toBe(201)
  const helpId=requested.body.id
  expect((await api(teacher,`/api/help/${helpId}/accept`,'POST')).status).toBe(200)
  const other=third?await apps.launch():null
  if(other){await login(other,3);expect((await api(other,'/api/sessions/join','POST',{code:room.code})).status).toBe(200)}
  return {teacher,student,other,room,helpId,path:`/api/help/${helpId}/messages`}
}

test('PT96 화면공유 OFF 양방향 채팅·원문 평문·새로고침 복구·종료 차단',async({apps})=>{
  const {teacher,student,room,helpId,path}=await accepted(apps,'PT96 채팅 화면')
  await student.page.reload()
  await teacher.page.getByRole('tab',{name:/^도움 요청/}).click()
  await teacher.page.getByRole('button',{name:'열기',exact:true}).click()
  const studentChat=student.page.getByRole('region',{name:'개별 도움 채팅'})
  const teacherChat=teacher.page.getByRole('region',{name:'개별 도움 채팅'})
  await expect(studentChat.getByText('연결됨',{exact:true})).toBeVisible()
  await expect(teacherChat.getByText('연결됨',{exact:true})).toBeVisible()
  await studentChat.getByRole('textbox').fill('<img src=x onerror=alert(1)> 질문')
  await studentChat.getByRole('button',{name:'보내기',exact:true}).click()
  await expect(teacherChat.getByRole('log')).toContainText('<img src=x onerror=alert(1)> 질문')
  await expect(teacherChat.getByRole('log').locator('img')).toHaveCount(0)
  await teacherChat.getByRole('textbox').fill('선생님 답변')
  await teacherChat.getByRole('button',{name:'보내기',exact:true}).click()
  await expect(studentChat.getByRole('log')).toContainText('선생님 답변')
  await student.page.reload();await expect(student.page.getByRole('log',{name:'도움 메시지'})).toContainText('선생님 답변')
  expect((await api(teacher,path)).body.items).toHaveLength(2)
  expect((await api(teacher,`/api/help/${helpId}/end`,'POST')).status).toBe(204)
  expect((await api(student,path)).status).toBe(410)
  expect((await api(student,path,'POST',{clientMessageId:crypto.randomUUID(),text:'종료 뒤'})).status).toBe(410)
  await expect(student.page.getByRole('region',{name:'개별 도움 채팅'})).toHaveCount(0)
})

test('PT96 제3자·타수업·미수락 접근 차단과 입력·멱등·동시 순서',async({apps})=>{
  const {teacher,student,other,room,path,helpId}=await accepted(apps,'PT96 채팅 권한',{third:true})
  expect((await api(other,path)).status).toBe(404)
  expect((await api(other,path,'POST',{clientMessageId:crypto.randomUUID(),text:'제3자'})).status).toBe(404)
  expect(await student.page.evaluate(async path=>(await fetch(path,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({clientMessageId:crypto.randomUUID(),text:'CSRF 누락'})})).status,path)).toBe(403)
  const clientMessageId=crypto.randomUUID(),body={clientMessageId,text:'중복 전송'}
  const [first,second]=await Promise.all([api(student,path,'POST',body),api(student,path,'POST',body)])
  expect(first.status).toBe(200);expect(second.status).toBe(200);expect(first.body.id).toBe(second.body.id)
  expect((await api(student,path,'POST',{...body,text:'다른 내용'})).status).toBe(409)
  expect((await api(student,path,'POST',{clientMessageId:crypto.randomUUID(),text:' \n\t '})).status).toBe(400)
  expect((await api(student,path,'POST',{clientMessageId:crypto.randomUUID(),text:'\u00a0\t\u3000'})).status).toBe(400)
  expect((await api(student,path,'POST',{clientMessageId:crypto.randomUUID(),text:'😀'.repeat(1001)})).status).toBe(400)
  const results=await Promise.all(Array.from({length:4},(_,i)=>api(i%2?teacher:student,path,'POST',{clientMessageId:crypto.randomUUID(),text:`동시 ${i}`})))
  expect(results.every(r=>r.status===200)).toBe(true)
  const all=(await api(student,path)).body
  expect(all.items).toHaveLength(5);expect(all.items.map(m=>m.sequence)).toEqual(['1','2','3','4','5'])
  const page=(await api(student,`${path}?afterSequence=1&limit=2`)).body
  expect(page.items.map(m=>m.sequence)).toEqual(['2','3']);expect(page.hasMore).toBe(true)
  const ownRoom=await api(other,'/api/sessions','POST',{title:'PT96 다른 수업',topic:'수학'})
  expect(ownRoom.status).toBe(201);expect((await api(other,path)).status).toBe(404)
  const pending=await api(other,`/api/sessions/${room.id}/help`,'POST',{message:'미수락',shareScreen:false})
  expect(pending.status).toBe(201)
  expect((await api(other,`/api/help/${pending.body.id}/messages`)).status).toBe(410)
  expect((await api(other,`/api/help/${pending.body.id}/end`,'POST')).status).toBe(204)
  const [racedSend,racedEnd]=await Promise.all([
    api(student,path,'POST',{clientMessageId:crypto.randomUUID(),text:'종료 경합'}),
    api(teacher,`/api/help/${helpId}/end`,'POST'),
  ])
  expect([200,410]).toContain(racedSend.status);expect(racedEnd.status).toBe(204)
  expect((await api(student,`/api/sessions/${room.id}/participants/me`,'DELETE')).status).toBe(204)
  expect((await api(student,path)).status).toBe(404);expect((await api(teacher,path)).status).toBe(410)
})

test('PT96 내보내기·재참가 후 새 도움·수업 종료 접근 회수',async({apps})=>{
  const {teacher,student,room,path}=await accepted(apps,'PT96 권한 회수')
  const studentId=(await api(student,'/api/users/me')).body.id
  expect((await api(teacher,`/api/sessions/${room.id}/participants/${studentId}`,'DELETE')).status).toBe(204)
  expect((await api(student,path)).status).toBe(404);expect((await api(teacher,path)).status).toBe(410)
  expect((await api(student,'/api/sessions/join','POST',{code:room.code})).status).toBe(200)
  const next=await api(student,`/api/sessions/${room.id}/help`,'POST',{message:'재참가 새 도움',shareScreen:false})
  expect(next.status).toBe(201);expect((await api(teacher,`/api/help/${next.body.id}/accept`,'POST')).status).toBe(200)
  const nextPath=`/api/help/${next.body.id}/messages`
  expect((await api(student,nextPath,'POST',{clientMessageId:crypto.randomUUID(),text:'종료 전'})).status).toBe(200)
  expect((await api(teacher,`/api/sessions/${room.id}/end`,'POST')).status).toBe(200)
  expect((await api(student,nextPath)).status).toBe(410);expect((await api(teacher,nextPath)).status).toBe(410)
})

test('PT96 학생 탈퇴 시 대화도 정리하고 다른 도움은 보존',async({apps})=>{
  const {teacher,student,other,room,path}=await accepted(apps,'PT96 탈퇴 정리',{third:true})
  expect((await api(student,path,'POST',{clientMessageId:crypto.randomUUID(),text:'학생 탈퇴 전'})).status).toBe(200)
  const another=await api(other,`/api/sessions/${room.id}/help`,'POST',{message:'보존 도움',shareScreen:false})
  expect((await api(teacher,`/api/help/${another.body.id}/accept`,'POST')).status).toBe(200)
  const otherPath=`/api/help/${another.body.id}/messages`
  expect((await api(other,otherPath,'POST',{clientMessageId:crypto.randomUUID(),text:'보존 메시지'})).status).toBe(200)
  expect((await api(student,'/api/users/me','DELETE')).status).toBe(204)
  expect((await api(teacher,path)).status).toBe(404)
  expect((await api(teacher,otherPath)).body.items.map(m=>m.text)).toEqual(['보존 메시지'])
  expect((await api(teacher,'/api/users/me')).status).toBe(200)
})

test('PT96 소유 교사 탈퇴의 수업 삭제 시 메시지 FK 정리',async({apps})=>{
  const {teacher,student,path,room}=await accepted(apps,'PT96 소유 수업 정리')
  expect((await api(student,path,'POST',{clientMessageId:crypto.randomUUID(),text:'수업 삭제 전'})).status).toBe(200)
  expect((await api(teacher,'/api/users/me','DELETE')).status).toBe(204)
  expect((await api(student,path)).status).toBe(404)
  expect((await api(student,`/api/sessions/${room.id}`)).status).toBe(404)
  expect((await api(student,'/api/users/me')).status).toBe(200)
})
