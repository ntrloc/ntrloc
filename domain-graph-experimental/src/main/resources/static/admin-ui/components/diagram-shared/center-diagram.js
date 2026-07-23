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
