import { execFile } from 'node:child_process'
import { promisify } from 'node:util'
import { createRoom, expect, login, test } from './fixtures.js'

const exec = promisify(execFile)
const listPath = (kind) => `/api/admin/content?kind=${kind}`
const itemPath = (kind, id) => `/api/admin/content/${kind}/${id}`

async function db(sql) {
  const container = process.env.E2E_DB_CONTAINER ?? `tutipal-pt24-${process.env.GITHUB_RUN_ID}`
  if (!/^(tutipal-pt24-\d+|tutipal-pt109-moderation)$/.test(container)) {
    throw new Error('Disposable PT-109 database container required')
  }
  return exec('docker', ['exec', container, 'psql', '-U', 'tutipal_e2e', '-d', 'tutipal_e2e',
    '-At', '-v', 'ON_ERROR_STOP=1', '-c', sql], { windowsHide: true })
}

async function api(app, path, method = 'GET', payload, csrf = true) {
  return app.page.evaluate(async ({ path, method, payload, csrf }) => {
    const headers = {}
    if (csrf && method !== 'GET') {
      const value = await (await fetch('/api/auth/csrf')).json()
      headers[value.headerName] = value.token
    }
    if (payload !== undefined) headers['Content-Type'] = 'application/json'
    const response = await fetch(path, { method, headers, body: JSON.stringify(payload) })
    const text = await response.text()
    const body = response.headers.get('content-type')?.includes('application/json') && text ? JSON.parse(text) : text
    return { status: response.status, body, cache: response.headers.get('cache-control') }
  }, { path, method, payload, csrf })
}

async function withAdmin(apps, run) {
  await db(`INSERT INTO users(created_at, updated_at, name, provider, provider_id, role)
    VALUES (now(), now(), '콘텐츠 검사 관리자', 'dev', 'dev-9', 'ADMIN')
    ON CONFLICT (provider, provider_id) DO UPDATE SET role = 'ADMIN'`)
  try {
    const admin = await apps.launch()
    expect((await api(admin, listPath('POST'))).status).toBe(401)
    await login(admin, 9)
    const me = await api(admin, '/api/users/me')
    expect(me.body.role).toBe('ADMIN')
    await admin.page.getByRole('link', { name: '콘텐츠 관리' }).click()
    await expect(admin.page.getByRole('heading', { name: '콘텐츠 관리', exact: true })).toBeVisible()
    await run(admin, me.body.id)
  } finally {
    await db("UPDATE users SET role = 'USER' WHERE provider = 'dev' AND provider_id = 'dev-9'")
  }
}

async function createPost(app, room, title) {
  const response = await api(app, `/api/sessions/${room.id}/posts`, 'POST', { title, body: '공개 여부 검사 본문' })
  expect(response.status).toBe(201)
  return response.body.id
}

function setHidden(app, kind, id, hidden, version, reason, csrf = true) {
  return api(app, itemPath(kind, id), 'PUT', { hidden, version, reason }, csrf)
}

async function submitFromUi(admin, label, reason, action) {
  await admin.page.getByRole('button', { name: `${label} 관리`, exact: true }).click()
  await admin.page.getByRole('textbox', { name: '조치 사유' }).fill(reason)
  await admin.page.getByRole('button', { name: action, exact: true }).click()
}

test('PT109 관리자만 게시글을 내리고 다시 공개하며 열려 있던 글과 댓글 접근도 회수한다', async ({ apps }) => {
  const teacher = await apps.launch()
  await login(teacher, 1)
  const room = await createRoom(teacher, 'PT109 게시글 관리')
  const label = 'PT109 게시글 노출 확인'
  const id = await createPost(teacher, room, label)
  const comment = await api(teacher, `/api/posts/${id}/comments`, 'POST', { body: '복구 뒤에도 남을 댓글' })
  expect(comment.status).toBe(200)
  const commentId = comment.body.comments[0].id
  expect((await api(teacher, listPath('POST'))).status).toBe(403)
  expect((await setHidden(teacher, 'POST', id, true, 0, '교사는 거절')).status).toBe(403)
  await expect(teacher.page.getByRole('link', { name: '콘텐츠 관리' })).toHaveCount(0)
  await teacher.page.getByRole('tab', { name: /게시판/ }).click()
  await teacher.page.getByRole('button', { name: new RegExp(label) }).click()
  await expect(teacher.page.getByText('공개 여부 검사 본문', { exact: true })).toBeVisible()

  await withAdmin(apps, async (admin, actorId) => {
    expect((await setHidden(admin, 'POST', id, true, 0, 'CSRF 없이 거절', false)).status).toBe(403)
    expect(Number.isSafeInteger(id)).toBe(true)
    expect(Number.isSafeInteger(room.id)).toBe(true)
    expect(Number.isSafeInteger(actorId)).toBe(true)
    await db(`INSERT INTO content_moderation_actions(kind, content_id, session_id, actor_id, version, hidden, reason, created_at)
      VALUES ('POST', ${id}, ${room.id}, ${actorId}, 1, true, 'synthetic audit conflict', now())`)
    try {
      expect((await setHidden(admin, 'POST', id, true, 0, '감사 기록 실패 확인')).status).toBe(409)
      expect((await api(teacher, `/api/posts/${id}`)).status).toBe(200)
      const adminList = await api(admin, listPath('POST'))
      expect(adminList.body.items.find((row) => row.id === id)).toMatchObject({ hiddenAt: null, version: 0 })
    } finally {
      await db(`DELETE FROM content_moderation_actions WHERE kind = 'POST' AND content_id = ${id}
        AND version = 1 AND reason = 'synthetic audit conflict'`)
    }
    await submitFromUi(admin, label, '게시글 신고 확인', '콘텐츠 내리기')
    await expect(admin.page.getByText('콘텐츠를 내렸어요.', { exact: true })).toBeVisible()
    await expect(teacher.page.getByText('공개 여부 검사 본문', { exact: true })).toHaveCount(0)
    await expect(teacher.page.getByRole('button', { name: new RegExp(label) })).toHaveCount(0)
    expect((await api(teacher, `/api/posts/${id}`)).status).toBe(404)
    expect((await api(teacher, `/api/posts/${id}/comments`, 'POST', { body: '추가 거절' })).status).toBe(404)
    expect((await api(teacher, `/api/comments/${commentId}`, 'DELETE')).status).toBe(404)
    expect((await api(teacher, `/api/posts/${id}`, 'DELETE')).status).toBe(404)
    expect((await setHidden(admin, 'POST', id, true, 0, '중복 조치')).status).toBe(204)
    expect((await setHidden(admin, 'POST', id, false, 0, '이전 버전')).status).toBe(409)
    const history = await api(admin, itemPath('POST', id) + '/history')
    expect(history.body).toEqual([expect.objectContaining({ actorId, version: 1, hidden: true, reason: '게시글 신고 확인' })])
    await submitFromUi(admin, label, '재검토 후 공개', '다시 공개')
    await expect(admin.page.getByText('콘텐츠를 다시 공개했어요.', { exact: true })).toBeVisible()
    await expect(teacher.page.getByRole('button', { name: new RegExp(label) })).toBeVisible()
    const restored = await api(teacher, `/api/posts/${id}`)
    expect(restored.body.comments).toEqual([expect.objectContaining({ id: commentId })])
    await db(`UPDATE users SET role = 'USER' WHERE id = ${actorId}`)
    expect((await api(admin, listPath('POST'))).status).toBe(403)
    expect((await setHidden(admin, 'POST', id, true, 2, '역할 변경 후 거절')).status).toBe(403)
  })
})

test('PT109 자료 내리기는 원본을 보존하고 목록·다운로드·재사용·재색인을 차단한다', async ({ apps }) => {
  const teacher = await apps.launch()
  await login(teacher, 1)
  const room = await createRoom(teacher, 'PT109 자료 원본 수업')
  const name = 'PT109-visibility.txt'
  const content = 'PT109 synthetic original content'
  const upload = await teacher.page.evaluate(async ({ room, name, content }) => {
    const csrf = await (await fetch('/api/auth/csrf')).json()
    const form = new FormData()
    form.append('file', new File([content], name))
    const result = await fetch(`/api/sessions/${room.id}/materials`, {
      method: 'POST', headers: { [csrf.headerName]: csrf.token }, body: form,
    })
    return { status: result.status, body: await result.json() }
  }, { room, name, content })
  expect(upload.status).toBe(201)
  const { id } = upload.body
  expect(Number.isSafeInteger(id)).toBe(true)
  const fileUrl = `/api/materials/${id}/file`
  expect(await api(teacher, fileUrl)).toEqual({ status: 200, body: content, cache: expect.stringContaining('no-store') })
  await teacher.page.goto(new URL('/', teacher.page.url()).href)
  const target = await createRoom(teacher, 'PT109 자료 대상 수업')
  const reusable = `/api/sessions/${target.id}/materials/reusable`
  const reuse = `/api/sessions/${target.id}/materials/reuse/${id}`
  expect((await api(teacher, reusable)).body.map((row) => row.id)).toContain(id)

  await withAdmin(apps, async (admin) => {
    await admin.page.getByRole('button', { name: '자료', exact: true }).click()
    await submitFromUi(admin, name, '자료 신고 확인', '콘텐츠 내리기')
    await expect(admin.page.getByText('콘텐츠를 내렸어요.', { exact: true })).toBeVisible()
    expect((await api(teacher, `/api/sessions/${room.id}/materials`)).body.map((row) => row.id)).not.toContain(id)
    expect((await api(teacher, reusable)).body.map((row) => row.id)).not.toContain(id)
    expect((await api(teacher, fileUrl)).status).toBe(404)
    expect((await api(teacher, reuse, 'POST')).status).toBe(404)
    expect((await api(teacher, `/api/materials/${id}/index`, 'POST')).status).toBe(404)
    expect((await api(teacher, `/api/materials/${id}`, 'DELETE')).status).toBe(404)

    const { stdout: original } = await db(`SELECT storage_key FROM materials WHERE id = ${id}`)
    const key = original.trim()
    expect(key).toMatch(/^materials\/[0-9a-f-]+$/)
    await db(`UPDATE materials SET storage_key = 'materials/pt109-missing-${id}' WHERE id = ${id}`)
    expect((await setHidden(admin, 'MATERIAL', id, false, 1, '원본이 없어서 거절')).status).toBe(409)
    expect((await api(admin, itemPath('MATERIAL', id) + '/history')).body).toHaveLength(1)
    expect((await api(teacher, fileUrl)).status).toBe(404)
    await db(`UPDATE materials SET storage_key = '${key}' WHERE id = ${id}`)
    await submitFromUi(admin, name, '원본 확인 후 재공개', '다시 공개')
    await expect(admin.page.getByText('콘텐츠를 다시 공개했어요.', { exact: true })).toBeVisible()
    expect((await api(teacher, fileUrl)).body).toBe(content)
    expect((await api(teacher, reusable)).body.map((row) => row.id)).toContain(id)
  })
})
