/** 도움 요청 메시지 상한 (POST /api/sessions/{id}/help, 서버 검증과 같다). */
export const HELP_MESSAGE_MAX = 300

/**
 * SSE agent 이벤트에서 도움 요청 메시지 초안을 꺼낸다 (PT-113).
 * 도움 요청을 추천한 턴(suggestHelp === true)이고 초안이 비어 있지 않은 문자열일 때만, 다듬어서 300자로 자른다.
 * 그 밖에는 null — 화면은 버튼을 보여 주지 않는다. 이 값은 학생이 직접 보내기 전까지 서버로 가지 않는다.
 */
export function helpDraftOf(event) {
  if (!event || event.suggestHelp !== true || typeof event.helpDraft !== 'string') return null
  const text = event.helpDraft.trim()
  if (!text) return null
  return Array.from(text).slice(0, HELP_MESSAGE_MAX).join('')
}
