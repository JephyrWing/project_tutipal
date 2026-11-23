import { Room, RoomEvent } from 'livekit-client'
import { useCallback, useEffect, useRef, useState } from 'react'
import { screenshare } from '../api/screenshare'
import { request } from '../api/client'
import { useHelpDrawing } from '../api/realtime'
import { applyDraw, COLORS, hitStrokes, renderDraw } from '../draw'
import Icon from '../components/Icon'
import Mascot from '../components/Mascot'
import { createLiveRoomSession } from '../utils/liveRoomSession'
import { createScreenShareSession } from '../utils/screenShareSession'
import { createShareSourceCatalog } from '../utils/shareSourceCatalog'

const errText = (e, fallback) => (e instanceof Error ? e.message : fallback)

/** LiveKit 방에 들어가서, 화면 공유 영상이 오면 video 에 붙인다. 컴포넌트가 사라지면 나간다 */
function useRoom(getInfo, onVideo) {
  const [room, setRoom] = useState(null)
  const [error, setError] = useState('')
  const [state, setState] = useState('connecting')
  const [attemptId, setAttemptId] = useState(0)
  const videoCb = useRef(onVideo)
  videoCb.current = onVideo

  useEffect(() => {
    if (!getInfo) return
    setError('')
    setRoom(null)
    const r = new Room({ adaptiveStream: true, dynacast: true })
    const session = createLiveRoomSession({
      room: r,
      events: RoomEvent,
      getInfo,
      resolveUrl: (url) => {
        const proxy = new URL(url, location.origin)
        proxy.protocol = location.protocol === 'https:' ? 'wss:' : 'ws:'
        proxy.host = location.host
        return proxy.href
      },
      onRoom: setRoom,
      onVideo: track => videoCb.current?.(track),
      onState: state => {
        setState(state)
        if (state === 'connected') setError('')
      },
      onError: (e) => setError(errText(e, '화면공유 서버에 연결하지 못했어요')),
    })
    return () => {
      void session.dispose()
      setRoom(null)
      videoCb.current?.(null)
    }
  }, [getInfo, attemptId])

  return { room, error, state, retry: () => setAttemptId(id => id + 1) }
}

function ConnectionNotice({ state, error, retry }) {
  return <>
    {(state === 'reconnecting' || state === 'signal-reconnecting') &&
      <span className="muted small" role="status">연결을 복구하고 있어요…</span>}
    {error && <span className="error-text small">{error}</span>}
    {state === 'disconnected' && <button type="button" className="btn btn-sm" onClick={retry}>다시 연결</button>}
  </>
}

/** 원격 화면 영상 + (선생님이면) 그 위에 그리는 캔버스. 비율을 영상과 같게 맞춰 좌표가 어긋나지 않게 */
function RemoteScreen({ track, children }) {
  const videoRef = useRef(null)
  const [ratio, setRatio] = useState(16 / 9)
  useEffect(() => {
    const el = videoRef.current
    if (!track || !el) return
    track.attach(el)
    const onMeta = () => el.videoWidth && setRatio(el.videoWidth / el.videoHeight)
    el.addEventListener('loadedmetadata', onMeta)
    el.addEventListener('resize', onMeta)
    return () => {
      el.removeEventListener('loadedmetadata', onMeta)
      el.removeEventListener('resize', onMeta)
      track.detach(el)
    }
  }, [track])
  return (
    <div className="screen-frame" style={{ aspectRatio: String(ratio) }}>
      <video ref={videoRef} autoPlay playsInline muted />
      {children}
    </div>
  )
}

// ---------------- 수업 공유 (방 A) ----------------

/** 목업 27 선생님: 내 화면을 세션 전체에 공유 */
export function TeacherShareTab({ sessionId }) {
  const desktop = window.desktop
  const getInfo = useCallback(() => screenshare.classToken(sessionId), [sessionId])
  const connection = useRoom(getInfo)
  const { room } = connection
  const [sources, setSources] = useState([])
  const [picked, setPicked] = useState('')
  const [sharing, setSharing] = useState(false)
  const [viewers, setViewers] = useState(0)
  const [status, setStatus] = useState('')
  const [starting, setStarting] = useState(false)
  const previewRef = useRef(null)
  const sourceCatalog = useRef(null)
  const shareControl = useRef(null)
  const pickedRef = useRef(picked)
  pickedRef.current = picked
  const refreshSources = useCallback(() => sourceCatalog.current?.refresh() ?? Promise.resolve(null), [])

  useEffect(() => {
    if (!desktop) return
    const catalog = createShareSourceCatalog(
      () => desktop.screenSources(),
      (next) => {
        setSources(next)
        setPicked((selected) => next.some((source) => source.id === selected) ? selected : '')
      },
      () => setStatus('공유할 창 목록을 읽지 못했어요. 새로고침해 주세요'),
    )
    sourceCatalog.current = catalog
    void catalog.refresh()
    const refresh = () => void catalog.refresh()
    window.addEventListener('focus', refresh)
    return () => {
      window.removeEventListener('focus', refresh)
      catalog.dispose()
      sourceCatalog.current = null
    }
  }, [desktop])

  useEffect(() => {
    setSharing(false)
    setStarting(false)
    setViewers(0)
    if (!room) return
    let previewTrack = null
    let approvedSource = ''
    const control = createScreenShareSession({
      participant: room.localParticipant,
      prepare: async () => {
        const selected = pickedRef.current
        if (desktop) {
          const available = await refreshSources()
          if (!available?.some(source => source.id === selected)) {
            throw new Error('선택한 창이 닫혔거나 목록이 바뀌었어요. 공유할 창을 다시 골라 주세요')
          }
        }
        if (room.state !== 'connected') throw new Error('연결을 복구한 뒤 공유를 다시 시작해 주세요')
        approvedSource = selected
      },
      selectSource: () => desktop?.setShareSource(approvedSource),
      clearSource: () => desktop?.setShareSource(null),
      onSharing: setSharing,
      onTrack: track => {
        if (previewTrack && previewRef.current) previewTrack.detach(previewRef.current)
        previewTrack = track
        if (track && previewRef.current) track.attach(previewRef.current)
      },
      onError: e => setStatus(errText(e, '공유를 시작하지 못했어요')),
    })
    shareControl.current = control
    const count = () => setViewers(room.remoteParticipants.size)
    count()
    room.on(RoomEvent.ParticipantConnected, count).on(RoomEvent.ParticipantDisconnected, count)
    return () => {
      shareControl.current = null
      if (previewTrack && previewRef.current) previewTrack.detach(previewRef.current)
      void control.dispose()
      room.off(RoomEvent.ParticipantConnected, count).off(RoomEvent.ParticipantDisconnected, count)
    }
  }, [room, desktop, refreshSources])

  async function start() {
    const control = shareControl.current
    if (!control || starting) return
    setStarting(true)
    setStatus('')
    await control.start()
    if (shareControl.current === control) setStarting(false)
  }

  return (
    <section className="card session-body live-body">
      <div className="row-between">
        <div className="live-badges">
          <span className={`badge ${sharing ? 'badge-live' : 'badge-gray'}`}><span className={`dot${sharing ? '' : ' off'}`} /> {sharing ? '공유 중' : '공유 안 함'}</span>
          <span className="badge badge-gray"><Icon name="users" size={16} /> 시청 {viewers}명</span>
        </div>
        {sharing
          ? <button className="btn btn-danger" onClick={() => void shareControl.current?.stop()}><Icon name="stop" size={18} /> 공유 중지</button>
          : <button className="btn btn-primary" disabled={!room || starting || (!!desktop && !picked)} onClick={() => void start()}><Icon name="screen" size={18} /> {starting ? '공유 준비 중…' : '공유 시작'}</button>}
      </div>
      <ConnectionNotice {...connection} />
      {status && <p className="error-text">{status}</p>}
      {desktop && !sharing && (
        <label className="field small-label">
          <span className="row-between">
            <strong>공유할 창</strong>
            <button type="button" className="btn btn-sm" disabled={starting} onClick={() => void refreshSources()}>목록 새로고침</button>
          </span>
          <select className="input" value={picked} disabled={starting} onPointerDown={() => void refreshSources()}
            onFocus={() => void refreshSources()} onChange={(e) => setPicked(e.target.value)}>
            <option value="">고르세요</option>
            {sources.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
          </select>
        </label>
      )}
      <div className={`screen-frame preview${sharing ? ' on' : ''}`}>
        <video ref={previewRef} autoPlay playsInline muted />
        {!sharing && <p className="muted screen-empty">공유를 시작하면 학생들이 보는 화면이 여기에 보여요</p>}
      </div>
    </section>
  )
}

/** 목업 13 학생 "라이브" 탭: 선생님 공유 화면 보기 */
export function StudentLiveTab({ sessionId }) {
  const [track, setTrack] = useState(null)
  const getInfo = useCallback(() => screenshare.classToken(sessionId), [sessionId])
  const connection = useRoom(getInfo, setTrack)
  const { room, error } = connection
  return (
    <section className="card session-body live-body">
      <div className="row-between">
        <span className={`badge ${track ? 'badge-live live-red' : 'badge-gray'}`}>{track ? '● LIVE' : '대기 중'}</span>
        {!room && !error && <span className="muted small">연결 중…</span>}
      </div>
      <ConnectionNotice {...connection} />
      {track ? <RemoteScreen track={track} /> : (
        <div className="session-empty">
          <Mascot mood="sleep" size={110} />
          <p className="muted">선생님이 화면을 공유하면 여기에 바로 보여요</p>
        </div>
      )}
    </section>
  )
}

// ---------------- 도움 요청 (방 B) ----------------

/** 목업 17 학생: 도움 요청 보내기 + 진행 상태. 수락되면 내 화면(주 모니터)을 선생님에게 공유하고 그림을 오버레이로 받는다 */
export function StudentHelp({ sessionId, reloadKey }) {
  const [mine, setMine] = useState(null)
  const [open, setOpen] = useState(false)
  const [message, setMessage] = useState('')
  const [share, setShare] = useState(true)
  const [error, setError] = useState('')
  const [note, setNote] = useState('')

  useEffect(() => {
    screenshare.listHelp(sessionId).then((l) => {
      const next = l[0] ?? null
      // 대기 중이던 요청이 사라짐 = 선생님이 "나중에" (내가 취소한 경우는 cancel() 이 먼저 mine 을 비움)
      setMine((prev) => {
        if (prev?.status === 'PENDING' && !next) setNote('선생님이 조금 뒤에 볼게요. 잠시 후 다시 요청해 주세요')
        return next
      })
    }, () => undefined)
  }, [sessionId, reloadKey])

  async function onSubmit(e) {
    e.preventDefault()
    setError('')
    try {
      setMine(await screenshare.requestHelp(sessionId, message.trim(), share))
      setOpen(false)
      setMessage('')
      setNote('')
    } catch (err) {
      setError(errText(err, '보내지 못했어요'))
    }
  }

  async function cancel() {
    if (mine) await screenshare.end(mine.id).catch(() => undefined)
    setMine(null)
  }

  return (
    <>
      {mine?.status === 'ACCEPTED' ? (
        <HelpSession help={mine} onEnd={() => void cancel()} />
      ) : mine?.status === 'PENDING' ? (
        <span className="help-chip">
          <span className="badge badge-warn">도움 요청 대기 중</span>
          <button className="btn btn-sm" onClick={() => void cancel()}>취소</button>
        </span>
      ) : (
        <button className="btn btn-sm" onClick={() => setOpen(true)}>✋ 도움 요청</button>
      )}
      {note && <span className="muted small">{note}</span>}
      {open && (
        <div className="modal-backdrop" role="dialog" aria-modal="true" aria-label="선생님에게 도움 요청">
          <form className="card modal" onSubmit={(e) => void onSubmit(e)}>
            <header className="quiz-modal-head">
              <Mascot mood="happy" size={80} />
              <h2>선생님에게 도움 요청</h2>
            </header>
            <textarea className="input" rows={4} maxLength={300} value={message} onChange={(e) => setMessage(e.target.value)}
              placeholder="어디서 막혔는지 적어 주세요" autoFocus required />
            <label className="check-row">
              <input type="checkbox" checked={share} onChange={(e) => setShare(e.target.checked)} />
              내 화면을 선생님에게 보여주기 <span className="muted small">(수락 후 직접 공유를 시작해요)</span>
            </label>
            {error && <p className="error-text">{error}</p>}
            <div className="consent-actions">
              <button type="button" className="btn" onClick={() => setOpen(false)}>닫기</button>
              <button className="btn btn-primary" disabled={!message.trim()}>요청 보내기</button>
            </div>
          </form>
        </div>
      )}
    </>
  )
}

/** 학생 쪽 진행 중인 1:1: 화면 올리기 + 받은 그림을 오버레이로 */
function HelpSession({ help, onEnd }) {
  const desktop = window.desktop
  const getInfo = useCallback(() => screenshare.helpToken(help.id), [help.id])
  const connection = useRoom(getInfo)
  const { room, error } = connection
  const [shareError, setShareError] = useState('')
  const [consented, setConsented] = useState(false)
  const [sharing, setSharing] = useState(false)
  const [starting, setStarting] = useState(false)
  const shareControl = useRef(null)
  useHelpDrawing(help.id, (message) => desktop?.sendOverlayDraw(JSON.stringify(message)))

  useEffect(() => {
    setConsented(false)
    setSharing(false)
    setStarting(false)
    setShareError('')
    if (!room) return
    const control = createScreenShareSession({
      participant: room.localParticipant,
      requiresConsent: true,
      prepare: () => {
        if (!help.shareScreen || room.state !== 'connected') return false
      },
      selectSource: () => desktop?.setShareSource(''), // 명시적으로 동의한 주 모니터 전체
      clearSource: () => desktop?.setShareSource(null),
      onTrack: () => {},
      onSharing: setSharing,
      onError: (e) => setShareError(errText(e, '화면을 공유하지 못했어요')),
    })
    shareControl.current = control
    return () => {
      shareControl.current = null
      void control.dispose()
    }
  }, [room, help.shareScreen, desktop])

  useEffect(() => {
    desktop?.showOverlay(sharing)
    return () => desktop?.showOverlay(false)
  }, [sharing, desktop])

  async function startSharing() {
    const control = shareControl.current
    if (!control || !consented) return
    setStarting(true)
    setShareError('')
    await control.start(consented)
    if (shareControl.current === control) {
      setStarting(false)
      setConsented(false)
    }
  }

  return (
    <span className="help-chip" style={{ flexWrap: 'wrap' }}>
      <span className={`badge ${error || shareError || !room ? 'badge-warn' : 'badge-live'}`}>
        <span className="dot" /> {error || shareError ? '도움 연결 오류' : room ? '선생님과 연결됨' : '도움 연결 중'}
      </span>
      {help.shareScreen && !sharing && (
        <>
          <label className="check-row small">
            <input type="checkbox" checked={consented} disabled={starting || !room}
              onChange={(e) => setConsented(e.target.checked)} />
            주 모니터 전체를 선생님에게 보여주는 데 동의해요
          </label>
          <button className="btn btn-sm btn-primary" disabled={!room || !consented || starting}
            onClick={() => void startSharing()}>{starting ? '공유 준비 중…' : '화면 공유 시작'}</button>
        </>
      )}
      {sharing && (
        <>
          <span className="muted small">공유 중 · 주 모니터 전체</span>
          <button className="btn btn-sm" onClick={() => void shareControl.current?.stop()}>화면 공유 중지</button>
        </>
      )}
      <button className="btn btn-sm" onClick={onEnd}>끝내기</button>
      <ConnectionNotice {...connection} />
      {shareError && <span className="error-text small">{shareError}</span>}
      {!desktop && <span className="muted small">(그림 설명은 앱에서 보여요)</span>}
    </span>
  )
}

/** 목업 25 선생님: 도움 요청 목록 (대기 시간 · 수락 · 나중에) + 진행 중인 학생 화면 보기(26) */
export function TeacherHelpTab({ sessionId, reloadKey }) {
  const [list, setList] = useState([])
  const [viewing, setViewing] = useState(null)
  const [error, setError] = useState('')
  const [now, setNow] = useState(() => Date.now())

  const load = useCallback(() => {
    screenshare.listHelp(sessionId).then((l) => {
      setList(l)
      setViewing((v) => (v && l.some((h) => h.id === v.id && h.status === 'ACCEPTED') ? v : null))
    }, (e) => setError(errText(e, '불러오지 못했어요')))
  }, [sessionId])
  useEffect(load, [load, reloadKey])
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 15_000)
    return () => clearInterval(t)
  }, [])

  const act = async (fn) => {
    setError('')
    try {
      await fn()
      load()
    } catch (e) {
      setError(errText(e, '처리하지 못했어요'))
    }
  }

  if (viewing) {
    return <HelpViewer help={viewing} onClose={() => setViewing(null)} onEnd={() => void act(() => screenshare.end(viewing.id))} />
  }

  return (
    <section className="card session-body">
      <strong className="section-title"><Icon name="users" size={18} /> 도움 요청</strong>
      {error && <p className="error-text">{error}</p>}
      {list.length === 0 && <p className="muted">아직 도움 요청이 없어요</p>}
      {list.map((h) => (
        <div key={h.id} className="help-row">
          <span className="avatar"><Icon name="user" /></span>
          <div className="help-text">
            <strong>{h.name}</strong>
            <span className="muted">{h.message}</span>
            {!h.shareScreen && <span className="muted small">화면 공유 없이 요청</span>}
          </div>
          {h.status === 'PENDING' ? (
            <>
              <span className="badge badge-warn">대기 {Math.max(0, Math.round((now - new Date(h.createdAt).getTime()) / 60000))}분</span>
              <button className="btn btn-primary btn-sm" onClick={() => void act(() => screenshare.accept(h.id))}>수락</button>
              <button className="btn btn-sm" onClick={() => void act(() => screenshare.later(h.id))}>나중에</button>
            </>
          ) : (
            <>
              <span className="badge badge-live">진행 중</span>
              <button className="btn btn-primary btn-sm" onClick={() => setViewing(h)}>{h.shareScreen ? '화면 보기' : '열기'}</button>
              <button className="btn btn-sm" onClick={() => void act(() => screenshare.end(h.id))}>종료</button>
            </>
          )}
        </div>
      ))}
    </section>
  )
}

/** 목업 26: 학생 화면 + 펜 · 형광펜 · 지우개 · 색. 그림은 Spring 업무 API/STOMP로 전달한다. */
function HelpViewer({ help, onClose, onEnd }) {
  const [track, setTrack] = useState(null)
  const getInfo = useCallback(() => screenshare.helpToken(help.id), [help.id])
  const connection = useRoom(getInfo, setTrack)
  const { room } = connection
  const [tool, setTool] = useState('pen')
  const [color, setColor] = useState(COLORS[0])
  const canvasRef = useRef(null)
  const strokes = useRef(new Map())
  const current = useRef(null)
  const drawQueue = useRef([])
  const sendingDraw = useRef(false)
  const [drawingError, setDrawingError] = useState('')

  async function flushDraw() {
    if (sendingDraw.current) return
    sendingDraw.current = true
    try {
      while (drawQueue.current.length) {
        const message = drawQueue.current.shift()
        await request(`/api/help/${help.id}/draw`, {
          method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ message }),
        })
      }
    } catch {
      drawQueue.current = []
      setDrawingError('그림을 전달하지 못했어요. 도움 요청 연결 상태를 확인해 주세요')
    } finally { sendingDraw.current = false }
  }

  const redraw = useCallback(() => {
    const c = canvasRef.current
    if (!c) return
    c.width = c.clientWidth
    c.height = c.clientHeight
    const ctx = c.getContext('2d')
    if (ctx) renderDraw(ctx, strokes.current, c.width, c.height)
  }, [])
  // A new video track has a fresh student overlay; never redraw the previous screen's ink.
  useEffect(() => {
    strokes.current.clear()
    current.current = null
    drawQueue.current = []
    setDrawingError('')
    redraw()
  }, [track, help.id, redraw])

  useEffect(() => {
    const c = canvasRef.current
    if (!c) return
    const ro = new ResizeObserver(redraw)
    ro.observe(c)
    return () => ro.disconnect()
  }, [redraw, track])

  const send = (m) => {
    if (!track) return
    applyDraw(strokes.current, m)
    redraw()
    const tail = drawQueue.current.at(-1)
    if (m.k === 'add' && tail?.k === 'add' && tail.id === m.id && tail.p.length < 100) tail.p.push(...m.p)
    else drawQueue.current.push(m)
    void flushDraw()
  }

  const pos = (e) => {
    const r = e.currentTarget.getBoundingClientRect()
    return [Math.min(1, Math.max(0, (e.clientX - r.left) / r.width)), Math.min(1, Math.max(0, (e.clientY - r.top) / r.height))]
  }
  const erase = (p) => {
    const ids = hitStrokes(strokes.current, p[0], p[1])
    if (ids.length) send({ k: 'del', ids })
  }

  function down(e) {
    try {
      e.currentTarget.setPointerCapture(e.pointerId)   // 캔버스 밖으로 나가도 선이 이어지게
    } catch {
      // 일부 입력 장치는 지원 안 함
    }
    const p = pos(e)
    if (tool === 'eraser') return erase(p)
    current.current = Math.random().toString(36).slice(2, 10)
    send({ k: 'add', id: current.current, tool, c: color, p: [p] })
  }
  function move(e) {
    if (e.buttons !== 1) return
    const p = pos(e)
    if (tool === 'eraser') return erase(p)
    if (current.current) send({ k: 'add', id: current.current, tool, c: color, p: [p] })
  }

  return (
    <section className="card session-body help-viewer">
      <div className="row-between">
        <strong className="section-title">학생 화면 · {help.name}</strong>
        <span className="row-gap">
          <button className="btn btn-sm" onClick={onClose}>목록으로</button>
          <button className="btn btn-sm btn-danger" onClick={onEnd}>⏻ 종료</button>
        </span>
      </div>
      <p className="muted small">{help.message}</p>
      <ConnectionNotice {...connection} />
      {drawingError && <p className="error-text">{drawingError}</p>}
      <div className="viewer-body">
        <div className="tool-col" role="toolbar" aria-label="그림 도구">
          {([['pen', '펜'], ['hl', '형광펜'], ['eraser', '지우개']]).map(([t, label]) => (
            <button key={t} className={`btn tool-btn${tool === t ? ' on' : ''}`} onClick={() => setTool(t)} aria-pressed={tool === t}>{label}</button>
          ))}
          <div className="color-row">
            {COLORS.map((c) => (
              <button key={c} className={`color-dot${color === c ? ' on' : ''}`} style={{ background: c }}
                onClick={() => setColor(c)} aria-label={`색 ${c}`} aria-pressed={color === c} />
            ))}
          </div>
          <button className="btn btn-sm" disabled={!track} onClick={() => send({ k: 'clear' })}>모두 지우기</button>
        </div>
        {track ? (
          <RemoteScreen track={track}>
            <canvas ref={canvasRef} className="draw-canvas" onPointerDown={down} onPointerMove={move}
              onPointerUp={() => (current.current = null)} />
          </RemoteScreen>
        ) : (
          <div className="session-empty">
            <Mascot mood="think" size={100} />
            <p className="muted">{!room ? '연결 중…' : help.shareScreen ? '학생 화면을 기다리는 중…' : '학생이 화면 공유 없이 요청했어요'}</p>
          </div>
        )}
      </div>
    </section>
  )
}
