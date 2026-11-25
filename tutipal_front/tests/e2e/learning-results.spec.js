import { execFile } from 'node:child_process'
import { promisify } from 'node:util'
import { test, expect, login } from './fixtures.js'

const exec = promisify(execFile)
async function sql(text) {
  const container = process.env.E2E_DB_CONTAINER ?? `tutipal-pt24-${process.env.GITHUB_RUN_ID}`
  if (!/^(tutipal-pt24-\d+|tutipal-pt101-results-check)$/.test(container)) throw new Error('Disposable container required')
  return exec('docker', ['exec', container, 'psql', '-U', 'tutipal_e2e', '-d', 'tutipal_e2e', '-v', 'ON_ERROR_STOP=1', '-c', text], { windowsHide: true })
}
async function seed(app) {
  const id = await app.page.evaluate(async () => (await (await fetch('/api/users/me')).json()).id)
  if (!Number.isSafeInteger(id) || id < 1) throw new Error('Invalid synthetic owner')
  // A unique ID range per developer account, only inside the disposable database.
  const b = 100000 + id * 1000
  await sql(`
    INSERT INTO study_sessions(id,created_at,updated_at,title,topic,invite_code,owner_id,ended_at)
    SELECT ${b}+n,now(),now(),'PT101 수업 '||n,'수학',lpad((${b}+n)::text,6,'0'),${id},
      CASE WHEN n=3 THEN now() ELSE NULL END FROM generate_series(1,7)n;
    INSERT INTO session_participants(created_at,updated_at,session_id,user_id,role)
    SELECT now(),now(),${b}+n,${id},CASE WHEN n=6 THEN 'TEACHER' ELSE 'STUDENT' END
      FROM generate_series(1,7)n WHERE n NOT IN (4,5);
    INSERT INTO quizzes(id,created_at,updated_at,session_id,type,question,choices,answer_index,model_answer,closes_at)
    SELECT ${b}+100+n,now(),now(),${b}+CASE WHEN n<=23 THEN 1 WHEN n=24 THEN 2 ELSE n-22 END,
      CASE WHEN n=24 THEN 'TEXT' ELSE 'CHOICE' END,
      CASE WHEN n<=24 THEN '기록 '||lpad(n::text,3,'0') ELSE '제외 기록 '||n END,
      CASE WHEN n=24 THEN NULL ELSE ARRAY['첫 보기','둘째 보기'] END,
      CASE WHEN n=24 THEN NULL ELSE 1 END,'비공개 모범답안',now()+interval '1 hour'
      FROM generate_series(1,29)n;
    INSERT INTO quiz_answers(created_at,updated_at,quiz_id,user_id,choice_index,text,correct,graded_by,feedback)
    SELECT '2026-11-25T00:00:00Z'::timestamptz+n*interval '1 second',now(),${b}+100+n,${id},
      CASE WHEN n=24 THEN NULL ELSE 0 END,CASE WHEN n=24 THEN '<script>내 서술 답</script>' ELSE NULL END,
      CASE WHEN n=23 THEN NULL ELSE n%2=0 END,
      CASE WHEN n=23 THEN NULL ELSE 'TEACHER' END,'비공개 피드백' FROM generate_series(1,28)n;
  `)
  return { id, b }
}
async function open(app) {
  await app.page.goto(new URL('/', app.page.url()).href)
  await app.page.getByRole('link', { name: /내 제출 기록/ }).click()
}
async function get(app, query = '') {
  return app.page.evaluate(async query => {
    const r = await fetch('/api/learning-results' + query)
    return { status: r.status, body: await r.json() }
  }, query)
}

test('본인 제출만 페이지·필터로 조회하고 민감 필드·수업 경계를 격리', async ({ apps }, info) => {
  const owner = await apps.launch(), other = await apps.launch()
  await login(owner, 4); await login(other, 5)
  const { b } = await seed(owner)
  const foreign = await seed(other)
  await open(owner)
  await expect(owner.page.getByText('전체 24개 · 최근 제출 순', { exact: true })).toBeVisible()
  await expect(owner.page.locator('.result-row')).toHaveCount(20)
  await expect(owner.page.locator('.result-row h2').first()).toHaveText('기록 024')
  await expect(owner.page.getByText('<script>내 서술 답</script>', { exact: false })).toBeVisible()
  await owner.page.screenshot({ path: info.outputPath('learning-results.png') })
  await owner.page.getByRole('button', { name: '다음', exact: true }).click()
  await expect(owner.page.locator('.result-row')).toHaveCount(4)
  await expect(owner.page.locator('.result-row h2').last()).toHaveText('기록 001')
  await owner.page.getByRole('combobox', { name: '수업', exact: true }).selectOption(String(b + 2))
  await expect(owner.page.getByText('전체 1개 · 최근 제출 순', { exact: true })).toBeVisible()
  await expect(owner.page.locator('.result-row h2')).toHaveText('기록 024')
  await owner.page.getByRole('combobox', { name: '수업', exact: true }).selectOption('')
  await owner.page.getByRole('combobox', { name: '판정', exact: true }).selectOption('PENDING')
  await expect(owner.page.locator('.result-row h2')).toHaveText('기록 023')
  const all = await get(owner, '?size=100&userId=' + foreign.id)
  expect(all.status).toBe(200); expect(all.body.totalCount).toBe(24)
  expect((await get(owner, '?verdict=CORRECT')).body.totalCount).toBe(12)
  expect((await get(owner, '?verdict=INCORRECT')).body.totalCount).toBe(11)
  expect(all.body.sessions.map(s => s.id).sort()).toEqual([b + 1, b + 2])
  expect(all.body.items.map(r => r.question)).not.toContain('제외 기록 25')
  for (const row of all.body.items) {
    expect(Object.keys(row).sort()).toEqual(['answerId','quizId','sessionId','sessionTitle','topic','type','question','choices','choiceIndex','text','submittedAt','verdict'].sort())
  }
  for (const session of [b + 3,b + 4,b + 5,b + 6,b + 7,foreign.b + 1,99999999]) {
    const response = await get(owner, '?sessionId=' + session)
    expect(response.body.totalCount).toBe(0); expect(response.body.items).toEqual([])
  }
})

test('재판정·실제 나가기·수업 종료 후 새 조회에서 판정과 노출 범위를 갱신', async ({ apps }) => {
  const app = await apps.launch(); await login(app, 6)
  const { b } = await seed(app)
  await open(app)
  await expect(app.page.getByText('전체 24개 · 최근 제출 순', { exact: true })).toBeVisible()
  await app.page.getByRole('combobox', { name: '판정', exact: true }).selectOption('PENDING')
  await expect(app.page.locator('.result-row h2')).toHaveText('기록 023')
  await sql(`UPDATE quiz_answers SET correct=true,graded_by='TEACHER',updated_at=now() WHERE quiz_id=${b + 123}`)
  await app.page.getByRole('button', { name: '새로고침', exact: true }).click()
  await expect(app.page.getByText('조회할 제출 기록이 없어요.', { exact: true })).toBeVisible()
  await app.page.getByRole('combobox', { name: '판정', exact: true }).selectOption('ALL')
  await expect(app.page.getByText('전체 24개 · 최근 제출 순', { exact: true })).toBeVisible()
  await app.page.getByRole('button', { name: '다음', exact: true }).click()
  await expect(app.page.getByText('2 / 2 페이지')).toBeVisible()
  const status = await app.page.evaluate(async id => {
    const csrf = await (await fetch('/api/auth/csrf')).json()
    return (await fetch(`/api/sessions/${id}/participants/me`, { method: 'DELETE', headers: { [csrf.headerName]: csrf.token } })).status
  }, b + 1)
  expect(status).toBe(204)
  await app.page.getByRole('button', { name: '새로고침', exact: true }).click()
  await expect(app.page.getByText('전체 1개 · 최근 제출 순', { exact: true })).toBeVisible()
  await expect(app.page.getByText('1 / 1 페이지')).toBeVisible()
  await sql(`UPDATE study_sessions SET ended_at=now() WHERE id=${b + 2}`)
  await app.page.getByRole('button', { name: '새로고침', exact: true }).click()
  await expect(app.page.getByText('전체 0개 · 최근 제출 순', { exact: true })).toBeVisible()
  await expect(app.page.getByRole('combobox', { name: '수업', exact: true }).locator('option')).toHaveCount(1)
})

test('로딩·실패 재시도와 늦은 이전 필터 응답 무시', async ({ apps }) => {
  const app = await apps.launch(); await login(app, 7); const { b } = await seed(app)
  await app.page.route('**/api/learning-results?*', route => route.fulfill({ status:503,contentType:'application/json',body:JSON.stringify({message:'임시 검사 오류'}) }), { times:1 })
  await open(app)
  await expect(app.page.getByRole('alert')).toContainText('임시 검사 오류')
  await expect(app.page.getByText('전체 0개 · 최근 제출 순')).toHaveCount(0)
  await app.page.getByRole('button', { name:'다시 불러오기',exact:true }).click()
  await expect(app.page.getByText('전체 24개 · 최근 제출 순')).toBeVisible()
  let release
  const gate = new Promise(resolve => { release = resolve })
  let held
  let fulfilled
  const completed = new Promise(resolve => { fulfilled = resolve })
  await app.page.route('**/api/learning-results?*', async route => { held = route; await gate; await route.fulfill({status:200,contentType:'application/json',body:JSON.stringify({items:[],sessions:[],totalCount:999,page:0,size:20,totalPages:50})}); fulfilled() }, {times:1})
  await app.page.getByRole('button', {name:'새로고침',exact:true}).click()
  await expect(app.page.getByRole('status')).toHaveText('제출 기록을 불러오는 중이에요…')
  await expect.poll(() => !!held).toBe(true)
  await app.page.getByRole('combobox', {name:'판정',exact:true}).selectOption('PENDING')
  await expect(app.page.locator('.result-row h2')).toHaveText('기록 023')
  release()
  await completed
  await expect(app.page.locator('.result-row h2')).toHaveText('기록 023')
  await expect.poll(() => app.page.getByText('전체 999개 · 최근 제출 순').count()).toBe(0)
  expect((await get(app,'?sessionId='+ (b+1)+'&verdict=PENDING')).body.totalCount).toBe(1)
})
