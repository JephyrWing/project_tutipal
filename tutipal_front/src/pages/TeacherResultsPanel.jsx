import { useEffect, useState } from 'react'
import { sessions } from '../api/sessions'

const STATUS_COLUMNS = [
  ['unsubmitted', '미제출'],
  ['pending', '채점 대기'],
  ['correct', '정답'],
  ['incorrect', '오답'],
]

export default function TeacherResultsPanel({ sessionId, reloadKey }) {
  const [response, setResponse] = useState(null)
  const [refresh, setRefresh] = useState(0)

  useEffect(() => {
    let current = true
    sessions.teacherResults(sessionId).then(
      (results) => { if (current) setResponse({ sessionId, results }) },
      (error) => {
        if (current) setResponse({ sessionId, error: error?.message || '학습 결과를 불러오지 못했어요' })
      },
    )
    return () => { current = false }
  }, [sessionId, reloadKey, refresh])

  const current = response?.sessionId === sessionId ? response : null
  const results = current?.results

  return (
    <section className="card session-body" aria-label="학습 결과">
      <div className="row-between">
        <h2>학습 결과</h2>
        <button type="button" className="btn btn-sm" onClick={() => setRefresh((v) => v + 1)}>새로고침</button>
      </div>
      <p className="muted">미제출은 참가 기록 기준입니다. 문항 마감 전에 참여했고 답안이 없는 학생을 셉니다.</p>
      {!current && <p role="status" className="muted">학습 결과를 불러오는 중…</p>}
      {current?.error && <p role="alert">{current.error}</p>}
      {results && (
        <>
          <dl className="teacher-result-totals" aria-label="수업 전체 집계">
            {STATUS_COLUMNS.map(([key, label]) => (
              <div key={key}><dt>{label}</dt><dd>{results.totals[key]}</dd></div>
            ))}
          </dl>
          <ResultsTable
            title="학생별 결과" rowHeading="학생" rows={results.students}
            rowId={(row) => row.userId} rowLabel={(row) => row.name} empty="집계할 학생이 없어요"
          />
          <ResultsTable
            title="문항별 결과" rowHeading="문항" rows={results.questions}
            rowId={(row) => row.quizId} rowLabel={(row) => row.question} empty="출제한 문항이 없어요"
          />
        </>
      )}
    </section>
  )
}

function ResultsTable({ title, rowHeading, rows, rowId, rowLabel, empty }) {
  return (
    <section aria-label={title}>
      <h3>{title}</h3>
      {rows.length === 0 ? <p className="muted">{empty}</p> : (
        <div className="teacher-result-table">
          <table className="table" aria-label={title}>
            <thead>
              <tr>
                <th scope="col">{rowHeading}</th>
                {STATUS_COLUMNS.map(([key, label]) => <th key={key} scope="col">{label}</th>)}
                <th scope="col">합계</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => (
                <tr key={rowId(row)}>
                  <th scope="row">{rowLabel(row)}</th>
                  {STATUS_COLUMNS.map(([key]) => <td key={key}>{row.counts[key]}</td>)}
                  <td>{STATUS_COLUMNS.reduce((total, [key]) => total + row.counts[key], 0)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}
