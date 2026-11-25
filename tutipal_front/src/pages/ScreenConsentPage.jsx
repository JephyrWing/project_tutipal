import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import Mascot from '../components/Mascot'

/** 목업 04 화면 분석의 창 선택과 시작 (데스크톱 앱 전용) */
export default function ScreenConsentPage() {
  const navigate = useNavigate()
  const desktop = window.desktop
  const [settings, setSettings] = useState(null)
  const [sources, setSources] = useState(null)
  const [picked, setPicked] = useState('')

  const loadSources = () => {
    setSources(null)
    desktop?.screenSources().then(setSources, () => setSources([]))
  }

  useEffect(() => {
    if (!desktop) return
    desktop.getScreenSettings().then((s) => {
      setSettings(s)
      setPicked(s.sourceId)
    }, () => undefined)
    loadSources()
  }, [])

  if (!desktop) {
    return (
      <div className="center-page">
        <div className="card session-card">
          <Mascot mood="curious" size={110} />
          <p>화면 분석은 TUTIPAL 데스크톱 앱에서만 쓸 수 있어요.</p>
        </div>
      </div>
    )
  }

  async function turnOn() {
    const src = sources?.find((s) => s.id === picked)
    if (!src || !desktop) return
    setSettings(await desktop.setScreenSettings({ enabled: true, sourceId: src.id, sourceName: src.name }))
    navigate('/')
  }

  async function turnOff() {
    if (!desktop || !settings) return
    setSettings(await desktop.setScreenSettings({ ...settings, enabled: false }))
  }

  return (
    <div className="center-page">
      <div className="card modal consent">
        <Mascot mood="happy" size={72} float />
        <h1>분석할 창 선택</h1>
        <p className="muted">고른 창만 분석하며 화면 이미지는 저장하지 않아요.</p>
        {settings?.enabled && <p className="badge badge-live consent-now"><span className="dot" /> 지금 보는 창: {settings.sourceName}</p>}
        <div className="row-between">
          <strong>볼 창 고르기</strong>
          <button className="btn btn-sm" onClick={loadSources}>새로고침</button>
        </div>
        <div className="source-grid" role="radiogroup" aria-label="분석할 창">
          {sources === null && <p className="muted">창 목록을 불러오는 중…</p>}
          {sources?.length === 0 && <p className="muted">고를 수 있는 창이 없어요</p>}
          {sources?.map((s) => (
            <label key={s.id} className={`source-card${picked === s.id ? ' on' : ''}`}>
              <input type="radio" name="source" checked={picked === s.id} onChange={() => setPicked(s.id)} />
              <img src={s.thumbnail} alt="" />
              <span>{s.name}</span>
            </label>
          ))}
        </div>
        <div className="consent-actions">
          {settings?.enabled
            ? <button className="btn" onClick={() => void turnOff()}>화면 분석 끄기</button>
            : <button className="btn" onClick={() => navigate(-1)}>나중에</button>}
          <button className="btn btn-primary" disabled={!sources?.some((s) => s.id === picked)} onClick={() => void turnOn()}>
            {settings?.enabled ? '이 창으로 바꾸기' : '분석 시작'}
          </button>
        </div>
      </div>
    </div>
  )
}
