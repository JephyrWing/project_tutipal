import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import Mascot from '../components/Mascot'

/** 목업 04 화면 분석의 창 선택과 시작 (데스크톱 앱 전용) */
export default function ScreenConsentPage() {
  const navigate = useNavigate()
  const desktop = window.desktop
  const [settings, setSettings] = useState(null)
  const [sources, setSources] = useState(null)
  const [picked, setPicked] = useState('')
  const [preview, setPreview] = useState(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const revision = useRef(0)

  function clearPreview() {
    revision.current++
    setPreview(null)
    setBusy(false)
    setError('')
    void desktop?.cancelScreenPreview()
  }

  const loadSources = () => {
    clearPreview()
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
    return () => { revision.current++; void desktop.cancelScreenPreview() }
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
    if (!src || !desktop || preview?.sourceId !== picked || busy) return
    setBusy(true); setError('')
    try {
      setSettings(await desktop.setScreenSettings({ enabled: true, sourceId: src.id, sourceName: src.name, previewToken: preview.previewToken }))
      navigate('/')
    } catch (e) { setError(e.message); setBusy(false) }
  }

  async function showPreview() {
    if (!picked || busy) return
    const own = ++revision.current
    setBusy(true); setPreview(null); setError('')
    try {
      const next = await desktop.previewScreenSource(picked)
      if (revision.current !== own) return
      setPreview(next)
      setSettings(await desktop.getScreenSettings())
    } catch (e) { if (revision.current === own) setError(e.message) }
    finally { if (revision.current === own) setBusy(false) }
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
        <p className="muted">목록에서는 이미지를 읽지 않아요. 창을 고르고 미리보기를 누르면 그 창 한 장만 확인해요. 분석 시작 전에는 AI에 보내지 않아요.</p>
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
              <input type="radio" name="source" checked={picked === s.id} onChange={() => { clearPreview(); setPicked(s.id) }} />
              <span>{s.name}</span>
            </label>
          ))}
        </div>
        <button className="btn" disabled={!picked || busy} onClick={() => void showPreview()}>선택 창 미리보기</button>
        {busy && <p role="status">창을 확인하고 있어요…</p>}
        {error && <p role="alert">{error}</p>}
        {preview?.sourceId === picked && <img src={preview.image} alt="선택 창 미리보기" style={{ maxWidth: '100%', maxHeight: 300 }} />}
        <div className="consent-actions">
          {settings?.enabled
            ? <button className="btn" onClick={() => void turnOff()}>화면 분석 끄기</button>
            : <button className="btn" onClick={() => navigate(-1)}>나중에</button>}
          <button className="btn btn-primary" disabled={busy || preview?.sourceId !== picked || !sources?.some((s) => s.id === picked)} onClick={() => void turnOn()}>
            {settings?.enabled ? '이 창으로 바꾸기' : '분석 시작'}
          </button>
        </div>
      </div>
    </div>
  )
}
