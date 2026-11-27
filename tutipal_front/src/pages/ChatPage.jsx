import { useEffect, useRef, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { formatRetryTime, isQuotaError } from '../api/client'
import { streamChat } from '../api/tutor'
import Icon from '../components/Icon'
import Mascot from '../components/Mascot'
import SubjectPicker from '../components/SubjectPicker'
import { helpDraftOf } from '../utils/helpDraft'
import { useRetryLock } from '../utils/useRetryLock'
import { useAuth } from '../auth'
import PersonalStoredChat from './PersonalStoredChat'

/** 예전 주소(?topic=coding)도 열리게 id → 과목 이름 */
const LEGACY = { general: '자유 주제', coding: '코딩', english: '영어', math: '수학' }
const DEFAULT_TOPIC = '자유 주제'
/** ChatRequest.Message.content 의 @Size(max) 와 같게 */
const MAX_CONTENT = 8000

const EXAMPLES = {
  '자유 주제': ['오늘 공부 계획 세우는 법 알려줘', '집중이 안 될 때 어떻게 해?'],
  코딩: ['자바에서 static 은 언제 써?', 'NullPointerException 은 왜 나?'],
  영어: ['"I am agree" 맞는 표현이야?', '면접에서 쓸 자기소개 문장 다듬어줘'],
  수학: ['이차방정식 근의 공식 유도해줘', '미분과 적분의 관계가 뭐야?'],
}

/** 개인 챗봇 · 단체 방 모두 과목 이름("한국사") 으로 주고받는다. 서버가 이 이름으로 챗봇 지시문을 만든다 (TopicPrompt) */
const examplesOf = (topic) => EXAMPLES[topic] ?? [`${topic} 기초부터 차근차근 알려줘`, `${topic} 공부 순서를 짜 줘`]

function toTopic(value) {
  return LEGACY[value] ?? (value ?? DEFAULT_TOPIC)
}

/** 목업 06 도우미 챗봇 (개인 학습): 주소의 ?topic= 과목 이름 (드롭다운 · 직접 입력) */
export default function ChatPage() {
  const [params, setParams] = useSearchParams()
  const { me } = useAuth()
  const ask = params.get('ask')
  return (
    <PersonalStoredChat key={`${me?.id}:${toTopic(params.get('topic'))}`}
      topic={toTopic(params.get('topic'))}
      onTopicChange={(t) => setParams({ topic: t }, { replace: true })}
      initialAsk={ask ?? undefined}
      onAsked={() => setParams((p) => { p.delete('ask'); return p }, { replace: true })}
    />
  )
}

/**
 * LLM 이 바로 답하고, 답변이 글자 단위로 흘러나온다. 세션 챗봇(목업 13 챗봇 탭)도 같이 쓴다.
 * onRequestHelp(초안): 수업 학생 화면만 넘긴다. Agent 가 도움 요청을 추천한 답변 아래에 버튼을 보여 주고,
 * 누르면 초안을 넘긴다 (PT-113). 개인 학습은 넘기지 않으므로 버튼이 없다. 초안은 다음 질문 기록에도 실리지 않는다.
 */
export function ChatPanel({ topic, onTopicChange, sessionId, initialAsk, onAsked, onRequestHelp }) {
  const [bubbles, setBubbles] = useState([])
  const [input, setInput] = useState('')
  const [streaming, setStreaming] = useState(false)
  const abortRef = useRef(null)
  const sending = useRef(false)
  const endRef = useRef(null)
  /** 챗봇 질문 한도(429)를 다 쓰면 기다릴 시간 동안 보내기를 막는다. 이 패널이 떠 있는 동안만 유지되고(주제를 바꿔도 유지),
   * 페이지 · 세션 탭을 떠났다 오면 풀린다: 그때 다시 보내면 서버가 다시 429 로 막는다 (한도는 서버가 사용자 단위로 지킴). */
  const [lock, lockFor] = useRetryLock()

  useEffect(() => {
    endRef.current?.scrollIntoView({ block: 'end' })
  }, [bubbles])

  // 주제 · 세션이 바뀌면 새 대화
  useEffect(() => {
    abortRef.current?.abort()
    abortRef.current = null
    sending.current = false
    setStreaming(false)
    setBubbles([])
  }, [topic, sessionId])

  useEffect(() => () => abortRef.current?.abort(), [])

  const asked = useRef(null)
  useEffect(() => {
    if (!initialAsk) { asked.current = null; return }
    if (asked.current === initialAsk || sending.current) return
    asked.current = initialAsk
    void send(initialAsk.slice(0, MAX_CONTENT))
    onAsked?.()
  })

  async function send(text, prior = bubbles) {
    const question = text.trim()
    if (!question || sending.current || lock) return
    sending.current = true
    const history = [
      ...prior
        .filter((b) => !b.error && b.content)
        // 서버는 한 줄에 8000자까지 받음 → 긴 답변은 앞부분만 기록으로 보냄
        .map(({ role, content }) => ({ role, content: content.slice(0, MAX_CONTENT) })),
      { role: 'user', content: question },
    ]
    setBubbles([...history, { role: 'assistant', content: '' }])
    setInput('')
    setStreaming(true)
    const controller = new AbortController()
    abortRef.current = controller
    try {
      await streamChat(topic.trim() || DEFAULT_TOPIC, history.slice(-20), (delta) => {
        if (abortRef.current !== controller || controller.signal.aborted) return
        setBubbles((prev) => {
          const next = [...prev]
          const last = next[next.length - 1]
          next[next.length - 1] = { ...last, content: last.content + delta }
          return next
        })
      }, controller.signal, sessionId, (sources) => {
        if (abortRef.current !== controller || controller.signal.aborted) return
        setBubbles((prev) => {
          const next = [...prev]
          next[next.length - 1] = { ...next[next.length - 1], sources }
          return next
        })
      }, (event) => {
        if (abortRef.current !== controller || controller.signal.aborted) return
        const helpDraft = helpDraftOf(event)
        if (!helpDraft) return
        setBubbles((prev) => {
          const next = [...prev]
          next[next.length - 1] = { ...next[next.length - 1], helpDraft }
          return next
        })
      })
    } catch (e) {
      if (abortRef.current === controller) {
        const quota = !controller.signal.aborted && isQuotaError(e)
        if (quota) lockFor(e)
        setBubbles((prev) => {
          const next = [...prev]
          next[next.length - 1] = {
            role: 'assistant',
            content: controller.signal.aborted ? '답변을 중지했어요'
              : quota ? `챗봇 질문 한도를 다 썼어요. ${formatRetryTime(e.retryAt)} 이후 다시 물어봐 주세요`
                : e instanceof Error ? e.message : '답변하지 못했어요',
            error: true,
          }
          return next
        })
      }
    } finally {
      if (abortRef.current === controller) {
        sending.current = false
        setStreaming(false)
      }
    }
  }

  function onSubmit(e) {
    e.preventDefault()
    void send(input)
  }

  function onKeyDown(e) {
    if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
      e.preventDefault()
      void send(input)
    }
  }

  const last = bubbles[bubbles.length - 1]

  return (
    <div className="page chat-page card">
      <header className="chat-head">
        <h1>도우미 챗봇</h1>
        {onTopicChange ? (
          <div className="topic-select">
            <span className="muted">주제</span>
            <SubjectPicker value={topic} onChange={onTopicChange} />
          </div>
        ) : (
          <span className="badge">{topic} · 선생님 설정 적용</span>
        )}
      </header>

      <div className="chat-list" aria-live="polite">
        {bubbles.length === 0 && (
          <div className="chat-empty">
            <Mascot mood="curious" size={120} float />
            <p>무엇이든 물어보세요. {topic.trim() || DEFAULT_TOPIC} 주제에 맞춰 답해 드려요.</p>
            <div className="chips">
              {examplesOf(topic.trim() || DEFAULT_TOPIC).map((q) => (
                <button key={q} className="chip" onClick={() => void send(q)}>{q}</button>
              ))}
            </div>
          </div>
        )}
        {bubbles.map((b, i) =>
          b.role === 'user' ? (
            <div key={i} className="msg msg-user">
              <div className="bubble bubble-user">{b.content}</div>
              <span className="avatar"><Icon name="user" /></span>
            </div>
          ) : (
            <div key={i} className="msg msg-ai">
              <span className="avatar avatar-ai">
                <Mascot
                  size={46}
                  mood={b.error ? 'curious' : streaming && b === last ? (b.content ? 'talk' : 'think') : 'idle'}
                />
              </span>
              <div className={`bubble bubble-ai${b.error ? ' bubble-error' : ''}`}>
                {b.content || <span className="typing">답변을 생각하는 중…</span>}
                {b.sources && <span className="sources">📎 참고한 수업 자료: {b.sources.join(', ')}</span>}
                {onRequestHelp && b.helpDraft && !(streaming && b === last) && (
                  <div>
                    <button type="button" className="btn btn-sm" onClick={() => onRequestHelp(b.helpDraft)}>
                      ✋ 선생님께 도움 요청
                    </button>
                  </div>
                )}
              </div>
            </div>
          ),
        )}
        <div ref={endRef} />
      </div>

      {!streaming && last?.error && bubbles.at(-2)?.role === 'user' &&
        <button className="btn" disabled={!!lock}
          onClick={() => void send(bubbles.at(-2).content, bubbles.slice(0, -2))}>답변 다시 시도</button>}
      <form className="chat-input" onSubmit={onSubmit}>
        <textarea
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={onKeyDown}
          placeholder="질문을 입력하세요 (Enter 보내기 · Shift+Enter 줄바꿈)"
          rows={2}
          maxLength={MAX_CONTENT}
        />
        {streaming ? (
          <button type="button" className="btn" onClick={() => abortRef.current?.abort()}>
            <Icon name="stop" size={18} /> 중지
          </button>
        ) : (
          <button type="submit" className="btn btn-primary" disabled={!input.trim() || !!lock}
            title={lock ? `${formatRetryTime(lock.retryAt)} 이후 다시 보낼 수 있어요` : undefined}>
            보내기
          </button>
        )}
      </form>
    </div>
  )
}
