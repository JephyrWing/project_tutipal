import { useEffect, useState } from 'react'
import { Navigate } from 'react-router-dom'
import { moderation } from '../api/moderation'
import { useAuth } from '../auth'

const kinds = { POST: '게시글', MATERIAL: '자료' }
const showTime = (iso) => new Date(iso).toLocaleString('ko-KR')

export default function ContentModerationPage() {
  const { me } = useAuth()
  if (me?.role !== 'ADMIN') return <Navigate to="/" replace />
  return <ModerationPanel />
}

function ModerationPanel() {
  const [kind, setKind] = useState('POST')
  const [cursors, setCursors] = useState([null])
  const [result, setResult] = useState(null)
  const [selected, setSelected] = useState(null)
  const [refresh, setRefresh] = useState(0)
  const [notice, setNotice] = useState('')
  const cursor = cursors.at(-1)
  const requestKey = `${kind}:${cursor}:${refresh}`
  const page = result?.key === requestKey ? result : null

  useEffect(() => {
    const load = new AbortController()
    moderation.list(kind, cursor, load.signal).then(
      (data) => { if (!load.signal.aborted) setResult({ key: requestKey, data }) },
      (error) => { if (!load.signal.aborted) setResult({ key: requestKey, error: error.message }) },
    )
    return () => load.abort()
  }, [kind, cursor, requestKey])

  function move(nextCursors) {
    setCursors(nextCursors)
    setSelected(null)
    setNotice('')
  }

  function refreshList() {
    setSelected(null)
    setRefresh((value) => value + 1)
  }

  return (
    <section className="card session-body">
      <h1>콘텐츠 관리</h1>
      <div className="row-gap" aria-label="콘텐츠 유형">
        {Object.entries(kinds).map(([value, label]) => (
          <button key={value} type="button" className={kind === value ? 'btn btn-primary' : 'btn'}
            aria-pressed={kind === value} onClick={() => {
              if (kind !== value) {
                setKind(value)
                move([null])
              }
            }}>{label}</button>
        ))}
        <button type="button" className="btn" onClick={refreshList}>새로고침</button>
      </div>
      {notice && <p role="status">{notice}</p>}
      {!page && <p role="status">콘텐츠를 불러오는 중이에요.</p>}
      {page?.error && <p className="error-text" role="alert">{page.error}</p>}
      {page?.data && (page.data.items.length ? (
        <table className="table">
          <thead><tr><th>콘텐츠</th><th>수업 번호</th><th>상태</th><th>관리</th></tr></thead>
          <tbody>
            {page.data.items.map((item) => (
              <tr key={item.id}>
                <td>{item.label} <span className="muted small">#{item.id}</span></td>
                <td>{item.sessionId}</td>
                <td>{item.hiddenAt ? `공개 중지 · ${showTime(item.hiddenAt)}` : '공개'}</td>
                <td><button type="button" className="btn btn-sm" aria-label={`${item.label} 관리`}
                  onClick={() => setSelected(item)}>{item.hiddenAt ? '원복 및 기록' : '내리기 및 기록'}</button></td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : <p className="muted">표시할 콘텐츠가 없어요.</p>)}
      {page?.data && (cursors.length > 1 || page.data.nextBefore !== null) && (
        <div className="row-gap" aria-label="콘텐츠 페이지">
          <button type="button" className="btn" disabled={cursors.length === 1}
            onClick={() => move(cursors.slice(0, -1))}>이전</button>
          <button type="button" className="btn" disabled={page.data.nextBefore === null}
            onClick={() => move([...cursors, page.data.nextBefore])}>다음</button>
        </div>
      )}
      {selected && <ModerationAction key={`${kind}:${selected.id}`} kind={kind} item={selected}
        onClose={() => setSelected(null)} onDone={(message) => { setNotice(message); refreshList() }}
        onRefresh={refreshList} />}
    </section>
  )
}

function ModerationAction({ kind, item, onClose, onDone, onRefresh }) {
  const [reason, setReason] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)
  const [history, setHistory] = useState(null)
  const targetHidden = !item.hiddenAt

  useEffect(() => {
    const load = new AbortController()
    moderation.history(kind, item.id, load.signal).then(
      (actions) => { if (!load.signal.aborted) setHistory({ actions }) },
      (failure) => { if (!load.signal.aborted) setHistory({ error: failure.message }) },
    )
    return () => load.abort()
  }, [kind, item.id])

  async function submit(event) {
    event.preventDefault()
    if (busy || !reason.trim()) return
    setBusy(true)
    setError(null)
    try {
      await moderation.setVisibility(kind, item.id, targetHidden, item.version, reason.trim())
      onDone(targetHidden ? '콘텐츠를 내렸어요.' : '콘텐츠를 다시 공개했어요.')
    } catch (failure) {
      setError(failure)
    } finally {
      setBusy(false)
    }
  }

  return (
    <section className="card session-body" aria-label="콘텐츠 조치">
      <h2>{item.label}</h2>
      <p>{targetHidden ? '이 항목을 수업에서 내릴까요?' : '이 항목을 수업에 다시 공개할까요?'}</p>
      <form className="notice-form" onSubmit={(event) => void submit(event)}>
        <label>조치 사유
          <input className="input" value={reason} onChange={(event) => setReason(event.target.value)}
            maxLength={300} required disabled={busy} />
        </label>
        <div className="row-gap">
          <button type="submit" className="btn btn-primary" disabled={busy || !reason.trim()}>
            {targetHidden ? '콘텐츠 내리기' : '다시 공개'}
          </button>
          <button type="button" className="btn" disabled={busy} onClick={onClose}>닫기</button>
        </div>
      </form>
      {error && <div>
        <p role="alert" className="error-text">{error.message}</p>
        {error.status === 409 && <button type="button" className="btn" onClick={onRefresh}>최신 상태 보기</button>}
      </div>}
      <h3>최근 조치 기록</h3>
      {!history && <p role="status">기록을 불러오는 중이에요.</p>}
      {history?.error && <p role="alert" className="error-text">{history.error}</p>}
      {history?.actions && (history.actions.length ? (
        <ul>{history.actions.map((action) => (
          <li key={action.version}>
            {action.hidden ? '내리기' : '다시 공개'} · {showTime(action.createdAt)} · 관리자 #{action.actorId} · {action.reason}
          </li>
        ))}</ul>
      ) : <p className="muted">조치 기록이 없어요.</p>)}
    </section>
  )
}
