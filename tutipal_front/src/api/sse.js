/**
 * PT-106 SSE reader for POST /api/conversations/{id}/messages.
 * Same event format as TutorController (tutor.js keeps its own copy until PT-113 merges; unify them then).
 * Events: sources {"items"} → delta {"t"} … → agent {...} → saved {"userMessageId","assistantMessageId"} → done,
 * or error {"message"}. Comment lines (": ping") are ignored.
 */

export const CUT_MESSAGE = '연결이 끊겨 답변이 중간에 멈췄어요'
export const BAD_EVENT_MESSAGE = '서버 응답을 읽지 못했어요. 다시 시도해 주세요'
const KNOWN = new Set(['delta', 'sources', 'agent', 'saved', 'error', 'done'])

/** One raw event block → { name, data } (name defaults to "message", data lines joined with \n). */
export function parseSseEvent(raw) {
  let name = 'message'
  const data = []
  for (const line of raw.split('\n')) {
    if (line.startsWith('event:')) name = line.slice(6).trim()
    else if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''))
  }
  return { name, data: data.join('\n') }
}

function json(data) {
  try {
    return JSON.parse(data)
  } catch {
    throw new Error(BAD_EVENT_MESSAGE)
  }
}

/**
 * Reads the stream until done. Calls the handlers in order; throws the server message on an error event,
 * CUT_MESSAGE when the stream ends without done (server restart, proxy timeout, ...) and BAD_EVENT_MESSAGE for data
 * that is not JSON. CRLF, CR and LF line endings are accepted, also when a CRLF is split across chunks.
 */
export async function readSse(stream, { onDelta, onSources, onAgent, onSaved } = {}) {
  const reader = stream.pipeThrough(new TextDecoderStream()).getReader()
  let buffer = ''
  // a chunk ending in CR may be the first half of CRLF: hold that CR until the next chunk
  let carry = ''
  /** Dispatches every complete event in the buffer; true once done arrived. */
  const drain = () => {
    let end
    while ((end = buffer.indexOf('\n\n')) >= 0) {
      const event = parseSseEvent(buffer.slice(0, end))
      buffer = buffer.slice(end + 2)
      if (!KNOWN.has(event.name)) continue
      const data = json(event.data)     // parse even without a handler: bad data is an error either way
      if (event.name === 'delta') onDelta?.(data.t)
      else if (event.name === 'sources') onSources?.(data.items)
      else if (event.name === 'agent') onAgent?.(data)
      else if (event.name === 'saved') onSaved?.(data)
      else if (event.name === 'error') throw new Error(data?.message || '답변 중 문제가 생겼어요')
      else if (event.name === 'done') return true
    }
    return false
  }
  try {
    for (;;) {
      const { value, done } = await reader.read()
      if (done) {
        // a held CR at the very end is a line break
        if (carry) {
          buffer += '\n'
          carry = ''
          if (drain()) return
        }
        throw new Error(CUT_MESSAGE)
      }
      let raw = carry + value
      carry = raw.endsWith('\r') ? '\r' : ''
      if (carry) raw = raw.slice(0, -1)
      buffer += raw.replace(/\r\n?/g, '\n')
      if (drain()) return
    }
  } finally {
    reader.releaseLock()
  }
}
