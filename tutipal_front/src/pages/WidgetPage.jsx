import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { ApiRequestError } from '../api/client'
import { reviewApi } from '../api/review'
import { screenApi } from '../api/screen'
import { createScreenAnalysis } from '../utils/screenAnalysis'
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

  const analysis = useRef(null)
  if (!analysis.current) analysis.current = createScreenAnalysis({
    capture: () => desktop.captureScreen(),
    describe: async (image, question) => (await screenApi.describe(image, question)).text,
    observe: (summary, seconds) => reviewApi.observe(summary, seconds),
    onAnswer: (answer, asked) => { setText(answer); setMood(asked ? 'talk' : 'happy') },
    onSuggestion: setSuggestion,
    onGone: (closed) => {
      setGone(closed)
      if (closed) { setMood('curious'); setText('보던 창이 닫혔어요. 볼 창을 다시 골라 주세요.') }
    },
    onError: (error, asked) => {
      if (!asked && error instanceof ApiRequestError && error.status === 503) return
      setMood('curious')
      setText(error instanceof ApiRequestError && error.status === 401
        ? '메인 창에서 로그인하면 화면을 같이 볼 수 있어요.'
        : error instanceof Error ? error.message : '지금은 화면을 보지 못했어요')
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
    const load = () => desktop.getScreenSettings().then((value) => {
      if (active) setSettings(value)
    }, () => undefined)
    load()
    desktop.getReviewDeletionState().then(value => { if (active) analysis.current.deletion(value) })
    const unsubscribe = desktop.onScreenSettingsChanged(() => {
      analysis.current.setRunning(false)
      analysis.current.reset()
      setGone(false)
      load()
    })
    const reviewUnsubscribe = desktop.onReviewDeletionChanged(value => analysis.current.deletion(value))
    return () => {
      active = false
      analysis.current.setRunning(false)
      unsubscribe()
      reviewUnsubscribe()
    }
  }, [desktop])

  const watching = !!settings?.enabled && !paused && !gone
  useEffect(() => {
    analysis.current.setRunning(watching)
    if (!watching || asking) return
    void analysis.current.look()
    const timer = setInterval(() => void analysis.current.look(), SUMMARY_EVERY_MS)
    return () => clearInterval(timer)
  }, [watching, asking, settings])

  async function onAsk(e) {
    e.preventDefault()
    const q = question.trim()
    if (!q) return
    setText('화면을 보면서 생각하는 중…')
    setAsking(false)
    setQuestion('')
    await analysis.current.look(q)
  }

  function openAsk() {
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
            <button className="btn btn-primary btn-sm" disabled={!question.trim()}>보내기</button>
            <button type="button" className="btn btn-sm" onClick={() => setAsking(false)} aria-label="취소">✕</button>
          </form>
        ) : (
          <p className="widget-text" aria-live="polite">{message}</p>
        )}
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
          <button className="btn btn-primary btn-sm" onClick={openAsk}>
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
