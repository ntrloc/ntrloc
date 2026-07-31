// Shared by every component to keep its CSS co-located with its own file (mirroring Angular's
// per-component styles) despite these being light-DOM elements with no shadow root of their own
// -- each component's stylesheet still ends up in one global cascade, just defined next to the
// code that owns it instead of centralized in index.html. Guarded by id since re-running a
// component's script (e.g. a stray double <script> include) must not inject its rules twice.
function injectStyles(id, css) {
  if (document.getElementById(id)) return;
  const style = document.createElement('style');
  style.id = id;
  style.textContent = css;
  document.head.appendChild(style);
}
