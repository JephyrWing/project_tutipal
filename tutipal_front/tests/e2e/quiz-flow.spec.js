import { execFile, spawn } from 'node:child_process'
import { once } from 'node:events'
import { promisify } from 'node:util'
import { test, expect, login, createRoom, joinRoom } from './fixtures.js'

async function quizRequest(app, url, body, method = body === undefined ? 'GET' : 'POST') {
  return app.page.evaluate(async ({ url, body, method }) => {
    const options = { method }
    if (method !== 'GET') {
      const csrf = await (await fetch('/api/auth/csrf')).json()
      options.headers = { [csrf.headerName]: csrf.token }
      if (body !== undefined) {
        options.headers['Content-Type'] = 'application/json'
        options.body = JSON.stringify(body)
      }
    }
    const response = await fetch(url, options)
    const text = await response.text()
    return { status: response.status, data: text ? JSON.parse(text) : null }
  }, { url, body, method })
}

async function quizRoom(apps, title) {
  const teacher = await apps.launch()
  const student = await apps.launch()
  await login(teacher, 1)
  await login(student, 2)
  const room = await createRoom(teacher, title)
  await joinRoom(student, room.code)
  await expect(student.page.getByRole('heading', { name: new RegExp(title) })).toBeVisible()
  await expect(teacher.page.getByRole('tab', { name: '챗봇 미리보기' })).toHaveCount(0)
  await teacher.page.getByRole('tab', { name: '이해도 질문' }).click()
  return { teacher, student, room }
}

test('PT-68 직접 출제·제출·답안 관리와 수업별 권한', async ({ apps }) => {
  const { teacher, student, room } = await quizRoom(apps, 'PT-68 직접 출제')
  const otherTeacher = await apps.launch()
  await login(otherTeacher, 3)
  await createRoom(otherTeacher, 'PT-68 다른 수업')

  const path = `/api/sessions/${room.id}/quizzes`
  const quiz = { type: 'CHOICE', question: '8 + 5는 얼마인가요?', choices: ['11', '13'], answerIndex: 1 }
  expect((await quizRequest(student, path, quiz)).status).toBe(403)
  expect((await quizRequest(otherTeacher, path, quiz)).status).toBe(404)
  expect((await quizRequest(student, `${path}/generate`, { type: 'CHOICE' })).status).toBe(403)
  expect((await quizRequest(otherTeacher, `${path}/generate`, { type: 'CHOICE' })).status).toBe(404)

  await teacher.page.getByRole('button', { name: '직접 문제 출제', exact: true }).click()
  const form = teacher.page.getByRole('dialog', { name: '직접 문제 출제' })
  await form.getByRole('textbox', { name: '질문', exact: true }).fill(quiz.question)
  await form.getByPlaceholder('보기 1').fill('11')
  await form.getByPlaceholder('보기 3').fill('13')
  await form.getByRole('radio', { name: '3번을 정답으로' }).check()
  await form.getByRole('button', { name: '전체 학생에게 보내기' }).click()

  const sent = teacher.page.locator('.quiz-row').filter({ hasText: quiz.question })
  const review = teacher.page.getByRole('dialog', { name: '학생별 답안 관리' })
  await expect(form).toBeHidden()
  await expect(teacher.page.getByRole('button', { name: '직접 문제 출제', exact: true })).toBeVisible()
  await expect(teacher.page.getByRole('button', { name: 'AI 문제 생성', exact: true })).toBeVisible()
  await expect(sent.getByText('방금 보냄', { exact: true })).toBeVisible()
  await expect(review).toHaveCount(0)

  const answerDialog = student.page.getByRole('dialog', { name: '선생님 질문' })
  await expect(answerDialog.getByText(quiz.question, { exact: true })).toBeVisible()
  const { data: [liveQuiz] } = await quizRequest(student, `${path}/open`)
  expect(liveQuiz).toMatchObject({ question: quiz.question, choices: quiz.choices, myAnswer: null })
  expect(liveQuiz).not.toHaveProperty('answerIndex')
  expect(liveQuiz).not.toHaveProperty('modelAnswer')

  await answerDialog.getByRole('radio', { name: '13', exact: true }).check()
  await answerDialog.getByRole('button', { name: '제출', exact: true }).click()
  await expect(answerDialog.getByRole('heading', { name: /정답이에요/ })).toBeVisible()

  const answers = `/api/quizzes/${liveQuiz.id}/answers`
  expect((await quizRequest(student, answers, { choiceIndex: 1 })).status).toBe(409)
  expect((await quizRequest(student, answers)).status).toBe(403)
  expect((await quizRequest(otherTeacher, answers)).status).toBe(404)
  const { data: [{ answerId }] } = await quizRequest(teacher, answers)
  const grade = `/api/quiz-answers/${answerId}`
  expect((await quizRequest(student, grade, { correct: false }, 'PUT')).status).toBe(403)
  expect((await quizRequest(otherTeacher, grade, { correct: false }, 'PUT')).status).toBe(404)
  const openedAnswers = teacher.page.waitForResponse(r => new URL(r.url()).pathname === answers && r.request().method() === 'GET')
  await sent.getByRole('button', { name: /답안 보기/ }).click()
  expect((await openedAnswers).status()).toBe(200)
  await expect(review.getByText(quiz.question, { exact: true })).toBeVisible()
  await expect(review.getByText('응답 1/1 · 정답 1 · 채점 대기 0')).toBeVisible()
  await expect(review.getByText('13', { exact: true })).toBeVisible()
  await expect(review.getByText('정답', { exact: true })).toBeVisible()

  await otherTeacher.page.goto(new URL('/', otherTeacher.page.url()).href)
  await joinRoom(otherTeacher, room.code)
  await expect(otherTeacher.page.getByRole('heading', { name: /PT-68 직접 출제/ })).toBeVisible()
  const publicQuiz = await quizRequest(otherTeacher, `/api/quizzes/${liveQuiz.id}`)
  expect(publicQuiz.status).toBe(200)
  expect(publicQuiz.data.myAnswer).toBeNull()

  await review.getByRole('button', { name: '지금 마감' }).click()
  await expect.poll(async () => {
    const { data } = await quizRequest(otherTeacher, `/api/quizzes/${liveQuiz.id}`)
    return data.open === false && Date.now() > new Date(data.closesAt).getTime() + 3000
  }).toBe(true)
  expect((await quizRequest(otherTeacher, answers, { choiceIndex: 1 })).status).toBe(410)

  const { data: graceQuiz } = await quizRequest(teacher, path, { ...quiz, question: '유예 시간 확인' })
  const gracePath = `/api/quizzes/${graceQuiz.id}`
  const closed = await quizRequest(teacher, `${gracePath}/close`, undefined, 'POST')
  expect(closed.data.open).toBe(false)
  expect((await quizRequest(otherTeacher, `${gracePath}/answers`, { choiceIndex: 1 })).status).toBe(200)
})

test('PT-68·PT-50 AI 초안 취소·서버 재승인과 승인 후 전송', async ({ apps }) => {
  const { teacher, student, room } = await quizRoom(apps, 'PT-68 AI 초안')
  const path = `/api/sessions/${room.id}/quizzes`
  const draft = { type: 'TEXT', question: '2의 제곱을 쓰세요.', modelAnswer: '4' }
  const form = teacher.page.getByRole('dialog', { name: 'AI 문제 생성' })
  const sendButton = form.getByRole('button', { name: '전체 학생에게 보내기' })
  const approveButton = form.getByRole('button', { name: '초안 승인' })

  async function openDraft() {
    await teacher.page.getByRole('button', { name: 'AI 문제 생성', exact: true }).click()
    await form.getByRole('tab', { name: '주관식' }).click()
    await form.getByRole('textbox', { name: 'AI 문제 힌트' }).fill('PT50DRAFT')
    await form.getByRole('button', { name: /AI로 문제 만들기/ }).click()
    await expect(form.getByRole('textbox', { name: '질문', exact: true })).toHaveValue(draft.question)
  }

  await openDraft()
  await expect(sendButton).toBeDisabled()
  await form.getByRole('button', { name: '닫기', exact: true }).click()
  expect((await quizRequest(student, `${path}/open`)).data).toEqual([])

  await openDraft()
  await approveButton.click()
  await expect(sendButton).toBeEnabled()
  const editedQuestion = '3의 제곱을 쓰세요.'
  await form.getByRole('textbox', { name: '질문', exact: true }).fill(editedQuestion)
  await form.getByRole('textbox', { name: /모범 답안/ }).fill('9')
  await expect(sendButton).toBeDisabled()
  await approveButton.click()
  await expect(sendButton).toBeEnabled()
  expect((await quizRequest(student, `${path}/open`)).data).toEqual([])
  await sendButton.click()

  const sent = teacher.page.locator('.quiz-row').filter({ hasText: editedQuestion })
  const review = teacher.page.getByRole('dialog', { name: '학생별 답안 관리' })
  await expect(form).toBeHidden()
  await expect(sent.getByText('방금 보냄', { exact: true })).toBeVisible()
  await expect(review).toHaveCount(0)

  const studentDialog = student.page.getByRole('dialog', { name: '선생님 질문' })
  await expect(studentDialog.getByText(editedQuestion, { exact: true })).toBeVisible()
  const { data: [liveQuiz] } = await quizRequest(student, `${path}/open`)
  expect(liveQuiz.question).toBe(editedQuestion)
  await studentDialog.getByPlaceholder('답을 적어 주세요').fill('9')
  await studentDialog.getByRole('button', { name: '제출', exact: true }).click()
  const openedAnswers = teacher.page.waitForResponse(r => new URL(r.url()).pathname === `/api/quizzes/${liveQuiz.id}/answers` &&
    r.request().method() === 'GET')
  await sent.getByRole('button', { name: /답안 보기/ }).click()
  expect((await openedAnswers).status()).toBe(200)
  await expect(review.getByText(editedQuestion, { exact: true })).toBeVisible()
  await expect(review.locator('.answer-text')).toContainText('9')
})

test('PT-50 승인 내용과 교사·수업을 검증하고 동시 전송은 한 번만 처리한다', async ({ apps }) => {
  const { teacher, student, room } = await quizRoom(apps, 'PT-50 승인 검증')
  const other = await apps.launch()
  await login(other, 3)
  const foreign = await createRoom(other, 'PT-50 다른 교사의 수업')
  const path = `/api/sessions/${room.id}/quizzes`
  const { status, data: draft } = await quizRequest(teacher, `${path}/generate`, {
    type: 'CHOICE', hint: 'PT50CHOICE',
  })
  expect(status).toBe(200)
  expect(draft.draftId).toEqual(expect.any(Number))
  const { draftId, ...content } = draft
  const approval = `${path}/drafts/${draftId}/approve`
  const send = `${path}/drafts/${draftId}/send`

  expect((await quizRequest(teacher, send, content)).status).toBe(409)
  for (const endpoint of [approval, send]) {
    expect((await quizRequest(student, endpoint, content)).status).toBe(403)
    expect((await quizRequest(other, endpoint, content)).status).toBe(404)
    expect((await quizRequest(other, endpoint.replace(`/sessions/${room.id}/`, `/sessions/${foreign.id}/`), content)).status).toBe(404)
  }
  expect((await quizRequest(teacher, approval, content)).status).toBe(204)
  for (const change of [
    { question: '본문 변경' }, { choices: ['4', '3'] }, { answerIndex: 0 },
    { type: 'TEXT', choices: null, answerIndex: null, modelAnswer: '4' },
  ]) {
    expect((await quizRequest(teacher, send, { ...content, ...change })).status).toBe(409)
  }
  const textQuiz = { type: 'TEXT', question: content.question, modelAnswer: '4' }
  expect((await quizRequest(teacher, approval, textQuiz)).status).toBe(204)
  expect((await quizRequest(teacher, send, { ...textQuiz, modelAnswer: '다른 답' })).status).toBe(409)
  const payload = { ...textQuiz, timeLimitSec: 120 }
  const first = await Promise.all([quizRequest(teacher, send, payload), quizRequest(teacher, send, payload)])
  expect(first.map(reply => reply.status)).toEqual([201, 201])
  expect(first[0].data.id).toBe(first[1].data.id)
  expect((await quizRequest(teacher, path)).data.map(quiz => quiz.id)).toEqual([first[0].data.id])
  expect((await quizRequest(teacher, approval, textQuiz)).status).toBe(409)

  const { data: unsent } = await quizRequest(teacher, `${path}/generate`, { type: 'TEXT', hint: 'PT50DRAFT' })
  const unsentPath = `${path}/drafts/${unsent.draftId}`
  expect((await quizRequest(teacher, `${unsentPath}/approve`, unsent)).status).toBe(204)
  expect((await quizRequest(teacher, `/api/sessions/${room.id}/end`, undefined, 'POST')).status).toBe(200)
  expect((await quizRequest(teacher, `${unsentPath}/send`, unsent)).status).toBe(410)
  expect((await quizRequest(teacher, `${unsentPath}/approve`, unsent)).status).toBe(410)
  expect((await quizRequest(teacher, send, payload)).status).toBe(410)
})

async function gradingControl(marker, release = false) {
  const response = await fetch('http://127.0.0.1:28081/_test/quiz-grading', {
    method: 'POST',
    body: JSON.stringify({ marker, release }),
  })
  if (!response.ok) throw new Error(`Synthetic AI control status ${response.status}`)
  return response.json()
}

async function overlapGradeWrites(regrade, marker) {
  const container = process.env.E2E_DB_CONTAINER ?? `tutipal-pt24-${process.env.GITHUB_RUN_ID}`
  const db = process.env.E2E_DB_URL?.split('/').at(-1) ?? 'tutipal_e2e'
  if (!/^(tutipal-pt24-\d+|tutipal-pt50-quiz-e2e)$/.test(container) || !/^tutipal_e2e(?:_[a-z0-9]+)?$/.test(db)) {
    throw new Error('PT-50 lock checks require the disposable E2E database')
  }
  const psql = [container, 'psql', '-X', '-v', 'ON_ERROR_STOP=1', '-U', 'tutipal_e2e', '-d', db]
  // EXCLUSIVE allows ordinary reads, while both grading transactions must wait at SELECT ... FOR UPDATE.
  const blocker = spawn('docker', ['exec', '-i', ...psql], { stdio: ['pipe', 'pipe', 'inherit'] })
  const exited = once(blocker, 'close')
  let output = ''
  let teacherRequest
  blocker.stdout.on('data', chunk => { output += chunk })
  try {
    blocker.stdin.write('BEGIN;\nLOCK TABLE quiz_answers IN EXCLUSIVE MODE;\n\\echo PT50_LOCKED\n')
    await expect.poll(() => output.includes('PT50_LOCKED')).toBe(true)
    teacherRequest = regrade()
    await gradingControl(marker, true)
    await expect.poll(async () => {
      const { stdout } = await promisify(execFile)('docker', ['exec', ...psql, '-tAc',
        "SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query ~* 'from quiz_answers.*for (no key )?update'"])
      return Number(stdout.trim())
    }).toBe(2)
  } finally {
    blocker.stdin.end('ROLLBACK;\n')
    await exited
    if (teacherRequest) expect((await teacherRequest).status).toBe(200)
  }
}

test('PT-50 AI·교사 판정 순서와 동시 저장, 학생 공개 및 재시작 후 PT-105 집계', async ({ apps }) => {
  test.setTimeout(150_000)
  const { teacher, student, room } = await quizRoom(apps, 'PT-50 판정 보존')
  const teacherUrl = teacher.page.url()
  const studentUrl = student.page.url()
  const feedback = 'AI 모의 오답 피드백'
  const checked = []
  const quizPath = `/api/sessions/${room.id}/quizzes`
  const dialog = student.page.getByRole('dialog', { name: '선생님 질문' })
  for (const order of ['AI_FIRST', 'HELD_TEACHER_FIRST', 'HELD_OVERLAP']) {
    const marker = `PT50GRADE_${order}_${room.id}`
    const question = `검증 문항 ${order}`
    const created = await quizRequest(teacher, quizPath, {
      type: 'TEXT', question, modelAnswer: '합성 모범 답안', timeLimitSec: 300,
    })
    expect(created.status).toBe(201)
    const quiz = `/api/quizzes/${created.data.id}`
    const answers = `${quiz}/answers`
    const latest = async () => (await quizRequest(teacher, answers)).data[0]
    const studentResult = async () => (await quizRequest(student, quiz)).data.myAnswer
    await expect(dialog.getByText(question, { exact: true })).toBeVisible()
    await dialog.getByPlaceholder('답을 적어 주세요').fill(marker)
    await dialog.getByRole('button', { name: '제출', exact: true }).click()
    await expect.poll(latest).toMatchObject({ text: marker })
    const { answerId } = await latest()
    const grade = `/api/quiz-answers/${answerId}`
    const regrade = () => quizRequest(teacher, grade, { correct: true }, 'PUT')

    if (order === 'AI_FIRST') {
      await expect.poll(latest).toMatchObject({ aiOriginalCorrect: false, gradedBy: 'AI', teacherCorrect: null })
      await expect.poll(studentResult).toMatchObject({ correct: false, feedback })
      expect((await regrade()).status).toBe(200)
    } else {
      await expect.poll(() => gradingControl(marker)).toEqual({ held: true })
      await expect.poll(latest).toMatchObject({ aiOriginalCorrect: null, gradedBy: null, teacherCorrect: null })
      if (order === 'HELD_TEACHER_FIRST') {
        expect((await regrade()).status).toBe(200)
        await expect.poll(latest).toMatchObject({ aiOriginalCorrect: null, gradedBy: 'TEACHER', teacherCorrect: true })
        await gradingControl(marker, true)
      } else {
        await overlapGradeWrites(regrade, marker)
      }
    }

    const expected = {
      aiOriginalCorrect: false, aiOriginalFeedback: feedback, correct: true,
      teacherCorrect: true, gradedBy: 'TEACHER', feedback: null,
    }
    await expect.poll(latest).toMatchObject(expected)
    const visible = await studentResult()
    expect(visible).toMatchObject({ correct: true, gradedBy: 'TEACHER', feedback: null })
    expect(visible).not.toHaveProperty('aiOriginalCorrect')
    expect(visible).not.toHaveProperty('aiOriginalFeedback')
    await expect(dialog.getByRole('heading', { name: /정답이에요/ })).toBeVisible()
    await expect(dialog.getByText(feedback)).toHaveCount(0)
    await dialog.getByRole('button', { name: '닫기', exact: true }).click()
    checked.push({ quizId: created.data.id, answers, grade, expected, question })
  }

  const review = teacher.page.getByRole('dialog', { name: '학생별 답안 관리' })
  await teacher.page.locator('.quiz-row').filter({ hasText: checked.at(-1).question })
    .getByRole('button', { name: /답안 보기/ }).click()
  await expect(review.locator('.answer-text')).toContainText(`AI 원판정 오답 · ${feedback}`)
  await expect(review.locator('.answer-text')).toContainText('교사 최종판정 정답')
  await review.getByRole('button', { name: '닫기', exact: true }).click()

  const results = `/api/sessions/${room.id}/learning-results`
  const before = (await quizRequest(teacher, results)).data
  expect(before.totals).toEqual({ unsubmitted: 0, pending: 0, correct: 3, incorrect: 0 })
  expect(before.students.map(studentRow => studentRow.counts)).toEqual([before.totals])
  expect(before.questions.map(question => [question.quizId, question.counts.correct]))
    .toEqual(checked.map(row => [row.quizId, 1]))

  const restarted = await fetch('http://127.0.0.1:28081/_test/material-index', {
    method: 'POST', body: JSON.stringify({ restart: true }),
  })
  expect(restarted.status).toBe(200)
  await expect.poll(async () => {
    try { return (await fetch('http://127.0.0.1:28080/actuator/health')).status } catch { return 0 }
  }, { timeout: 90_000 }).toBe(200)
  await login(teacher, 1)
  await login(student, 2)
  await teacher.page.goto(teacherUrl)
  await student.page.goto(studentUrl)
  await expect(teacher.page.getByRole('tab', { name: '학습 결과' })).toBeVisible()
  await expect(student.page).toHaveURL(new RegExp(`/sessions/${room.id}$`))
  for (const row of checked) {
    expect((await quizRequest(teacher, row.answers)).data[0]).toMatchObject(row.expected)
    expect((await quizRequest(student, `/api/quizzes/${row.quizId}`)).data.myAnswer)
      .toMatchObject({ correct: true, gradedBy: 'TEACHER', feedback: null })
  }
  expect((await quizRequest(teacher, results)).data).toEqual(before)
  expect((await quizRequest(teacher, `/api/sessions/${room.id}/end`, undefined, 'POST')).status).toBe(200)
  expect((await quizRequest(teacher, checked[0].grade, { correct: false }, 'PUT')).status).toBe(410)
  expect((await quizRequest(teacher, results)).data).toEqual(before)
})
