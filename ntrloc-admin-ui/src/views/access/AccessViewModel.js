// Stub for now -- Access (Group/User permissions management) has no state to manage yet. Kept as
// a real hook, not an inline literal, so AccessView already calls it the way every other view
// calls its own view model -- filling this in later needs no change to AccessView's own call site.
export function useAccessViewModel() {
  return {}
}
