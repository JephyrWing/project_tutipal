import { useCallback, useEffect, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { explainQuestion, reviewApi } from '../api/review'
import Icon from '../components/Icon'
import Mascot from '../components/Mascot'

const looksLikeCode = (s) => /코드|프로그램|자바|파이썬|함수|변수|for|class|오류|에러/i.test(s)

/** 목업 10 복습 기록: 자주 · 오래 본 내용 (화면 요약 텍스트만 저장), 설명 듣기 · 기록 삭제 */
export default function ReviewPage() {
  const navigate = useNavigate()
  const [params] = useSearchParams()
  const [items, setItems] = useState(null)
  const [page, setPage] = useState(0)
  const [totalCount, setTotalCount] = useState(0)
  const [totalPages, setTotalPages] = useState(0)
  const [revision, setRevision] = useState(0)
  const [loading, setLoading] = useState(true)
  const [loadedPage, setLoadedPage] = useState(null)
  const [busy, setBusy] = useState(false)
  const [actionError, setActionError] = useState('')
  const [confirming, setConfirming] = useState(false)
  const [error, setError] = useState('')
  const pending = loading || (!error && loadedPage !== page)

  const explain = useCallback((summary) => {
    navigate(`/chat?ask=${encodeURIComponent(explainQuestion(summary))}`)
  }, [navigate])

  const load = () => setRevision((v) => v + 1)
  useEffect(() => {
    let active = true
    setLoading(true)
    setError('')
    reviewApi.history(page).then((result) => {
      if (!active) return
      // 마지막 페이지의 기록을 삭제했거나 다른 창에서 지웠으면 유효한 페이지로 이동한다.
      const lastPage = Math.max(0, result.totalPages - 1)
      if (page > lastPage) {
        setPage(lastPage)
        return
      }
      setItems(result.items)
      setTotalCount(result.totalCount)
      setTotalPages(result.totalPages)
      setLoadedPage(page)
    }, (e) => {
      if (active) setError(e instanceof Error ? e.message : '불러오지 못했어요')
    }).finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [page, revision])

  // 위젯의 "설명 듣기"(?explain=id) → 바로 챗봇으로
  useEffect(() => {
    let active = true
    const id = Number(params.get('explain'))
    if (id) reviewApi.item(id).then((it) => { if (active) explain(it.summary) }, (e) => {
      if (active) setActionError(e instanceof Error ? e.message : '기록을 불러오지 못했어요')
    })
    return () => { active = false }
  }, [params, explain])

  async function remove(id) {
    if (busy) return
    setBusy(true)
    setActionError('')
    try {
      if (id === undefined) await reviewApi.deleteAll()
      else await reviewApi.remove(id)
      setConfirming(false)
      load()
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '삭제하지 못했어요')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="page">
      <section className="card session-body review-page">
        <header className="row-between review-head">
          <div className="row-gap">
            <Mascot mood="happy" size={90} />
            <div>
              <h1>복습 기록</h1>
              <p className="muted">화면 분석이 켜져 있을 때 자주 보거나 오래 본 내용이 모여요. 이미지는 저장하지 않고 요약 글만 남아요.</p>
            </div>
          </div>
          {confirming ? (
            <span className="row-gap">
              <span className="muted small">모두 지울까요? 되돌릴 수 없어요. 화면 분석이 켜져 있으면 이후 새 기록이 모일 수 있어요.</span>
              <button className="btn btn-danger btn-sm" disabled={busy || pending} onClick={() => void remove()}>삭제</button>
              <button className="btn btn-sm" disabled={busy} onClick={() => setConfirming(false)}>취소</button>
            </span>
          ) : (
            <button className="btn btn-outline-danger" onClick={() => setConfirming(true)} disabled={busy || pending || !!error || !totalCount}>🗑 기록 삭제</button>
          )}
        </header>
        {actionError && <p className="error-text" role="alert">{actionError}</p>}
        {pending && <p role="status">복습 기록을 불러오는 중이에요…</p>}
        {!pending && error && <div role="alert"><p className="error-text">{error}</p><button className="btn" onClick={load}>다시 불러오기</button></div>}
        {!pending && !error && <>
        <p className="muted" role="status">전체 {totalCount}개 · 최근 본 순</p>
        {items?.length === 0 && <p className="muted">아직 모인 내용이 없어요</p>}
        {items?.map((it) => (
          <div key={it.id} className="review-row">
            <span className="post-icon"><Icon name={looksLikeCode(it.summary) ? 'code' : 'book'} size={24} /></span>
            <span className="review-text">{it.summary}<br /><time className="muted small" dateTime={it.lastSeen}>최근 본 날짜: {new Date(it.lastSeen).toLocaleString('ko-KR')}</time></span>
            <span className="badge">{it.seenCount}번 봄 · 누적 {Math.floor(it.seenSeconds / 60)}분 {it.seenSeconds % 60}초</span>
            <button className="btn btn-outline" disabled={busy} onClick={() => explain(it.summary)}>▶ 설명 듣기</button>
            <button className="btn-link small" disabled={busy} onClick={() => void remove(it.id)} aria-label="이 기록 지우기">✕</button>
          </div>
        ))}
        {totalPages > 0 && <nav className="row-gap" aria-label="복습 기록 페이지">
          <button className="btn" disabled={busy || page === 0} onClick={() => setPage((v) => v - 1)}>이전</button>
          <span>{page + 1} / {totalPages} 페이지</span>
          <button className="btn" disabled={busy || page + 1 >= totalPages} onClick={() => setPage((v) => v + 1)}>다음</button>
        </nav>}
        </>}
      </section>
    </div>
  )
}
