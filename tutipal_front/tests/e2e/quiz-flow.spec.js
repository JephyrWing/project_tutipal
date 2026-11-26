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
    return { status: response.status, data: await response.json() }
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

test('PT-68 AI 초안 취소·재승인과 승인 후 전송', async ({ apps }) => {
  const { teacher, student, room } = await quizRoom(apps, 'PT-68 AI 초안')
  const path = `/api/sessions/${room.id}/quizzes`
  const draft = { type: 'TEXT', question: '2의 제곱을 쓰세요.', modelAnswer: '4' }
  // 생성 모델을 고정하고 출제·수강생 조회는 실제 Spring을 사용한다.
  await teacher.page.route(`**${path}/generate`, (route) => route.fulfill({ json: draft }))
  const form = teacher.page.getByRole('dialog', { name: 'AI 문제 생성' })
  const sendButton = form.getByRole('button', { name: '전체 학생에게 보내기' })
  const approveButton = form.getByRole('button', { name: '초안 승인' })

  async function openDraft() {
    await teacher.page.getByRole('button', { name: 'AI 문제 생성', exact: true }).click()
    await form.getByRole('tab', { name: '주관식' }).click()
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
