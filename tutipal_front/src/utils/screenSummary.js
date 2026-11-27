/** Only the summary is eligible for chat context. Never fall back to detail. */
export function screenSummaryText(result) {
  if (typeof result === 'string') return result
  if (typeof result?.summary === 'string') return result.summary
  return typeof result?.text === 'string' ? result.text : '' // Current ScreenController response.
}

/** Java @Size and JS length both count UTF-16 units. Keep surrogate pairs intact. */
export function boundedScreenSummary(value) {
  if (typeof value !== 'string') return undefined
  const text = value.trim()
  let end = Math.min(text.length, 4000)
  if (end < text.length && /[\uD800-\uDBFF]/.test(text[end - 1])) end--
  return text.slice(0, end) || undefined
}
