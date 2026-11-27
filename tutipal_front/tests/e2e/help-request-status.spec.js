import { test, expect, login, createRoom, joinRoom } from './fixtures.js'

async function requestHelp(student, message) {
  await student.page.getByRole('button', { name: '✋ 도움 요청', exact: true }).click()
  const dialog = student.page.getByRole('dialog', { name: '선생님에게 도움 요청' })
  await dialog.getByRole('textbox').fill(message)
  await dialog.getByRole('checkbox').uncheck()
  await dialog.getByRole('button', { name: '요청 보내기' }).click()
  await expect(student.page.getByText('도움 요청 대기 중', { exact: true })).toBeVisible()
}

test('PT47 a failed student cancellation preserves the request and can be retried', async ({ apps }) => {
  const teacher = await apps.launch()
  const student = await apps.launch()
  await login(teacher, 1)
  await login(student, 2)
  const room = await createRoom(teacher, 'PT47 도움 요청 취소')
  await joinRoom(student, room.code)
  const pending = student.page.getByText('도움 요청 대기 중', { exact: true })
  const helpButton = student.page.getByRole('button', { name: '✋ 도움 요청', exact: true })
  await requestHelp(student, '요청 취소 검사')

  await teacher.page.getByRole('tab', { name: /^도움 요청/ }).click()
  const teacherRequest = teacher.page.getByText('요청 취소 검사', { exact: true })
  await expect(teacherRequest).toBeVisible()

  const endRoute = '**/api/help/*/end'
  await student.page.route(endRoute, (route) => route.fulfill({
    status: 503,
    json: { message: '테스트 취소 오류' },
  }), { times: 1 })
  const cancel = student.page.getByRole('button', { name: '취소', exact: true })
  await cancel.click()
  await expect(student.page.getByRole('alert')).toHaveText('테스트 취소 오류')
  await expect(pending).toBeVisible()
  await expect(teacherRequest).toBeVisible()
  await expect(cancel).toBeEnabled()

  await cancel.click()
  await expect(helpButton).toBeVisible()
  await expect(teacherRequest).toHaveCount(0)
  await expect(student.page.getByRole('alert')).toHaveCount(0)
})

test('PT64 teacher decisions update the student help UI without reloading', async ({ apps }) => {
  const teacher = await apps.launch()
  const student = await apps.launch()
  await login(teacher, 1)
  await login(student, 2)
  const room = await createRoom(teacher, 'PT64 도움 요청 알림')

  let subscribedToEvents = false
  student.page.on('websocket', (socket) => {
    if (new URL(socket.url()).pathname !== '/ws') return
    socket.on('framesent', ({ payload }) => {
      const frame = payload.toString()
      if (frame.startsWith('SUBSCRIBE\n') && frame.includes('\ndestination:/user/queue/events\n')) {
        subscribedToEvents = true
      }
    })
  })
  await joinRoom(student, room.code)
  await expect.poll(() => subscribedToEvents).toBe(true)
  await teacher.page.getByRole('tab', { name: /^도움 요청/ }).click()

  await requestHelp(student, '첫 번째 도움 요청')
  const first = teacher.page.locator('.help-row').filter({ hasText: '첫 번째 도움 요청' })
  await first.getByRole('button', { name: '나중에', exact: true }).click()
  await expect(first).toHaveCount(0)
  await expect(student.page.getByText('선생님이 조금 뒤에 볼게요. 잠시 후 다시 요청해 주세요', { exact: true })).toBeVisible()

  await requestHelp(student, '다시 보낸 도움 요청')
  const second = teacher.page.locator('.help-row').filter({ hasText: '다시 보낸 도움 요청' })
  await second.getByRole('button', { name: '수락', exact: true }).click()
  await expect(second.getByText('진행 중', { exact: true })).toBeVisible()
  const studentChat = student.page.getByRole('region', { name: '개별 도움 채팅' })
  await expect(studentChat).toBeVisible()

  await second.getByRole('button', { name: '종료', exact: true }).click()
  await expect(second).toHaveCount(0)
  await expect(studentChat).toHaveCount(0)
  await expect(student.page.getByRole('button', { name: '✋ 도움 요청', exact: true })).toBeVisible()
})
