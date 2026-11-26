import { request } from './client.js'
export const HELP_CHAT_MAX_LENGTH = 1000
const decimal = value => typeof value === 'string' && /^(0|[1-9]\d*)$/.test(value)

export function createHelpChatTransport(call = request) {
  const pending = new Set()
  let disposed = false
  async function invoke(path, init = {}) {
    if (disposed) throw new Error('Chat closed')
    const controller = new AbortController()
    const timeout = setTimeout(() => controller.abort(), 10000)
    pending.add(controller)
    try { return await call(path, { ...init, signal: controller.signal }) }
    finally { clearTimeout(timeout); pending.delete(controller) }
  }
  function base(helpId) {
    if (!decimal(helpId)) throw new TypeError('Invalid help ID')
    return `/api/help/${helpId}/messages`
  }
  return {
    async load({ helpId, afterSequence }) {
      if (!decimal(afterSequence)) throw new TypeError('Invalid cursor')
      const items = []
      let cursor = afterSequence
      for (let pageNumber = 0; pageNumber < 100; pageNumber++) {
        const page = await invoke(`${base(helpId)}?afterSequence=${cursor}&limit=100`)
        if (!page || !Array.isArray(page.items) || !decimal(page.nextCursor)
          || typeof page.hasMore !== 'boolean' || page.maxLength !== HELP_CHAT_MAX_LENGTH
          || BigInt(page.nextCursor) < BigInt(cursor)
          || (page.hasMore && BigInt(page.nextCursor) <= BigInt(cursor))) throw new TypeError('Invalid chat page')
        items.push(...page.items); cursor = page.nextCursor
        if (!page.hasMore) return { items, nextCursor: cursor, hasMore: false }
      }
      throw new Error('Chat page range exceeded')
    },
    send({ helpId, clientMessageId, text }) {
      return invoke(base(helpId), { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ clientMessageId, text }) })
    },
    dispose() { disposed = true; for (const controller of pending) controller.abort(); pending.clear() },
  }
}
