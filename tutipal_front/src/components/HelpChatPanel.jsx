import { useEffect, useId, useState, useSyncExternalStore } from 'react'
import './HelpChatPanel.css'

/** Independent view. Owner creates/disposes one client per authorized help/account. */
export default function HelpChatPanel({ client, partnerName }) {
  const state = useSyncExternalStore(client.subscribe, client.getSnapshot, client.getSnapshot)
  // Keyed view ensures a draft cannot move between clients/accounts.
  return <ChatView key={client.scope} client={client} state={state}
    userId={client.userId} partnerName={partnerName} />
}

function ChatView({ client, state, userId, partnerName }) {
  const inputHelp = useId()
  const [draft, setDraft] = useState('')
  const [validation, setValidation] = useState('')
  useEffect(() => { setDraft(''); setValidation('') }, [client, userId])
  useEffect(() => { if (state.status === 'closed') { setDraft(''); setValidation('') } }, [state.status])
  const length = [...draft].length
  const ready = state.status === 'ready'
  const canSend = ready && !state.sending && !!draft.trim() && length <= state.maxLength
  function send() {
    if (!canSend) return
    const text = draft
    setDraft(''); setValidation('')
    void client.send(text)
  }
  const labels = { idle: '대기 중', loading: '연결 확인 중', ready: '연결됨', offline: '연결 끊김', closed: '종료됨' }
  return <section className="help-chat-panel" aria-label="개별 도움 채팅">
    <header className="help-chat-heading"><div><strong>도움 채팅</strong><p>{partnerName}님과 대화</p></div>
      <span role="status">{labels[state.status]}</span></header>
    <p className="help-chat-note">화면을 공유하지 않아도 대화할 수 있어요.</p>
    {state.status === 'closed' ? <p className="help-chat-closed" role="status">도움이 끝났거나 접근 권한이 없어 채팅을 닫았어요.</p> : <>
      <div className="help-chat-messages" role="log" aria-label="도움 메시지" aria-live="polite" aria-relevant="additions text">
        {state.items.length === 0 && <p className="muted small">{ready ? '궁금한 내용을 적어 보세요.' : '대화를 불러오는 중이에요.'}</p>}
        {state.items.map(item => <div className={`help-chat-message${item.senderId === userId ? ' own' : ''}`} key={item.id}>
          <span className="help-chat-author">{item.senderId === userId ? '나' : partnerName}</span>
          <p className="help-chat-bubble">{item.text}</p>
          {item.delivery !== 'sent' && <div className="help-chat-delivery">
            <span>{item.delivery === 'failed' ? '전송 실패' : '보내는 중…'}</span>
            {item.delivery === 'failed' && <button type="button" className="btn btn-sm"
              disabled={!ready || state.sending} onClick={() => void client.retry(item.clientMessageId)}>다시 보내기</button>}
          </div>}
        </div>)}
      </div>
      {state.error && <p className="error-text" role="alert">{state.error}</p>}
      {state.status === 'offline' && <button className="btn btn-sm" type="button" onClick={() => void client.refresh()}>다시 연결</button>}
      <form onSubmit={event => { event.preventDefault(); send() }}>
        <label className="field"><span className="small">보낼 메시지</span><textarea className="input" rows={3} value={draft}
          placeholder="궁금한 내용을 적어 주세요" disabled={state.status === 'idle'}
          aria-describedby={inputHelp} aria-invalid={length > state.maxLength || !!validation}
          onChange={event => { setDraft(event.target.value); setValidation('') }}
          onKeyDown={event => {
            if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing && event.keyCode !== 229) {
              event.preventDefault()
              if (length > state.maxLength) setValidation(`메시지는 ${state.maxLength}자까지 보낼 수 있어요.`)
              else send()
            }
          }} /></label>
        <div className="help-chat-compose"><span className="muted small" id={inputHelp}>
          {length} / {state.maxLength}자 · Shift+Enter 줄바꿈</span>
          <button className="btn btn-primary btn-sm" type="submit" disabled={!canSend}>{state.sending ? '보내는 중…' : '보내기'}</button></div>
        {(length > state.maxLength || validation) && <p className="error-text small" role="alert">{validation || `메시지는 ${state.maxLength}자까지 보낼 수 있어요.`}</p>}
      </form>
    </>}
  </section>
}
