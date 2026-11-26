import { useCallback, useEffect, useRef, useState } from 'react'
import { focusApi } from '../api/focus'
import { canWithdraw, consentSummary, withdrawFocusConsent } from '../focus/focusConsent'
import { FOCUS_NOTICE, consentText, focusLabel, staleAfterMs, studentFocusView } from '../focus/focusLabels'

const REFRESH_MS = 60_000
const RELOAD_DEBOUNCE_MS = 1_000
const STALE_CHECK_MS = 15_000
const WITHDRAW_CONFIRM = '동의를 철회하면 수집이 멈추고 저장된 내 집중도 기록이 바로 지워져요. 철회할까요?'
const STOP_MESSAGES = {
  server: '서버가 수집을 멈췄어요 (동의 · 참여 상태를 확인해 주세요)',
  'max-duration': '3시간이 지나 수집을 멈췄어요',
  suspend: '절전으로 수집을 멈췄어요',
  'lock-screen': '화면 잠금으로 수집을 멈췄어요',
  cookie: '로그인 상태가 바뀌어 수집을 멈췄어요',
  probe: '앱 정보를 읽지 못해 수집을 멈췄어요',
}

/** PT-90 상태 배지 (6종). 규칙 일치도 기반 참고 상태라는 안내를 title 로 함께 보여 준다. */
export function FocusBadge({ state }) {
  const { label, className } = focusLabel(state)
  return <span className={className} title={FOCUS_NOTICE}>{label}</span>
}

export function FocusNotice() {
  return <p className="muted small">집중도: {FOCUS_NOTICE}</p>
}

/** 선생님: 학생별 상태 (userId → { state, updatedAt }). 실시간 알림(reloadKey)과 1분 주기로 다시 읽는다. */
export function useFocusStates(sessionId, reloadKey) {
  const [states, setStates] = useState({})
  const latest = useRef(0)
  const load = useCallback(() => {
    // only the newest request may write: an older, slower answer must not overwrite a newer one
    const request = ++latest.current
    focusApi.list(sessionId).then(
      (list) => { if (request === latest.current) setStates(Object.fromEntries(list.map((s) => [s.userId, s]))) },
      () => undefined,
    )
  }, [sessionId])
  useEffect(load, [load])
  // N students each announce once a minute: one trailing read per burst instead of one GET per event
  useEffect(() => {
    if (!reloadKey) return undefined
    const timer = setTimeout(load, RELOAD_DEBOUNCE_MS)
    return () => clearTimeout(timer)
  }, [load, reloadKey])
  useEffect(() => {
    const timer = setInterval(load, REFRESH_MS)
    return () => clearInterval(timer)
  }, [load])
  return states
}

/**
 * 학생: 집중도 수집 시작 · 중지, 첫 시작 때 동의, 본인 상태, 동의 철회. 데스크톱 앱에서만 보인다.
 * 화면을 떠나면(방 나가기 · 내보내짐 · 수업 종료 · 다른 화면) 수집을 멈춘다.
 */
export function StudentFocusCard({ sessionId }) {
  const desktop = typeof window !== 'undefined' ? window.desktop : undefined
  const supported = typeof desktop?.focusStart === 'function'
  const [collector, setCollector] = useState({ status: 'off' })
  const [consent, setConsent] = useState(null)
  const [asking, setAsking] = useState(false)
  const [message, setMessage] = useState('')
  const [busy, setBusy] = useState(false)
  const mounted = useRef(true)

  useEffect(() => {
    if (!supported) return undefined
    mounted.current = true
    desktop.focusState().then((s) => { if (mounted.current && s) setCollector(s) }, () => undefined)
    const unsubscribe = desktop.onFocusState((s) => { if (mounted.current) setCollector(s) })
    focusApi.consent().then((c) => { if (mounted.current) setConsent(c) }, () => undefined)
    return () => {
      mounted.current = false
      unsubscribe()
      void desktop.focusStop().catch(() => undefined)
    }
  }, [desktop, supported, sessionId])

  // re-check the age of the last answer while collecting (sends that keep failing must not show an old state)
  const [now, setNow] = useState(() => Date.now())
  const ticking = supported && collector.status === 'collecting'
  useEffect(() => {
    if (!ticking) return undefined
    const timer = setInterval(() => setNow(Date.now()), STALE_CHECK_MS)
    return () => clearInterval(timer)
  }, [ticking])

  if (!supported) return null

  const collecting = collector.status === 'collecting' && collector.sessionId === sessionId
  const consented = consent?.consented === true

  async function begin() {
    setMessage('')
    const state = await desktop.focusStart(sessionId)
    if (state?.status === 'unsupported') setMessage('이 PC에서는 집중도 체크를 쓸 수 없어요 (Windows 앱 전용)')
    if (state) setCollector(state)
  }

  async function agreeAndStart() {
    setBusy(true)
    try {
      setConsent(await focusApi.giveConsent(consent?.currentVersion))
      setAsking(false)
      await begin()
    } catch (e) {
      setMessage(e instanceof Error ? e.message : '동의를 저장하지 못했어요')
    } finally {
      setBusy(false)
    }
  }

  async function withdraw() {
    if (!window.confirm(WITHDRAW_CONFIRM)) return
    setBusy(true)
    try {
      setConsent(await withdrawFocusConsent({ api: focusApi, desktop }))
      setMessage('동의를 철회하고 기록을 지웠어요')
    } catch (e) {
      setMessage(e instanceof Error ? e.message : '철회하지 못했어요')
    } finally {
      setBusy(false)
    }
  }

  const view = studentFocusView(collector, now, staleAfterMs(consent))
  const stopMessage = !collecting && collector.reason ? STOP_MESSAGES[collector.reason] : ''

  return (
    <section className="card session-body focus-card" aria-label="집중도 체크">
      <div className="row-between">
        <strong>집중도 체크</strong>
        {collecting ? (
          <span className="row-gap">
            <span className="badge badge-live"><span className="dot" /> 수집 중</span>
            <FocusBadge state={view.state} />
            {view.stale && <span className="muted small">연결 확인 중</span>}
            <button className="btn btn-sm" onClick={() => void desktop.focusStop()}>중지</button>
          </span>
        ) : (
          <button className="btn btn-sm btn-primary" disabled={busy || consent === null}
            onClick={() => (consented ? void begin() : setAsking(true))}>
            시작
          </button>
        )}
      </div>
      <FocusNotice />
      {(message || stopMessage) && <p className="muted small" aria-live="polite">{message || stopMessage}</p>}
      {canWithdraw(consent) && <button className="btn-link small" disabled={busy} onClick={() => void withdraw()}>동의 철회</button>}
      {asking && (
        <div className="modal-backdrop" role="dialog" aria-modal="true" aria-label="집중도 기록 저장 동의">
          <div className="card modal">
            <strong>집중도 기록 저장 동의</strong>
            <p style={{ whiteSpace: 'pre-line' }}>{consentText(consent ?? undefined)}</p>
            <div className="row-gap">
              <button className="btn btn-primary" disabled={busy} onClick={() => void agreeAndStart()}>동의하고 시작</button>
              <button className="btn" disabled={busy} onClick={() => setAsking(false)}>취소</button>
            </div>
          </div>
        </div>
      )}
    </section>
  )
}

/**
 * 설정 화면: 수업과 상관없이(브라우저 · 수업이 끝난 뒤 · 다른 PC) 집중도 저장 동의를 확인하고 철회한다.
 * 철회하면 서버가 내 집중도 기록 · 상태 · 동의를 바로 지운다. 데스크톱 앱이면 이 PC 의 수집도 먼저 멈춘다.
 */
export function FocusConsentSettings() {
  const [consent, setConsent] = useState(null)
  const [error, setError] = useState('')
  const [message, setMessage] = useState('')
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    let active = true
    focusApi.consent().then((c) => { if (active) setConsent(c) }, (e) => {
      if (active) setError(e instanceof Error ? e.message : '동의 상태를 불러오지 못했어요')
    })
    return () => { active = false }
  }, [])

  async function withdraw() {
    if (busy || !window.confirm(WITHDRAW_CONFIRM)) return
    setBusy(true)
    setError('')
    setMessage('')
    try {
      setConsent(await withdrawFocusConsent({ api: focusApi, desktop: window.desktop }))
      setMessage('동의를 철회하고 저장된 집중도 기록을 지웠어요')
    } catch (e) {
      setError(e instanceof Error ? e.message : '철회하지 못했어요. 잠시 뒤 다시 해 주세요')
    } finally {
      setBusy(false)
    }
  }

  return (
    <>
      <div className="setting-card">
        <span>
          <strong>{error && !consent ? '동의 상태를 확인하지 못했어요' : consentSummary(consent)}</strong>
          <span className="muted small">수집 시작은 수업 화면(데스크톱 앱)에서 해요. {FOCUS_NOTICE}</span>
        </span>
      </div>
      <details className="muted small">
        <summary>동의 내용 보기</summary>
        <p style={{ whiteSpace: 'pre-line' }}>{consentText(consent ?? undefined)}</p>
      </details>
      {canWithdraw(consent) && (
        <div className="settings-danger">
          <button className="btn btn-outline-danger" disabled={busy} onClick={() => void withdraw()}>
            동의 철회하고 기록 지우기
          </button>
        </div>
      )}
      {message && <p className="muted small" role="status">{message}</p>}
      {error && <p className="error-text" role="alert">{error}</p>}
    </>
  )
}
