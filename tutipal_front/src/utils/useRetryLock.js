import { useEffect, useState } from 'react'
import { activeLock, lockFromError } from './quotaRetry'

/** setTimeout 이 받을 수 있는 최대 지연 (약 24.8일) */
const MAX_DELAY = 2 ** 31 - 1

/**
 * AI 사용 한도(429) 로 막힌 버튼을 기다릴 시간이 지나면 저절로 푼다. 시간은 로컬 시계 기준 (quotaRetry.js).
 * 잠금은 이 컴포넌트 상태라서 컴포넌트가 사라지면(탭 이동 · 대화상자 닫기) 함께 사라진다. 그 뒤 다시 보내면
 * 서버가 다시 429 로 막으므로 한도 자체는 서버가 지킨다.
 * 반환: [lock({ until, retryAt } | null), lockFor(error)]
 */
export function useRetryLock() {
  const [lock, setLock] = useState(null)
  useEffect(() => {
    if (!lock) return undefined
    const delay = Math.max(0, lock.until - Date.now())
    const timer = setTimeout(() => {
      // 아주 먼 시각이면 한 번에 못 기다리므로 남았으면 새 객체로 다시 건다
      setLock((current) => (activeLock(current) ? { ...current } : null))
    }, Math.min(delay, MAX_DELAY))
    return () => clearTimeout(timer)
  }, [lock])
  return [lock, (error) => setLock(lockFromError(error))]
}
