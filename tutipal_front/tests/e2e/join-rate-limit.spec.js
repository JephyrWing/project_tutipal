import { test, expect, login, createRoom, joinRoom } from './fixtures.js'

test('초대 시도 제한 안내가 끝나면 같은 코드로 다시 참여할 수 있다', async ({ apps }) => {
  const teacher = await apps.launch()
  const student = await apps.launch()
  await login(teacher, 1)
  await login(student, 2)
  const room = await createRoom(teacher, 'E2E 참여 제한과 재시도')
  await student.page.clock.install()
  // 서버의 제한 시간 자체는 Spring에서 검사하고, 렌더러는 고정 응답과 가상 시계로 검증한다.
  await student.page.route('**/api/sessions/join', route => route.fulfill({
    status: 429,
    headers: { 'Retry-After': '300' },
    json: { status: 429, message: '초대 코드 시도 횟수를 초과했어요. 잠시 후 다시 시도해 주세요.' },
  }), { times: 1 })

  await joinRoom(student, room.code)
  const enter = student.page.getByRole('button', { name: '입장', exact: true })
  await expect(student.page.getByRole('alert')).toContainText('초대 코드 시도 횟수를 초과했어요')
  await expect(enter).toBeDisabled()
  await expect(student.page.getByRole('textbox', { name: '초대 코드 6자리' })).toHaveValue(room.code)

  await student.page.clock.fastForward(300_000)
  await expect(enter).toBeEnabled()
  await expect(student.page.getByRole('alert')).toHaveCount(0)
  await enter.click()
  await expect(student.page.getByRole('heading', { name: /E2E 참여 제한과 재시도/ })).toBeVisible()
})
