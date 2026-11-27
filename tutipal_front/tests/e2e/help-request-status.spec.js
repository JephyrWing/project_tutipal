import { test, expect, login, createRoom, joinRoom } from './fixtures.js'

test('PT47 a failed student cancellation preserves the request and can be retried', async ({ apps }) => {
  const teacher = await apps.launch()
  const student = await apps.launch()
  await login(teacher, 1)
  await login(student, 2)
  const room = await createRoom(teacher, 'PT47 도움 요청 취소')
  await joinRoom(student, room.code)
  const pending = student.page.getByText('도움 요청 대기 중', { exact: true })
  const helpButton = student.page.getByRole('button', { name: '✋ 도움 요청', exact: true })
  await helpButton.click()
  const dialog = student.page.getByRole('dialog', { name: '선생님에게 도움 요청' })
  await dialog.getByRole('textbox').fill('요청 취소 검사')
  await dialog.getByRole('checkbox').uncheck()
  await dialog.getByRole('button', { name: '요청 보내기' }).click()
  await expect(pending).toBeVisible()

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
