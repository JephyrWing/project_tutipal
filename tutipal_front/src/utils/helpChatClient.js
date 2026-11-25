// Transport is injected. This module never reads cookies, stores transcripts, or opens a socket.
const decimal = value => typeof value === 'string' && /^(0|[1-9]\d*)$/.test(value)
const compare = (a, b) => BigInt(a.sequence) < BigInt(b.sequence) ? -1 : BigInt(a.sequence) > BigInt(b.sequence) ? 1 : 0
const forbidden = error => [401, 403, 404, 410].includes(error?.status)

export function createHelpChatClient({ helpId, userId, maxLength, transport, newId = () => crypto.randomUUID() }) {
  if (!decimal(helpId) || !decimal(userId) || !Number.isSafeInteger(maxLength) || maxLength < 1
    || typeof transport?.load !== 'function' || typeof transport?.send !== 'function') throw new TypeError('Invalid help chat configuration')
  let snapshot = { status: 'idle', items: [], error: '', sending: false, maxLength }
  let generation = 0, loadVersion = 0, cursor = '0', disposed = false
  let confirmed = new Map(), pending = new Map()
  const listeners = new Set()
  const live = version => !disposed && generation === version && snapshot.status !== 'closed'
  const emit = patch => {
    snapshot = { ...snapshot, ...patch, items: [...confirmed.values()].sort(compare).concat([...pending.values()]) }
    listeners.forEach(listener => listener())
  }
  function message(value) {
    if (!value || value.helpId !== helpId || !decimal(value.id) || !decimal(value.sequence) || value.sequence === '0'
      || !decimal(value.senderId) || typeof value.clientMessageId !== 'string' || !value.clientMessageId
      || typeof value.text !== 'string' || !value.text.trim() || [...value.text].length > maxLength) throw new TypeError('Invalid chat message')
    return { id: value.id, helpId, sequence: value.sequence, senderId: value.senderId,
      clientMessageId: value.clientMessageId, text: value.text, delivery: 'sent' }
  }
  function merge(messages) {
    const next = new Map(confirmed), remaining = new Map(pending)
    for (const item of messages) {
      const previous = next.get(item.id)
      if (previous && ['sequence', 'senderId', 'clientMessageId', 'text'].some(key => previous[key] !== item[key])) throw new TypeError('Conflicting message')
      for (const other of next.values()) {
        if (other.id !== item.id && (other.sequence === item.sequence
          || (other.senderId === item.senderId && other.clientMessageId === item.clientMessageId))) throw new TypeError('Conflicting message identity')
      }
      if (item.senderId === userId && remaining.has(item.clientMessageId)) {
        if (remaining.get(item.clientMessageId).text !== item.text) throw new TypeError('Conflicting pending message')
        remaining.delete(item.clientMessageId)
      }
      next.set(item.id, item)
    }
    confirmed = next; pending = remaining
  }
  function close() {
    generation++; loadVersion++; cursor = '0'; confirmed.clear(); pending.clear()
    emit({ status: 'closed', sending: false, error: '' })
  }
  async function refresh() {
    if (disposed || snapshot.status === 'closed') return false
    const version = generation, attempt = ++loadVersion
    const startedAt = cursor
    emit({ status: 'loading', error: '' })
    try {
      // Adapter returns a fully drained page range, not an unconsumed hasMore page.
      const page = await transport.load({ helpId, afterSequence: startedAt })
      if (!live(version) || attempt !== loadVersion) return false
      if (!Array.isArray(page?.items) || !decimal(page.nextCursor) || page.hasMore === true) throw new TypeError('Incomplete chat page')
      const items = page.items.map(message)
      const sequences = items.map(item => BigInt(item.sequence))
      const last = sequences.reduce((a, b) => a > b ? a : b, BigInt(startedAt))
      if (BigInt(page.nextCursor) !== last || sequences.some(s => s <= BigInt(startedAt))) throw new TypeError('Invalid chat cursor')
      merge(items); cursor = page.nextCursor
      emit({ status: 'ready', error: '' }); return true
    } catch (error) {
      if (!live(version) || attempt !== loadVersion) return false
      if (forbidden(error)) close()
      else emit({ status: 'offline', error: '대화를 불러오지 못했어요. 다시 연결해 주세요.' })
      return false
    }
  }
  async function deliver(clientMessageId) {
    const item = pending.get(clientMessageId)
    if (!item || snapshot.status !== 'ready' || snapshot.sending || disposed) return false
    const version = generation
    pending.set(clientMessageId, { ...item, delivery: 'sending' }); emit({ sending: true, error: '' })
    try {
      const ack = message(await transport.send({ helpId, clientMessageId, text: item.text }))
      if (!live(version)) return false
      if (ack.clientMessageId !== clientMessageId || ack.senderId !== userId || ack.text !== item.text) throw new TypeError('Invalid chat acknowledgement')
      merge([ack]); emit({ sending: false }); return true
    } catch (error) {
      if (!live(version)) return false
      if (forbidden(error)) close()
      else {
        // A load may already have confirmed the message while its POST response failed.
        if (pending.has(clientMessageId)) pending.set(clientMessageId, { ...item, delivery: 'failed' })
        emit({ sending: false, error: pending.has(clientMessageId) ? '메시지를 보내지 못했어요. 다시 보내기를 눌러 주세요.' : '' })
      }
      return false
    }
  }
  function send(text) {
    if (typeof text !== 'string' || !text.trim() || [...text].length > maxLength
      || snapshot.status !== 'ready' || snapshot.sending || disposed) return Promise.resolve(false)
    const clientMessageId = newId()
    if (typeof clientMessageId !== 'string' || !clientMessageId || pending.has(clientMessageId)
      || [...confirmed.values()].some(m => m.senderId === userId && m.clientMessageId === clientMessageId)) return Promise.resolve(false)
    pending.set(clientMessageId, { id: `pending:${clientMessageId}`, clientMessageId, helpId, senderId: userId, text, delivery: 'queued' })
    return deliver(clientMessageId)
  }
  return {
    scope: `${userId}:${helpId}`, userId,
    getSnapshot: () => snapshot,
    subscribe: listener => { listeners.add(listener); return () => listeners.delete(listener) },
    refresh, send, retry: deliver, close,
    dispose() { close(); disposed = true; listeners.clear() },
  }
}
