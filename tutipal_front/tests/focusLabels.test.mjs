// PT-90 AC-21 (text parts): state badges for the six states, the notice that this is a rule-based reference
// state (not accuracy), and the consent text (purpose, items, 30 days, withdrawal). Screens are checked by hand.
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { CONSENT_TEXT, FOCUS_NOTICE, FOCUS_STATES, focusLabel } from '../src/focus/focusLabels.js'

const STATES = ['FOCUS', 'DISTRACTED', 'BREAK', 'UNKNOWN', 'PENDING', 'NOT_PARTICIPATING']

test('FOCUS_STATES lists the six server states', () => {
  assert.deepEqual([...FOCUS_STATES].sort(), [...STATES].sort())
})

test('every state has its own non-empty label and class name', () => {
  const labels = new Set()
  const classes = new Set()
  for (const state of STATES) {
    const { label, className } = focusLabel(state)
    assert.equal(typeof label, 'string', state)
    assert.ok(label.trim().length > 0, state)
    assert.equal(typeof className, 'string', state)
    assert.ok(className.trim().length > 0, state)
    labels.add(label)
    classes.add(className)
  }
  assert.equal(labels.size, 6)
  assert.equal(classes.size, 6)
})

test('an unexpected value falls back to the UNKNOWN badge', () => {
  for (const value of ['focus', 'SLEEPING', '', null, undefined, 3]) {
    assert.deepEqual(focusLabel(value), focusLabel('UNKNOWN'), String(value))
  }
})

test('the notice says it is a rule-match reference state, not accuracy', () => {
  assert.ok(FOCUS_NOTICE.includes('규칙 일치도 기반 참고 상태(정확도 아님)'))
})

test('the consent text covers purpose, collected items, 30-day retention and withdrawal', () => {
  assert.equal(typeof CONSENT_TEXT, 'string')
  assert.ok(CONSENT_TEXT.includes('30일'))
  assert.ok(CONSENT_TEXT.includes('철회'))
  assert.match(CONSENT_TEXT, /목적/)
  assert.match(CONSENT_TEXT, /실행 파일|앱 이름|프로그램 이름/)
  assert.match(CONSENT_TEXT, /입력 없는 시간|유휴|자리 비움/)
  assert.match(CONSENT_TEXT, /창 전환/)
})

test('the consent text does not list grading or model retraining as a purpose', () => {
  assert.doesNotMatch(CONSENT_TEXT, /(평가|성적|재학습|모델 학습)(에|을|를)?\s*(사용|활용|반영)(해요|합니다|됩니다|돼요)/)
})
