/** Ignore obsolete source snapshots after a newer refresh or an unmount. */
export function createShareSourceCatalog(load, onSources, onError) {
  let revision = 0
  let disposed = false
  return {
    async refresh() {
      if (disposed) return null
      const request = ++revision
      try {
        const sources = await load()
        if (disposed || request !== revision) return null
        onSources(sources)
        return sources
      } catch (error) {
        if (!disposed && request === revision) {
          onSources([])
          onError(error)
        }
        return null
      }
    },
    dispose() {
      disposed = true
      revision++
    },
  }
}
