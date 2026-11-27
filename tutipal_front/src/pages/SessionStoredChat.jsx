import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { useAuth } from '../auth'
import { createConversation, sendMessage } from '../api/conversations'
import { formatRetryTime, isQuotaError } from '../api/client'
import { clientMessageIdFor, initialDetailState, turnDelta, turnFailed, turnSaved, turnStarted, turnStopped, visibleMessages } from '../utils/conversationView'
import { helpDraftOf } from '../utils/helpDraft'
import { useRetryLock } from '../utils/useRetryLock'

/** Separate session entry: legacy ChatPanel and widget transports keep their contracts. */
export default function SessionStoredChat(props) {
  const { me } = useAuth()
  return <StoredSessionChat key={`${me?.id}:${props.sessionId}:${props.topic}`} {...props} />
}

function StoredSessionChat({ topic, sessionId, onRequestHelp }) {
  const [state, setState] = useState(() => ({ ...initialDetailState(), status: 'ready' }))
  const [creating, setCreating] = useState(false)
  const [metadata, setMetadata] = useState({})
  const [pendingMeta, setPendingMeta] = useState({})
  const [lock, lockFor] = useRetryLock()
  const conversation = useRef(null)
  const creation = useRef(null)
  const flight = useRef(null)
  const alive = useRef(false)
  useEffect(() => {
    alive.current = true
    return () => { alive.current = false; flight.current?.abort(); flight.current = null }
  }, [])

  async function send(text) {
    const question = text.trim()
    if (!question || flight.current || lock || state.status === 'missing') return
    const controller = new AbortController()
    flight.current = controller
    const current = () => alive.current && flight.current === controller
    const key = clientMessageIdFor(state, question)
    let meta = {}
    setPendingMeta({})
    setState(s => turnStarted(s, question, key))
    try {
      if (!conversation.current) {
        setCreating(true)
        // Stop/retry share a pending creation, avoiding a second empty conversation.
        const pendingCreation = creation.current ??= createConversation({ sessionId })
        let created
        try { created = await pendingCreation }
        catch (error) { if (creation.current === pendingCreation) creation.current = null; throw error }
        if (!current()) return
        conversation.current = created
        setState(s => ({ ...s, conversation: created }))
        setCreating(false)
      }
      controller.signal.throwIfAborted()
      const result = await sendMessage(conversation.current.id, question, {
        onDelta: delta => { if (current() && !controller.signal.aborted) setState(s => turnDelta(s, delta)) },
        onSources: sources => {
          if (current() && !controller.signal.aborted) { meta = { ...meta, sources }; setPendingMeta(meta) }
        },
        onAgent: event => {
          if (current() && !controller.signal.aborted) { meta = { ...meta, helpDraft: helpDraftOf(event) }; setPendingMeta(meta) }
        },
      }, controller.signal, key)
      if (!current()) return
      if (result.saved) {
        // Metadata is transient. A replay/replaced answer must not inherit another answer's recommendation.
        setMetadata(m => ({ ...m, [result.saved.assistantMessageId]: result.saved.replaced ? {} : meta }))
        setState(s => turnSaved(s, result.saved))
      } else setState(s => turnFailed(s, new Error('답변을 저장하지 못했어요. 같은 질문으로 다시 시도할 수 있어요.')))
    } catch (error) {
      if (!current()) return
      if (controller.signal.aborted) setState(s => turnStopped(s))
      else {
        if (isQuotaError(error)) lockFor(error)
        setState(s => {
          const next = turnFailed(s, error)
          return [403, 404, 410].includes(error?.status)
            ? { ...next, status: 'missing', pending: null, retry: null, input: '', messages: [], conversation: null } : next
        })
      }
    } finally {
      if (current()) { flight.current = null; setCreating(false) }
    }
  }

  function stop() {
    const controller = flight.current
    if (!controller) return
    flight.current = null
    controller.abort()
    setCreating(false)
    setPendingMeta({})
    setState(s => ({ ...turnStopped(s), notice: { text: '답변을 중지했어요. 저장 여부가 불확실한 경우 같은 질문으로 다시 확인할 수 있어요.' } }))
  }

  const busy = state.pending !== null
  return <section className="page chat-page card">
    <header className="chat-head">
      <h1>도우미 챗봇</h1>
      <span className="badge">{topic} · 선생님 설정 적용</span>
      {state.conversation && <Link className="btn" to={`/conversations/${encodeURIComponent(state.conversation.id)}`}>이 대화 기록 열기</Link>}
    </header>
    <p className="muted small">완료된 질문과 답변은 이 수업의 AI 대화 기록에 남아요. 보관 기간 이후에도 남기려면 기록에서 대화 저장을 선택해 주세요.</p>
    <div className="chat-list" aria-live="polite">
      {!state.messages.length && !busy && <p>{topic} 수업의 질문을 입력해 주세요.</p>}
      {visibleMessages(state).map(m => {
        const meta = m.streaming ? pendingMeta : metadata[m.id]
        return <div key={m.id} className={`msg ${m.role === 'user' ? 'msg-user' : 'msg-ai'}`}>
          <div className={`bubble ${m.role === 'user' ? 'bubble-user' : 'bubble-ai'}`}>
            {m.content || '답변을 생각하는 중…'}
            {meta?.sources?.length > 0 && <span className="sources">📎 참고한 수업 자료: {meta.sources.join(', ')}</span>}
            {!busy && m.role === 'assistant' && meta?.helpDraft && onRequestHelp &&
              <div><button type="button" className="btn btn-sm" onClick={() => onRequestHelp(meta.helpDraft)}>✋ 선생님께 도움 요청</button></div>}
          </div>
        </div>
      })}
    </div>
    {state.notice && <p role="alert">{state.notice.message ?? state.notice.text ?? String(state.notice)}</p>}
    {state.retry && !busy && <button className="btn" disabled={!!lock} onClick={() => void send(state.input)}>답변 다시 시도</button>}
    {!busy && !state.notice && !state.retry && state.messages.length > 0 && <p role="status">질문과 답변이 기록에 저장됐어요.</p>}
    {lock && <p role="status">{formatRetryTime(lock.retryAt)} 이후 다시 보낼 수 있어요.</p>}
    <form className="chat-input" onSubmit={e => { e.preventDefault(); void send(state.input) }}>
      <textarea aria-label="질문" placeholder="질문을 입력하세요" rows={2} maxLength={8000}
        disabled={busy || state.status === 'missing'} value={state.input} onChange={e => setState(s => ({ ...s, input: e.target.value }))}
        onKeyDown={e => { if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) { e.preventDefault(); void send(state.input) } }} />
      {busy ? <button key="stop" type="button" className="btn" onClick={e => { e.preventDefault(); stop() }}>{creating ? '대화 준비 중지' : '중지'}</button>
        : <button key="send" type="submit" className="btn btn-primary" disabled={!state.input.trim() || !!lock || state.status === 'missing'}>보내기</button>}
    </form>
  </section>
}
