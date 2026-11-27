import { quotaWaitMs } from './quotaRetry.js'

/**
 * One widget's capture/analysis/recording lifetime. No image is retained here.
 * onPaused() is called whenever an automatic summary is skipped because the quota pause is still running.
 */
export function createScreenAnalysis({ capture, describe, observe, onAnswer, onSuggestion, onGone, onError, onInvalidate = () => {}, onResult = () => {}, checkTarget = async () => ({ status: 'ok' }), onPaused = () => {}, now = Date.now }) {
  let revision = 0
  let running = false
  let deleting = false
  let flight = null
  let summary = null
  let retry = true
  let lastSeen = now()
  // Automatic summaries pause until the server quota frees up (429, timed on this clock). Questions are never paused.
  let autoPausedUntil = 0

  function invalidate() {
    revision++
    summary = null
    onResult(null)
    flight?.abort()
    flight = null
    retry = true
    lastSeen = now()
    onSuggestion(null)
    onInvalidate()
  }

  async function targetIsCurrent(current, sourceId) {
    const target = await checkTarget(sourceId)
    if (!current()) return false
    if (['off', 'gone', 'changed'].includes(target.status)) {
      running = false
      invalidate()
      onGone(target.status === 'gone')
      return false
    }
    return true
  }

  return {
    setRunning(value) {
      if (running !== value) { running = value; invalidate() }
    },
    reset() { invalidate() },
    /** Epoch ms until which automatic summaries are paused by the server quota (0 = not paused). */
    autoPausedUntil() { return now() < autoPausedUntil ? autoPausedUntil : 0 },
    deletion(value) { deleting = value; invalidate() },
    async summaryForChat() {
      const ownRevision = revision
      const current = () => running && !deleting && revision === ownRevision
      if (!current()) return undefined
      const snapshot = summary
      if (!await targetIsCurrent(current, snapshot?.sourceId)) return undefined
      return current() && summary === snapshot ? snapshot?.text : undefined
    },
    async look(question) {
      if (!running || deleting) return
      const ownRevision = revision
      const current = () => running && !deleting && revision === ownRevision
      // A slow model must not prevent the next timer tick from detecting a closed target.
      if (flight) {
        if (!question) {
          try { await targetIsCurrent(current) } catch { /* The active request also checks before publishing. */ }
        }
        return
      }
      const controller = new AbortController()
      flight = controller
      const record = async (summary) => {
        if (!current()) return
        // Time spent paused by the quota is not counted as observed learning time.
        const seconds = Math.max(0, Math.round((now() - Math.max(lastSeen, autoPausedUntil)) / 1000))
        lastSeen = now()
        try {
          const prior = await observe(null, seconds)
          if (!current()) return
          if (prior?.suggestion) onSuggestion(prior.suggestion)
          if (summary) {
            const next = await observe(summary, 0)
            if (current() && next?.suggestion) onSuggestion(next.suggestion)
          }
        } catch { /* Recording failure must not break screen analysis. */ }
      }
      try {
        const cap = await capture()
        if (!current()) return
        if (cap.status === 'off' || cap.status === 'gone') {
          running = false
          invalidate()
          onGone(cap.status === 'gone')
          return
        }
        // Capture above still runs while paused so a closed window is noticed; only describe is skipped.
        if (cap.changed) { summary = null; onResult(null) }
        if (!question && now() < autoPausedUntil) { onPaused(); return }
        if (!question && !cap.changed && !retry) {
          await record(null)
          return
        }
        const answer = await describe(cap.image, question, controller.signal)
        if (!current() || !await targetIsCurrent(current, cap.sourceId)) return
        // Only a successful automatic summary acknowledges the captured screen.
        // A question must not consume the next automatic summary either.
        if (!question) {
          retry = false
          summary = { text: answer, sourceId: cap.sourceId }
          onResult(answer.trim() ? answer : null)
        }
        onAnswer(answer, !!question)
        if (!question) await record(answer)
      } catch (error) {
        if (!current()) return
        retry = true
        lastSeen = now() // Failed analysis is not counted as observed learning time.
        const wait = question ? null : quotaWaitMs(error, now())
        if (wait !== null) autoPausedUntil = now() + wait
        onError(error, !!question)
      } finally {
        // An invalidated request must never unlock a newer request.
        if (flight === controller) flight = null
      }
    },
  }
}
