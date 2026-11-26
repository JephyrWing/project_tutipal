/**
 * 서버가 4xx/5xx 를 보내면 던지는 오류 (GlobalExceptionHandler 의 {status, message}).
 * 429(AI 사용 한도)일 때만 retryAt · retryAfterMs · feature 를 채운다. 그 밖의 상태(503 + Retry-After 포함)는 전부 null.
 */
export class ApiRequestError extends Error {
  status
  /** 다시 쓸 수 있는 시각 (서버 시계 기준, 화면에 HH:mm 으로 보여 줄 때만 쓴다). @type {Date | null} */
  retryAt
  /**
   * 응답을 받은 순간부터 기다릴 시간(ms). 잠금 · 일시 정지는 이 값으로 로컬 시계 기준으로 잰다
   * (PC 시계가 서버와 어긋나도 너무 일찍 다시 부르거나 너무 오래 막지 않게). @type {number | null}
   */
  retryAfterMs
  /** @type {import('./types').AiFeature | null} */
  feature

  constructor(error) {
    super(error.message)
    this.status = error.status
    this.retryAt = error.retryAt ?? null
    this.retryAfterMs = error.retryAfterMs ?? null
    this.feature = error.feature ?? null
  }
}

const validDate = (date) => (date instanceof Date && !Number.isNaN(date.getTime()) ? date : null)

/**
 * 429 의 다시 시도 정보. 기다릴 시간은 본문 retryAfterSeconds → Retry-After 헤더(초) 순서,
 * 표시용 시각은 본문 retryAt → 받은 시각 + 기다릴 시간 → Retry-After(HTTP 날짜) 순서.
 */
function retryOf(res, body, receivedAt) {
  const header = res.headers?.get?.('Retry-After')?.trim() || null
  const headerSeconds = header !== null && /^\d+$/.test(header) ? Number(header) : null
  const bodySeconds = Number.isFinite(body?.retryAfterSeconds) && body.retryAfterSeconds >= 0 ? body.retryAfterSeconds : null
  const seconds = bodySeconds ?? headerSeconds
  const retryAt = validDate(typeof body?.retryAt === 'string' ? new Date(body.retryAt) : null)
    ?? (seconds !== null ? new Date(receivedAt + seconds * 1000) : null)
    ?? validDate(header !== null ? new Date(header) : null)
  const retryAfterMs = seconds !== null ? seconds * 1000
    : retryAt ? Math.max(0, retryAt.getTime() - receivedAt) : null
  return { retryAt, retryAfterMs }
}

function errorFrom(res, body) {
  const quota = res.status === 429
  const { retryAt, retryAfterMs } = quota ? retryOf(res, body, Date.now()) : { retryAt: null, retryAfterMs: null }
  return new ApiRequestError({
    status: res.status,
    message: body?.message ?? res.statusText,
    retryAt,
    retryAfterMs,
    feature: quota && typeof body?.feature === 'string' ? body.feature : null,
  })
}

// 세션 쿠키는 Electron의 Spring 쿠키 저장소에만 둔다. CSRF 값만 서버에서 읽는다.
let csrf = null
let authGeneration = 0
export const AUTH_EXPIRED_EVENT = 'tutipal-auth-expired'
export const AUTH_REQUEST_TIMEOUT_MS = 10000
export function advanceAuthGeneration() { authGeneration++; csrf = null }
function requireAuthGeneration(generation) {
  if (generation !== authGeneration) {
    const error = new Error('로그인 상태가 바뀌었어요. 다시 시도해 주세요')
    error.name = 'AuthContextChangedError'
    throw error
  }
}
export async function csrfHeader(signal, generation = authGeneration) {
  if (!csrf) {
    const response = await fetch('/api/auth/csrf', { signal })
    if (!response.ok) throw await toApiError(response, generation)
    const value = await response.json()
    requireAuthGeneration(generation)
    csrf = { [value.headerName]: value.token }
  }
  return csrf
}

export async function request(path, init = {}) {
  const generation = authGeneration
  const method = (init.method ?? 'GET').toUpperCase()
  const headers = new Headers(init.headers)
  if (method !== 'GET') {
    for (const [k, v] of Object.entries(await csrfHeader(init.signal, generation))) headers.set(k, v)
    requireAuthGeneration(generation)
  }
  let res = await fetch(path, { ...init, headers })
  // 다른 앱 창에서 로그인/로그아웃하면 기존 CSRF 값이 회전한다. 거절된 요청만 새 값으로 한 번 다시 보낸다.
  if (res.status === 403 && generation === authGeneration && !['GET', 'HEAD'].includes(method)) {
    csrf = null
    for (const [name, value] of Object.entries(await csrfHeader(init.signal, generation))) headers.set(name, value)
    requireAuthGeneration(generation)
    res = await fetch(path, { ...init, headers })
  }
  if (!res.ok) {
    throw await toApiError(res, generation)
  }
  if (path === '/logout' || path.startsWith('/dev/login')) csrf = null
  if (res.status === 204 || res.headers.get('content-length') === '0') return undefined
  const text = await res.text()
  return text ? JSON.parse(text) : undefined
}

export const api = {
  /** 로그인 안 했으면 null */
  async me() {
    try {
      return await request('/api/users/me', { signal: AbortSignal.timeout(AUTH_REQUEST_TIMEOUT_MS) })
    } catch (e) {
      if (e instanceof ApiRequestError && e.status === 401) return null
      throw e
    }
  },

  logout: () => request('/logout', { method: 'POST', signal: AbortSignal.timeout(AUTH_REQUEST_TIMEOUT_MS) }),

  updateName: (name) => request('/api/users/me/name', {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name }),
  }),

  /** 회원 탈퇴: 서버가 계정을 익명화하고(기록은 보존) 로그인 세션을 끊는다 (CSRF 값도 바뀌므로 비움) */
  async withdraw() {
    await request('/api/users/me', { method: 'DELETE' })
    csrf = null
  },

  /** 로컬 테스트용 로그인 (서버에서 app.dev-login=true 일 때만 동작, 아니면 404) */
  /** n = 계정 번호 1~9 (선생님 · 학생을 따로 시험할 때) */
  devLogin: (n = 1) => request(`/dev/login?n=${n}`, { method: 'POST', signal: AbortSignal.timeout(AUTH_REQUEST_TIMEOUT_MS) }),
}

/** 서버 오류 본문({status, message}, 429 는 ProblemDetail)을 ApiRequestError 로 */
export async function toApiError(res, generation = null) {
  if (res.status === 401 && generation !== null && generation === authGeneration) {
    csrf = null
    if (typeof window !== 'undefined') window.dispatchEvent(new Event(AUTH_EXPIRED_EVENT))
  }
  const body = await res.json().catch(() => null)
  return errorFrom(res, body)
}

/** 한도 초과(429)로 retryAt 이 있는 오류인지. */
export function isQuotaError(error) {
  return error instanceof ApiRequestError && error.status === 429 && error.retryAt instanceof Date
}

/** 다시 시도할 시각을 사용자 시간대의 HH:mm 으로. */
export function formatRetryTime(date) {
  return date.toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit', hour12: false })
}
