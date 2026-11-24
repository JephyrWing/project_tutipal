import { test, expect, login } from './fixtures.js'

async function account(app) {
  await app.page.goto(new URL('/settings', app.page.url()).href)
  await app.page.getByRole('tab', { name: '계정', exact: true }).click()
}

test('본인 이름 저장 후 새로고침·로그아웃·재로그인해도 유지', async ({ apps }) => {
  const user = await apps.launch()
  const other = await apps.launch()
  await login(user, 7)
  await login(other, 8)
  const otherName = await other.page.evaluate(async () => (await (await fetch('/api/users/me')).json()).name)
  await account(user)
  const name = user.page.getByRole('textbox', { name: '이름', exact: true })
  const save = user.page.getByRole('button', { name: '이름 저장', exact: true })
  await expect(save).toBeDisabled()
  await name.fill('   ')
  await expect(save).toBeDisabled()
  await name.fill('  이름 유지 검사  ')
  await save.click()
  await expect(user.page.getByRole('status')).toHaveText('이름을 저장했어요.')
  await expect(name).toHaveValue('이름 유지 검사')
  expect(await user.page.evaluate(async () => (await (await fetch('/api/users/me')).json()).name)).toBe('이름 유지 검사')
  await user.page.reload()
  await user.page.getByRole('tab', { name: '계정', exact: true }).click()
  await expect(name).toHaveValue('이름 유지 검사')
  await user.page.getByRole('button', { name: '로그아웃', exact: true }).last().click()
  await expect(user.page).toHaveURL(/\/login$/)
  await login(user, 7)
  await account(user)
  await expect(name).toHaveValue('이름 유지 검사')
  expect(await other.page.evaluate(async () => (await (await fetch('/api/users/me')).json()).name)).toBe(otherName)
})

test('이름 저장 실패 시 입력과 기존 계정을 보존하고 다시 저장 가능', async ({ apps }) => {
  const user = await apps.launch()
  await login(user, 9)
  await account(user)
  const previous = await user.page.evaluate(async () => (await (await fetch('/api/users/me')).json()).name)
  const name = user.page.getByRole('textbox', { name: '이름', exact: true })
  await name.fill('재시도 이름')
  await user.page.route('**/api/users/me/name', route => route.fulfill({
    status: 503, contentType: 'application/json', body: JSON.stringify({ status: 503, message: '임시 저장 실패' }),
  }), { times: 1 })
  await user.page.getByRole('button', { name: '이름 저장', exact: true }).click()
  await expect(user.page.getByRole('alert')).toHaveText('임시 저장 실패')
  await expect(name).toHaveValue('재시도 이름')
  expect(await user.page.evaluate(async () => (await (await fetch('/api/users/me')).json()).name)).toBe(previous)
  await user.page.getByRole('button', { name: '이름 저장', exact: true }).click()
  await expect(user.page.getByRole('status')).toHaveText('이름을 저장했어요.')
})
