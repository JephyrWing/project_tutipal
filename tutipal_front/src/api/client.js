/** 서버가 4xx/5xx 를 보내면 던지는 오류 (GlobalExceptionHandler 의 {status, message}). */
export class ApiRequestError extends Error {
  status

  constructor(error) {
    super(error.message)
    this.status = error.status
  }
}

// 세션 쿠키는 Electron의 Spring 쿠키 저장소에만 둔다. CSRF 값만 서버에서 읽는다.
let csrf = null
export async function csrfHeader() {
  if (!csrf) {
    const response = await fetch('/api/auth/csrf')
    if (!response.ok) throw await toApiError(response)
    const value = await response.json()
    csrf = { [value.headerName]: value.token }
  }
  return csrf
}

export async function request(path, init = {}) {
  const method = (init.method ?? 'GET').toUpperCase()
  const headers = new Headers(init.headers)
  if (method !== 'GET') {
    for (const [k, v] of Object.entries(await csrfHeader())) headers.set(k, v)
  }
  let res = await fetch(path, { ...init, headers })
  // 다른 앱 창에서 로그인/로그아웃하면 기존 CSRF 값이 회전한다. 거절된 요청만 새 값으로 한 번 다시 보낸다.
  if (res.status === 403 && !['GET', 'HEAD'].includes(method)) {
    csrf = null
    for (const [name, value] of Object.entries(await csrfHeader())) headers.set(name, value)
    res = await fetch(path, { ...init, headers })
  }
  if (!res.ok) {
    const body = await res.json().catch(() => null)
    throw new ApiRequestError({ status: res.status, message: body?.message ?? res.statusText })
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
      return await request('/api/users/me')
    } catch (e) {
      if (e instanceof ApiRequestError && e.status === 401) return null
      throw e
    }
  },

  logout: () => request('/logout', { method: 'POST' }),

  /** 회원 탈퇴: 서버가 기록을 지우고 로그인 세션을 끊는다 (CSRF 값도 바뀌므로 비움) */
  async withdraw() {
    await request('/api/users/me', { method: 'DELETE' })
    csrf = null
  },

  /** 로컬 테스트용 로그인 (서버에서 app.dev-login=true 일 때만 동작, 아니면 404) */
  /** n = 계정 번호 1~9 (선생님 · 학생을 따로 시험할 때) */
  devLogin: (n = 1) => request(`/dev/login?n=${n}`, { method: 'POST' }),
}

/** 서버 오류 본문({status, message})을 ApiRequestError 로 */
export async function toApiError(res) {
  const body = await res.json().catch(() => null)
  return new ApiRequestError({ status: res.status, message: body?.message ?? res.statusText })
}
