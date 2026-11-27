import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { reviewApi } from '../api/review'
import { sessions } from '../api/sessions'
import { useAuth } from '../auth'
import Icon from '../components/Icon'
import Mascot from '../components/Mascot'

const ROOM_CARDS = [
  { to: '/sessions/new', icon: 'plus', label: '방 만들기', desc: '과목을 정하고 초대 코드로 친구 · 학생을 불러요' },
  { to: '/sessions/join', icon: 'users', label: '방 참여하기', desc: '받은 초대 코드를 입력해서 들어가요' },
]

/** 목업 03 홈: 맨 위 방 만들기 · 방 참여하기, 아래 내가 방장인 방 · 참여한 방 (각각 스크롤). 개인 챗봇은 왼쪽 메뉴 */
export default function HomePage() {
  const { me } = useAuth()
  const [mine, setMine] = useState(null)
  const [ended, setEnded] = useState(null)
  const [endedError, setEndedError] = useState('')
  const [reviewCount, setReviewCount] = useState(0)
  const owned = mine?.filter((r) => r.myRole === 'TEACHER')
  /** 즐겨찾기 먼저, 그다음 최근(id 큰) 것부터 — 서버 mySessions 정렬과 같게 */
  const sorted = (list) => [...list].sort((a, b) => (b.pinned - a.pinned) || (b.id - a.id))
  async function togglePin(room) {
    const next = !room.pinned
    setMine((list) => sorted(list.map((r) => (r.id === room.id ? { ...r, pinned: next } : r))))
    try {
      await sessions.pin(room.id, next)
    } catch {
      setMine((list) => sorted(list.map((r) => (r.id === room.id ? { ...r, pinned: !next } : r))))
    }
  }
  useEffect(() => {
    sessions.mine().then(setMine, () => setMine([]))
    sessions.endedMine().then(setEnded, () => setEndedError('종료한 수업을 불러오지 못했어요'))
    reviewApi.items().then((l) => setReviewCount(l.length), () => undefined)
  }, [])
  return (
    <div className="page">
      <header className="home-head">
        <Mascot mood="happy" size={130} float />
        <div className="home-greet">
          <p className="muted">{me?.name} 님, 반가워요</p>
          <h1>오늘은 무엇을 공부할까요?</h1>
        </div>
      </header>

      <section className="room-grid">
        {ROOM_CARDS.map((t) => (
          <Link key={t.to} to={t.to} className="card room-card">
            <span className="room-icon"><Icon name={t.icon} size={34} /></span>
            <span>
              <strong>{t.label}</strong>
              <span className="muted">{t.desc}</span>
            </span>
          </Link>
        ))}
      </section>

      <Link to="/stats" className="card review-strip">
        <span className="review-icon"><Icon name="chart" size={30} /></span>
        <strong>내 제출 기록</strong>
        <span className="muted">진행 중인 참여 수업의 내 답안과 판정을 모아 봐요</span>
      </Link>

      <section className="room-lists">
        <RoomList
          title="내가 방장인 방"
          rooms={owned}
          empty="아직 만든 방이 없어요. 위의 방 만들기로 시작해 보세요"
          onPin={togglePin}
        />
        <RoomList
          title="참여한 방"
          rooms={mine?.filter((r) => r.myRole !== 'TEACHER')}
          empty="아직 참여한 방이 없어요. 초대 코드를 받으면 방 참여하기로 들어가요"
          onPin={togglePin}
        />
      </section>

      {(owned?.length > 0 || ended?.length > 0 || endedError) && <EndedRooms rooms={ended} error={endedError} />}

      <Link to="/review" className="card review-strip">
        <span className="review-icon"><Icon name="clock" size={30} /></span>
        <strong>복습할 내용 {reviewCount}개</strong>
        <span className="muted">화면 분석을 켜면 자주 보거나 오래 본 내용이 여기에 모여요</span>
      </Link>
    </div>
  )
}

function EndedRooms({ rooms, error }) {
  return (
    <section className="card room-list ended-room-list" aria-label="내가 종료한 수업">
      <h2>내가 종료한 수업 {rooms && <span className="badge badge-gray">{rooms.length}</span>}</h2>
      <div className="room-scroll">
        {error && <p role="alert">{error}</p>}
        {!rooms && !error && <p className="muted">불러오는 중…</p>}
        {rooms?.length === 0 && <p className="muted">종료한 수업이 없어요</p>}
        {rooms?.map((room) => (
          <div className="room-row ended-room-row" key={room.id}>
            <div className="room-link"><strong>{room.title}</strong><span className="badge">{room.topic}</span></div>
            <div className="ended-room-actions">
              <Link className="btn btn-sm" to={`/sessions/${room.id}?tab=results`}
                aria-label={`${room.title} 학습 결과`}>학습 결과</Link>
              <Link className="btn btn-sm" to={`/sessions/${room.id}?tab=insights`}
                aria-label={`${room.title} 개념 분석`}>개념 분석</Link>
            </div>
          </div>
        ))}
      </div>
    </section>
  )
}

/** 방 목록 한 칸: 길어지면 칸 안에서 스크롤, 별(즐겨찾기)을 누르면 맨 위에 고정 (닫힌 방은 서버가 빼고 줌) */
function RoomList({ title, rooms, empty, onPin }) {
  return (
    <div className="card room-list">
      <h2>{title} {rooms && <span className="badge badge-gray">{rooms.length}</span>}</h2>
      <div className="room-scroll">
        {!rooms && <p className="muted">불러오는 중…</p>}
        {rooms?.length === 0 && <p className="muted">{empty}</p>}
        {rooms?.map((r) => (
          <div key={r.id} className={r.pinned ? 'room-row pinned' : 'room-row'}>
            <button
              type="button"
              className="pin-btn"
              onClick={() => onPin(r)}
              aria-pressed={r.pinned}
              aria-label={r.pinned ? `${r.title} 즐겨찾기 해제` : `${r.title} 즐겨찾기`}
              title={r.pinned ? '즐겨찾기 해제' : '즐겨찾기 (맨 위 고정)'}
            >
              <Icon name="star" size={20} />
            </button>
            <Link to={`/sessions/${r.id}`} className="room-link">
              <strong>{r.title}</strong>
              <span className="badge">{r.topic}</span>
              <span className="muted small">참가자 {r.participantCount}명</span>
            </Link>
          </div>
        ))}
      </div>
    </div>
  )
}
