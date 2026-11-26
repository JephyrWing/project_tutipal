import { useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { api } from '../api/client'
import { reviewApi } from '../api/review'
import { useAuth } from '../auth'
import Icon from '../components/Icon'
import Mascot from '../components/Mascot'
import { FocusConsentSettings } from './FocusPanels'

const TABS = [
  { id: 'screen', icon: 'screen', label: '화면 분석' },
  { id: 'review', icon: 'book', label: '복습 기록' },
  { id: 'focus', icon: 'shield', label: '집중도 체크' },
  { id: 'account', icon: 'user', label: '계정' },
]
const PROVIDER = { kakao: '카카오', naver: '네이버', google: '구글', dev: '개발용' }

/** 목업 05 설정: 화면 분석 시작·중지, 복습 기록 전체 삭제, 집중도 동의 철회(PT-90), 계정, 로그아웃, 회원 탈퇴 */
export default function SettingsPage() {
  const [tab, setTab] = useState('screen')
  return (
    <div className="page">
      <section className="settings-page">
        <nav className="card settings-menu" role="tablist" aria-label="설정 메뉴">
          {TABS.map((t) => (
            <button key={t.id} role="tab" aria-selected={tab === t.id} onClick={() => setTab(t.id)}>
              <Icon name={t.icon} /> {t.label}
            </button>
          ))}
        </nav>
        <div className="card settings-body">
          {tab === 'screen' && <ScreenTab />}
          {tab === 'review' && <ReviewTab />}
          {tab === 'focus' && <FocusTab />}
          {tab === 'account' && <AccountTab />}
        </div>
      </section>
    </div>
  )
}

function Head({ mood, title, text }) {
  return (
    <header className="settings-head">
      <Mascot mood={mood} size={110} />
      <div>
        <h1>{title}</h1>
        <p className="muted">{text}</p>
      </div>
    </header>
  )
}

function ScreenTab() {
  const navigate = useNavigate()
  const desktop = window.desktop
  const [screen, setScreen] = useState(null)

  useEffect(() => {
    if (!desktop) return
    const load = () => desktop.getScreenSettings().then(setScreen, () => undefined)
    load()
    return desktop.onScreenSettingsChanged?.(load)
  }, [desktop])

  // 분석을 시작할 때 창을 고르고, 중지는 현재 상태에 바로 반영한다.
  async function toggleScreen() {
    if (!screen?.enabled) return navigate('/screen-consent')
    setScreen(await desktop.setScreenSettings({ ...screen, enabled: false }))
  }

  return (
    <>
      <Head mood="happy" title="화면 분석" text="고른 창만 보고, 화면 이미지는 저장하지 않아요. 언제든 끌 수 있어요." />
      <label className="setting-card">
        <Icon name="screen" size={28} />
        <span>
          <strong>화면 요약</strong>
          <span className="muted small">
            {!desktop ? 'TUTIPAL 데스크톱 앱에서만 쓸 수 있어요'
              : screen?.enabled ? `지금 보는 창: ${screen.sourceName}` : '켜면 고른 창의 내용을 요약해서 캐릭터가 알려 줘요'}
          </span>
          {screen?.enabled && <Link to="/screen-consent" className="btn-link small">다른 창 고르기</Link>}
        </span>
        <input type="checkbox" className="switch" checked={!!screen?.enabled} onChange={() => void toggleScreen()} disabled={!desktop || !screen} />
      </label>
      <DeleteAll />
      <Logout />
    </>
  )
}

function ReviewTab() {
  const [count, setCount] = useState(null)
  const [error, setError] = useState('')
  const [revision, setRevision] = useState(0)
  useEffect(() => {
    let active = true
    setCount(null)
    setError('')
    reviewApi.history(0, 1).then((result) => { if (active) setCount(result.totalCount) }, (e) => {
      if (active) setError(e instanceof Error ? e.message : '기록 개수를 불러오지 못했어요')
    })
    return () => { active = false }
  }, [revision])
  return (
    <>
      <Head mood="listen" title="복습 기록" text="화면 분석이 켜져 있을 때 자주 보거나 오래 본 내용이 모여요." />
      <div className="setting-card">
        <Icon name="book" size={28} />
        <span>
          <strong>{error ? '기록 개수를 확인하지 못했어요' : count === null ? '기록 개수 확인 중…' : `모인 기록 ${count}개`}</strong>
          <span className="muted small">설명 듣기 · 하나씩 지우기는 복습 기록 화면에서</span>
        </span>
        <Link to="/review" className="btn btn-outline">복습 기록 보기</Link>
      </div>
      {error && <div role="alert"><p className="error-text">{error}</p><button className="btn" onClick={() => setRevision((v) => v + 1)}>다시 불러오기</button></div>}
      <DeleteAll onDone={() => setRevision((v) => v + 1)} />
    </>
  )
}

// PT-90: 집중도 저장 동의 확인 · 철회 (수업과 무관하게, 브라우저에서도)
function FocusTab() {
  return (
    <>
      <Head mood="listen" title="집중도 체크" text="수업 중 내가 시작한 동안만 모아요. 동의를 철회하면 저장된 기록이 바로 지워져요." />
      <FocusConsentSettings />
    </>
  )
}

function AccountTab() {
  const { me, updateName } = useAuth()
  const [name, setName] = useState(me?.name ?? '')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [saved, setSaved] = useState(false)
  const normalized = name.trim()

  useEffect(() => { setName(me?.name ?? '') }, [me?.name])

  async function saveName(event) {
    event.preventDefault()
    if (busy || !normalized || normalized.length > 50 || normalized === me?.name) return
    setBusy(true)
    setError('')
    setSaved(false)
    try {
      const updated = await updateName(normalized)
      setName(updated.name)
      setSaved(true)
    } catch (e) {
      setError(e.message || '이름을 저장하지 못했어요. 다시 시도해 주세요')
    } finally {
      setBusy(false)
    }
  }

  return (
    <>
      <Head mood="idle" title="계정" text="TUTIPAL 에 로그인한 계정이에요." />
      <dl className="account-list">
        <dt>이름</dt><dd>{me?.name}</dd>
        <dt>로그인 방식</dt><dd>{PROVIDER[me?.provider] ?? me?.provider}</dd>
      </dl>
      <form onSubmit={saveName} aria-label="이름 수정" className="field">
        <label htmlFor="account-name">이름</label>
        <input id="account-name" name="name" className="input" value={name} maxLength={50} required
          autoComplete="nickname" disabled={busy}
          aria-describedby="account-name-help"
          onChange={(e) => { setName(e.target.value); setError(''); setSaved(false) }} />
        <button type="submit" className="btn btn-outline"
          disabled={busy || !normalized || normalized === me?.name}>
          {busy ? '저장 중…' : '이름 저장'}
        </button>
      </form>
      <p id="account-name-help" className="muted small">1~50자로 입력해 주세요. 바꾼 이름은 다시 로그인해도 유지돼요.</p>
      {error && <p className="error-text" role="alert">{error}</p>}
      {saved && <p className="muted small" role="status">이름을 저장했어요.</p>}
      <Logout />
      <Withdraw />
    </>
  )
}

/** 회원 탈퇴 (FR-MY-02): 한 번 더 확인한 뒤 서버가 계정을 익명화하면(기록은 보존, PM 2026-11-26) 로그인 화면으로 */
function Withdraw() {
  const { refresh } = useAuth()
  const [confirming, setConfirming] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)
  async function run() {
    setBusy(true)
    setError(null)
    try {
      await api.withdraw()
      await refresh()
    } catch (e) {
      setError(e.message || '탈퇴하지 못했어요. 잠시 뒤 다시 해 주세요')
      setBusy(false)
    }
  }
  if (confirming) {
    return (
      <div className="settings-danger row-gap">
        <span className="muted small">
          탈퇴하면 이름 · 이메일 · 소셜 계정 연결이 지워지고 다시 로그인할 수 없어요. 수업 · 답안 · 글 같은 기록은 '탈퇴한 사용자'로 남아요. 되돌릴 수 없어요
        </span>
        <button className="btn btn-danger btn-sm" onClick={() => void run()} disabled={busy}>탈퇴</button>
        <button className="btn btn-sm" onClick={() => setConfirming(false)} disabled={busy}>취소</button>
        {error && <span className="error-text" role="alert">{error}</span>}
      </div>
    )
  }
  return (
    <div className="settings-danger">
      <button className="btn btn-outline-danger btn-lg" onClick={() => setConfirming(true)}>
        <Icon name="trash" /> 회원 탈퇴
      </button>
    </div>
  )
}

function DeleteAll({ onDone }) {
  const [confirming, setConfirming] = useState(false)
  const [done, setDone] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  async function run() {
    if (busy) return
    setBusy(true)
    setError('')
    try {
      await reviewApi.deleteAll()
      setConfirming(false)
      setDone(true)
      onDone?.()
    } catch (e) {
      setError(e instanceof Error ? e.message : '삭제하지 못했어요')
    } finally {
      setBusy(false)
    }
  }
  if (confirming) {
    return (
      <div className="settings-danger row-gap">
        <span className="muted small">복습 기록을 모두 지울까요? 되돌릴 수 없어요. 화면 분석이 켜져 있으면 이후 새 기록이 모일 수 있어요.</span>
        <button className="btn btn-danger btn-sm" disabled={busy} onClick={() => void run()}>삭제</button>
        <button className="btn btn-sm" disabled={busy} onClick={() => { setConfirming(false); setError('') }}>취소</button>
        {error && <span className="error-text" role="alert">{error}</span>}
      </div>
    )
  }
  return (
    <div className="settings-danger">
      <button className="btn btn-outline-danger btn-lg" onClick={() => { setDone(false); setConfirming(true) }}>
        <Icon name="trash" /> 기록 전체 삭제
      </button>
      {done && <span className="muted small">모두 지웠어요</span>}
    </div>
  )
}

function Logout() {
  const { logout } = useAuth()
  return (
    <button className="btn-link settings-logout" onClick={() => void logout()}>
      <Icon name="logout" /> 로그아웃
    </button>
  )
}
