// Draft ids reach request URLs and session storage, so each is checked to be what the server issues
// (a UUID) before it's used in either; anything else is refused rather than sent.
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

export function isId(value) {
  return typeof value === 'string' && UUID.test(value)
}

function requireId(value) {
  if (!isId(value)) throw new Error('Not a valid id')
  return encodeURIComponent(value)
}

/** The address of a draft's API: /api/drafts/{draftId}, followed by any further path segments. */
export function draftAddress(draftId, ...segments) {
  return [`/api/drafts/${requireId(draftId)}`, ...segments].join('/')
}

/** A user (or other) id as a path segment. */
export function idSegment(id) {
  return requireId(id)
}
