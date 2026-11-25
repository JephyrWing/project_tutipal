import { request } from './client'

export function learningResults({ page = 0, sessionId = '', verdict = 'ALL' } = {}) {
  const query = new URLSearchParams({ page: String(page), size: '20', verdict })
  if (sessionId) query.set('sessionId', sessionId)
  return request(`/api/learning-results?${query}`)
}
