import { test, expect, login } from './fixtures.js'

function sessionRequest(app, path, options) {
  return app.page.evaluate(async ({ path, options }) => {
    const response = await fetch(path, options)
    return { status: response.status, body: await response.json() }
  }, { path, options })
}

async function preparePost(app) {
  const { status, body } = await sessionRequest(app, '/api/auth/csrf')
  expect(status).toBe(200)
  return {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', [body.headerName]: body.token },
  }
}

test('수업 종료와 초대 참여가 겹쳐도 종료 응답과 최종 참가 상태가 일치한다', async ({ apps }) => {
  const teacher = await apps.launch()
  const student = await apps.launch()
  await login(teacher, 5)
  await login(student, 6)
  const [teacherPost, studentPost] = await Promise.all([preparePost(teacher), preparePost(student)])

  for (let round = 1; round <= 3; round++) {
    await test.step(`새 수업 ${round}의 참여와 종료`, async () => {
      const created = await sessionRequest(teacher, '/api/sessions', {
        ...teacherPost,
        body: JSON.stringify({ title: `PT15 동시 참여와 종료 ${round}`, topic: '수학' }),
      })
      expect(created.status).toBe(201)
      expect(created.body).toMatchObject({ participantCount: 1, ended: false, myRole: 'TEACHER' })
      const { id, inviteCode } = created.body
      const roomPath = `/api/sessions/${id}`

      const [closed, joined] = await Promise.all([
        sessionRequest(teacher, `${roomPath}/end`, teacherPost),
        sessionRequest(student, '/api/sessions/join', {
          ...studentPost,
          body: JSON.stringify({ code: inviteCode }),
        }),
      ])

      expect(closed.status).toBe(200)
      expect([200, 410]).toContain(joined.status)
      const joinedBeforeClose = joined.status === 200
      const participantCount = joinedBeforeClose ? 2 : 1
      expect(closed.body).toMatchObject({ id, participantCount, ended: true, myRole: 'TEACHER' })

      const [teacherRoom, studentRoom, activeRooms] = await Promise.all([
        sessionRequest(teacher, roomPath),
        sessionRequest(student, roomPath),
        sessionRequest(student, '/api/sessions'),
      ])
      expect(teacherRoom.status).toBe(200)
      expect(teacherRoom.body).toMatchObject({ id, participantCount, ended: true, myRole: 'TEACHER' })
      expect(studentRoom.status).toBe(joinedBeforeClose ? 200 : 404)
      if (joinedBeforeClose) {
        expect(joined.body).toMatchObject({ id, participantCount: 2, ended: false, myRole: 'STUDENT' })
        expect(studentRoom.body).toMatchObject({ id, participantCount: 2, ended: true, myRole: 'STUDENT' })
      }
      expect(activeRooms.status).toBe(200)
      expect(activeRooms.body.map(room => room.id)).not.toContain(id)
    })
  }
})
