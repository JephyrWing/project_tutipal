import { memo, useCallback, useEffect, useRef, useState } from 'react'
import { useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { formatRetryTime, isQuotaError } from '../api/client'
import {
  createConversation,
  deleteConversation,
  deleteMemory,
  getConversation,
  listConversations,
  listMessages,
  saveMemory,
  sendMessage,
  setConversationSaved,
} from '../api/conversations'
import { sessions } from '../api/sessions'
import { applySavedState, personalRetentionDaysOf, retentionNotice } from '../utils/aiRetention'
import Icon from '../components/Icon'
import Mascot from '../components/Mascot'
import SubjectPicker from '../components/SubjectPicker'
import {
  CONVERSATIONS_PATH,
  MEMORY_MAX,
  clientMessageIdFor,
  confirmAndDelete,
  conversationTitle,
  detailFailed,
  detailLoaded,
  initialDetailState,
  initialListState,
  isInputLocked,
  listFailed,
  listPathFor,
  listLoaded,
  listLoading,
  memoryDraft,
  olderLoaded,
  turnDelta,
  turnFailed,
  turnSaved,
  turnStarted,
  turnStopped,
  visibleMessages,
} from '../utils/conversationView'
import './ConversationsPage.css'

/** ConversationService.MAX_QUESTION_LENGTH 와 같게 */
const MAX_CONTENT = 8000
const DEFAULT_TOPIC = '자유 주제'
const REDIRECT_DELAY_MS = 1500

const errorText = (e, fallback) => (e instanceof Error && e.message ? e.message : fallback)
const formatTime = (iso) => (iso ? new Date(iso).toLocaleString('ko-KR') : '')

/**
 * PT-106 AI 대화 기록: /conversations (목록 · 새 대화) 와 /conversations/:id (이어가기 · 요약 · 기억 · 삭제).
 * ?sessionId= 가 있으면 그 수업에서 내가 나눈 대화만 보고, 새 대화도 그 수업으로 만든다.
 * 모델 출력은 글자로만 보여 준다 (HTML 로 렌더하지 않음).
 */
export default function ConversationsPage() {
  const { id } = useParams()
  return id ? <ConversationDetail key={id} id={id} /> : <ConversationList />
}

/**
 * The session's AI conversation retention (days after it ends) for the notice, or null while unknown (personal
 * scope, not loaded, or no longer a participant): the notice then falls back to "up to 90 days".
 */
function useSessionRetention(sessionId) {
  const [days, setDays] = useState(null)
  useEffect(() => {
    setDays(null)
    if (!sessionId) return undefined
    let current = true
    sessions.get(sessionId).then(
      (s) => { if (current) setDays(s?.aiRetentionDays ?? null) },
      () => {},
    )
    return () => { current = false }
  }, [sessionId])
  return days
}

function ConversationList() {
  const navigate = useNavigate()
  const [params] = useSearchParams()
  const sessionId = params.get('sessionId') || null
  const [state, setState] = useState(initialListState)
  const [revision, setRevision] = useState(0)
  const [topic, setTopic] = useState(DEFAULT_TOPIC)
  const [creating, setCreating] = useState(false)
  const [createError, setCreateError] = useState('')
  const [loadingMore, setLoadingMore] = useState(false)
  const retentionDays = useSessionRetention(sessionId)
  /** Server's personal retention (list response); null until loaded → the notice falls back to 30. */
  const [personalDays, setPersonalDays] = useState(null)
  const alive = useRef(true)
  /** Changes with ?sessionId: a next page requested for the previous scope is dropped. */
  const scopeRef = useRef(sessionId)
  useEffect(() => { alive.current = true; return () => { alive.current = false } }, [])

  // 다른 수업(또는 개인)으로 바뀌면 이전 범위의 목록을 보이지 않게 처음부터
  useEffect(() => {
    scopeRef.current = sessionId
    setState(initialListState())
    setLoadingMore(false)
  }, [sessionId])

  useEffect(() => {
    const controller = new AbortController()
    setState((s) => listLoading(s))
    listConversations({ sessionId }, controller.signal).then(
      (page) => {
        setState((s) => listLoaded(s, page))
        setPersonalDays((d) => personalRetentionDaysOf(page) ?? d)
      },
      (e) => { if (!controller.signal.aborted) setState((s) => listFailed(s, e)) },
    )
    return () => controller.abort()
  }, [sessionId, revision])

  async function loadMore() {
    if (loadingMore || !state.nextCursor) return
    const scope = sessionId
    const current = () => alive.current && scopeRef.current === scope
    setLoadingMore(true)
    try {
      const page = await listConversations({ sessionId: scope, cursor: state.nextCursor })
      if (current()) setState((s) => listLoaded(s, page, { append: true }))
    } catch (e) {
      if (current()) setState((s) => listFailed(s, e))
    } finally {
      if (current()) setLoadingMore(false)
    }
  }

  async function create(e) {
    e.preventDefault()
    if (creating) return
    const chosen = topic.trim()
    if (!sessionId && !chosen) { setCreateError('주제를 골라 주세요'); return }
    setCreating(true)
    setCreateError('')
    try {
      const created = await createConversation(sessionId ? { sessionId } : { topic: chosen })
      if (alive.current) navigate(`${CONVERSATIONS_PATH}/${encodeURIComponent(created.id)}`)
    } catch (err) {
      if (alive.current) setCreateError(errorText(err, '대화를 만들지 못했어요'))
    } finally {
      if (alive.current) setCreating(false)
    }
  }

  return (
    <div className="page">
      <section className="card session-body conversations-page">
        <header className="row-between review-head">
          <div className="row-gap">
            <Mascot mood="happy" size={90} />
            <div>
              <h1>AI 대화 기록</h1>
              <p className="muted">
                {sessionId ? '이 수업에서 AI 조교와 나눈 대화예요.' : '개인 학습에서 AI 조교와 나눈 대화예요.'}
                {' '}이어서 질문하면 앞 대화 내용을 기억하고 답해요.
              </p>
              <p className="muted small">{retentionNotice({ sessionId, aiRetentionDays: retentionDays, personalRetentionDays: personalDays })}</p>
            </div>
          </div>
        </header>

        <form className="conversation-new row-gap" onSubmit={(e) => void create(e)}>
          {sessionId ? (
            <span className="badge">수업 대화 · 선생님 설정 적용</span>
          ) : (
            <div className="topic-select">
              <span className="muted">주제</span>
              <SubjectPicker value={topic} onChange={setTopic} disabled={creating} />
            </div>
          )}
          <button type="submit" className="btn btn-primary" disabled={creating}>
            <Icon name="plus" size={18} /> 새 대화
          </button>
          {createError && <span className="error-text" role="alert">{createError}</span>}
        </form>

        {state.status === 'loading' && state.items.length === 0 && <p role="status">대화 목록을 불러오는 중이에요…</p>}
        {state.status === 'error' && (
          <div role="alert">
            <p className="error-text">{state.error}</p>
            <button className="btn" onClick={() => setRevision((v) => v + 1)}>다시 불러오기</button>
          </div>
        )}
        {state.status === 'empty' && (
          <div className="chat-empty">
            <Mascot mood="curious" size={110} float />
            <p>아직 저장된 AI 대화가 없어요. 새 대화를 시작해 보세요.</p>
          </div>
        )}

        <ul className="conversation-list" aria-label="AI 대화 목록">
          {state.items.map((c) => (
            <li key={c.id}>
              <button className="conversation-row" onClick={() => navigate(`${CONVERSATIONS_PATH}/${encodeURIComponent(c.id)}`)}>
                <span className="post-icon"><Icon name="chat" size={22} /></span>
                <span className="conversation-text">
                  <strong>{conversationTitle(c)}</strong>
                  <time className="muted small" dateTime={c.lastMessageAt}>{formatTime(c.lastMessageAt)}</time>
                </span>
                {c.saved && <span className="badge badge-ok">저장됨</span>}
                <span className="badge">{c.topic}</span>
                <span className="muted small">메시지 {c.messageCount}개</span>
              </button>
            </li>
          ))}
        </ul>
        {state.hasMore && (
          <button className="btn" disabled={loadingMore} onClick={() => void loadMore()}>
            {loadingMore ? '불러오는 중…' : '더 보기'}
          </button>
        )}
      </section>
    </div>
  )
}

function ConversationDetail({ id }) {
  const navigate = useNavigate()
  const [state, setState] = useState(initialDetailState)
  const [revision, setRevision] = useState(0)
  const [loadingOlder, setLoadingOlder] = useState(false)
  const [memoryText, setMemoryText] = useState(null)
  const [memoryBusy, setMemoryBusy] = useState(false)
  const [actionError, setActionError] = useState('')
  const [deleting, setDeleting] = useState(false)
  const [savingToggle, setSavingToggle] = useState(false)
  const retentionDays = useSessionRetention(state.conversation?.sessionId ?? null)
  const abortRef = useRef(null)
  const sending = useRef(false)
  const endRef = useRef(null)
  const alive = useRef(true)
  useEffect(() => { alive.current = true; return () => { alive.current = false; abortRef.current?.abort() } }, [])

  useEffect(() => {
    const controller = new AbortController()
    setState((s) => (s.status === 'error' ? { ...s, status: 'loading', error: null } : s))
    Promise.all([getConversation(id, controller.signal), listMessages(id, {}, controller.signal)]).then(
      ([conversation, page]) => setState((s) => detailLoaded(s, conversation, page)),
      (e) => { if (!controller.signal.aborted) setState((s) => detailFailed(s, e)) },
    )
    return () => controller.abort()
  }, [id, revision])

  // 없는 대화(삭제됨 · 남의 대화) → 잠깐 안내하고 목록으로
  useEffect(() => {
    if (!state.redirectTo) return undefined
    const timer = setTimeout(() => navigate(state.redirectTo, { replace: true }), REDIRECT_DELAY_MS)
    return () => clearTimeout(timer)
  }, [state.redirectTo, navigate])

  const shown = visibleMessages(state)
  const lastShown = shown.at(-1)
  useEffect(() => {
    endRef.current?.scrollIntoView({ block: 'end' })
  }, [shown.length, lastShown?.content])

  const refreshConversation = useCallback(async () => {
    const conversation = await getConversation(id)
    if (alive.current) setState((s) => ({ ...s, conversation }))
  }, [id])

  async function loadOlder() {
    if (loadingOlder || !state.nextBefore) return
    setLoadingOlder(true)
    setActionError('')
    try {
      const page = await listMessages(id, { before: state.nextBefore })
      if (alive.current) setState((s) => olderLoaded(s, page))
    } catch (e) {
      if (!alive.current) return
      if (e?.status === 404) setState((s) => detailFailed(s, e))
      else setActionError(errorText(e, '이전 메시지를 불러오지 못했어요'))
    } finally {
      if (alive.current) setLoadingOlder(false)
    }
  }

  async function send(text) {
    const question = text.trim()
    if (!question || sending.current || state.status !== 'ready') return
    sending.current = true
    const controller = new AbortController()
    abortRef.current = controller
    // 같은 글을 다시 보내면 실패한 턴의 전송 번호를 그대로 쓴다 (저장됐는데 saved 만 못 받은 경우 중복 저장 방지)
    const clientMessageId = clientMessageIdFor(state, question)
    setState((s) => turnStarted(s, question, clientMessageId))
    try {
      const result = await sendMessage(id, question, {
        onDelta: (t) => {
          if (abortRef.current === controller && !controller.signal.aborted) setState((s) => turnDelta(s, t))
        },
      }, controller.signal, clientMessageId)
      if (!alive.current || abortRef.current !== controller) return
      if (result.saved) setState((s) => turnSaved(s, result.saved))
      else setState((s) => turnFailed(s, new Error('답변을 저장하지 못했어요')))
      // 요약은 서버가 턴이 끝난 뒤 따로 만든다: 잠시 뒤 다시 읽으면 새 요약이 보일 수 있다
      void refreshConversation().catch(() => {})
    } catch (e) {
      if (!alive.current || abortRef.current !== controller) return
      if (controller.signal.aborted) {
        setState((s) => turnStopped(s))
      } else {
        const error = isQuotaError(e)
          ? Object.assign(new Error(`${e.message} ${formatRetryTime(e.retryAt)} 이후 다시 물어봐 주세요`), { status: 429 })
          : e
        setState((s) => turnFailed(s, error))
      }
    } finally {
      if (abortRef.current === controller) {
        abortRef.current = null
        sending.current = false
      }
    }
  }

  function onSubmit(e) {
    e.preventDefault()
    void send(state.input)
  }

  function onKeyDown(e) {
    if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
      e.preventDefault()
      void send(state.input)
    }
  }

  async function removeConversation() {
    if (deleting || isInputLocked(state)) return
    setDeleting(true)
    setActionError('')
    try {
      await confirmAndDelete({
        confirm: () => window.confirm('이 대화를 지울까요? 메시지 · 요약 · 이 대화에서 저장한 기억이 모두 지워지고 되돌릴 수 없어요.'),
        remove: () => deleteConversation(id),
        navigate: (to) => navigate(to, { replace: true }),
      })
    } catch (e) {
      if (!alive.current) return
      if (e?.status === 404) setState((s) => detailFailed(s, e))
      else setActionError(errorText(e, '대화를 지우지 못했어요'))
    } finally {
      if (alive.current) setDeleting(false)
    }
  }

  /** "저장" 켜고 끄기: 저장한 대화는 보관 기간이 지나도 남는다 (사용자가 지울 때까지). */
  async function toggleSaved() {
    if (savingToggle || !state.conversation) return
    setSavingToggle(true)
    setActionError('')
    try {
      const result = await setConversationSaved(id, !state.conversation.saved)
      if (alive.current) setState((s) => ({ ...s, conversation: applySavedState(s.conversation, result) }))
    } catch (err) {
      if (!alive.current) return
      if (err?.status === 404) setState((s) => detailFailed(s, err))
      else setActionError(errorText(err, '저장 상태를 바꾸지 못했어요'))
    } finally {
      if (alive.current) setSavingToggle(false)
    }
  }

  async function submitMemory(e) {
    e.preventDefault()
    const text = (memoryText ?? '').trim()
    if (!text || memoryBusy) return
    setMemoryBusy(true)
    setActionError('')
    try {
      await saveMemory(id, text)
      if (!alive.current) return
      setMemoryText(null)
      await refreshConversation()
    } catch (err) {
      if (!alive.current) return
      if (err?.status === 404) setState((s) => detailFailed(s, err))
      else setActionError(errorText(err, '기억을 저장하지 못했어요'))
    } finally {
      if (alive.current) setMemoryBusy(false)
    }
  }

  async function removeMemory(memoryId) {
    if (memoryBusy) return
    setMemoryBusy(true)
    setActionError('')
    try {
      await deleteMemory(id, memoryId)
      if (alive.current) await refreshConversation()
    } catch (err) {
      if (!alive.current) return
      if (err?.status === 404) {
        void refreshConversation().catch((e) => { if (alive.current) setState((s) => detailFailed(s, e)) })
      } else setActionError(errorText(err, '기억을 지우지 못했어요'))
    } finally {
      if (alive.current) setMemoryBusy(false)
    }
  }

  if (state.status === 'loading') {
    return <div className="page"><section className="card session-body"><p role="status">대화를 불러오는 중이에요…</p></section></div>
  }
  if (state.status === 'missing') {
    return (
      <div className="page">
        <section className="card session-body" role="alert">
          <p>{state.notice?.text}</p>
          <button className="btn" onClick={() => navigate(state.redirectTo ?? CONVERSATIONS_PATH, { replace: true })}>목록으로</button>
        </section>
      </div>
    )
  }
  if (state.status === 'error') {
    return (
      <div className="page">
        <section className="card session-body" role="alert">
          <p className="error-text">{state.error}</p>
          <div className="row-gap">
            <button className="btn" onClick={() => setRevision((v) => v + 1)}>다시 불러오기</button>
            <button className="btn" onClick={() => navigate(listPathFor(state.conversation))}>목록으로</button>
          </div>
        </section>
      </div>
    )
  }

  const { conversation } = state
  const locked = isInputLocked(state)
  const memories = conversation?.memories ?? []

  return (
    <div className="page conversation-detail">
      <section className="card chat-page">
        <header className="chat-head">
          <button className="btn-link" onClick={() => navigate(listPathFor(conversation))}>
            ← 목록
          </button>
          <h1>{conversationTitle(conversation)}</h1>
          <span className="badge">{conversation?.sessionId ? `수업 대화 · ${conversation.topic}` : conversation?.topic}</span>
          {conversation?.saved && <span className="badge badge-ok">저장됨</span>}
          <button className="btn btn-sm" disabled={savingToggle} aria-pressed={!!conversation?.saved}
            title="저장한 대화는 보관 기간이 지나도 지워지지 않아요"
            onClick={() => void toggleSaved()}>
            {conversation?.saved ? '저장 해제' : '저장'}
          </button>
          <button className="btn btn-outline-danger btn-sm" disabled={deleting || locked}
            onClick={() => void removeConversation()}>
            <Icon name="trash" size={16} /> 대화 삭제
          </button>
        </header>

        {actionError && <p className="error-text" role="alert">{actionError}</p>}
        <p className="muted small">
          {conversation?.saved
            ? `저장한 대화예요. 직접 지우기 전까지 보관 기간이 지나도 남아요.${
              conversation.sessionId ? ' 이 수업의 기억은 수업 보관 기간이 지나면 지워져요.' : ''}`
            : retentionNotice({
              sessionId: conversation?.sessionId,
              aiRetentionDays: retentionDays,
              personalRetentionDays: personalRetentionDaysOf(conversation),
            })}
        </p>

        <div className="chat-list" aria-live="polite">
          {state.hasMore && (
            <button className="btn btn-sm conversation-older" disabled={loadingOlder} onClick={() => void loadOlder()}>
              {loadingOlder ? '불러오는 중…' : '이전 메시지 더 보기'}
            </button>
          )}
          {shown.length === 0 && (
            <div className="chat-empty">
              <Mascot mood="curious" size={110} float />
              <p>아직 메시지가 없어요. {conversation?.topic} 주제로 무엇이든 물어보세요.</p>
            </div>
          )}
          {shown.map((m) => <MessageRow key={m.id} message={m} />)}
          <div ref={endRef} />
        </div>

        {state.notice && (
          <p className={state.notice.kind === 'error' ? 'error-text' : 'conversation-notice'} role="alert">
            {state.notice.text}
          </p>
        )}

        <form className="chat-input" onSubmit={onSubmit}>
          <textarea
            value={state.input}
            onChange={(e) => { const value = e.target.value; setState((s) => ({ ...s, input: value })) }}
            onKeyDown={onKeyDown}
            placeholder="이어서 질문하세요 (Enter 보내기 · Shift+Enter 줄바꿈)"
            rows={2}
            maxLength={MAX_CONTENT}
            disabled={locked}
          />
          {locked ? (
            <button type="button" className="btn" onClick={() => abortRef.current?.abort()}>
              <Icon name="stop" size={18} /> 중지
            </button>
          ) : (
            <button type="submit" className="btn btn-primary" disabled={!state.input.trim()}>보내기</button>
          )}
        </form>
      </section>

      <aside className="card conversation-side">
        <h2 className="section-title">대화 요약</h2>
        {conversation?.summary ? (
          <>
            <p className="conversation-summary">{conversation.summary.text}</p>
            <time className="muted small" dateTime={conversation.summary.updatedAt}>
              {formatTime(conversation.summary.updatedAt)} 기준
            </time>
          </>
        ) : (
          <p className="muted small">대화가 길어지면 앞부분 요약이 자동으로 만들어져요.</p>
        )}

        <h2 className="section-title">기억</h2>
        <p className="muted small">저장한 기억은 같은 {conversation?.sessionId ? '수업' : '개인 학습'}의 다른 대화에서도 AI 조교가 참고해요.</p>
        {memories.length === 0 && <p className="muted small">저장한 기억이 없어요.</p>}
        <ul className="conversation-memories">
          {memories.map((m) => (
            <li key={m.id}>
              <span>{m.text}</span>
              <button className="btn-link small" disabled={memoryBusy} onClick={() => void removeMemory(m.id)}
                aria-label="이 기억 지우기">✕</button>
            </li>
          ))}
        </ul>
        {memoryText === null ? (
          <button className="btn btn-sm" disabled={memoryBusy} onClick={() => setMemoryText(memoryDraft(state))}>
            기억 저장
          </button>
        ) : (
          <form className="conversation-memory-form" onSubmit={(e) => void submitMemory(e)}>
            <textarea value={memoryText} onChange={(e) => setMemoryText(e.target.value)} rows={4}
              maxLength={MEMORY_MAX} aria-label="저장할 기억" placeholder="기억해 둘 내용을 써 주세요" />
            <span className="muted small">{memoryText.length} / {MEMORY_MAX}</span>
            <div className="row-gap">
              <button type="submit" className="btn btn-primary btn-sm" disabled={memoryBusy || !memoryText.trim()}>저장</button>
              <button type="button" className="btn btn-sm" disabled={memoryBusy} onClick={() => setMemoryText(null)}>취소</button>
            </div>
          </form>
        )}
      </aside>
    </div>
  )
}

/** Stored rows keep their object identity, so only the streaming row re-renders on each delta. */
const MessageRow = memo(function MessageRow({ message: m }) {
  return m.role === 'user' ? (
    <div className="msg msg-user">
      <div className="bubble bubble-user">{m.content}</div>
      <span className="avatar"><Icon name="user" /></span>
    </div>
  ) : (
    <div className="msg msg-ai">
      <span className="avatar avatar-ai">
        <Mascot size={46} mood={m.streaming ? (m.content ? 'talk' : 'think') : 'idle'} />
      </span>
      <div className="bubble bubble-ai">
        {m.content || <span className="typing">답변을 생각하는 중…</span>}
      </div>
    </div>
  )
})
