import { request } from './client'

const json = (method, body) => ({
  method,
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify(body),
})

async function deleteRecords(path) {
  const desktop = window.desktop
  await desktop?.beginReviewDeletion()
  try {
    return await request(path, { method: 'DELETE' })
  } finally {
    await desktop?.endReviewDeletion()
  }
}

/** 로그인한 사용자의 복습 기록 */
export const reviewApi = {
  /** summary 없으면 같은 화면을 계속 보는 중 */
  observe: (summary, seconds) => request('/api/review/observe', json('POST', { summary, seconds })),
  items: () => request('/api/review/items'),
  history: (page = 0, size = 20) => request(`/api/review/history?${new URLSearchParams({ page, size })}`),
  item: (id) => request(`/api/review/items/${id}`),
  deleteAll: () => deleteRecords('/api/review/items'),
  remove: (id) => deleteRecords(`/api/review/items/${id}`),
}

/** 챗봇에 보낼 "설명 듣기" 질문 */
export const explainQuestion = (summary) => `내가 자주 본 이 내용을 처음 배우는 사람에게 설명하듯 자세히 알려줘: ${summary}`
