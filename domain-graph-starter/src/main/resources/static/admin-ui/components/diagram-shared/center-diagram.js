import { getBBox } from '../../vendor/diagram-js/diagram-js/lib/util/Elements.js';

// Centers whatever's currently on a diagram-js Diagram's canvas (both axes), zooming out to fit
// if it's larger than the viewport but never zooming in past 100% for a small one -- see
// canvas.viewbox()'s own fit-to-box behavior (Canvas.js): it scales *and* anchors the box's
// top-left corner to the viewport's origin, which fits the content but doesn't center it, hence
// computing an inflated box of exactly the container's aspect ratio around the content's center
// instead. Entirely generic -- takes a Diagram instance, not caring what kind of shapes/
// connections it holds -- so both ntrloc-process-editor and the DRD canvas share this unchanged.
export function centerDiagram(diagram) {
  if (!diagram) return;
  const canvas = diagram.get('canvas');
  const elementRegistry = diagram.get('elementRegistry');
  const elements = elementRegistry.getAll().filter((e) => e.waypoints || typeof e.width === 'number');
  if (!elements.length) return;

  const bbox = getBBox(elements);
  const outer = canvas.getSize();
  // A freshly-connected tab's container can still measure 0x0 for a frame or two after this
  // element's own connectedCallback runs (tab-workspace toggles the container's display right
  // around when the new content element is appended, and the browser hasn't necessarily laid it
  // out yet at that exact synchronous point) -- most visible on a brand-new DRD, whose default
  // decision node makes `elements` non-empty immediately, unlike a brand-new BPMN process's empty
  // canvas (which short-circuits above and never reaches this code at all). Computing against a
  // zero-sized outer would divide by zero into an infinite/NaN scale and boxWidth/boxHeight, and
  // canvas.viewbox() applying that throws trying to build an SVGMatrix from it -- which aborts
  // before ever repositioning the viewport, leaving the content wherever diagram-js first placed
  // it (visually pinned near the origin) instead of centered. Retry next frame instead.
  if (!outer.width || !outer.height) {
    requestAnimationFrame(() => centerDiagram(diagram));
    return;
  }
  const scale = Math.min(1, outer.width / bbox.width, outer.height / bbox.height);
  const boxWidth = outer.width / scale;
  const boxHeight = outer.height / scale;

  canvas.viewbox({
    x: bbox.x + bbox.width / 2 - boxWidth / 2,
    y: bbox.y + bbox.height / 2 - boxHeight / 2,
    width: boxWidth,
    height: boxHeight,
  });
}
