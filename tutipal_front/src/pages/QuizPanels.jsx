import { useCallback, useEffect, useRef, useState } from 'react'
import { quizzes } from '../api/quizzes'
import Icon from '../components/Icon'
import Mascot from '../components/Mascot'

const errText = (e, fallback) => (e instanceof Error ? e.message : fallback)

/** 1초마다 다시 그려서 남은 시간을 보여 준다. ponytail: PC 시계 기준 (서버가 3초 유예를 줌) */
function useRemaining(closesAt) {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(t)
  }, [])
  return Math.max(0, Math.ceil((new Date(closesAt).getTime() - now) / 1000))
}

const mmss = (sec) => `${Math.floor(sec / 60)}:${String(sec % 60).padStart(2, '0')}`

// ---------------- 선생님 ----------------

/** 목업 22 · 23 · 24: 이해도 질문 만들기 + 질문별 정답률 + 응답 검토. reloadKey 가 바뀌면(실시간 알림) 다시 읽는다 */
export function TeacherQuizTab({ sessionId, reloadKey }) {
  const [list, setList] = useState([])
  const [formMode, setFormMode] = useState(null)
  const [reviewId, setReviewId] = useState(null)
  const [error, setError] = useState('')

  const load = useCallback(() => {
    quizzes.list(sessionId).then(setList, (e) => setError(errText(e, '불러오지 못했어요')))
  }, [sessionId])
  useEffect(load, [load, reloadKey])

  function onCreated(quiz) {
    setList((current) => current.some((item) => item.id === quiz.id) ? current : [...current, quiz])
    setFormMode(null)
    setReviewId(quiz.id)
  }

  const reviewedQuiz = list.find((quiz) => quiz.id === reviewId)
  return (
    <div className="quiz-tab">
      <section className="card session-body">
        <div className="row-between">
          <button className="btn" onClick={() => setFormMode('manual')}>직접 문제 출제</button>
          <button className="btn btn-primary" onClick={() => setFormMode('ai')}>AI 문제 생성</button>
        </div>
        <strong className="section-title"><Icon name="chart" size={18} /> 질문별 정답률</strong>
        {error && <p className="error-text">{error}</p>}
        {list.length === 0 && <p className="muted">아직 보낸 질문이 없어요</p>}
        {[...list].reverse().map((q) => (
          <QuizRow key={q.id} quiz={q} onOpen={() => setReviewId(q.id)} />
        ))}
      </section>
      {formMode && <QuizForm key={formMode} sessionId={sessionId} aiMode={formMode === 'ai'}
        onCreated={onCreated} onClose={() => setFormMode(null)} />}
      {reviewedQuiz && (
        <TeacherQuizDialog title="학생별 답안 관리" onClose={() => setReviewId(null)}>
          <p className="quiz-question">{reviewedQuiz.question}</p>
          <p className="muted small">
            응답 {reviewedQuiz.answered}/{reviewedQuiz.studentCount} · 정답 {reviewedQuiz.correct} · 채점 대기 {reviewedQuiz.pending}
          </p>
          <AnswerReview key={reviewedQuiz.id} quiz={reviewedQuiz} reloadKey={reloadKey} onChanged={load} />
        </TeacherQuizDialog>
      )}
    </div>
  )
}

function TeacherQuizDialog({ title, onClose, closeDisabled, children }) {
  const dialog = useRef(null)
  useEffect(() => {
    const element = dialog.current
    element.showModal()
    return () => element.close()
  }, [])

  return (
    <dialog ref={dialog} className="card modal teacher-quiz-dialog" aria-label={title} onCancel={(event) => {
      event.preventDefault()
      if (!closeDisabled) onClose()
    }}>
      <header className="row-between">
        <h2>{title}</h2>
        <button type="button" className="btn btn-sm" disabled={closeDisabled} onClick={onClose}>닫기</button>
      </header>
      {children}
    </dialog>
  )
}

const EMPTY_CHOICES = ['', '', '', '']

function QuizForm({ sessionId, aiMode, onCreated, onClose }) {
  const [type, setType] = useState('CHOICE')
  const [question, setQuestion] = useState('')
  const [choices, setChoices] = useState(EMPTY_CHOICES)
  const [answerIndex, setAnswerIndex] = useState(null)
  const [modelAnswer, setModelAnswer] = useState('')
  const [timeLimitSec, setTimeLimitSec] = useState(60)
  const [hint, setHint] = useState('')
  const [busy, setBusy] = useState('')
  const [status, setStatus] = useState('')
  const [draftState, setDraftState] = useState('empty')
  const kept = choices.map((value, index) => ({ value: value.trim(), index })).filter(({ value }) => value)
  const newAnswer = kept.findIndex(({ index }) => index === answerIndex)
  const validQuestion = question.trim() !== '' && (type === 'CHOICE'
    ? kept.length >= 2 && newAnswer >= 0
    : modelAnswer.trim() !== '')

  function edit(setter, value) {
    setter(value)
    if (draftState === 'approved') {
      setDraftState('review')
      setStatus('수정한 초안을 다시 승인해 주세요.')
    }
  }

  function changeType(nextType) {
    if (nextType === type) return
    setType(nextType)
    setDraftState('empty')
    setStatus('')
  }

  async function generate() {
    setDraftState('empty')
    setBusy('ai')
    setStatus('AI가 문제를 만들고 있어요…')
    try {
      const d = await quizzes.generate(sessionId, type, hint)
      setQuestion(d.question)
      setChoices(d.choices ? [...d.choices, ...EMPTY_CHOICES].slice(0, Math.max(4, d.choices.length)) : EMPTY_CHOICES)
      setAnswerIndex(d.answerIndex ?? null)
      setModelAnswer(d.modelAnswer ?? '')
      setDraftState('review')
      setStatus('문제와 정답을 확인하고 초안을 승인해 주세요.')
    } catch (e) {
      setStatus(errText(e, 'AI 가 문제를 만들지 못했어요'))
    } finally {
      setBusy('')
    }
  }

  async function onSubmit(e) {
    e.preventDefault()
    if (busy || !validQuestion || (aiMode && draftState !== 'approved')) return
    setBusy('send')
    try {
      const created = await quizzes.create(sessionId, {
        type,
        question,
        choices: type === 'CHOICE' ? kept.map(({ value }) => value) : null,
        answerIndex: type === 'CHOICE' ? newAnswer : null,
        modelAnswer: type === 'TEXT' ? modelAnswer : null,
        timeLimitSec,
      })
      onCreated(created)
    } catch (err) {
      setStatus(errText(err, '보내지 못했어요'))
    } finally {
      setBusy('')
    }
  }

  return (
    <TeacherQuizDialog title={aiMode ? 'AI 문제 생성' : '직접 문제 출제'} onClose={onClose} closeDisabled={busy === 'send'}>
      <form className="session-body quiz-form" onSubmit={onSubmit}>
        <div className="row-between">
          <div className="seg" role="tablist" aria-label="질문 종류">
            <button type="button" role="tab" aria-selected={type === 'CHOICE'} disabled={!!busy} onClick={() => changeType('CHOICE')}>객관식</button>
            <button type="button" role="tab" aria-selected={type === 'TEXT'} disabled={!!busy} onClick={() => changeType('TEXT')}>주관식</button>
          </div>
          {aiMode && <div className="ai-gen">
            <input value={hint} disabled={!!busy} onChange={(e) => setHint(e.target.value)} maxLength={200} placeholder="수업 내용 (예: 자바 반복문)" aria-label="AI 문제 힌트" />
            <button type="button" className="btn btn-outline" onClick={() => void generate()} disabled={busy !== ''}>
              ✦ {busy === 'ai' ? '만드는 중…' : 'AI로 문제 만들기'}
            </button>
          </div>}
        </div>
        {(!aiMode || draftState !== 'empty') && <fieldset className="quiz-fields" disabled={!!busy}>
          <textarea className="input" value={question} onChange={(e) => edit(setQuestion, e.target.value)} maxLength={1000} rows={3} placeholder="질문" aria-label="질문" required />
          {type === 'CHOICE' ? (
            <div className="choice-edit">
              {choices.map((c, i) => (
                <label key={i} className={`choice-row${answerIndex === i ? ' correct' : ''}`}>
                  <input type="radio" name="answer" checked={answerIndex === i} onChange={() => edit(setAnswerIndex, i)} aria-label={`${i + 1}번을 정답으로`} />
                  <input className="input" value={c} maxLength={200} placeholder={`보기 ${i + 1}`}
                    onChange={(e) => edit(setChoices, choices.map((x, j) => (j === i ? e.target.value : x)))} />
                </label>
              ))}
              <span className="muted small">동그라미를 눌러 정답을 고르세요. 빈 보기는 빠져요.</span>
            </div>
          ) : (
            <label className="field small-label">
              <strong>모범 답안 <span className="muted">(AI 채점 기준)</span></strong>
              <textarea className="input" value={modelAnswer} onChange={(e) => edit(setModelAnswer, e.target.value)} maxLength={2000} rows={3} required />
            </label>
          )}
          <div className="row-between">
            <label className="muted">
              제한 시간{' '}
              <select value={timeLimitSec} onChange={(e) => setTimeLimitSec(Number(e.target.value))}>
                {[30, 60, 120, 300].map((s) => <option key={s} value={s}>{mmss(s)}</option>)}
              </select>
            </label>
          </div>
          <div className="row-between">
            {aiMode && draftState !== 'approved' && (
              <button type="button" className="btn" disabled={!validQuestion} onClick={() => {
                setDraftState('approved')
                setStatus('초안이 승인됐어요. 수업에 보낼 수 있습니다.')
              }}>초안 승인</button>
            )}
            <button type="submit" className="btn btn-primary" disabled={!validQuestion || (aiMode && draftState !== 'approved')}>
              <Icon name="send" size={18} /> 전체 학생에게 보내기
            </button>
          </div>
        </fieldset>}
        <span className="muted" role="status">{status}</span>
      </form>
    </TeacherQuizDialog>
  )
}

function QuizRow({ quiz, onOpen }) {
  const graded = quiz.answered - quiz.pending
  const pct = graded > 0 ? Math.round((quiz.correct / graded) * 100) : 0
  return (
    <div className="quiz-row">
      <button className="quiz-row-head" onClick={onOpen} aria-haspopup="dialog">
        <strong className="quiz-no">{quiz.no}번</strong>
        <span className="quiz-q">{quiz.question}</span>
        <span className="bar" aria-label={`정답률 ${pct}%`}>
          <span className={`bar-fill${pct < 50 ? ' low' : ''}`} style={{ width: `${pct}%` }} />
        </span>
        <strong className="quiz-pct">{graded > 0 ? `${pct}%` : '-'}</strong>
        <span className="muted quiz-count">
          응답 {quiz.answered}/{quiz.studentCount}{quiz.pending > 0 && ` · 채점 중 ${quiz.pending}`}
        </span>
        {quiz.open ? <OpenBadge closesAt={quiz.closesAt} /> : <span className="badge badge-gray">마감</span>}
      </button>
    </div>
  )
}

function OpenBadge({ closesAt }) {
  const left = useRemaining(closesAt)
  return <span className="badge badge-live"><span className="dot" /> {left > 0 ? mmss(left) : '마감'}</span>
}

/** 목업 24: 응답 검토 · 판정 바꾸기 (선생님 판정이 최종) */
function AnswerReview({ quiz, reloadKey, onChanged }) {
  const [rows, setRows] = useState([])
  const [error, setError] = useState('')
  useEffect(() => {
    quizzes.answers(quiz.id).then(setRows, (e) => setError(errText(e, '불러오지 못했어요')))
  }, [quiz.id, reloadKey])

  async function regrade(row, correct) {
    try {
      const updated = await quizzes.regrade(row.answerId, correct)
      setRows((rs) => rs.map((r) => (r.answerId === row.answerId ? updated : r)))
      onChanged()
    } catch (e) {
      setError(errText(e, '바꾸지 못했어요'))
    }
  }

  async function close() {
    await quizzes.close(quiz.id).catch(() => undefined)
    onChanged()
  }

  return (
    <div className="answer-review">
      <div className="row-between">
        <p className="muted small">
          {quiz.type === 'CHOICE'
            ? `정답: ${quiz.answerIndex !== null && quiz.choices ? quiz.choices[quiz.answerIndex] : '-'}`
            : `모범 답안: ${quiz.modelAnswer}`}
        </p>
        {quiz.open && <button className="btn btn-sm" onClick={() => void close()}>지금 마감</button>}
      </div>
      {error && <p className="error-text">{error}</p>}
      {rows.length === 0 && <p className="muted small">아직 응답이 없어요</p>}
      {rows.map((r) => (
        <div key={r.answerId} className="answer-item">
          <span className="avatar avatar-sm"><Icon name="user" size={16} /></span>
          <strong className="answer-name">{r.name}</strong>
          <span className="answer-text">
            {r.text ?? (r.choiceIndex !== null && quiz.choices ? quiz.choices[r.choiceIndex] : '')}
            {r.feedback && <span className="muted small"> — {r.feedback}</span>}
          </span>
          <Verdict row={r} />
          {quiz.type === 'TEXT' && (
            <span className="answer-actions">
              <button className="btn btn-sm" onClick={() => void regrade(r, true)} disabled={r.correct === true}>✓ 맞음으로</button>
              <button className="btn btn-sm" onClick={() => void regrade(r, false)} disabled={r.correct === false}>✕ 틀림으로</button>
            </span>
          )}
        </div>
      ))}
    </div>
  )
}

function Verdict({ row }) {
  if (row.gradedBy === null) return <span className="badge badge-gray">채점 대기</span>
  const who = row.gradedBy === 'AI' ? 'AI: ' : row.gradedBy === 'TEACHER' ? '선생님: ' : ''
  return <span className={`badge ${row.correct ? 'badge-ok' : 'badge-bad'}`}>{who}{row.correct ? '정답' : '오답'}</span>
}

// ---------------- 학생 ----------------

/** 목업 15(응답) → 16(결과). 결과는 주관식이면 AI 채점 뒤 실시간 알림으로 채워진다 */
export function StudentQuizModal({ quiz, onAnswered, onClose }) {
  const left = useRemaining(quiz.closesAt)
  const [choice, setChoice] = useState(null)
  const [text, setText] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const mine = quiz.myAnswer

  async function onSubmit(e) {
    e.preventDefault()
    setBusy(true)
    setError('')
    try {
      onAnswered(await quizzes.answer(quiz.id, quiz.type === 'CHOICE' ? { choiceIndex: choice ?? -1 } : { text }))
    } catch (err) {
      setError(errText(err, '제출하지 못했어요'))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="modal-backdrop" role="dialog" aria-modal="true" aria-label="선생님 질문">
      {mine ? <QuizResult mine={mine} onClose={onClose} /> : (
        <form className="card modal quiz-modal" onSubmit={onSubmit}>
          <header className="quiz-modal-head">
            <Mascot mood={quiz.type === 'CHOICE' ? 'happy' : 'curious'} size={84} />
            <div>
              <h2>선생님 질문</h2>
              <span className="muted"><Icon name="clock" size={16} /> 남은 시간 {mmss(left)}</span>
            </div>
          </header>
          <p className="quiz-question">{quiz.question}</p>
          {quiz.type === 'CHOICE' ? (
            <div className="choice-list">
              {quiz.choices?.map((c, i) => (
                <label key={i} className={`radio-card${choice === i ? ' on' : ''}`}>
                  <input type="radio" name="choice" checked={choice === i} onChange={() => setChoice(i)} />
                  {c}
                </label>
              ))}
            </div>
          ) : (
            <textarea className="input" rows={5} value={text} onChange={(e) => setText(e.target.value)} maxLength={2000}
              placeholder="답을 적어 주세요" autoFocus />
          )}
          {error && <p className="error-text">{error}</p>}
          <button className="btn btn-primary btn-lg"
            disabled={busy || left === 0 || (quiz.type === 'CHOICE' ? choice === null : !text.trim())}>
            {left === 0 ? '시간이 끝났어요' : busy ? '제출 중…' : '제출'}
          </button>
          {left === 0 && <button type="button" className="btn" onClick={onClose}>닫기</button>}
        </form>
      )}
    </div>
  )
}

function QuizResult({ mine, onClose }) {
  const pending = mine.gradedBy === null
  return (
    <div className="card modal quiz-result" aria-live="polite">
      {pending && !mine.feedback ? (
        <>
          <h2>AI 가 채점하고 있어요…</h2>
          <Mascot mood="think" size={130} float />
          <p className="muted">결과가 나오면 여기에 바로 보여요</p>
        </>
      ) : pending ? (
        <>
          <h2>선생님이 확인 중이에요</h2>
          <Mascot mood="listen" size={130} />
          <p className="muted">{mine.feedback}</p>
        </>
      ) : mine.correct ? (
        <>
          <h2><span className="result-icon ok">✓</span> 정답이에요!</h2>
          <Mascot mood="happy" size={130} float />
          {mine.feedback && <p className="muted">{mine.feedback}</p>}
        </>
      ) : (
        <>
          <h2>
            <span className="result-icon warn">!</span> 조금 아쉬워요
            {mine.gradedBy === 'AI' && <span className="badge">AI 채점</span>}
          </h2>
          <Mascot mood="curious" size={130} />
          {mine.feedback && <p className="muted">{mine.feedback}</p>}
        </>
      )}
      <button className="btn" onClick={onClose}>닫기</button>
    </div>
  )
}
