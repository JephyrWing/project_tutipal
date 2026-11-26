import { useCallback, useEffect, useRef, useState } from 'react'
import { formatRetryTime, isQuotaError } from '../api/client'
import { insights } from '../api/insights.js'
import Icon from '../components/Icon'
import './InsightsPanels.css'

// PT-87 개념 분석 (선생님). 서버 값은 전부 텍스트 노드로만 그린다 (HTML 로 넣지 않는다).

const errText = (e, fallback) => (e instanceof Error || typeof e?.message === 'string' ? e.message : fallback)

const MASTERY_RELOAD_DELAY_MS = 3000
export const MAX_TAGS = 3
export const MAX_TAG_LENGTH = 30
const sameName = (a, b) => a.trim().replace(/\s+/g, ' ').toLowerCase() === b.trim().replace(/\s+/g, ' ').toLowerCase()

/** 선생님 "개념 분석" 탭: 헷갈리는 주제 TOP3 (분석하기를 눌렀을 때만) + 학생별 숙련도 표. */
export default function InsightsTab({ sessionId, reloadKey }) {
  return (
    <div className="insights-tab">
      <ConfusionsPanel sessionId={sessionId} />
      <MasteryPanel sessionId={sessionId} reloadKey={reloadKey} />
    </div>
  )
}

function ConfusionsPanel({ sessionId }) {
  const [view, setView] = useState(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const request = useRef(0)

  useEffect(() => {
    request.current++
    setView(null)
    setError('')
  }, [sessionId])

  async function analyze() {
    const mine = ++request.current
    setBusy(true)
    setError('')
    try {
      const result = await insights.analyzeConfusions(sessionId)
      if (mine === request.current) setView(result)
    } catch (e) {
      if (mine !== request.current) return
      setError(isQuotaError(e)
        ? `개념 분석 한도를 다 썼어요. ${formatRetryTime(e.retryAt)} 이후 다시 분석할 수 있어요`
        : errText(e, '분석하지 못했어요'))
    } finally {
      if (mine === request.current) setBusy(false)
    }
  }

  return (
    <section className="card session-body insights-confusions">
      <div className="row-between">
        <strong className="section-title"><Icon name="chart" size={18} /> 학생들이 헷갈리는 주제 TOP3</strong>
        <button type="button" className="btn btn-primary" disabled={busy} onClick={() => void analyze()}>
          {busy ? '분석하는 중…' : '분석하기'}
        </button>
      </div>
      <p className="muted small">
        이 수업 학생들의 최근 챗봇 질문을 비슷한 것끼리 묶어요. 질문 원문과 누가 물었는지는 보여 주지 않아요.
        학생 3명 이상이 함께 물은 주제만 보여요.
      </p>
      {error && <p className="error-text" role="alert">{error}</p>}
      {view && <ConfusionsResult view={view} />}
    </section>
  )
}

function ConfusionsResult({ view }) {
  return (
    <div className="insights-result" role="status">
      {view.message && <p className={`insights-note insights-note-${view.state}`}>{view.message}</p>}
      {view.state !== 'unavailable' && (
        <p className="muted small">
          분석한 질문 {view.questionCount}개 · 질문한 학생 {view.studentCount}명{view.cached && ' · 최근 분석 결과'}
        </p>
      )}
      {view.state !== 'insufficient' && view.state !== 'unavailable' && view.topics.length === 0 && (
        <p className="muted">여러 학생이 함께 물은 주제를 찾지 못했어요</p>
      )}
      <ol className="insights-topics">
        {view.topics.map((topic, index) => (
          <li key={index} className="insights-topic">
            <span className="insights-rank">{topic.rank}</span>
            <div>
              <strong className="insights-label">{topic.label}</strong>
              {topic.conceptId === null && <span className="badge badge-gray">태그 없음</span>}
              <div className="muted small">학생 {topic.studentCount}명 · 질문 {topic.questionCount}개</div>
            </div>
          </li>
        ))}
      </ol>
    </div>
  )
}

function MasteryPanel({ sessionId, reloadKey }) {
  const [table, setTable] = useState(null)
  const [error, setError] = useState('')
  const request = useRef(0)

  const load = useCallback(() => {
    const mine = ++request.current
    insights.mastery(sessionId).then(
      (result) => { if (mine === request.current) { setTable(result); setError('') } },
      (e) => { if (mine === request.current) setError(errText(e, '불러오지 못했어요')) },
    )
  }, [sessionId])
  useEffect(load, [load])
  // reloadKey 는 학생 답안마다 바뀐다: 몰려 와도 마지막 변경 뒤 한 번만 다시 읽는다.
  const firstReload = useRef(true)
  useEffect(() => {
    if (firstReload.current) { firstReload.current = false; return undefined }
    const timer = setTimeout(load, MASTERY_RELOAD_DELAY_MS)
    return () => clearTimeout(timer)
  }, [load, reloadKey])

  return (
    <section className="card session-body insights-mastery">
      <div className="row-between">
        <strong className="section-title"><Icon name="chart" size={18} /> 학생별 개념 숙련도</strong>
        <button type="button" className="btn btn-sm" onClick={load}>새로고침</button>
      </div>
      <p className="muted small">
        개념 태그를 붙인 이해도 질문의 채점 결과로 추정한 값이에요 (BKT, 참고용). 흐린 칸은 답이 3개 미만이라 믿기 어려워요.
      </p>
      {error && <p className="error-text" role="alert">{error}</p>}
      {table && table.concepts.length === 0 && (
        <p className="muted">이해도 질문에 개념 태그를 붙이면 숙련도가 보여요 (이해도 질문 탭 → 답안 보기)</p>
      )}
      {table && table.concepts.length > 0 && table.students.length === 0 && <p className="muted">아직 학생이 없어요</p>}
      {table && table.concepts.length > 0 && table.students.length > 0 && <MasteryTable table={table} />}
    </section>
  )
}

const percent = (value) => (value === null ? '-' : `${value}%`)

function MasteryTable({ table }) {
  return (
    <div className="insights-table-wrap">
      <table className="table insights-table">
        <thead>
          <tr>
            <th scope="col">학생</th>
            {table.concepts.map((c) => (
              <th key={c.id} scope="col" className={c.belowHalf ? 'insights-low' : undefined}>
                <div>{c.name}</div>
                <div className="muted small">문항 {c.quizCount}개 · 평균 {percent(c.classAvg)}</div>
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {table.students.map((s) => (
            <tr key={s.userId}>
              <th scope="row">{s.name}</th>
              {s.cells.map((cell) => (
                <td key={cell.conceptId}
                  className={[cell.lowConfidence ? 'insights-unsure' : '', cell.pKnown !== null && cell.pKnown < 50 ? 'insights-low' : '']
                    .filter(Boolean).join(' ') || undefined}
                  title={cell.pKnown === null ? '채점된 답이 없어요' : `채점된 답 ${cell.observations}개`}>
                  {percent(cell.pKnown)}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

/**
 * 개념 태그 이름 입력 (칩). 최대 3개, 각 1~30자, 대소문자 · 공백만 다른 이름은 하나로. 저장은 하지 않는다.
 * suggestions: 이 수업에 이미 있는 개념 이름 (자동 완성).
 */
export function ConceptTagInput({ names, onChange, suggestions = [], disabled = false, idPrefix }) {
  const [draft, setDraft] = useState('')
  const [hint, setHint] = useState('')
  const listId = `${idPrefix}-concepts`

  function add() {
    const name = draft.trim().replace(/\s+/g, ' ')
    if (!name) return
    if ([...name].length > MAX_TAG_LENGTH) { setHint(`개념 이름은 ${MAX_TAG_LENGTH}자까지 쓸 수 있어요`); return }
    if (names.some((n) => sameName(n, name))) { setDraft(''); return }
    if (names.length >= MAX_TAGS) { setHint(`개념은 문항당 ${MAX_TAGS}개까지 붙일 수 있어요`); return }
    const existing = suggestions.find((s) => sameName(s, name))
    onChange([...names, existing ?? name])
    setDraft('')
    setHint('')
  }

  return (
    <div className="concept-tags">
      <div className="concept-chips">
        {names.map((name) => (
          <span key={name} className="concept-chip">
            {name}
            <button type="button" aria-label={`${name} 태그 빼기`} disabled={disabled}
              onClick={() => onChange(names.filter((n) => n !== name))}>×</button>
          </span>
        ))}
        {names.length < MAX_TAGS && (
          <input className="input concept-input" value={draft} disabled={disabled} list={listId}
            maxLength={MAX_TAG_LENGTH + 10} placeholder="개념 태그 (예: 반복문)" aria-label="개념 태그 입력"
            onChange={(e) => setDraft(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); add() } }} />
        )}
        {names.length < MAX_TAGS && (
          <button type="button" className="btn btn-sm" disabled={disabled || !draft.trim()} onClick={add}>추가</button>
        )}
      </div>
      <datalist id={listId}>
        {suggestions.map((s) => <option key={s} value={s} />)}
      </datalist>
      {hint && <span className="muted small" role="status">{hint}</span>}
    </div>
  )
}

/** 답안 보기 대화상자 안의 태그 편집: 칩을 고치고 저장하면 그 문항의 태그를 통째로 바꾼다. */
export function QuizConceptEditor({ quizId, tags, suggestions, onSaved }) {
  const [names, setNames] = useState(() => tags.map((t) => t.name))
  const [busy, setBusy] = useState(false)
  const [status, setStatus] = useState('')
  const saved = tags.map((t) => t.name)
  const changed = names.length !== saved.length || names.some((n, i) => n !== saved[i])

  async function save() {
    setBusy(true)
    setStatus('')
    try {
      const result = await insights.setQuizConcepts(quizId, names)
      setNames(result.map((t) => t.name))
      setStatus('태그를 저장했어요')
      onSaved?.(quizId, result)
    } catch (e) {
      setStatus(errText(e, '태그를 저장하지 못했어요'))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="quiz-concepts">
      <strong className="small">개념 태그</strong>
      <ConceptTagInput names={names} onChange={(next) => { setNames(next); setStatus('') }}
        suggestions={suggestions} disabled={busy} idPrefix={`quiz-${quizId}`} />
      <div className="row-between">
        <span className="muted small" role="status">{status}</span>
        <button type="button" className="btn btn-sm" disabled={busy || !changed} onClick={() => void save()}>
          {busy ? '저장하는 중…' : '태그 저장'}
        </button>
      </div>
    </div>
  )
}

/** 문항 목록 줄의 읽기 전용 태그 칩. */
export function QuizConceptChips({ tags }) {
  if (!tags || tags.length === 0) return null
  return (
    <span className="concept-chips concept-chips-inline">
      {tags.map((t) => <span key={t.conceptId} className="concept-chip concept-chip-static">{t.name}</span>)}
    </span>
  )
}
