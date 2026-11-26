function validRect(r) {
  return r && ['x', 'y', 'width', 'height'].every(k => Number.isFinite(r[k])) &&
    Math.abs(r.x) <= 65536 && Math.abs(r.y) <= 65536 && r.width > 0 && r.height > 0 && r.width <= 32768 && r.height <= 32768
}
function captureBounds(state, size) {
  if (!size || !Number.isInteger(size.width) || !Number.isInteger(size.height) ||
      size.width < 1 || size.height < 1 || size.width > 32768 || size.height > 32768) return null
  const aspect = size.width / size.height
  const choices = [state.window, state.frame, state.client].filter(validRect)
    .map(rect => ({ rect, error: Math.abs(rect.width / rect.height / aspect - 1) }))
    .sort((a, b) => a.error - b.error)
  return choices[0]?.error <= 0.035 ? choices[0].rect : null
}
module.exports = { validRect, captureBounds }
