import { boundedScreenSummary } from './screenSummary.js'

/** One explicit question per request. No automatic retries or screen API calls. */
export function createWidgetChat({ getContext, getSummary, stream, onStart, onDelta, onDone, onError, onBusy }) {
  let flight = null
  function reset() {
    const previous = flight
    flight = null
    previous?.abort()
    onBusy(false)
  }
  return {
    reset,
    busy: () => flight !== null,
    async ask(input) {
      const question = input.trim().slice(0, 500)
      if (!question || flight) return false
      const controller = new AbortController()
      flight = controller
      const current = () => flight === controller && !controller.signal.aborted
      onBusy(true)
      onStart(question)
      let answer = ''
      try {
        const context = await getContext()
        if (!current()) return false
        const summary = await getSummary()
        if (!current()) return false
        await stream(context.topic, [{ role: 'user', content: question }], delta => {
          if (!current()) return
          answer += delta
          onDelta(answer)
        }, controller.signal, context.sessionId, undefined, undefined,
        { screenSummary: boundedScreenSummary(summary) })
        // Confirm the selected target again before treating the answer as complete.
        if (current()) await getSummary()
        if (current()) onDone(answer)
        return current()
      } catch (error) {
        if (current()) onError(error)
        return false
      } finally {
        if (flight === controller) { flight = null; onBusy(false) }
      }
    },
  }
}
