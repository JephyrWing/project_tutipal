import { test } from 'node:test'
import assert from 'node:assert/strict'
import { HELP_MESSAGE_MAX, helpDraftOf } from '../src/utils/helpDraft.js'

const DRAFT = '[AI 조교 요약] 수업 주제: 파이썬 · 질문 종류: 코드 오류 · 받은 힌트: 3단계까지 · 코드 오류가 계속 해결되지 않아요.'

test('help draft is read only from a help-suggesting agent event', () => {
  assert.equal(helpDraftOf({ suggestHelp: true, helpDraft: `  ${DRAFT}\n` }), DRAFT)
  assert.equal(helpDraftOf({ suggestHelp: false, helpDraft: DRAFT }), null)
  assert.equal(helpDraftOf({ suggestHelp: 'true', helpDraft: DRAFT }), null)
  assert.equal(helpDraftOf({ suggestHelp: true, helpDraft: null }), null)
  assert.equal(helpDraftOf({ suggestHelp: true, helpDraft: 123 }), null)
  assert.equal(helpDraftOf({ suggestHelp: true, helpDraft: '   ' }), null)
  assert.equal(helpDraftOf({ suggestHelp: true }), null)
  assert.equal(helpDraftOf(null), null)
  assert.equal(helpDraftOf(undefined), null)
})

test('help draft is cut to the help message limit without splitting characters', () => {
  assert.equal(HELP_MESSAGE_MAX, 300)
  const long = '가'.repeat(299) + '😀😀'
  const cut = helpDraftOf({ suggestHelp: true, helpDraft: long })
  assert.equal(Array.from(cut).length, 300)
  assert.ok(cut.endsWith('😀'))
})
