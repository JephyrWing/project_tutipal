import { useEffect, useRef, useState } from 'react'
import { useAuth } from '../auth'
import { createHelpChatTransport, HELP_CHAT_MAX_LENGTH } from '../api/helpChat'
import { createHelpChatClient } from '../utils/helpChatClient'
import HelpChatPanel from './HelpChatPanel'

export default function HelpChat({ helpId, refreshKey, partnerName }) {
  const { me } = useAuth()
  const userId = me?.id == null ? null : String(me.id)
  const [owned, setOwned] = useState(null)
  const refresh = useRef(() => {})
  useEffect(() => {
    if (!userId) return
    const transport = createHelpChatTransport()
    const client = createHelpChatClient({ helpId: String(helpId), userId, maxLength: HELP_CHAT_MAX_LENGTH, transport })
    let running = false, dirty = false, disposed = false
    const update = async () => {
      if (disposed || client.getSnapshot().status === 'closed') return
      if (running) { dirty = true; return }
      running = true
      try { do { dirty = false; await client.refresh() } while (dirty && !disposed && client.getSnapshot().status !== 'closed') }
      finally { running = false }
    }
    refresh.current = update
    setOwned({ client, helpId, userId }); void update()
    // Permission-checked recovery also covers a lost notification or expired session.
    const timer = setInterval(() => void update(), 5000)
    const focus = () => void update()
    window.addEventListener('focus', focus)
    return () => {
      disposed = true; refresh.current = () => {}; clearInterval(timer)
      window.removeEventListener('focus', focus); client.dispose(); transport.dispose()
    }
  }, [helpId, userId])
  useEffect(() => { void refresh.current() }, [refreshKey])
  if (!owned || owned.helpId !== helpId || owned.userId !== userId) return null
  return <div className="card" style={{ padding: 16, marginTop: 12, width: '100%' }}>
    <HelpChatPanel client={owned.client} partnerName={partnerName} />
  </div>
}
