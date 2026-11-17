import { Client } from '@stomp/stompjs'
import { useEffect, useRef, useState } from 'react'
import { csrfHeader } from './client'

/**
 * 세션 실시간 알림 (STOMP over WebSocket, 로그인 세션 쿠키로 인증).
 * 알림은 "무엇이 바뀌었나" 만 오고, 화면이 REST 로 다시 읽는다.
 * teacher 면 선생님 전용 토픽도 구독한다 (서버가 선생님인지 다시 확인함).
 * 끊기면 3초 뒤 다시 연결하고, 다시 연결되면 onEvent({type:'reconnect'}) 로 화면이 전체를 새로 읽게 한다.
 */
export function useSessionEvents(
  sessionId,
  teacher,
  onEvent,
) {
  const handler = useRef(onEvent)
  handler.current = onEvent
  const [connected, setConnected] = useState(false)

  useEffect(() => {
    let first = true
    const scheme = location.protocol === 'https:' ? 'wss' : 'ws'
    const client = new Client({
      brokerURL: `${scheme}://${location.host}/ws`,
      reconnectDelay: 3000,
      heartbeatIncoming: 20000,
      heartbeatOutgoing: 20000,
    })
    client.beforeConnect = async () => { client.connectHeaders = await csrfHeader() }
    const deliver = (body) => handler.current(JSON.parse(body))
    client.onConnect = () => {
      setConnected(true)
      client.subscribe(`/topic/sessions/${sessionId}/events`, (m) => deliver(m.body))
      client.subscribe('/user/queue/events', (m) => deliver(m.body))
      if (teacher) client.subscribe(`/topic/sessions/${sessionId}/teacher`, (m) => deliver(m.body))
      if (!first) handler.current({ type: 'reconnect', id: 0 })
      first = false
    }
    client.onWebSocketClose = () => setConnected(false)
    client.activate()
    return () => {
      void client.deactivate()
    }
  }, [sessionId, teacher])

  return connected
}

/** 그림 업무 데이터는 Spring이 도움 요청 당사자와 선생님 권한을 확인한 뒤 전달한다. */
export function useHelpDrawing(helpId, onDrawing) {
  const handler = useRef(onDrawing)
  handler.current = onDrawing
  useEffect(() => {
    if (!helpId) return
    const scheme = location.protocol === 'https:' ? 'wss' : 'ws'
    const client = new Client({ brokerURL: `${scheme}://${location.host}/ws`, reconnectDelay: 3000 })
    client.beforeConnect = async () => { client.connectHeaders = await csrfHeader() }
    client.onConnect = () => client.subscribe('/user/queue/draw', (event) => {
      try {
        const body = JSON.parse(event.body)
        if (body.helpId === helpId) handler.current(body.message)
      } catch { /* 잘못된 이벤트는 그리지 않는다. */ }
    })
    client.activate()
    return () => { void client.deactivate() }
  }, [helpId])
}
