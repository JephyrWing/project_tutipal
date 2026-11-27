import { createContext, useCallback, useContext, useEffect, useRef, useState } from 'react'
import { Navigate, Outlet } from 'react-router-dom'
import { advanceAuthGeneration, api, AUTH_EXPIRED_EVENT } from './api/client'
import { createAuthSession, initialAuthState } from './utils/authSession'

const AuthContext = createContext(null)
const stopAnalysis = () => { void window.desktop?.stopScreenAnalysis?.().catch(() => undefined) }

export function AuthProvider({ children }) {
  const [state, setState] = useState(initialAuthState)
  const session = useRef(null)
  const refresh = useCallback(() => session.current?.refresh(), [])
  const logout = useCallback(() => { stopAnalysis(); return session.current?.logout() }, [])
  const updateName = useCallback(name => session.current?.updateName(name), [])
  const previousAccount = useRef(null)
  useEffect(() => {
    const current = state.me?.id ?? null
    if (previousAccount.current !== null && previousAccount.current !== current) stopAnalysis()
    previousAccount.current = current
  }, [state.me?.id])

  useEffect(() => {
    const control = createAuthSession({ api, onChange: setState, onBoundary: advanceAuthGeneration })
    session.current = control
    const expired = () => control.expire()
    window.addEventListener(AUTH_EXPIRED_EVENT, expired)
    void control.refresh()
    return () => {
      window.removeEventListener(AUTH_EXPIRED_EVENT, expired)
      control.dispose()
      if (session.current === control) session.current = null
    }
  }, [])

  return <AuthContext.Provider value={{ ...state, refresh, logout, updateName }}>{children}</AuthContext.Provider>
}

export function useAuth() {
  const auth = useContext(AuthContext)
  if (!auth) throw new Error('AuthProvider 밖에서 useAuth 사용')
  return auth
}

/** 로그인해야 들어갈 수 있는 화면들을 감싼다 */
export function RequireAuth() {
  const { me, authError, refresh } = useAuth()
  const [retrying, setRetrying] = useState(false)
  async function retry() {
    setRetrying(true)
    try { await refresh() } finally { setRetrying(false) }
  }
  if (authError) return <div className="center-page"><section className="card login-card">
    <h1>연결을 확인해 주세요</h1><p role="alert" className="error-text">{authError}</p>
    <button className="btn btn-primary" disabled={retrying} onClick={() => void retry()}>{retrying ? '연결 확인 중…' : '다시 연결'}</button>
  </section></div>
  if (me === undefined) return <div className="center-page muted">불러오는 중…</div>
  if (me === null) return <Navigate to="/login" replace />
  return <Outlet />
}
