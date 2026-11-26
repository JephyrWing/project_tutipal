import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { confusionsView } from '../src/api/insights.js'

// PT-87 Round 1 (F33, F1): defensive mapping of the confusions response and the ended-session teacher path.

const base = { status: 'OK', reason: null, questionCount: 22, studentCount: 6, cached: false, topics: [] }

test('unknown status is shown as unavailable, not as a weak trend', () => {
  const view = confusionsView({ ...base, status: 'SOMETHING_NEW', topics: [{ rank: 1, label: 'x', studentCount: 3 }] })
  assert.equal(view.state, 'unavailable')
  assert.match(view.message, /잠시 후 다시/)
  assert.deepEqual(view.topics, [])
})

test('missing, duplicated or string ranks are renumbered 1..3 in order', () => {
  const view = confusionsView({ ...base, topics: [
    { rank: 2, label: 'b', conceptId: 2, questionCount: 4, studentCount: 3 },
    { label: 'none', conceptId: null, questionCount: 3, studentCount: 3 },
    { rank: 2, label: 'dup', conceptId: 3, questionCount: 3, studentCount: 3 },
    { rank: '1', label: 'text', conceptId: 4, questionCount: 3, studentCount: 3 },
  ] })
  assert.deepEqual(view.topics.map((t) => t.rank), [1, 2, 3])
  assert.equal(new Set(view.topics.map((t) => t.rank)).size, 3)
})

test('topic list items are not keyed by the server rank', () => {
  const panels = readFileSync(new URL('../src/pages/InsightsPanels.jsx', import.meta.url), 'utf8')
  assert.ok(!panels.includes('key={topic.rank}'))
})

test('a teacher of an ended session still gets the insights panel', () => {
  const page = readFileSync(new URL('../src/pages/SessionPage.jsx', import.meta.url), 'utf8')
  assert.match(page, /session\.ended[\s\S]*myRole === 'TEACHER'[\s\S]*EndedTeacherView/)
  assert.match(page, /function EndedTeacherView[\s\S]*<InsightsTab/)
})
