// PT-90 집중도 상태 표시 문구 (순수 모듈: 화면과 테스트가 같이 쓴다).

export const FOCUS_STATES = ['FOCUS', 'DISTRACTED', 'BREAK', 'UNKNOWN', 'PENDING', 'NOT_PARTICIPATING']

const LABELS = {
  FOCUS: { label: '집중', className: 'badge badge-ok focus-state-focus' },
  DISTRACTED: { label: '딴짓 가능성', className: 'badge badge-warn focus-state-distracted' },
  BREAK: { label: '자리 비움', className: 'badge badge-gray focus-state-break' },
  UNKNOWN: { label: '알 수 없음', className: 'badge badge-gray focus-state-unknown' },
  PENDING: { label: '판단 중', className: 'badge badge-gray focus-state-pending' },
  NOT_PARTICIPATING: { label: '참여 안 함', className: 'badge badge-gray focus-state-off' },
}

/** 서버 상태 → 배지 문구 · 클래스. 모르는 값은 "알 수 없음". */
export function focusLabel(state) {
  return { ...(Object.hasOwn(LABELS, state) ? LABELS[state] : LABELS.UNKNOWN) }
}

// PM decision 2026-11-26: the rule-match evaluation checks the feature itself; nobody may use the state to judge a student
export const FOCUS_NOTICE = '규칙 일치도 기반 참고 상태(정확도 아님)예요. 학생을 평가하거나 성적 · 출결을 판단하는 데 쓰지 말아 주세요.'

const DEFAULT_RETENTION_DAYS = 30
const DEFAULT_MAX_SAMPLE_AGE_DAYS = 31

const validDays = (value, fallback) => (Number.isSafeInteger(value) && value > 0 ? value : fallback)

/**
 * 동의 문구. 보관 기간은 서버가 실제로 적용하는 값(GET /api/focus/consent 의 retentionDays · maxSampleAgeDays)으로
 * 만든다. 값이 없거나 이상하면 기본값 30 · 31일.
 */
export function consentText({ retentionDays, maxSampleAgeDays } = {}) {
  const retention = validDays(retentionDays, DEFAULT_RETENTION_DAYS)
  const maxAge = validDays(maxSampleAgeDays, DEFAULT_MAX_SAMPLE_AGE_DAYS)
  return [
    '목적: 수업 중 선생님과 내가 지금 수업에 집중하고 있는지 참고 상태로 보기 위해서예요.',
    '저장한 프로그램 이름과 1분 기록은 집중도 판정 기능 자체가 정해 둔 규칙과 얼마나 맞는지 확인하는 기능 평가(규칙 일치도)에도 쓸 수 있어요. 나를 평가하는 데는 쓰지 않아요.',
    '모델 재학습에는 쓰지 않고, 성적이나 출결에도 쓰지 않아요.',
    '모으는 항목: 1분마다 가장 오래 쓴 프로그램의 실행 파일 이름(예: chrome.exe), 입력 없는 시간(초), 창 전환 횟수.',
    '창 제목, 화면 내용, 입력한 글자, 파일 경로는 모으지 않아요. 선생님에게는 프로그램 이름이 아니라 상태(집중 · 자리 비움 등)만 보여요.',
    '수집은 내가 이 수업 화면에서 시작한 동안만 하고, 중지 · 방 나가기 · 수업 종료 · 앱 종료 때 멈춰요.',
    `보관: 수업이 끝나고 ${retention}일 뒤 자동으로 지워져요. 수업이 끝나지 않았더라도 ${maxAge}일이 지난 기록은 지워져요.`,
    '철회: 언제든 동의를 철회할 수 있고(수업 화면 또는 설정 > 집중도 체크), 철회하면 지금까지 저장된 내 기록이 바로 지워져요.',
  ].join('\n')
}

/** 기본 설정값(30 · 31일)으로 만든 문구. */
export const CONSENT_TEXT = consentText()

/** app.focus.stale-after 기본값(3분). 서버 값(동의 응답 staleAfterSeconds)이 없을 때만 쓴다. */
export const FOCUS_STALE_MS = 3 * 60_000

/** 동의 응답의 staleAfterSeconds(서버 app.focus.stale-after) → ms. 없거나 이상하면 180초. */
export function staleAfterMs(consent) {
  const seconds = consent?.staleAfterSeconds
  return Number.isSafeInteger(seconds) && seconds > 0 ? seconds * 1000 : FOCUS_STALE_MS
}
const FIRST_SEND_MS = 60_000

/**
 * 학생 본인 카드의 배지 상태 (collector 상태에서). 마지막 응답(focus.receivedAt)이 FOCUS_STALE_MS 보다 오래됐거나,
 * 시작(startedAt)하고 첫 전송 1분 + FOCUS_STALE_MS 가 지나도록 응답이 없으면(전송이 계속 실패) UNKNOWN 과
 * stale=true 라서 화면이 "연결 확인 중"을 보여 줄 수 있다. 같은 시점에 서버는 선생님에게 NOT_PARTICIPATING 을
 * 보여 준다. 시각은 모두 이 PC 시계(collector 가 붙임). 아직 첫 응답 전이면 PENDING.
 */
export function studentFocusView(collector, nowMs, staleMs = FOCUS_STALE_MS) {
  const focus = collector?.focus
  if (focus && typeof focus.state === 'string') {
    const receivedAt = Number(focus.receivedAt)
    if (!Number.isFinite(receivedAt) || nowMs - receivedAt > staleMs) return { state: 'UNKNOWN', stale: true }
    return { state: focus.state, stale: false }
  }
  const startedAt = Number(collector?.startedAt)
  if (Number.isFinite(startedAt) && nowMs - startedAt > FIRST_SEND_MS + staleMs) return { state: 'UNKNOWN', stale: true }
  return { state: 'PENDING', stale: false }
}
