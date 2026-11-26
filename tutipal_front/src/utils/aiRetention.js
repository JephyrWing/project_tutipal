/**
 * PT-106 / PT-13 AI 대화 보관 기간 (PM 결정 2026-11-26, docs/AI_CONVERSATIONS.md "보관 기간").
 * - 수업 대화: 선생님이 수업마다 정한 기간(수업 종료 뒤 0~90일)이 지나면 삭제. 0 = 종료 직후 삭제.
 * - 개인 대화: 마지막 대화 뒤 30일이 지나면 삭제.
 * - "저장"한 대화는 사용자가 지울 때까지 남는다.
 */

/** CreateSessionRequest.aiRetentionDays @Max 와 같게 */
export const AI_RETENTION_MAX_DAYS = 90
/** app.ai.conversations.personal-retention-days 기본값과 같게 (서버 설정을 바꾸면 같이 바꾼다) */
export const PERSONAL_RETENTION_DAYS = 30
/** 방 만들기 · 설정에서 고르는 값. 기본 선택 없음 (선생님이 반드시 고른다) */
export const AI_RETENTION_OPTIONS = [0, 7, 30, 60, 90]

/** select 값(문자열) → 0..90 정수, 고르지 않았거나 범위를 벗어나면 null */
export function parseAiRetentionDays(value) {
  if (value === null || value === undefined || value === '') return null
  const text = String(value).trim()
  if (!/^\d+$/.test(text)) return null
  const days = Number(text)
  return days <= AI_RETENTION_MAX_DAYS ? days : null
}

export function aiRetentionLabel(days) {
  if (days === null || days === undefined) return '정해지지 않음'
  return days === 0 ? '수업 종료 직후 삭제' : `수업 종료 후 ${days}일`
}

/**
 * 대화 목록 · 상세 아래에 보여 줄 안내. sessionId 가 있으면 수업 대화, aiRetentionDays 는 그 수업 설정
 * (모르면 null → 최대 90일 문구).
 */
export function retentionNotice({ sessionId, aiRetentionDays } = {}) {
  if (!sessionId) {
    return `개인 대화는 마지막 대화 후 ${PERSONAL_RETENTION_DAYS}일이 지나면 자동으로 지워져요. 남기고 싶은 대화는 "저장"해 두세요.`
  }
  const when = aiRetentionDays === null || aiRetentionDays === undefined
    ? `선생님이 정한 기간(최대 ${AI_RETENTION_MAX_DAYS}일)이 지나면`
    : aiRetentionDays === 0 ? '수업이 끝나면 바로' : `수업이 끝나고 ${aiRetentionDays}일이 지나면`
  return `수업 대화는 ${when} 자동으로 지워져요. 남기고 싶은 대화는 "저장"해 두세요. 저장해도 이 수업의 기억은 함께 지워져요.`
}

/** 저장 토글 응답({id, saved, savedAt})을 상세 대화 · 목록 항목에 반영 */
export function applySavedState(conversation, state) {
  if (!conversation || !state || String(conversation.id) !== String(state.id)) return conversation
  return { ...conversation, saved: !!state.saved, savedAt: state.savedAt ?? null }
}
