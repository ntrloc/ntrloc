import DraggingModule from '../dragging/index.js';
import RulesModule from '../rules/index.js';

import Bendpoints from './Bendpoints.js';
import BendpointMove from './BendpointMove.js';
import BendpointMovePreview from './BendpointMovePreview.js';
import ConnectionSegmentMove from './ConnectionSegmentMove.js';


/**
 * @type { import('didi').ModuleDeclaration }
 */
export default {
  __depends__: [
    DraggingModule,
    RulesModule
  ],
  // Upstream also registers 'bendpointSnapping' here -- deliberately not vendored (it pulls in
  // the whole separate `snapping` feature, grid/alignment guides unrelated to bendpoint editing
  // itself, for a nice-to-have magnetic-snap-while-dragging behavior this app doesn't need).
  __init__: [ 'bendpoints', 'bendpointMovePreview' ],
  bendpoints: [ 'type', Bendpoints ],
  bendpointMove: [ 'type', BendpointMove ],
  bendpointMovePreview: [ 'type', BendpointMovePreview ],
  connectionSegmentMove: [ 'type', ConnectionSegmentMove ]
};
