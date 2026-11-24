export function selectedHelpSource(sources, id) {
  if (!id || typeof id !== 'string') return null
  return sources.find(source => source.id === id && source.supported === true &&
    typeof source.name === 'string' && typeof source.thumbnail === 'string' &&
    source.thumbnail.startsWith('data:image/')) ?? null
}
