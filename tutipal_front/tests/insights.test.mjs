import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { createInsightsApi, confusionsView, masteryView } from '../src/api/insights.js'

// PT-87 AC-21: api/insights.js request paths, response mapping and state branches.
// The request function is injected (same style as helpChat.test / createHelpChatTransport), so no network is used.

const okBody = {
  status: 'OK', reason: null, questionCount: 22, studentCount: 6, algorithm: 'kmeans-silhouette-v2', k: 5,
  silhouette: 0.42, cached: false,
  topics: [
    { rank: 2, label: '태그되지 않은 주제 1', conceptId: null, questionCount: 4, studentCount: 4 },
    { rank: 1, label: '반복문', conceptId: 200, questionCount: 9, studentCount: 5, userIds: [21, 22] },
    { rank: 3, label: '변수', conceptId: 100, questionCount: 4, studentCount: 3 },
  ],
}

function recorder(reply = async () => ({})) {
  const calls = []
  const call = async (path, init = {}) => { calls.push({ path, init }); return reply(path, init) }
  return { calls, api: createInsightsApi(call) }
}

const method = (init) => (init?.method ?? 'GET').toUpperCase()

test('creating the api sends nothing; analysis is a POST only when analyzeConfusions is called', async () => {
  const { calls, api } = recorder(async () => okBody)
  assert.equal(calls.length, 0)
  await api.analyzeConfusions(7)
  assert.equal(calls.length, 1)
  assert.equal(calls[0].path, '/api/sessions/7/insights/confusions')
  assert.equal(method(calls[0].init), 'POST')
})

test('reads use GET on the documented paths', async () => {
  const { calls, api } = recorder(async (path) => (path.endsWith('/mastery') ? { concepts: [], students: [] } : []))
  await api.concepts(7)
  await api.quizConcepts(7)
  await api.mastery(7)
  assert.deepEqual(calls.map((c) => c.path), [
    '/api/sessions/7/concepts',
    '/api/sessions/7/quiz-concepts',
    '/api/sessions/7/insights/mastery',
  ])
  assert.ok(calls.every((c) => method(c.init) === 'GET'))
})

test('setQuizConcepts PUTs the whole name list as JSON', async () => {
  const { calls, api } = recorder(async () => [])
  await api.setQuizConcepts(77, ['변수', '반복문'], 'AI')
  assert.equal(calls[0].path, '/api/quizzes/77/concepts')
  assert.equal(method(calls[0].init), 'PUT')
  assert.deepEqual(JSON.parse(calls[0].init.body), { names: ['변수', '반복문'], source: 'AI' })

  await api.setQuizConcepts(77, [])
  const body = JSON.parse(calls[1].init.body)
  assert.deepEqual(body.names, [])
  assert.ok(body.source === undefined || body.source === 'TEACHER')
})

test('OK response maps to at most three topics in rank order with only the allowed fields', () => {
  const view = confusionsView(okBody)
  assert.equal(view.state, 'ok')
  assert.equal(view.questionCount, 22)
  assert.equal(view.studentCount, 6)
  assert.equal(view.cached, false)
  assert.deepEqual(view.topics.map((t) => t.rank), [1, 2, 3])
  assert.deepEqual(view.topics[0], { rank: 1, label: '반복문', conceptId: 200, questionCount: 9, studentCount: 5 })
  assert.equal(view.topics[1].conceptId, null)
  assert.ok(view.topics.every((t) => !('userIds' in t)))
})

test('more than three topics from the server are cut to three', () => {
  const extra = { ...okBody, topics: [...okBody.topics, { rank: 4, label: '함수', conceptId: 300, questionCount: 3, studentCount: 3 }] }
  assert.equal(confusionsView(extra).topics.length, 3)
})

test('INSUFFICIENT_DATA says data is not enough and shows no topics', () => {
  const view = confusionsView({ ...okBody, status: 'INSUFFICIENT_DATA', reason: 'FEW_QUESTIONS', topics: [], k: null, silhouette: null })
  assert.equal(view.state, 'insufficient')
  assert.match(view.message, /데이터가 부족해요/)
  assert.equal(view.reason, 'FEW_QUESTIONS')
  assert.deepEqual(view.topics, [])
})

test('LOW_QUALITY says the trend is weak', () => {
  const view = confusionsView({ ...okBody, status: 'LOW_QUALITY' })
  assert.equal(view.state, 'lowQuality')
  assert.match(view.message, /경향이 약해요/)
})

test('OK has no warning message', () => {
  assert.ok(!confusionsView(okBody).message)
})

test('cached results are marked', () => {
  assert.equal(confusionsView({ ...okBody, cached: true }).cached, true)
})

test('503 from the analysis becomes an unavailable state asking to retry later', async () => {
  const { api } = recorder(async () => { throw { status: 503, message: 'AI 조교에 연결할 수 없어요' } })
  const view = await api.analyzeConfusions(7)
  assert.equal(view.state, 'unavailable')
  assert.match(view.message, /잠시 후 다시/)
  assert.deepEqual(view.topics, [])
})

test('403, 404 and 429 are not swallowed', async () => {
  for (const status of [403, 404, 429]) {
    const error = { status, message: 'x' }
    const { api } = recorder(async () => { throw error })
    await assert.rejects(api.analyzeConfusions(7), (e) => e === error)
  }
})

test('mastery cells are aligned to the concept column order and keep null', () => {
  const view = masteryView({
    concepts: [
      { id: 100, name: '변수', quizCount: 2, classAvg: 65, belowHalf: false },
      { id: 300, name: '함수', quizCount: 0, classAvg: null, belowHalf: false },
    ],
    students: [{
      userId: 21, name: '김학생',
      cells: [
        { conceptId: 300, pKnown: null, observations: 0, lowConfidence: true },
        { conceptId: 100, pKnown: 65, observations: 1, lowConfidence: true },
      ],
    }],
  })
  assert.deepEqual(view.concepts.map((c) => c.id), [100, 300])
  assert.deepEqual(view.students[0].cells.map((c) => c.conceptId), [100, 300])
  assert.equal(view.students[0].cells[0].pKnown, 65)
  assert.equal(view.students[0].cells[1].pKnown, null)
  assert.equal(view.students[0].cells[0].lowConfidence, true)
  assert.equal(view.students[0].name, '김학생')
})

test('mastery cell missing from the server is shown as empty, not as zero', () => {
  const view = masteryView({
    concepts: [{ id: 100, name: '변수', quizCount: 1, classAvg: null, belowHalf: false }],
    students: [{ userId: 21, name: '김학생', cells: [] }],
  })
  assert.equal(view.students[0].cells.length, 1)
  assert.equal(view.students[0].cells[0].pKnown, null)
})

test('insights screen renders text only and the teacher view has the tab', () => {
  const panels = readFileSync(new URL('../src/pages/InsightsPanels.jsx', import.meta.url), 'utf8')
  assert.ok(!panels.includes('dangerouslySetInnerHTML'))
  assert.ok(!panels.includes('innerHTML'))
  const sessionPage = readFileSync(new URL('../src/pages/SessionPage.jsx', import.meta.url), 'utf8')
  assert.ok(sessionPage.includes('개념 분석'))
})
