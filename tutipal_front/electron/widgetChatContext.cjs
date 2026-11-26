/** Navigation context only, never a permission grant. Spring checks session membership. */
function widgetChatContext(url, base) {
  const current = new URL(url)
  if (current.origin !== new URL(base).origin) throw new Error('학습 화면을 다시 열어 주세요.')
  const match = /^\/sessions\/([1-9]\d*)\/?$/.exec(current.pathname)
  const sessionId = match ? Number(match[1]) : undefined
  if (match && !Number.isSafeInteger(sessionId)) throw new Error('수업을 다시 선택해 주세요.')
  const legacy = new Map([['general', '자유 주제'], ['coding', '코딩'], ['english', '영어'], ['math', '수학']])
  const selected = current.pathname === '/chat' ? current.searchParams.get('topic') : null
  const topic = (legacy.get(selected) ?? selected?.trim()) || '자유 주제'
  return { topic: topic.slice(0, 100), ...(sessionId ? { sessionId } : {}) }
}
module.exports = { widgetChatContext }
