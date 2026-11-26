import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { ApiRequestError, formatRetryTime, isQuotaError } from '../api/client'
import { reviewApi } from '../api/review'
import { screenApi } from '../api/screen'
import { streamChat } from '../api/tutor'
import { createScreenAnalysis } from '../utils/screenAnalysis'
import { createWidgetChat } from '../utils/widgetChat'
import { screenSummaryText } from '../utils/screenSummary'
import { useRetryLock } from '../utils/useRetryLock'
import Icon from '../components/Icon'
import Mascot from '../components/Mascot'

/** 화면이 그대로면 서버를 부르지 않으므로, 40초마다 한 장 찍어 비교만 한다 */
const SUMMARY_EVERY_MS = 40_000

/**
 * 목업 07 캐릭터 위젯 (Electron 의 투명 · 항상 위 작은 창).
 * 화면 분석이 켜져 있으면 고른 창을 주기적으로 요약해 말풍선에 보여 주고(07),
 * "질문하기" 로 지금 화면에 대해 물어볼 수 있다(08). 이미지는 저장하지 않는다.
 */
export default function WidgetPage() {
  const navigate = useNavigate()
  const desktop = window.desktop
  const [settings, setSettings] = useState(null)
  const [paused, setPaused] = useState(false)
  const [asking, setAsking] = useState(false)
  const [question, setQuestion] = useState('')
  const [text, setText] = useState('')
  const [mood, setMood] = useState('happy')
  /** 선택한 창이 닫히면 다시 선택해야 한다. */
  const [gone, setGone] = useState(false)
  const [suggestion, setSuggestion] = useState(null)
  const [chatting, setChatting] = useState(false)
  const [failedQuestion, setFailedQuestion] = useState(null)
  const [questionLock, lockQuestion] = useRetryLock()
  const lastQuestion = useRef('')
  const chat = useRef(null)

  const analysis = useRef(null)
  /** 화면 요약 한도(429)로 멈춘 동안 보여 줄 다시 시작 시각 (표시용). */
  const autoQuotaAt = useRef(null)
  const showAutoPaused = () => {
    const at = autoQuotaAt.current ?? new Date(analysis.current.autoPausedUntil())
    setMood('curious')
    setText(`화면 요약 한도를 다 썼어요. ${formatRetryTime(at)} 이후에 다시 살펴볼게요`)
  }
  if (!analysis.current) analysis.current = createScreenAnalysis({
    capture: () => desktop.captureScreen(),
    describe: async (image, _question, signal) => screenSummaryText(await screenApi.describe(image, undefined, signal)),
    checkTarget: (sourceId) => desktop.getScreenTargetStatus(sourceId),
    onInvalidate: () => { void desktop?.cancelScreenCapture?.(); chat.current?.reset(); setFailedQuestion(null); lastQuestion.current = ''; setText(''); setSuggestion(null); setAsking(false); setQuestion(''); setMood('happy') },
    observe: (summary, seconds) => reviewApi.observe(summary, seconds),
    onAnswer: (answer) => { if (!chat.current?.busy()) { setText(answer); setMood('happy') } },
    onSuggestion: setSuggestion,
    onGone: (closed) => {
      setGone(closed)
      if (!closed) setSettings(null)
      if (closed) { setMood('curious'); setText('보던 창이 닫혔어요. 볼 창을 다시 골라 주세요.') }
    },
    // 멈춘 동안 다음 주기(창을 다시 고른 뒤 · 질문 답을 보여 준 뒤 포함)마다 안내를 다시 띄운다
    onPaused: showAutoPaused,
    onError: (error, asked) => {
      if (!asked && error instanceof ApiRequestError && error.status === 503) return
      if (isQuotaError(error)) {
        // 자동 요약은 엔진이 기다릴 시간만큼 멈췄다가 스스로 다시 시작하고, 질문 한도는 자동 요약에 영향을 주지 않는다.
        if (!asked) {
          autoQuotaAt.current = error.retryAt
          showAutoPaused()
          return
        }
        setMood('curious')
        setText(`질문 한도를 다 썼어요. ${formatRetryTime(error.retryAt)} 이후 다시 물어봐 주세요`)
        return
      }
      setMood('curious')
      setText(error instanceof ApiRequestError && error.status === 401
        ? '메인 창에서 로그인하면 화면을 같이 볼 수 있어요.'
        : error instanceof Error ? error.message : '지금은 화면을 보지 못했어요')
    },
  })
  if (!chat.current) chat.current = createWidgetChat({
    getContext: () => desktop.getWidgetChatContext(),
    getSummary: () => analysis.current.summaryForChat(),
    stream: streamChat,
    onBusy: setChatting,
    onStart: value => { lastQuestion.current = value; setFailedQuestion(null); setSuggestion(null); setText('화면을 보면서 생각하는 중…'); setMood('think') },
    onDelta: answer => { setText(answer); setMood('talk') },
    onDone: answer => { setText(answer || '답변 내용이 도착하지 않았어요.'); setMood('happy') },
    onError: error => {
      setFailedQuestion(lastQuestion.current)
      setMood('curious')
      if (isQuotaError(error)) {
        lockQuestion(error)
        setText(`질문 한도를 다 썼어요. ${formatRetryTime(error.retryAt)} 이후 다시 물어봐 주세요`)
      } else setText(error instanceof ApiRequestError && error.status === 401
        ? '메인 창에서 로그인하면 질문할 수 있어요.'
        : error instanceof Error ? error.message : '답변하지 못했어요.')
    },
  })

  useEffect(() => {
    document.documentElement.classList.add('transparent')
    return () => document.documentElement.classList.remove('transparent')
  }, [])

  // 화면 분석 설정 (메인 창에서 바꾸면 알려 줌)
  useEffect(() => {
    if (!desktop) return
    let active = true
    let loadRevision = 0
    const load = () => {
      const ownRevision = ++loadRevision
      return desktop.getScreenSettings().then((value) => {
        if (active && ownRevision === loadRevision) setSettings(value)
      }, () => { if (active && ownRevision === loadRevision) setSettings(null) })
    }
    load()
    desktop.getReviewDeletionState().then(value => { if (active) analysis.current.deletion(value) })
    const unsubscribe = desktop.onScreenSettingsChanged(() => {
      analysis.current.setRunning(false)
      analysis.current.reset()
      setSettings(null)
      setGone(false)
      load()
    })
    const reviewUnsubscribe = desktop.onReviewDeletionChanged(value => analysis.current.deletion(value))
    const contextUnsubscribe = desktop.onWidgetChatContextChanged(() => analysis.current.reset())
    return () => {
      active = false
      analysis.current.setRunning(false)
      unsubscribe()
      reviewUnsubscribe()
      contextUnsubscribe()
    }
  }, [desktop])

  const watching = !!settings?.enabled && !paused && !gone
  useEffect(() => {
    analysis.current.setRunning(watching)
    if (!watching || asking) return
    if (!chat.current.busy()) void analysis.current.look()
    const timer = setInterval(() => {
      if (chat.current.busy()) void analysis.current.summaryForChat().catch(() => undefined)
      else void analysis.current.look()
    }, SUMMARY_EVERY_MS)
    return () => clearInterval(timer)
  }, [watching, asking, settings])

  async function onAsk(e) {
    e.preventDefault()
    const q = question.trim()
    if (!q || chat.current.busy() || questionLock) return
    setAsking(false)
    setQuestion('')
    await chat.current.ask(q)
  }

  function openAsk() {
    if (chat.current.busy()) return
    // 화면 분석이 꺼져 있으면 일반 챗봇으로
    if (!settings?.enabled || paused || gone) {
      if (desktop) desktop.openChat()
      else navigate('/chat')
      return
    }
    setAsking(true)
  }

  function togglePause() {
    analysis.current.setRunning(paused)
    setPaused((active) => !active)
  }

  const off = !settings?.enabled
  const message = off
    ? '화면 분석을 켜면 보고 있는 창을 요약해 드려요. 궁금한 건 질문하기!'
    : paused
      ? '화면 분석을 잠시 멈췄어요. 다시 켜려면 ▶ 를 눌러 주세요.'
      : text || '화면을 살펴보는 중이에요…'

  return (
    <div className="widget">
      <div className="widget-bubble">
        {suggestion && !asking ? (
          <div className="widget-suggest">
            <p className="widget-text"><b>이 내용 자주 보셨네요, 자세히 설명해 드릴까요?</b></p>
            <div className="widget-row">
              <button className="btn btn-primary btn-sm" onClick={() => { desktop?.openReview(suggestion.id); setSuggestion(null) }}>설명 듣기</button>
              <button className="btn btn-sm" onClick={() => setSuggestion(null)}>나중에</button>
            </div>
          </div>
        ) : asking ? (
          <form className="widget-ask" onSubmit={(e) => void onAsk(e)}>
            <input value={question} onChange={(e) => setQuestion(e.target.value)} maxLength={500}
              placeholder="지금 화면에 대해 물어보세요" autoFocus aria-label="화면 질문" />
            <button className="btn btn-primary btn-sm" disabled={!question.trim() || chatting || !!questionLock}>보내기</button>
            <button type="button" className="btn btn-sm" onClick={() => setAsking(false)} aria-label="취소">✕</button>
          </form>
        ) : (
          <p className="widget-text" aria-live="polite">{message}</p>
        )}
        {chatting && <button className="btn btn-sm" onClick={() => {
          const value = lastQuestion.current
          chat.current.reset(); setFailedQuestion(value); setText('답변을 중지했어요.'); setMood('curious')
        }}>답변 중지</button>}
        {!chatting && failedQuestion && <button className="btn btn-sm" disabled={!!questionLock}
          onClick={() => { if (!questionLock) void chat.current.ask(failedQuestion) }}>질문 다시 시도</button>}
        <div className="widget-row">
          <span className="widget-status">
            <span className={off || paused || gone ? 'dot off' : 'dot'} /> {off ? '꺼짐' : gone ? '창 종료' : paused ? '일시정지' : 'AI 보는 중'}
          </span>
          {off || gone ? (
            <button className="btn btn-sm" onClick={() => desktop?.openScreenConsent()}>{off ? '화면 분석 켜기' : '창 다시 고르기'}</button>
          ) : (
            <button className="btn btn-sm" onClick={togglePause} aria-label={paused ? '다시 시작' : '일시정지'}>
              <Icon name={paused ? 'play' : 'pause'} size={16} /> {paused ? '다시' : '일시정지'}
            </button>
          )}
          <button className="btn btn-primary btn-sm" onClick={openAsk} disabled={chatting}>
            <Icon name="chat" size={16} /> 질문하기
          </button>
        </div>
      </div>
      <div className="widget-mascot" title="끌어서 옮기기">
        <Mascot mood={off || paused || gone ? 'sleep' : mood} size={110} float={!paused && !off && !gone} />
      </div>
    </div>
  )
}
