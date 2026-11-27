import { request } from './client'

const json = (body) => ({
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify(body),
})

/** StudySessionController (/api/sessions) */
export const sessions = {
  /** aiRetentionDays: 수업 AI 대화 보관 일수 (수업 종료 뒤 0..90, 필수) */
  create: (title, topic, aiRetentionDays) => request('/api/sessions', json({ title, topic, aiRetentionDays })),
  /** 선생님만: 수업 AI 대화 보관 일수 변경 (끝난 수업도 가능) */
  updateAiRetention: (id, aiRetentionDays) =>
    request(`/api/sessions/${id}/ai-retention`, { ...json({ aiRetentionDays }), method: 'PUT' }),
  join: (code) => request('/api/sessions/join', json({ code })),
  mine: () => request('/api/sessions'),
  endedMine: () => request('/api/sessions/ended'),
  get: (id) => request(`/api/sessions/${id}`),
  teacherResults: (id) => request(`/api/sessions/${id}/learning-results`),
  participants: (id) => request(`/api/sessions/${id}/participants`),
  updateSettings: (id, settings) =>
    request(`/api/sessions/${id}/settings`, { ...json(settings), method: 'PUT' }),
  /** 홈 목록 즐겨찾기 (204) */
  pin: (id, pinned) => request(`/api/sessions/${id}/pin`, { ...json({ pinned }), method: 'PUT' }),
  end: (id) => request(`/api/sessions/${id}/end`, { method: 'POST' }),
  /** 학생이 방에서 나가기 (204, 선생님은 403) */
  leave: (id) => request(`/api/sessions/${id}/participants/me`, { method: 'DELETE' }),
  /** 선생님이 학생 내보내기 (204) */
  kick: (id, userId) => request(`/api/sessions/${id}/participants/${userId}`, { method: 'DELETE' }),
}
