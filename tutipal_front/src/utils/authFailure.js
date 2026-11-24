/** Public messages only; never display a raw provider response or credential. */
export function authFailure(error, fallback = '로그인 상태를 확인하지 못했어요. 다시 시도해 주세요') {
  if (error?.name === 'AuthContextChangedError') return '로그인 상태가 바뀌었어요. 다시 시도해 주세요'
  if (error?.status === 401) return '로그인이 만료됐어요. 다시 로그인해 주세요'
  if (error?.status === 403) return '접근 권한을 확인하지 못했어요. 관리자에게 문의해 주세요'
  if (error?.status === 429) return '요청이 많아요. 잠시 후 다시 시도해 주세요'
  if (error?.status === 503 || error?.status >= 500 && ![502, 504].includes(error.status)) return '서버가 잠시 응답하지 않아요. 잠시 후 다시 시도해 주세요'
  if (error?.name === 'TimeoutError' || error?.name === 'AbortError') return '서버 응답이 늦어지고 있어요. 잠시 후 다시 연결해 주세요'
  if (error?.name === 'TypeError' || [502, 504].includes(error?.status)) return '서버에 연결하지 못했어요. 인터넷 연결을 확인한 뒤 다시 시도해 주세요'
  return fallback
}
