import { execFile } from 'node:child_process'
import { randomUUID } from 'node:crypto'
import { promisify } from 'node:util'
import { createRoom, expect, joinRoom, login, test } from './fixtures.js'

const exec = promisify(execFile)
const teacherApi = (id) => `/api/sessions/${id}/learning-results`

async function api(app, path, { method = 'GET', body } = {}) {
  return app.page.evaluate(async ({ path, method, body }) => {
    const headers = {}
    if (method !== 'GET') {
      const csrf = await (await fetch('/api/auth/csrf')).json()
      headers[csrf.headerName] = csrf.token
    }
    if (body !== undefined) headers['Content-Type'] = 'application/json'
    const response = await fetch(path, { method, headers, body: JSON.stringify(body) })
    return { status: response.status, body: response.status === 204 ? null : await response.json() }
  }, { path, method, body })
}

async function seed(roomId, studentId) {
  const container = process.env.E2E_DB_CONTAINER ?? `tutipal-pt24-${process.env.GITHUB_RUN_ID}`
  const db = process.env.E2E_DB_URL?.split('/').at(-1) ?? 'tutipal_e2e'
  if (!/^(tutipal-pt24-\d+|tutipal-pt105-results-e2e)$/.test(container)
      || !/^tutipal_e2e(?:_[a-z0-9]+)?$/.test(db)
      || ![roomId, studentId].every((n) => Number.isSafeInteger(n) && n > 0)) {
    throw new Error('Disposable PT-105 fixture database and numeric IDs required')
  }
  const base = 7300000 + roomId * 100
  const first = base + 1
  const second = base + 2
  const kicked = base + 11
  const pending = base + 12
  const late = base + 13
  const missing = base + 14
  const pendingAnswer = base + 23
  const sql = `
    UPDATE session_participants SET created_at=now()-interval '20 minutes'
    WHERE session_id=${roomId} AND user_id=${studentId};
    INSERT INTO users(id,created_at,updated_at,name,provider,provider_id,role)
    VALUES (${kicked},now(),now(),'강제퇴장 학생','dev','pt105-kick-${roomId}','USER'),
           (${pending},now(),now(),'채점 대기 학생','dev','pt105-pending-${roomId}','USER'),
           (${late},now(),now(),'늦게 참여한 학생','dev','pt105-late-${roomId}','USER'),
           (${missing},now(),now(),'미제출 학생','dev','pt105-missing-${roomId}','USER');
    INSERT INTO session_participants(created_at,updated_at,session_id,user_id,role)
    SELECT now()-interval '20 minutes',now(),${roomId},id,'STUDENT'
    FROM users WHERE id IN (${kicked},${pending},${missing});
    INSERT INTO session_participants(created_at,updated_at,session_id,user_id,role)
    VALUES (now()-interval '10 minutes',now(),${roomId},${late},'STUDENT');
    INSERT INTO quizzes(id,created_at,updated_at,session_id,type,question,closes_at)
    VALUES (${first},now()-interval '30 minutes',now(),${roomId},'TEXT','첫 문항',now()-interval '10 minutes'),
           (${second},now(),now(),${roomId},'TEXT','둘째 문항',now()+interval '1 hour');
    INSERT INTO quiz_answers(id,created_at,updated_at,quiz_id,user_id,text,correct,graded_by)
    VALUES (${base + 21},now()-interval '15 minutes',now(),${first},${studentId},'정답 제출',true,'TEACHER'),
           (${base + 22},now()-interval '15 minutes',now(),${first},${kicked},'오답 제출',false,'AI'),
           (${pendingAnswer},now()-interval '15 minutes',now(),${first},${pending},'채점 전 제출',NULL,NULL);
  `
  await exec('docker', ['exec', container, 'psql', '-U', 'tutipal_e2e', '-d', db, '-v', 'ON_ERROR_STOP=1', '-c', sql],
    { windowsHide: true })
  return { first, second, kicked, pendingAnswer }
}

const counts = (unsubmitted, pending, correct, incorrect) => ({ unsubmitted, pending, correct, incorrect })

test('담당 교사 결과·참가 기록 기준 집계·종료 수업 홈 진입과 변경 차단', async ({ apps }) => {
  const teacher = await apps.launch()
  const student = await apps.launch()
  const other = await apps.launch()
  await login(teacher, 8)
  await login(student, 9)
  await login(other, 3)

  const suffix = randomUUID().slice(0, 8)
  const roomTitle = `PT-105 교사 결과 수업 ${suffix}`
  const foreignTitle = `PT-105 다른 교사의 수업 ${suffix}`
  const room = await createRoom(teacher, roomTitle)
  expect((await api(teacher, teacherApi(room.id))).body).toEqual({
    totals: counts(0, 0, 0, 0), students: [], questions: [],
  })
  const foreign = await createRoom(other, foreignTitle)
  expect((await api(other, `/api/sessions/${foreign.id}/end`, { method: 'POST' })).status).toBe(200)
  await joinRoom(student, room.code)
  await expect(student.page).toHaveURL(new RegExp(`/sessions/${room.id}$`))
  const studentId = (await api(student, '/api/users/me')).body.id
  const { first, second, kicked, pendingAnswer } = await seed(room.id, studentId)

  expect((await api(student, teacherApi(room.id))).status).toBe(403)
  expect((await api(other, teacherApi(room.id))).status).toBe(404)
  expect((await api(teacher, '/api/sessions/ended')).body.map((r) => r.id)).not.toContain(room.id)

  await teacher.page.getByRole('tab', { name: '학습 결과' }).click()
  await expect(teacher.page.getByText(/미제출은 참가 기록 기준/)).toBeVisible()
  const studentTable = teacher.page.getByRole('table', { name: '학생별 결과' })
  const questionTable = teacher.page.getByRole('table', { name: '문항별 결과' })
  await expect(studentTable.getByRole('row', { name: /늦게 참여한 학생/ }).getByRole('cell')).toHaveText(['1', '0', '0', '0', '1'])
  await expect(studentTable.getByRole('row', { name: /채점 대기 학생/ }).getByRole('cell')).toHaveText(['1', '1', '0', '0', '2'])
  await expect(questionTable.getByRole('row', { name: /첫 문항/ }).getByRole('cell')).toHaveText(['1', '1', '1', '1', '4'])
  const active = await api(teacher, teacherApi(room.id))
  expect(active.status).toBe(200)
  expect(active.body.totals).toEqual(counts(6, 1, 1, 1))
  expect(active.body.questions.find((q) => q.quizId === first).counts).toEqual(counts(1, 1, 1, 1))
  expect(active.body.questions.find((q) => q.quizId === second).counts).toEqual(counts(5, 0, 0, 0))
  expect(active.body.students.find((s) => s.name === '미제출 학생').counts).toEqual(counts(2, 0, 0, 0))

  expect((await api(student, `/api/sessions/${room.id}/participants/me`, { method: 'DELETE' })).status).toBe(204)
  expect((await api(teacher, `/api/sessions/${room.id}/participants/${kicked}`, { method: 'DELETE' })).status).toBe(204)
  let result = (await api(teacher, teacherApi(room.id))).body
  expect(result.totals).toEqual(counts(4, 1, 1, 1))
  expect(result.students.find((s) => s.userId === studentId).counts).toEqual(counts(0, 0, 1, 0))
  expect(result.students.find((s) => s.userId === kicked).counts).toEqual(counts(0, 0, 0, 1))

  const grade = `/api/quiz-answers/${pendingAnswer}`
  const close = `/api/quizzes/${second}/close`
  expect((await api(teacher, grade, { method: 'PUT', body: { correct: true } })).status).toBe(200)
  result = (await api(teacher, teacherApi(room.id))).body
  expect(result.totals).toEqual(counts(4, 0, 2, 1))

  await student.page.goto(new URL('/', student.page.url()).href)
  await joinRoom(student, room.code)
  await expect(student.page).toHaveURL(new RegExp(`/sessions/${room.id}$`))
  result = (await api(teacher, teacherApi(room.id))).body
  expect(result.students.find((s) => s.userId === studentId).counts).toEqual(counts(1, 0, 1, 0))
  expect((await api(teacher, `/api/sessions/${room.id}/end`, { method: 'POST' })).status).toBe(200)
  expect((await api(student, teacherApi(room.id))).status).toBe(403)
  expect((await api(other, teacherApi(room.id))).status).toBe(404)
  expect((await api(teacher, grade, { method: 'PUT', body: { correct: false } })).status).toBe(410)
  expect((await api(teacher, close, { method: 'POST' })).status).toBe(410)
  const ended = await api(teacher, teacherApi(room.id))
  expect(ended.status).toBe(200)
  expect(ended.body).toEqual(result)
  const endedRooms = await api(teacher, '/api/sessions/ended')
  const ownEnded = endedRooms.body.find((r) => r.id === room.id)
  expect(ownEnded).toMatchObject({ title: roomTitle })
  expect(ownEnded).not.toHaveProperty('inviteCode')
  expect(endedRooms.body.map((r) => r.id)).not.toContain(foreign.id)
  expect((await api(student, '/api/sessions/ended')).body.map((r) => r.id)).not.toContain(room.id)

  await teacher.page.goto(new URL('/', teacher.page.url()).href)
  const archive = teacher.page.getByRole('region', { name: '내가 종료한 수업' })
  await expect(archive.getByText(roomTitle, { exact: true })).toBeVisible()
  await expect(archive.getByText(foreignTitle, { exact: true })).toHaveCount(0)
  await archive.getByRole('link', { name: `${roomTitle} 학습 결과` }).click()
  await expect(questionTable.getByRole('row', { name: /첫 문항/ }).getByRole('cell')).toHaveText(['1', '0', '2', '1', '4'])
  await expect(teacher.page.getByRole('tab', { name: '이해도 질문' })).toHaveCount(0)
  await teacher.page.getByRole('link', { name: '홈으로' }).click()
  await archive.getByRole('link', { name: `${roomTitle} 개념 분석` }).click()
  await expect(teacher.page.getByText('학생들이 헷갈리는 주제 TOP3')).toBeVisible()
})
