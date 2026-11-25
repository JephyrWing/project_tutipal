import { useEffect, useState } from 'react'
import { learningResults } from '../api/learningResults'
import './LearningResultsPage.css'

const VERDICTS = { PENDING: '채점 대기', CORRECT: '정답', INCORRECT: '오답' }
const date = (value) => new Date(value).toLocaleString('ko-KR')

export default function LearningResultsPage() {
  const [page, setPage] = useState(0)
  const [sessionId, setSessionId] = useState('')
  const [verdict, setVerdict] = useState('ALL')
  const [revision, setRevision] = useState(0)
  const [result, setResult] = useState(null)
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    let active = true
    setLoading(true)
    setResult(null)
    setError('')
    learningResults({ page, sessionId, verdict }).then((next) => {
      if (!active) return
      // A close/leave or regrade may remove the last page's rows.
      if (page > 0 && page >= next.totalPages) { setPage(Math.max(0, next.totalPages - 1)); return }
      setResult(next)
      setLoading(false)
    }, (e) => {
      if (active) { setError(e.message || '기록을 불러오지 못했어요'); setLoading(false) }
    })
    return () => { active = false }
  }, [page, sessionId, verdict, revision])

  useEffect(() => {
    const refresh = () => setRevision((v) => v + 1)
    window.addEventListener('focus', refresh)
    return () => window.removeEventListener('focus', refresh)
  }, [])

  return (
    <div className="page learning-results">
      <header className="row-between">
        <h1>내 제출 기록</h1>
        <button className="btn" onClick={() => setRevision((v) => v + 1)}>새로고침</button>
      </header>
      <p className="muted">진행 중이며 학생으로 참여하고 있는 수업의 제출 기록이에요. 정답·모범답안·피드백과 종료·퇴장한 수업 기록은 제공하지 않아요.</p>
      <div className="card results-filters">
        <label>수업
          <select className="input" aria-label="수업" value={sessionId}
            onChange={(e) => { setSessionId(e.target.value); setPage(0) }}>
            <option value="">전체 수업</option>
            {sessionId && !result?.sessions.some((s) => String(s.id) === sessionId)
              && <option value={sessionId}>선택한 수업</option>}
            {result?.sessions.map((s) => <option key={s.id} value={s.id}>{s.title}</option>)}
          </select>
        </label>
        <label>판정
          <select className="input" aria-label="판정" value={verdict}
            onChange={(e) => { setVerdict(e.target.value); setPage(0) }}>
            <option value="ALL">전체 판정</option>
            {Object.entries(VERDICTS).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
          </select>
        </label>
      </div>
      {loading ? <p role="status">제출 기록을 불러오는 중이에요…</p>
        : error ? <div role="alert"><p className="error-text">{error}</p>
          <button className="btn" onClick={() => setRevision((v) => v + 1)}>다시 불러오기</button></div>
          : result && <>
            <p>전체 {result.totalCount}개 · 최근 제출 순</p>
            {result.items.length === 0 && <p className="card">조회할 제출 기록이 없어요.</p>}
            {result.items.map((item) => <article key={item.answerId} className="card result-row">
              <div className="row-between"><strong>{item.sessionTitle} · {item.topic}</strong>
                <span className="badge badge-gray">{VERDICTS[item.verdict]}</span></div>
              <h2>{item.question}</h2>
              {item.choices.length > 0 && <ol>{item.choices.map((choice, i) => <li key={i}>{choice}</li>)}</ol>}
              <p className="result-answer"><strong>내 답안: </strong>{item.type === 'CHOICE'
                ? item.choices[item.choiceIndex] ?? '선택한 보기를 확인할 수 없어요' : item.text}</p>
              <time className="muted small" dateTime={item.submittedAt}>제출 {date(item.submittedAt)}</time>
            </article>)}
            {result.totalPages > 0 && <nav className="results-pagination" aria-label="제출 기록 페이지">
              <button className="btn" disabled={page === 0} onClick={() => setPage((v) => v - 1)}>이전</button>
              <span>{page + 1} / {result.totalPages} 페이지</span>
              <button className="btn" disabled={page + 1 >= result.totalPages} onClick={() => setPage((v) => v + 1)}>다음</button>
            </nav>}
          </>}
    </div>
  )
}
