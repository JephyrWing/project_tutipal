import { request } from './client'

export const moderation = {
  list(kind, before, signal) {
    const params = new URLSearchParams({ kind })
    if (before !== null) params.set('before', String(before))
    return request(`/api/admin/content?${params}`, { signal })
  },
  history: (kind, id, signal) => request(`/api/admin/content/${kind}/${id}/history`, { signal }),
  setVisibility: (kind, id, hidden, version, reason) =>
    request(`/api/admin/content/${kind}/${id}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ hidden, version, reason }),
    }),
}
