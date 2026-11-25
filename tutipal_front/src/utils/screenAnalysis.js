import { quotaWaitMs } from './quotaRetry.js'

/**
 * One widget's capture/analysis/recording lifetime. No image is retained here.
 * onPaused() is called whenever an automatic summary is skipped because the quota pause is still running.
 */
export function createScreenAnalysis({ capture, describe, observe, onAnswer, onSuggestion, onGone, onError, onPaused = () => {}, now = Date.now }) {
  let revision = 0
  let running = false
  let deleting = false
  let busy = false
  let retry = true
  let lastSeen = now()
  // Automatic summaries pause until the server quota frees up (429, timed on this clock). Questions are never paused.
  let autoPausedUntil = 0

  function invalidate() {
    revision++
    retry = true
    lastSeen = now()
    onSuggestion(null)
  }

  return {
    setRunning(value) {
      if (running !== value) { running = value; invalidate() }
    },
    reset() { invalidate() },
    /** Epoch ms until which automatic summaries are paused by the server quota (0 = not paused). */
    autoPausedUntil() { return now() < autoPausedUntil ? autoPausedUntil : 0 },
    deletion(value) { deleting = value; invalidate() },
    async look(question) {
      if (!running || deleting || busy) return
      busy = true
      const ownRevision = revision
      const current = () => running && !deleting && revision === ownRevision
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
        if (!question && now() < autoPausedUntil) { onPaused(); return }
        if (!question && !cap.changed && !retry) {
          await record(null)
          return
        }
        const answer = await describe(cap.image, question)
        if (!current()) return
        // Only a successful automatic summary acknowledges the captured screen.
        // A question must not consume the next automatic summary either.
        if (!question) retry = false
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
        busy = false
      }
    },
  }
}
