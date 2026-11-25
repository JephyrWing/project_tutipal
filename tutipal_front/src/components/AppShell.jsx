import { NavLink, Outlet } from 'react-router-dom'
import { useAuth } from '../auth'
import Icon, {  } from './Icon'
import Mascot from './Mascot'

const MENU = [
  { to: '/', icon: 'home', label: '홈', ready: true },
  { to: '/chat', icon: 'chat', label: '도우미 챗봇', ready: true },
  { to: '/conversations', icon: 'talk', label: 'AI 대화 기록', ready: true, end: false },
  { to: '/review', icon: 'book', label: '복습 기록', ready: true },
  { to: '/stats', icon: 'chart', label: '학습 기록 (준비 중)', ready: false },
  { to: '/settings', icon: 'settings', label: '설정', ready: true },
]

/** 목업 공통 틀: 왼쪽 세로 아이콘 메뉴 카드 + 오른쪽 본문 */
export default function AppShell() {
  const { logout, logoutError, loggingOut } = useAuth()
  return (
    <div className="shell">
      <nav className="side card" aria-label="메인 메뉴">
        <div className="side-logo">
          <Mascot size={44} />
        </div>
        {MENU.map((m) =>
          m.ready ? (
            <NavLink key={m.to} to={m.to} end={m.end ?? true} className="side-item" title={m.label} aria-label={m.label}>
              <Icon name={m.icon} />
            </NavLink>
          ) : (
            <span key={m.to} className="side-item disabled" title={m.label} aria-label={m.label}>
              <Icon name={m.icon} />
            </span>
          ),
        )}
        <button className="side-item side-bottom" disabled={loggingOut} onClick={() => void logout()} title="로그아웃" aria-label="로그아웃">
          <Icon name="logout" />
        </button>
      </nav>
      <main className="shell-main">
        {logoutError && <div className="card" role="alert"><p className="error-text">{logoutError}</p>
          <button className="btn btn-sm" disabled={loggingOut} onClick={() => void logout()}>다시 로그아웃</button></div>}
        <Outlet />
      </main>
    </div>
  )
}
