/**
 * PT-106 SSE reader for POST /api/conversations/{id}/messages.
 * Same event format as TutorController (tutor.js keeps its own copy until PT-113 merges; unify them then).
 * Events: sources {"items"} → delta {"t"} … → agent {...} → saved {"userMessageId","assistantMessageId"} → done,
 * or error {"message"}. Comment lines (": ping") are ignored.
 */

export const CUT_MESSAGE = '연결이 끊겨 답변이 중간에 멈췄어요'

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

/**
 * Reads the stream until done. Calls the handlers in order; throws the server message on an error event and
 * CUT_MESSAGE when the stream ends without done (server restart, proxy timeout, ...).
 */
export async function readSse(stream, { onDelta, onSources, onAgent, onSaved } = {}) {
  const reader = stream.pipeThrough(new TextDecoderStream()).getReader()
  let buffer = ''
  try {
    for (;;) {
      const { value, done } = await reader.read()
      if (done) throw new Error(CUT_MESSAGE)
      buffer += value.replace(/\r\n/g, '\n')
      let end
      while ((end = buffer.indexOf('\n\n')) >= 0) {
        const event = parseSseEvent(buffer.slice(0, end))
        buffer = buffer.slice(end + 2)
        if (event.name === 'delta') onDelta?.(JSON.parse(event.data).t)
        else if (event.name === 'sources') onSources?.(JSON.parse(event.data).items)
        else if (event.name === 'agent') onAgent?.(JSON.parse(event.data))
        else if (event.name === 'saved') onSaved?.(JSON.parse(event.data))
        else if (event.name === 'error') throw new Error(JSON.parse(event.data).message)
        else if (event.name === 'done') return
      }
    }
  } finally {
    reader.releaseLock()
  }
}
