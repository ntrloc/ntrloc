// Shared by ntrloc-decision-table-editor.js and ntrloc-process-editor.js to auto-fill a new
// diagram's Key/Process ID field, so creating one doesn't force the user to invent a unique
// identifier up front -- they can still rename it (both fields stay editable until first save,
// same "locked after save" rule as before), but the common case needs zero typing.
//
// Prefixed rather than a bare hex string: both fields end up as an XML `id` attribute (DMN
// decisionService id / BPMN process id), and XML's NCName production forbids a leading digit --
// crypto.randomUUID()'s first hex digit is unconstrained, so a bare slice could start with one.
// 7 random hex digits after the prefix (8 characters total) keeps it "fairly short" while still
// collision-safe enough for hand-created admin artifacts (not a security identifier).
export function generateShortId(prefix) {
  const random = crypto.randomUUID().replace(/-/g, '').slice(0, 7);
  return `${prefix}${random}`;
}
