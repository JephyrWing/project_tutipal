import { authFailure } from './authFailure.js'

export const initialAuthState = () => ({ me: undefined, authError: null, logoutError: null, loggingOut: false, sessionExpired: false })

/** Owns client auth state. Late responses cannot revive a signed-out account. */
export function createAuthSession({ api, onChange, onBoundary = () => {} }) {
  let state = initialAuthState(), generation = 0, disposed = false
  const publish = patch => {
    if (disposed) return
    state = { ...state, ...patch }
    onChange(state)
  }
  const boundary = () => { onBoundary(); return ++generation }
  const current = attempt => !disposed && attempt === generation
  return {
    async refresh() {
      if (disposed || state.loggingOut) return undefined
      const attempt = boundary()
      try {
        const me = await api.me()
        if (!current(attempt)) return undefined
        publish({ me, authError: null, logoutError: null, sessionExpired: me ? false : state.sessionExpired })
        return me
      } catch (error) {
        if (current(attempt)) publish({ me: null, authError: authFailure(error), sessionExpired: false })
        return undefined
      }
    },
    expire() {
      if (disposed || !state.me) return
      boundary()
      publish({ me: null, authError: null, logoutError: null, loggingOut: false, sessionExpired: true })
    },
    async logout() {
      if (disposed || state.loggingOut) return false
      const attempt = boundary()
      publish({ loggingOut: true, logoutError: null })
      try {
        await api.logout()
        if (!current(attempt)) return false
        publish({ me: null, authError: null, logoutError: null, sessionExpired: false })
        return true
      } catch (error) {
        if (error?.status === 401) {
          if (current(attempt)) publish({ me: null, authError: null, logoutError: null, sessionExpired: false })
          return !disposed
        }
        if (current(attempt)) publish({ logoutError: '로그아웃을 완료하지 못했어요. ' + authFailure(error, '잠시 후 다시 시도해 주세요') })
        return false
      } finally {
        if (current(attempt)) publish({ loggingOut: false })
      }
    },
    async updateName(name) {
      if (disposed || !state.me || state.loggingOut) throw new Error('로그인 상태를 다시 확인해 주세요')
      const attempt = generation, userId = state.me.id
      const updated = await api.updateName(name)
      if (current(attempt) && state.me?.id === userId && updated.id === userId) publish({ me: updated })
      return updated
    },
    dispose() { disposed = true; generation++ },
  }
}
