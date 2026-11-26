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

// PM decision 2026-11-26: stored rows may be used for the rule-match evaluation (WBS 134), never for retraining
test('the consent text includes the rule-match evaluation purpose and says no retraining, grading or attendance', () => {
  assert.ok(CONSENT_TEXT.includes('규칙 일치도'))
  assert.match(CONSENT_TEXT, /재학습에는 쓰지 않/)
  assert.match(CONSENT_TEXT, /성적이나 출결에도 쓰지 않/)
  assert.ok(CONSENT_TEXT.includes('30일'))
  assert.ok(CONSENT_TEXT.includes('철회'))
  assert.equal(affirmsForbiddenUse(CONSENT_TEXT), false)
})

/**
 * True when retraining, model training, grades or attendance appear with an affirmative use verb in the same
 * clause ("재학습에도 사용해요", "성적에 써요", "출결에 쓸 수 있어요"). Negations ("쓰지 않아요", "사용하지 않아요")
 * contain none of these verb forms, so they pass.
 */
function affirmsForbiddenUse(text) {
  const forbidden = /(재학습|모델 학습|성적|출결)[^.,!?\n]{0,15}?(써요|쓰여요|씁니다|쓰입니다|쓸 수 있|사용해요|사용합니다|사용돼요|사용됩니다|사용할 수 있|활용해요|활용합니다|활용돼요|활용할 수 있|반영해요|반영합니다|반영돼요|반영됩니다|이용해요|이용합니다|이용할 수 있)/
  return forbidden.test(text)
}

test('the forbidden-use check catches affirmative sentences and lets negations through', () => {
  for (const bad of ['모델 재학습에도 사용해요.', '모델 재학습에 써요.', '기록은 모델 학습에 활용합니다.',
    '성적에 반영돼요.', '출결에도 쓸 수 있어요.', '재학습에 이용해요', '성적 산정에 쓰여요.']) {
    assert.equal(affirmsForbiddenUse(bad), true, bad)
  }
  for (const ok of ['모델 재학습에는 쓰지 않아요.', '성적이나 출결에도 쓰지 않아요.', '재학습에 사용하지 않아요.',
    '모델 재학습에는 쓰지 않고, 성적이나 출결에도 쓰지 않아요.', '규칙 일치도 평가에도 쓸 수 있어요.']) {
    assert.equal(affirmsForbiddenUse(ok), false, ok)
  }
})

// PM decision 2026-11-26: "evaluation" in the consent means checking the feature (rule match); the notice forbids
// judging students. The two texts must not contradict each other.
test('the notice forbids judging students while the consent allows only evaluating the feature', () => {
  assert.ok(FOCUS_NOTICE.includes('학생을 평가하거나'))
  assert.match(FOCUS_NOTICE, /성적 · 출결을 판단하는 데 쓰지 말아/)
  assert.doesNotMatch(FOCUS_NOTICE, /평가나 성적 판단/)
  assert.ok(CONSENT_TEXT.includes('판정 기능 자체'))
  assert.ok(CONSENT_TEXT.includes('기능 평가(규칙 일치도)'))
  assert.match(CONSENT_TEXT, /나를 평가하는 데는 쓰지 않/)
  assert.equal(affirmsForbiddenUse(FOCUS_NOTICE), false)
})
