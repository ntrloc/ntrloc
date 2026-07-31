// Directional connections (sequence flows, dependency arrows) need an arrowhead -- a plain line
// doesn't convey direction. SVG markers must exist in the document before anything references
// them via marker-end.
//
// Split into two calls (rather than one, generating and returning its own id) because of a real
// ordering constraint: the renderer that needs to reference this id (BpmnRenderer.js/
// DrdRenderer.js) is DI-injected and instantiated as part of `new Diagram(...)` itself, which is
// also what creates the canvas's <svg> -- so the id has to exist *before* that constructor call
// (to go into the DI config), while the actual <marker> DOM node can only be created *after* it
// (once the <svg> exists to append into). nextArrowheadMarkerId() is pure/synchronous so it can
// run first; addArrowheadMarker() does the DOM work once the container's <svg> is real.
//
// baseId is a prefix, not the literal id -- SVG marker ids resolve document-wide (getElementById/
// url(#...) fragment lookup), not scoped per <svg> root, so a fixed id collides the moment a
// second tab with the same editor type is open: two <marker id="X"> elements exist, and every
// marker-end: url(#X) in the whole document -- including the active tab's own connections --
// resolves to whichever is first in the DOM, not necessarily its own tab's. Confirmed live as the
// cause of a real "arrows don't render" report: the winning marker belonged to an *inactive* tab
// (display: none), and a display:none ancestor makes a <marker> unusable everywhere it's
// referenced, not just invisible in that one tab -- so every open tab lost its arrowheads at
// once. This module-level counter guarantees a distinct id per call for the lifetime of the page,
// which is exactly the scope that matters.
let counter = 0;

export function nextArrowheadMarkerId(baseId) {
  counter += 1;
  return `${baseId}-${counter}`;
}

export function addArrowheadMarker(container, markerId) {
  const svg = container.querySelector('svg');
  const NS = 'http://www.w3.org/2000/svg';
  const defs = document.createElementNS(NS, 'defs');
  const marker = document.createElementNS(NS, 'marker');
  marker.setAttribute('id', markerId);
  marker.setAttribute('viewBox', '0 0 10 10');
  marker.setAttribute('refX', '9');
  marker.setAttribute('refY', '5');
  marker.setAttribute('markerWidth', '6');
  marker.setAttribute('markerHeight', '6');
  marker.setAttribute('orient', 'auto-start-reverse');
  const arrow = document.createElementNS(NS, 'path');
  arrow.setAttribute('d', 'M 0 0 L 10 5 L 0 10 z');
  arrow.setAttribute('fill', 'var(--muted)');
  marker.appendChild(arrow);
  defs.appendChild(marker);
  svg.appendChild(defs);
}
