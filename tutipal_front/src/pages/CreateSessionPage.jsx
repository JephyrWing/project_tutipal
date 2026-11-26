import { useState } from 'react'
import { Link } from 'react-router-dom'
import { sessions } from '../api/sessions'
import Mascot from '../components/Mascot'
import SubjectPicker from '../components/SubjectPicker'
import AiRetentionSelect from '../components/AiRetentionSelect'
import { aiRetentionLabel, parseAiRetentionDays } from '../utils/aiRetention'

/**
 * 목업 11 방 만들기: 이름 + 과목(드롭다운 · 직접 입력) + AI 대화 보관 기간(필수, 기본값 없음) → 초대 코드
 * (만든 사람이 이 방의 선생님)
 */
export default function CreateSessionPage() {
  const [title, setTitle] = useState('')
  const [topic, setTopic] = useState('')
  const [retention, setRetention] = useState('')
  const [created, setCreated] = useState(null)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const [copied, setCopied] = useState(false)
  const retentionDays = parseAiRetentionDays(retention)
  const ready = !!title.trim() && !!topic.trim() && retentionDays !== null

  async function onSubmit(e) {
    e.preventDefault()
    if (!ready || busy) return
    setBusy(true)
    setError('')
    try {
      setCreated(await sessions.create(title.trim(), topic.trim(), retentionDays))
    } catch (err) {
      setError(err instanceof Error ? err.message : '방을 만들지 못했어요')
    } finally {
      setBusy(false)
    }
  }

  async function copy() {
    if (!created) return
    await navigator.clipboard.writeText(created.inviteCode).catch(() => undefined)
    setCopied(true)
  }

  return (
    <div className="page session-create">
      <form className="card session-card" onSubmit={onSubmit}>
        <Mascot mood="happy" size={120} />
        <label className="field">
          <strong>방 이름</strong>
          <input
            value={title}
            onChange={(e) => setTitle(e.target.value)}
            maxLength={50}
            placeholder="예: 자바 기초 3주차"
            disabled={!!created}
            autoFocus
          />
        </label>
        <div className="field">
          <strong>공부할 과목</strong>
          <SubjectPicker value={topic} onChange={setTopic} disabled={!!created} />
        </div>
        <div className="field">
          <strong>AI 대화 보관 기간 (수업 종료 후, 최대 90일)</strong>
          <AiRetentionSelect value={retention} onChange={setRetention} disabled={!!created} required />
          <span className="muted small">
            학생이 이 수업에서 AI 조교와 나눈 대화를 수업이 끝난 뒤 얼마나 남길지 정해요. 학생이 "저장"한 대화는 남아요.
          </span>
        </div>
        <button className="btn btn-primary btn-lg" disabled={!ready || busy || !!created}>
          {busy ? '만드는 중…' : '만들기'}
        </button>
        {error && <p className="error-text">{error}</p>}
      </form>

      <section className="card session-card code-card" aria-live="polite">
        {created ? (
          <>
            <Mascot mood="happy" size={120} float />
            <div className="code-row">
              <span className="invite-code">{created.inviteCode}</span>
              <button type="button" className="btn" onClick={() => void copy()}>{copied ? '복사됨' : '복사'}</button>
            </div>
            <p className="muted">학생에게 이 코드를 알려 주세요. 학생은 홈 → 방 참여하기에서 입력해요.</p>
            <p className="muted small">AI 대화 보관: {aiRetentionLabel(created.aiRetentionDays)} (방 설정에서 바꿀 수 있어요)</p>
            <Link to={`/sessions/${created.id}`} className="btn btn-primary">선생님 모드로 들어가기</Link>
          </>
        ) : (
          <>
            <Mascot mood="idle" size={120} />
            <p className="muted">방을 만들면 여기에 초대 코드가 나와요</p>
          </>
        )}
      </section>
    </div>
  )
}
