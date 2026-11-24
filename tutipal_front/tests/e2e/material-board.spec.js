import { readFile } from 'node:fs/promises'
import { test, expect, login, createRoom, joinRoom } from './fixtures.js'

async function guarded(app, url, { method = 'GET', json, file } = {}) {
  return app.page.evaluate(async ({ url, method, json, file }) => {
    const options = { method }
    if (method !== 'GET') {
      const csrf = await (await fetch('/api/auth/csrf')).json()
      options.headers = { [csrf.headerName]: csrf.token }
      if (file) {
        options.body = new FormData()
        options.body.append('file', new File([new Uint8Array(file.size)], file.name))
      } else if (json) {
        options.headers['Content-Type'] = 'application/json'
        options.body = JSON.stringify(json)
      }
    }
    const response = await fetch(url, options)
    const body = await response.text()
    return { status: response.status, data: response.headers.get('content-type')?.includes('application/json') ? JSON.parse(body) : body }
  }, { url, method, json, file })
}

async function actors(apps) {
  const teacher = await apps.launch()
  const student = await apps.launch()
  const outsider = await apps.launch()
  await login(teacher, 1)
  await login(student, 2)
  await login(outsider, 3)
  return { teacher, student, outsider }
}

async function enter(student, room) {
  await joinRoom(student, room.code)
  await expect(student.page.getByText('실시간', { exact: true })).toBeVisible()
}

function tab(app, name) {
  return app.page.getByRole('tab', { name, exact: true })
}

async function upload(teacher, name, contents, mimeType = 'text/plain') {
  const response = teacher.page.waitForResponse(r => r.url().endsWith('/materials') && r.request().method() === 'POST')
  await teacher.page.locator('input[type=file]').setInputFiles({ name, mimeType, buffer: Buffer.from(contents) })
  const posted = await response
  expect(posted.status()).toBe(201)
  const row = await posted.json()
  expect(row.status).toBe('PROCESSING')
  return row
}

function materialRow(app, filename) {
  return app.page.getByRole('row').filter({ has: app.page.getByRole('link', { name: filename, exact: true }) })
}

function pdf(text) {
  const stream = 'BT /F1 12 Tf 20 50 Td (' + text + ') Tj ET'
  const objects = [
    '<< /Type /Catalog /Pages 2 0 R >>',
    '<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
    '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 240 100] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>',
    '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',
    '<< /Length ' + stream.length + ' >>\nstream\n' + stream + '\nendstream',
  ]
  let contents = '%PDF-1.4\n'
  const offsets = objects.map((object, index) => {
    const offset = contents.length
    contents += (index + 1) + ' 0 obj\n' + object + '\nendobj\n'
    return offset
  })
  return contents + 'xref\n0 6\n0000000000 65535 f \n' +
    offsets.map(offset => String(offset).padStart(10, '0') + ' 00000 n \n').join('') +
    'trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n' + contents.length + '\n%%EOF\n'
}

async function sources(app, roomId) {
  const result = await guarded(app, '/api/tutor/chat', {
    method: 'POST',
    json: { topic: '수학', sessionId: roomId, messages: [{ role: 'user', content: 'PT70MATERIAL example' }] },
  })
  expect(result.status).toBe(200)
  expect(result.data).toContain('event:done')
  expect(result.data).toContain('PT70 test response')
  expect(result.data).not.toContain('event:error')
  return result.data.match(/event:sources\r?\ndata:(.+)/)?.[1]
}

test('PT-70 자료 형식·독립 재사용·수업 권한·삭제 후 검색', async ({ apps }) => {
  test.setTimeout(120_000)
  const { teacher, student, outsider } = await actors(apps)
  const foreign = await createRoom(outsider, 'PT70 다른 교사 수업')
  await tab(outsider, '자료 · 공지').click()
  const unrelated = await upload(outsider, 'foreign.txt', 'PT70MATERIAL other room')
  await expect(materialRow(outsider, 'foreign.txt')).toContainText('완료')

  const source = await createRoom(teacher, 'PT70 이전 수업')
  await tab(teacher, '자료 · 공지').click()
  const contents = 'PT70MATERIAL reference about fractions'
  const original = await upload(teacher, 'fractions.txt', contents)
  await expect(materialRow(teacher, original.filename)).toContainText('완료')
  const sourceFiles = '/api/sessions/' + source.id + '/materials'
  expect((await guarded(teacher, sourceFiles, { method: 'POST', file: { name: 'wrong.exe', size: 12 } })).status).toBe(415)
  expect((await guarded(teacher, sourceFiles, {
    method: 'POST', file: { name: 'large.txt', size: 10 * 1024 * 1024 + 1 },
  })).status).toBe(413)

  const sourceUrl = teacher.page.url()
  await teacher.page.goto(new URL('/', sourceUrl).href)
  const target = await createRoom(teacher, 'PT70 새 수업')
  await enter(student, target)
  await tab(student, '자료').click()
  await tab(teacher, '자료 · 공지').click()
  const targetUrl = teacher.page.url()
  const targetFiles = '/api/sessions/' + target.id + '/materials'
  const reuseUrl = id => targetFiles + '/reuse/' + id
  const fileUrl = id => '/api/materials/' + id + '/file'
  const deleteUrl = id => '/api/materials/' + id
  const candidates = targetFiles + '/reusable'
  expect((await guarded(student, candidates)).status).toBe(403)
  expect((await guarded(student, reuseUrl(original.id), { method: 'POST' })).status).toBe(403)
  expect((await guarded(student, targetFiles, { method: 'POST', file: { name: 'blocked.txt', size: 12 } })).status).toBe(403)
  expect((await guarded(student, fileUrl(original.id))).status).toBe(404)
  expect((await guarded(outsider, targetFiles)).status).toBe(404)
  expect((await guarded(outsider, candidates)).status).toBe(404)
  expect((await guarded(outsider, reuseUrl(unrelated.id), { method: 'POST' })).status).toBe(404)
  expect((await guarded(teacher, reuseUrl(unrelated.id), { method: 'POST' })).status).toBe(404)
  expect((await guarded(teacher, '/api/sessions/' + foreign.id + '/materials')).status).toBe(404)
  expect(await sources(student, target.id)).toBeUndefined()
  expect(await sources(teacher, source.id)).toContain(original.filename)

  async function copyOriginal() {
    await teacher.page.getByRole('button', { name: '이전 수업 자료 불러오기' }).click()
    const picker = teacher.page.getByRole('combobox', { name: '이전 수업 자료' })
    await expect(picker.getByRole('option', { name: 'PT70 이전 수업 · fractions.txt' })).toHaveCount(1)
    await expect(picker.getByRole('option', { name: /foreign\.txt/ })).toHaveCount(0)
    await picker.selectOption(String(original.id))
    const response = teacher.page.waitForResponse(r => r.url().endsWith(reuseUrl(original.id)))
    await teacher.page.getByRole('button', { name: '불러오기', exact: true }).click()
    const posted = await response
    expect(posted.status()).toBe(201)
    const copy = await posted.json()
    expect(copy.id).not.toBe(original.id)
    await expect(materialRow(student, original.filename)).toContainText('완료')
    return copy
  }

  const copy = await copyOriginal()
  expect((await guarded(outsider, fileUrl(copy.id))).status).toBe(404)
  expect((await guarded(student, deleteUrl(copy.id), { method: 'DELETE' })).status).toBe(403)
  const downloadEvent = student.page.waitForEvent('download')
  await student.page.getByRole('link', { name: original.filename }).click()
  const download = await downloadEvent
  expect(download.suggestedFilename()).toBe(original.filename)
  expect(await readFile(await download.path(), 'utf8')).toBe(contents)
  expect(await sources(student, target.id)).toContain(original.filename)
  await materialRow(teacher, original.filename).getByRole('button', { name: '삭제' }).click()
  await expect(materialRow(student, original.filename)).toHaveCount(0)
  expect((await guarded(student, fileUrl(copy.id))).status).toBe(404)
  expect(await sources(student, target.id)).toBeUndefined()
  expect((await guarded(teacher, fileUrl(original.id))).data).toBe(contents)
  expect(await sources(teacher, source.id)).toContain(original.filename)

  expect((await guarded(teacher, '/api/sessions/' + source.id + '/end', { method: 'POST' })).status).toBe(200)
  if (teacher.page.url() !== targetUrl) await teacher.page.goto(targetUrl)
  await tab(teacher, '자료 · 공지').click()
  const secondCopy = await copyOriginal()
  expect((await guarded(teacher, deleteUrl(original.id), { method: 'DELETE' })).status).toBe(204)
  expect((await guarded(teacher, fileUrl(original.id))).status).toBe(404)
  expect((await guarded(student, fileUrl(secondCopy.id))).data).toBe(contents)
  expect(await sources(student, target.id)).toContain(original.filename)

  const markdown = await upload(teacher, 'lesson.md', 'PT70MATERIAL markdown')
  await expect(materialRow(student, markdown.filename)).toContainText('완료')
  const handout = await upload(teacher, 'lesson.pdf', pdf('PT70MATERIAL pdf handout'), 'application/pdf')
  await expect(materialRow(student, handout.filename)).toContainText('완료')
  const blank = await upload(teacher, 'blank.txt', '   \n')
  await expect(materialRow(student, blank.filename)).toContainText('실패')
  const found = JSON.parse(await sources(student, target.id)).items
  expect(found).toHaveLength(3)
  expect(found).toContain('lesson.pdf p.1')
})

test('PT-70 공지·자료 알림 선택과 게시글·댓글 삭제 권한', async ({ apps }) => {
  test.setTimeout(90_000)
  const { teacher, student, outsider } = await actors(apps)
  const peer = await apps.launch()
  await login(peer, 4)
  await createRoom(outsider, 'PT70 다른 수업')
  const room = await createRoom(teacher, 'PT70 공지 게시판')
  await enter(student, room)
  await enter(peer, room)
  await tab(teacher, '자료 · 공지').click()
  await tab(student, '공지').click()
  const toast = student.page.locator('.toast[role=status]')
  const noticeAlert = teacher.page.getByRole('checkbox', { name: '학생에게 알림', exact: true })
  async function notice(title, alert) {
    await noticeAlert.setChecked(alert)
    await teacher.page.getByPlaceholder('제목', { exact: true }).fill(title)
    await teacher.page.getByPlaceholder('내용', { exact: true }).fill('PT70 공지 내용')
    await teacher.page.getByRole('button', { name: '공지 올리기' }).click()
    await expect(student.page.locator('.notice-item').getByText(title)).toBeVisible()
  }
  await notice('알림 없는 공지', false)
  await expect(toast).toHaveCount(0)
  await notice('알림 있는 공지', true)
  await expect(toast).toContainText('새 공지: 알림 있는 공지')
  await toast.getByRole('button', { name: '닫기' }).click()
  const noticesUrl = '/api/sessions/' + room.id + '/notices'
  expect((await guarded(student, noticesUrl, { method: 'POST', json: { title: 'denied', body: 'denied', alert: true } })).status).toBe(403)
  expect((await guarded(outsider, noticesUrl)).status).toBe(404)

  await tab(student, '자료').click()
  const materialAlert = teacher.page.getByRole('checkbox', { name: '자료 업로드 시 학생에게 알림' })
  await materialAlert.uncheck()
  const silent = await upload(teacher, 'silent.md', 'PT70MATERIAL silent')
  await expect(materialRow(student, silent.filename)).toContainText('완료')
  await expect(toast).toHaveCount(0)
  await tab(student, '공지').click()
  await materialAlert.check()
  const announced = await upload(teacher, 'announced.txt', 'PT70MATERIAL announced')
  await expect(toast).toContainText('새 자료: announced.txt')
  await toast.getByRole('button', { name: '보기' }).click()
  await expect(materialRow(student, announced.filename)).toContainText('완료')

  await tab(student, '게시판').click()
  await tab(teacher, '게시판').click()
  await tab(peer, '게시판').click()
  const postsUrl = '/api/sessions/' + room.id + '/posts'
  async function write(title) {
    await student.page.getByRole('button', { name: '글쓰기' }).click()
    await student.page.getByPlaceholder('제목').fill(title)
    await student.page.getByPlaceholder('질문이나 나누고 싶은 내용').fill('PT70 질문')
    const response = student.page.waitForResponse(r => r.url().endsWith(postsUrl) && r.request().method() === 'POST')
    await student.page.getByRole('button', { name: '올리기', exact: true }).click()
    const posted = await response
    expect(posted.status()).toBe(201)
    return (await posted.json()).id
  }
  const postId = await write('학생의 글')
  const postPath = '/api/posts/' + postId
  for (const app of [student, teacher, peer]) await app.page.getByRole('button', { name: /학생의 글/ }).click()
  await expect(peer.page.getByRole('button', { name: '글 삭제' })).toHaveCount(0)
  expect((await guarded(peer, postPath, { method: 'DELETE' })).status).toBe(403)
  expect((await guarded(outsider, postPath)).status).toBe(404)
  expect((await guarded(outsider, postsUrl, { method: 'POST', json: { title: 'denied', body: 'denied' } })).status).toBe(404)
  expect((await guarded(outsider, postPath + '/comments', { method: 'POST', json: { body: 'denied' } })).status).toBe(404)
  expect((await guarded(outsider, postPath, { method: 'DELETE' })).status).toBe(404)

  async function addComment(body) {
    await student.page.getByPlaceholder('댓글', { exact: true }).fill(body)
    await student.page.getByRole('button', { name: '등록', exact: true }).click()
    await expect(teacher.page.getByText(body, { exact: true })).toBeVisible()
    const { data } = await guarded(teacher, postPath)
    return data.comments.find(row => row.body === body).id
  }
  const commentId = await addComment('작성자 댓글')
  const commentPath = '/api/comments/' + commentId
  await expect(peer.page.getByText('작성자 댓글', { exact: true })).toBeVisible()
  await expect(peer.page.locator('.comment').getByRole('button', { name: '삭제' })).toHaveCount(0)
  expect((await guarded(peer, commentPath, { method: 'DELETE' })).status).toBe(403)
  expect((await guarded(outsider, commentPath, { method: 'DELETE' })).status).toBe(404)
  await student.page.locator('.comment').getByRole('button', { name: '삭제' }).click()
  await expect(teacher.page.getByText('작성자 댓글', { exact: true })).toHaveCount(0)
  await addComment('교사가 삭제할 댓글')
  await teacher.page.locator('.comment').getByRole('button', { name: '삭제' }).click()
  await expect(student.page.getByText('교사가 삭제할 댓글', { exact: true })).toHaveCount(0)
  await student.page.getByRole('button', { name: '글 삭제' }).click()
  await expect(peer.page.getByRole('button', { name: /학생의 글/ })).toHaveCount(0)
  expect((await guarded(teacher, postPath)).status).toBe(404)

  const secondPost = await write('교사가 삭제할 글')
  await teacher.page.getByRole('button', { name: /교사가 삭제할 글/ }).click()
  await teacher.page.getByRole('button', { name: '글 삭제' }).click()
  await expect(student.page.getByRole('button', { name: /교사가 삭제할 글/ })).toHaveCount(0)
  expect((await guarded(student, '/api/posts/' + secondPost)).status).toBe(404)
})
