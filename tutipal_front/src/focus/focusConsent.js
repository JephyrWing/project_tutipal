// PT-90 consent withdrawal flow shared by the class screen and the settings screen (pure: api and desktop are
// injected, so node tests can check the order without a browser).

/**
 * Stops collection on this PC first (if this is the desktop app), then asks the server to delete the consent and
 * every stored focus row (DELETE /api/focus/consent), then reads the consent again. A failing stop does not block
 * the withdrawal: the server refuses later sends without consent anyway.
 * Returns the fresh consent ({ consented, version, consentedAt, currentVersion }).
 */
export async function withdrawFocusConsent({ api, desktop }) {
  if (typeof desktop?.focusStop === 'function') {
    try { await desktop.focusStop() } catch { /* the server still deletes the rows */ }
  }
  await api.withdrawConsent()
  return api.consent()
}

/** A consent row exists (current or old version): there may be stored rows, so withdrawal is offered. */
export function canWithdraw(consent) {
  return Boolean(consent && (consent.consented || consent.version))
}

/** Settings screen summary of the current consent. */
export function consentSummary(consent) {
  if (!consent) return '동의 상태를 확인하는 중이에요'
  if (consent.consented) {
    const day = typeof consent.consentedAt === 'string' ? consent.consentedAt.slice(0, 10) : ''
    return day ? `${day}에 저장 동의했어요` : '저장 동의했어요'
  }
  if (consent.version) return '동의 문구가 바뀌었어요. 수업 화면에서 다시 동의해야 수집할 수 있어요'
  return '동의하지 않았어요. 저장된 집중도 기록이 없어요'
}
