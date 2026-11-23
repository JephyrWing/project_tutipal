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
  const [confirming, setConfirming] = useState(false)
  const [error, setError] = useState('')

  const explain = useCallback((summary) => {
    navigate(`/chat?ask=${encodeURIComponent(explainQuestion(summary))}`)
  }, [navigate])

  const load = useCallback(() => {
    reviewApi.items().then(setItems, (e) => setError(e instanceof Error ? e.message : '불러오지 못했어요'))
  }, [])
  useEffect(load, [load])

  // 위젯의 "설명 듣기"(?explain=id) → 바로 챗봇으로
  useEffect(() => {
    const id = Number(params.get('explain'))
    if (id) reviewApi.item(id).then((it) => explain(it.summary), () => undefined)
  }, [params, explain])

  async function deleteAll() {
    await reviewApi.deleteAll()
    setItems([])
    setConfirming(false)
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
              <span className="muted small">모두 지울까요? 되돌릴 수 없어요</span>
              <button className="btn btn-danger btn-sm" onClick={() => void deleteAll()}>삭제</button>
              <button className="btn btn-sm" onClick={() => setConfirming(false)}>취소</button>
            </span>
          ) : (
            <button className="btn btn-outline-danger" onClick={() => setConfirming(true)} disabled={!items?.length}>🗑 기록 삭제</button>
          )}
        </header>
        {error && <p className="error-text">{error}</p>}
        {items?.length === 0 && <p className="muted">아직 모인 내용이 없어요</p>}
        {items?.map((it) => (
          <div key={it.id} className="review-row">
            <span className="post-icon"><Icon name={looksLikeCode(it.summary) ? 'code' : 'book'} size={24} /></span>
            <span className="review-text">{it.summary}</span>
            <span className="badge">{it.seenCount >= 2 ? `${it.seenCount}번 봄` : `${Math.max(1, Math.round(it.seenSeconds / 60))}분 봄`}</span>
            <button className="btn btn-outline" onClick={() => explain(it.summary)}>▶ 설명 듣기</button>
            <button className="btn-link small" onClick={() => void reviewApi.remove(it.id).then(load)} aria-label="이 기록 지우기">✕</button>
          </div>
        ))}
      </section>
    </div>
  )
}
