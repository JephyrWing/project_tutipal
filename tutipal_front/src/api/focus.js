import { request } from './client'

const json = (method, body) => ({
  method,
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify(body),
})

/**
 * PT-90 집중도 체크 (FocusController · FocusConsentController). 수집 값은 화면이 아니라 데스크톱 앱 메인 프로세스가
 * 직접 보낸다. 화면은 동의 · 상태 조회만 한다.
 */
export const focusApi = {
  /** { consented, version, consentedAt, currentVersion } */
  consent: () => request('/api/focus/consent'),
  giveConsent: (version) => request('/api/focus/consent', json('PUT', { version })),
  /** 동의 철회: 서버가 내 집중도 기록 · 상태를 바로 지운다 (204) */
  withdrawConsent: () => request('/api/focus/consent', { method: 'DELETE' }),
  /** 학생 본인 상태 { state, updatedAt } */
  me: (sessionId) => request(`/api/sessions/${sessionId}/focus/me`),
  /** 선생님: 학생별 [{ userId, state, updatedAt }] */
  list: (sessionId) => request(`/api/sessions/${sessionId}/focus`),
}
