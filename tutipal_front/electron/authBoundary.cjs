/** Old 401 responses cannot revoke a later login. No cookies or identity data are retained. */
function createAuthBoundary(onBoundary = () => {}) {
  let revision = 0
  return {
    revision: () => revision,
    revoke() { revision++; onBoundary() },
    unauthorized(ownRevision) { if (ownRevision === revision) { revision++; onBoundary() } },
  }
}
module.exports = { createAuthBoundary }
