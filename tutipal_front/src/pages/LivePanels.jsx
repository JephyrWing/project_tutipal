import { Room, RoomEvent } from 'livekit-client'
import { useCallback, useEffect, useRef, useState } from 'react'
import { screenshare } from '../api/screenshare'
import { request } from '../api/client'
import { useHelpDrawing } from '../api/realtime'
import { HELP_MESSAGE_MAX } from '../utils/helpDraft'
import { applyDraw, COLORS, hitStrokes, renderDraw } from '../draw'
import Icon from '../components/Icon'
import Mascot from '../components/Mascot'
import { createLiveRoomSession } from '../utils/liveRoomSession'
import { createScreenShareSession } from '../utils/screenShareSession'
import { createShareSourceCatalog } from '../utils/shareSourceCatalog'
import HelpSharePicker from '../components/HelpSharePicker'
import HelpChat from '../components/HelpChat'

const errText = (e, fallback) => (e instanceof Error ? e.message : fallback)

/** LiveKit 방에 들어가서, 화면 공유 영상이 오면 video 에 붙인다. 컴포넌트가 사라지면 나간다 */
function useRoom(getInfo, onVideo) {
  const [room, setRoom] = useState(null)
  const [error, setError] = useState('')
  const [state, setState] = useState('connecting')
  const [attemptId, setAttemptId] = useState(0)
  const [mediaEpoch, setMediaEpoch] = useState(0)
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
        if (state === 'reconnecting') setMediaEpoch(epoch => epoch + 1)
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

  return { room, error, state, mediaEpoch, retry: () => setAttemptId(id => id + 1) }
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
  const { room, mediaEpoch } = connection
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
  }, [room, mediaEpoch, desktop, refreshSources])

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

/**
 * 수락 뒤 학생이 대상 선택·미리보기·동의를 거쳐 공유한다.
 * draft {text, seq}: 챗봇이 도움 요청을 추천한 답변에서 학생이 버튼을 누르면 넘어온다 (PT-113).
 * 메시지 칸을 AI 요약으로 채워 모달만 연다. 요청은 학생이 고치거나 지운 뒤 "요청 보내기"를 눌러야만 나간다.
 */
export function StudentHelp({ sessionId, reloadKey, draft }) {
  const [mine, setMine] = useState(null)
  const [open, setOpen] = useState(false)
  const [message, setMessage] = useState('')
  const [share, setShare] = useState(true)
  const [error, setError] = useState('')
  const [note, setNote] = useState('')
  const [fromDraft, setFromDraft] = useState(false)
  /** 이 수업의 내 도움 요청 목록 상태: loading → ok | failed. ok 일 때만 열린 요청 유무를 알 수 있으므로 초안 모달을 연다 */
  const [listState, setListState] = useState('loading')
  const handledDraft = useRef(0)
  /** 초안이 올 때마다 그 초안에 대해 한 번 목록을 새로 조회한다. 이전 조회 결과(성공·실패 모두)에 기대지 않는다 */
  const checkedDraft = useRef(0)
  const [refreshKey, setRefreshKey] = useState(0)
  const active = mine?.status === 'PENDING' || mine?.status === 'ACCEPTED'

  // 다른 수업으로 바뀌면 이전 수업의 요청 상태 · 모달 · 초안을 버린다
  useEffect(() => {
    setListState('loading')
    setMine(null)
    setOpen(false)
    setMessage('')
    setFromDraft(false)
    setNote('')
  }, [sessionId])

  useEffect(() => {
    if (!draft || draft.seq === handledDraft.current) return
    if (draft.sessionId !== sessionId) {
      handledDraft.current = draft.seq
      return
    }
    if (checkedDraft.current !== draft.seq) {
      checkedDraft.current = draft.seq
      setListState('loading')
      setRefreshKey((k) => k + 1)
      return
    }
    if (listState === 'loading') return
    handledDraft.current = draft.seq
    if (listState === 'failed') {
      setNote('도움 요청 상태를 확인하지 못했어요. 잠시 후 다시 눌러 주세요')
      return
    }
    if (active) {
      setNote('이미 보낸 도움 요청이 있어요. 선생님 답을 기다려 주세요')
      return
    }
    setMessage(Array.from(draft.text).slice(0, HELP_MESSAGE_MAX).join(''))
    setFromDraft(true)
    setError('')
    setOpen(true)
  }, [draft, listState, active, sessionId])

  // 초안 모달이 열려 있는 사이 열린 요청이 확인되면(다른 창에서 보냄 등) 모달을 닫고 안내만 한다
  useEffect(() => {
    if (open && fromDraft && active) {
      setOpen(false)
      setNote('이미 보낸 도움 요청이 있어요. 선생님 답을 기다려 주세요')
    }
  }, [open, fromDraft, active])

  useEffect(() => {
    let current = true // 수업이 바뀐 뒤 늦게 온 이전 수업 응답은 버린다
    screenshare.listHelp(sessionId).then((l) => {
      if (!current) return
      const next = l[0] ?? null
      // 대기 중이던 요청이 사라짐 = 선생님이 "나중에" (내가 취소한 경우는 cancel() 이 먼저 mine 을 비움)
      setMine((prev) => {
        if (prev?.status === 'PENDING' && !next) setNote('선생님이 조금 뒤에 볼게요. 잠시 후 다시 요청해 주세요')
        return next
      })
      setListState('ok')
    }, () => {
      if (current) setListState((s) => (s === 'ok' ? s : 'failed'))
    })
    return () => { current = false }
  }, [sessionId, reloadKey, refreshKey])

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
        <HelpSession help={mine} refreshKey={reloadKey} onEnd={() => void cancel()} />
      ) : mine?.status === 'PENDING' ? (
        <span className="help-chip">
          <span className="badge badge-warn">도움 요청 대기 중</span>
          <button className="btn btn-sm" onClick={() => void cancel()}>취소</button>
        </span>
      ) : (
        <button className="btn btn-sm" onClick={() => {
          if (fromDraft) setMessage('') // 닫아 둔 AI 초안을 안내 없이 남기지 않는다
          setFromDraft(false)
          setOpen(true)
        }}>✋ 도움 요청</button>
      )}
      {note && <span className="muted small">{note}</span>}
      {open && (
        <div className="modal-backdrop" role="dialog" aria-modal="true" aria-label="선생님에게 도움 요청">
          <form className="card modal" onSubmit={(e) => void onSubmit(e)}>
            <header className="quiz-modal-head">
              <Mascot mood="happy" size={80} />
              <h2>선생님에게 도움 요청</h2>
            </header>
            {fromDraft && (
              <p className="muted small">AI 조교가 만든 요약이에요. 질문 원문은 들어 있지 않아요. 고치거나 지운 뒤 보내 주세요.</p>
            )}
            <textarea className="input" rows={4} maxLength={HELP_MESSAGE_MAX} value={message} onChange={(e) => setMessage(e.target.value)}
              placeholder="어디서 막혔는지 적어 주세요" autoFocus required />
            <label className="check-row">
              <input type="checkbox" checked={share} onChange={(e) => setShare(e.target.checked)} />
              화면 공유로 도움받기 <span className="muted small">(수락 후 공유할 창을 고르고 동의해요)</span>
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
function HelpSession({ help, refreshKey, onEnd }) {
  const desktop = window.desktop
  const getInfo = useCallback(() => screenshare.helpToken(help.id), [help.id])
  const connection = useRoom(getInfo)
  const { room, error, mediaEpoch } = connection
  const [shareError, setShareError] = useState('')
  const [consented, setConsented] = useState(false)
  const [sharing, setSharing] = useState(false)
  const [starting, setStarting] = useState(false)
  const [selected, setSelected] = useState(null)
  const [previewTrack, setPreviewTrack] = useState(null)
  const selectedRef = useRef(null)
  selectedRef.current = selected
  const approvedSource = useRef(null)
  const previewVideo = useRef(null)
  const shareControl = useRef(null)
  useHelpDrawing(help.id, (message) => desktop?.sendOverlayDraw(JSON.stringify(message)))

  useEffect(() => {
    setConsented(false)
    setSharing(false)
    setStarting(false)
    setShareError('')
    setSelected(null)
    setPreviewTrack(null)
    if (!room) return
    const control = createScreenShareSession({
      participant: room.localParticipant,
      requiresConsent: true,
      prepare: () => {
        if (!help.shareScreen || room.state !== 'connected') return false
        const source = selectedRef.current
        if (desktop && !source) throw new Error('공유할 창을 먼저 골라 주세요')
        approvedSource.current = source
      },
      selectSource: async () => {
        if (!desktop) return
        try { await desktop.prepareHelpShare(approvedSource.current.id) }
        catch { throw new Error('선택한 창을 준비하지 못했어요. 창을 열고 다시 선택해 주세요') }
      },
      clearSource: () => desktop?.setShareSource(null),
      onTrack: setPreviewTrack,
      onSharing: setSharing,
      onError: (e) => setShareError(errText(e, '화면을 공유하지 못했어요')),
    })
    shareControl.current = control
    return () => {
      shareControl.current = null
      void control.dispose()
    }
  }, [room, mediaEpoch, help.shareScreen, desktop])

  useEffect(() => {
    desktop?.showOverlay(sharing)
  }, [sharing, desktop])
  useEffect(() => () => desktop?.showOverlay(false), [desktop])

  useEffect(() => desktop?.onHelpShareEnded(reason => {
    void shareControl.current?.stop()
    setSelected(null)
    setConsented(false)
    setShareError(reason)
  }), [desktop])

  useEffect(() => {
    const video = previewVideo.current
    if (!previewTrack || !video) return
    previewTrack.attach(video)
    const measure = () => {
      const settings = previewTrack.mediaStreamTrack?.getSettings()
      const width = video.videoWidth || settings?.width
      const height = video.videoHeight || settings?.height
      if (width && height && approvedSource.current) desktop?.setHelpShareVideoSize(approvedSource.current.id, width, height)
    }
    measure()
    video.addEventListener('loadedmetadata', measure)
    video.addEventListener('resize', measure)
    return () => {
      video.removeEventListener('loadedmetadata', measure)
      video.removeEventListener('resize', measure)
      previewTrack.detach(video)
    }
  }, [previewTrack, desktop])

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
    <div className="help-chip" style={{ flexWrap: 'wrap' }}>
      <span className={`badge ${error || shareError || !room ? 'badge-warn' : 'badge-live'}`}>
        <span className="dot" /> {error || shareError ? '도움 연결 오류' : room ? '선생님과 연결됨' : '도움 연결 중'}
      </span>
      {help.shareScreen && !sharing && (
        <>
          {desktop && <HelpSharePicker desktop={desktop} disabled={starting || !room} selected={selected}
            onSelect={source => { setSelected(source); setConsented(false); setShareError('') }} onError={setShareError} />}
          <label className="check-row small">
            <input type="checkbox" checked={consented} disabled={starting || !room || (!!desktop && !selected)}
              onChange={(e) => setConsented(e.target.checked)} />
            {desktop ? `선택한 ${selected?.id.startsWith('screen:') ? '전체 화면' : '창'}을 선생님에게 보여주는 데 동의해요` : '브라우저에서 고를 화면을 공유하는 데 동의해요'}
          </label>
          <button className="btn btn-sm btn-primary" disabled={!room || !consented || starting || (!!desktop && !selected)}
            onClick={() => void startSharing()}>{starting ? '공유 준비 중…' : '화면 공유 시작'}</button>
        </>
      )}
      {sharing && (
        <>
          <span className="muted small">공유 중 · {selected?.name ?? '선택한 화면'}</span>
          <video className="help-local-preview" ref={previewVideo} autoPlay playsInline muted aria-label="현재 공유 중인 화면 미리보기" />
          <button className="btn btn-sm" onClick={() => void shareControl.current?.stop()}>화면 공유 중지</button>
        </>
      )}
      <button className="btn btn-sm" onClick={onEnd}>끝내기</button>
      <ConnectionNotice {...connection} />
      {shareError && <span className="error-text small">{shareError}</span>}
      {!desktop && <span className="muted small">(그림 설명은 앱에서 보여요)</span>}
      <HelpChat helpId={help.id} refreshKey={refreshKey} partnerName="선생님" />
    </div>
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
    return <HelpViewer help={viewing} refreshKey={reloadKey} onClose={() => setViewing(null)} onEnd={() => void act(() => screenshare.end(viewing.id))} />
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
function HelpViewer({ help, refreshKey, onClose, onEnd }) {
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
      <HelpChat helpId={help.id} refreshKey={refreshKey} partnerName={help.name} />
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
