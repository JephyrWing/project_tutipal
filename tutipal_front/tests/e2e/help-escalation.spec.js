import { test, expect, login, createRoom, joinRoom } from './fixtures.js'

// PT-113: 튜터 SSE 를 모의해 도움 요청 추천 답변과 agent.helpDraft 를 보낸다 (모델 호출 없음).
const DRAFT = '[AI 조교 요약] 수업 주제: 수학 · 질문 종류: 코드 오류 · 받은 힌트: 3단계까지 · 코드 오류가 계속 해결되지 않아요.'
const HELP_SUGGESTION = '\n\n혼자 풀기 어렵다면 선생님께 도움 요청할래요?'
const sse = events => events.map(([name, data]) => `event: ${name}\ndata: ${JSON.stringify(data)}\n\n`).join('')
const agentEvent = (extra = {}) => ({
  route: 'code_debug', suggestHelp: true, variant: null, answerHidden: true, retryable: false, helpDraft: DRAFT, ...extra,
})
const reply = event => sse([
  ['sources', { items: ['PT124 수업 자료.pdf p.1'] }],
  ['delta', { t: '힌트 3단계입니다.' }],
  ...(event.suggestHelp ? [['delta', { t: HELP_SUGGESTION }]] : []),
  ['agent', event],
  ['done', {}],
])

async function mockTutor(app, event = agentEvent()) {
  await app.page.route('**/api/conversations/*/messages', route => {
    if (route.request().method() !== 'POST') return route.continue()
    return route.fulfill({ contentType: 'text/event-stream', body: reply(event).replace('event: done', 'event: saved\ndata: {"userMessageId":"mock-q","assistantMessageId":"mock-a"}\n\nevent: done') })
  })
}

/** 이 페이지에서 나가는 도움 요청 생성(POST /api/sessions/{id}/help) 본문을 모은다. */
function helpPosts(page) {
  const posts = []
  page.on('request', request => {
    if (request.method() === 'POST' && /\/api\/sessions\/\d+\/help$/.test(new URL(request.url()).pathname)) {
      posts.push(request.postDataJSON())
    }
  })
  return posts
}

async function ask(app, text) {
  await app.page.getByPlaceholder(/질문을 입력하세요/).fill(text)
  await app.page.getByRole('button', { name: '보내기', exact: true }).click()
  await expect(app.page.locator('.bubble-ai').last()).toContainText('힌트 3단계입니다.')
  // 스트림이 끝나야(agent · done 처리 뒤) 버튼 유무를 판단할 수 있다: 중지 버튼이 사라지고 보내기 버튼이 돌아온다
  await expect(app.page.getByRole('button', { name: /중지/ })).toHaveCount(0)
  await expect(app.page.getByRole('button', { name: '보내기', exact: true })).toBeVisible()
}

async function classroom(apps, title) {
  const teacher = await apps.launch(), student = await apps.launch()
  await login(teacher, 1); await login(student, 2)
  const room = await createRoom(teacher, title)
  await joinRoom(student, room.code)
  await expect(student.page).toHaveURL(new RegExp(`/sessions/${room.id}$`))
  return { teacher, student, room }
}

test('PT113 추천 답변에서 초안을 채우고 학생이 고친 문구만 선생님에게 전달', async ({ apps }) => {
  const { teacher, student } = await classroom(apps, 'PT113 도움 초안')
  await mockTutor(student)
  const posts = helpPosts(student.page)

  await ask(student, 'range 에러가 계속 나요')
  await expect(student.page.locator('.sources')).toContainText('PT124 수업 자료.pdf p.1')
  const helpButton = student.page.getByRole('button', { name: '✋ 선생님께 도움 요청', exact: true })
  await helpButton.click()
  const dialog = student.page.getByRole('dialog', { name: '선생님에게 도움 요청' })
  await expect(dialog).toBeVisible()
  await expect(dialog.getByRole('textbox')).toHaveValue(DRAFT)
  await expect(dialog.getByText('AI 조교가 만든 요약이에요', { exact: false })).toBeVisible()

  await dialog.getByRole('button', { name: '닫기', exact: true }).click()
  await expect(dialog).toHaveCount(0)
  await student.page.waitForTimeout(500) // 닫은 뒤 늦게 나가는 요청이 없는지 잠깐 지켜본다
  expect(posts).toHaveLength(0)

  await helpButton.click()
  await expect(dialog.getByRole('textbox')).toHaveValue(DRAFT)
  const edited = `${DRAFT} range(1, 10) 끝 값에서 막혔어요`
  await dialog.getByRole('textbox').fill(edited)
  await dialog.getByRole('checkbox').uncheck()
  expect(posts).toHaveLength(0)
  await dialog.getByRole('button', { name: '요청 보내기', exact: true }).click()

  await expect.poll(() => posts.length).toBe(1)
  expect(posts[0]).toEqual({ message: edited, shareScreen: false })
  await expect(student.page.getByText('도움 요청 대기 중', { exact: true })).toBeVisible()

  await teacher.page.getByRole('tab', { name: /^도움 요청/ }).click()
  await expect(teacher.page.getByText(edited, { exact: true })).toBeVisible()
})

test('PT113 열린 요청이 있으면 초안 모달 대신 안내만 하고 전송하지 않음', async ({ apps }) => {
  const { student, room } = await classroom(apps, 'PT113 열린 요청')
  // 화면이 목록을 읽은 뒤 다른 경로로 요청이 열린 상황: 초안 버튼은 목록을 새로 조회해 판단해야 한다
  const created = await student.page.evaluate(async id => {
    const csrf = await (await fetch('/api/auth/csrf')).json()
    const response = await fetch(`/api/sessions/${id}/help`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token },
      body: JSON.stringify({ message: '먼저 보낸 요청', shareScreen: false }),
    })
    return response.status
  }, room.id)
  expect(created).toBe(201)
  await mockTutor(student)
  const posts = helpPosts(student.page)

  await ask(student, '그래도 모르겠어요')
  await student.page.getByRole('button', { name: '✋ 선생님께 도움 요청', exact: true }).click()
  await expect(student.page.getByText('이미 보낸 도움 요청이 있어요. 선생님 답을 기다려 주세요', { exact: true })).toBeVisible()
  await expect(student.page.getByRole('dialog', { name: '선생님에게 도움 요청' })).toHaveCount(0)
  expect(posts).toHaveLength(0)
})

test('PT113 추천이 없거나 개인 학습 채팅이면 도움 요청 버튼이 없음', async ({ apps }) => {
  const { student } = await classroom(apps, 'PT113 추천 없음')
  await mockTutor(student, agentEvent({ suggestHelp: false, helpDraft: null, route: 'concept' }))
  await ask(student, 'for문이 뭐예요?')
  await expect(student.page.getByRole('button', { name: '✋ 선생님께 도움 요청', exact: true })).toHaveCount(0)

  const solo = await apps.launch(); await login(solo, 3)
  await solo.page.goto(new URL('/chat', solo.page.url()).href)
  await mockTutor(solo)
  // Personal entry also uses stored transport, but must never expose the help action.
  await solo.page.route('**/api/conversations/*/messages', route => route.fulfill({
    contentType: 'text/event-stream',
    body: sse([['delta', { t: '힌트 3단계입니다.' }], ['agent', agentEvent()],
      ['saved', { userMessageId: 'synthetic-user', assistantMessageId: 'synthetic-assistant' }], ['done', {}]]),
  }))
  await ask(solo, '에러가 나요')
  await expect(solo.page.getByRole('button', { name: '✋ 선생님께 도움 요청', exact: true })).toHaveCount(0)
})
