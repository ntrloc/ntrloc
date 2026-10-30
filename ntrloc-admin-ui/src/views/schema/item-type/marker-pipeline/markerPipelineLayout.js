// Pure layout math for a marker decision pipeline diagram -- a pipeline (+ its own saved
// PipelineLayout.positions, if any, + each carrier block's marker names, + each signal block's own
// condition text) in, concrete node rects and edge point-lists out. No React, no DOM, no mutable
// state -- same separation StateMachineDiagramView keeps from stateMachineLayout, though this module
// is deliberately its own (not a shared import from stateMachineLayout.js) -- the two diagrams are
// different things (see the design discussion this was built from) and shouldn't be coupled just
// because a couple of small text helpers happen to overlap.
//
// Deliberately NOT an auto-layout engine the way stateMachineLayout.js is. Layout here is
// admin-authored and persisted (see org.ntrloc.graph.access.PipelineLayout's own comment: the editor
// owns correctness, the server never recomputes it) -- this module only ever uses a saved position
// verbatim when one exists. The rank-based placement below is strictly a "nothing saved yet"
// fallback for a pipeline with an empty layout, not a general layout algorithm competing with the
// editor; once the editor exists and an admin drags a node, that saved position is what renders from
// then on, not this.
//
// Schema-free, deliberately: condition/marker *text* is always resolved by the caller
// (MarkerPipelineViewModel, which has schema access -- see pipelineConditionText.js) and handed in
// already-shaped (blocks/signalDescriptions below); this module only ever turns already-resolved
// strings into pixel geometry, never an id into a name itself.

const PSEUDO_DIAMETER = 32
const RANK_GAP = 90
const SIBLING_GAP = 28
// How far an orthogonal edge travels horizontally out of its source (or into its target) before it's
// allowed to turn -- see orthogonalPath.
const EDGE_STUB = 20
// Margin around the whole diagram's own content -- the View fits/zooms the diagram to its display
// area via the SVG's viewBox (see MarkerPipelineView.jsx), so this is the only padding that ends up
// visible around the edges once that scaling happens.
const PADDING = 30
// The standard ~10px breathing room this diagram gives every port and every piece of block content
// -- one number, reused everywhere something needs padding, rather than each spot inventing its own.
const STANDARD_PADDING = 10
const PIN_DIAMETER = 14
// A carrier block's single-port side strip width: the pin itself, plus STANDARD_PADDING on each
// side -- exported so the View draws its pins at exactly the same offset this module uses to attach
// edges, rather than two places needing to agree by coincidence. See MarkerPipelineView.jsx's own
// comment on when a port gets a side strip at all.
export const STRIP_WIDTH = PIN_DIAMETER + 2 * STANDARD_PADDING

// Vertical geometry of a carrier block, shared between this module's height calculation and the
// View's rendering -- same reasoning as STRIP_WIDTH above, one set of numbers instead of two places
// agreeing by coincidence. A block is two horizontal bands: a full-width header (the "<add>"-style
// title, its own row, divided from the rest by a full-width rule) and a body band below it (where
// the port side-strips/pins and marker chips live -- see chipLayout and CarrierBlockNode). Pins
// attach to the body band's own vertical center, not the whole block's, since the header isn't part
// of the carrier-flow path.
export const TITLE_AREA_HEIGHT = 24
export const CONTENT_TOP_GAP = 6
export const CHIP_LINE_HEIGHT = 14
export const CHIP_PADDING_Y = 8
export const CHIP_PADDING_X = STANDARD_PADDING
export const CHIP_GAP = 6
export const BOTTOM_PADDING = STANDARD_PADDING
// Breathing room between a marker chip's own edge and the body's strip dividers on either side --
// applied in both nodeDimensions (reserving the space) and chipLayout (actually inset by it), so a
// chip never renders flush against a divider.
export const BODY_SIDE_MARGIN = STANDARD_PADDING
// A block never shrinks below this even with a short marker name or a short title like "Filter" --
// keeps the shape legibly a "block" rather than collapsing to a sliver.
const MIN_BODY_WIDTH = 90
// Every carrier block and signal node's own edit (left) and delete (right) icons, shown in its
// header's own corners only while editing (see headerIcons below) -- but reserved in its own width
// unconditionally (see bodyWidth/selectBodyWidth/filterNodeDimensions/signalNodeDimensions), whether
// or not the diagram is currently in edit mode, so a node never resizes just because editing was
// toggled. The title stays centered on the node's own *full* width regardless of the icons (see
// chipLayout) -- never narrowed to center over just the space between them -- so a node whose width
// the title alone drives (titleCandidateWidth) needs *two* of HEADER_ICON_SIDE_RESERVE, not one:
// clearing one icon (plus HEADER_LABEL_ICON_GAP's own minimum breathing room) on each side while
// staying centered means each side's own reserve has to hold regardless of which side's icon is
// actually closer. <filter> in particular has no other content driving its width wider than its own
// ports need (see filterNodeDimensions' own comment) -- it's the one block type a title's own
// requirement most often actually decides.
const HEADER_ICON_SIZE = 14
// One icon's own footprint from whichever edge it sits at: the standard inset, plus the icon itself
// -- each side of the header holds exactly one icon now (edit left, delete right), not two sharing
// one side, so there's no second icon's own width/gap to add here the way there used to be.
const HEADER_ICON_SIDE_RESERVE = STANDARD_PADDING + HEADER_ICON_SIZE
// The closest a centered title is ever allowed to come to either icon -- titleCandidateWidth below
// adds this (doubled, same reasoning as HEADER_ICON_SIDE_RESERVE's own comment) on top of the icons'
// own footprint, so a title's own measured width alone never has to butt right up against either one.
const HEADER_LABEL_ICON_GAP = 10
// A <select> row's own vertical padding, and the floor every row (even an empty "(Otherwise)" one)
// stays above -- same role as STRIP_WIDTH/BODY_SIDE_MARGIN above, just for rows instead of the
// whole block. See selectRowLayout.
const ROW_PADDING_Y = STANDARD_PADDING
const ROW_MIN_HEIGHT = PIN_DIAMETER + 2 * STANDARD_PADDING
// A subject/predicate chip's own height -- the same as a marker chip's (see chipLayout), just
// auto-width instead of stretched to fill a shared strip (see textChip).
const TEXT_CHIP_HEIGHT = CHIP_LINE_HEIGHT + CHIP_PADDING_Y
const TEXT_CHIP_GAP = CHIP_GAP
// The vertical rhythm inside a predicate box specifically (see predicateBoxHeight/placePredicateBox):
// label to its own context chip, context chip to whatever follows (a sub-predicate's own box, or this
// box's own test chip when it has no children), and the last sub-predicate to this box's own test
// chip. Deliberately its own constant, not TEXT_CHIP_GAP -- a predicate box's stacked pieces read as
// distinct statements ("what's being read" / "what's nested under it" / "what the whole thing
// resolves to"), not just a list of chips, so it gets a wider, independently-tunable gap.
const BOX_CONTENT_GAP = 10
// A predicate box's own label is one line of text, same height as a chip's own line (see
// CHIP_LINE_HEIGHT) -- the header band is just that line plus one BOX_CONTENT_GAP before whatever
// comes next, not a full chip-sized row.
const BOX_LABEL_HEIGHT = CHIP_LINE_HEIGHT
const BOX_HEADER_HEIGHT = BOX_LABEL_HEIGHT + BOX_CONTENT_GAP
// The gap between a <select> row's own criteria (predicate) column and its marker column -- wider
// than the ordinary CHIP_GAP rhythm everything else uses, so the two columns read as clearly
// separate "cells" rather than crowding each other the way a plain content gap would.
const CRITERIA_RIGHT_MARGIN = 30

// No wrapping -- a marker chip is always exactly one line, sized to its own name; the block grows
// to fit whichever marker name is longest, rather than the other way around (see the design
// discussion this was built from).
export function estimateLabelWidth(text) {
  return Math.max(16, (text?.length ?? 0) * 6.2)
}

// Start/End each carry a "start"/"end" text label just outside their own shape (see
// MarkerPipelineView.jsx's PseudoStateNode) -- outside their own rect entirely, so it's not covered
// by nodeDimensions/rects the way everything else is. The canvas's own bounding box (and the
// fallback layout's own rank0 starting x) reserves room for it explicitly here, or it renders
// clipped past the diagram's own left/right edge. Exported so the View positions its label at
// exactly this same gap, not a second number that happens to match.
export const PSEUDO_LABEL_GAP = 10
const START_LABEL_OVERHANG = estimateLabelWidth('start') + PSEUDO_LABEL_GAP
const END_LABEL_OVERHANG = estimateLabelWidth('end') + PSEUDO_LABEL_GAP

// How far short of End's own circle an edge's input point lands -- not the line itself, but far
// enough that the edge's own terminal arrowhead (see MarkerPipelineView.jsx's EDGE_ARROW_HALF_WIDTH/
// EDGE_ARROW_OFFSET_X) lands with its tip exactly on the circle's own boundary, not short of it (a
// visible gap) or past it (buried in the circle). Landing the line's own target exactly on the
// boundary would do the burying -- the arrow is centered on its own terminal point, so half its own
// width would land inside the circle -- hence this gap, sized to cancel exactly that: EDGE_ARROW_HALF_WIDTH
// (how far the tip extends ahead of the terminal point) minus EDGE_ARROW_OFFSET_X (the terminal
// point's own leftward nudge off center).
const PSEUDO_INPUT_ARROW_GAP = 3

// A bit more generous than estimateLabelWidth's own flat per-character estimate -- the longer
// subject/predicate phrases this chip renders (e.g. "Approval status = APPROVED") compound that
// estimate's own imprecision enough to visibly overflow the chip's rounded rect without it.
function textChipWidth(text) {
  return estimateLabelWidth(text) * 1.1 + 2 * CHIP_PADDING_X
}

// A subject ('linked item: X', 'property: X'), predicate ('= NATURE', a <select> row's own branch
// caption), or test ('= APPROVED', 'any match' -- a predicate box's own comparison, see
// placePredicateBox) chip -- the colored-pill shapes the design calls for (filled green for a
// subject, unfilled light-blue for predicate/test alike -- see MarkerPipelineView.css), auto-sized to
// its own text rather than stretched to fill a shared strip the way a marker chip is (see chipLayout).
// x is the chip's own left edge; a centered caller passes centerX - width/2 instead.
function textChip(kind, text, x, y) {
  return { kind, text, x, y, width: textChipWidth(text), height: TEXT_CHIP_HEIGHT }
}

function isPseudo(node) {
  return node.type === 'START' || node.type === 'END'
}

// Where a node's own edit/delete icons land -- edit in its header's own STANDARD_PADDING-inset left
// corner, delete in its right one (HEADER_ICON_SIDE_RESERVE each), both vertically centered in the
// header band. null when not editable, so a caller can render conditionally off this one check
// rather than its own `editable` prop a second time. Shared by chipLayout/filterPortLayout/
// selectRowLayout/signalRowLayout/signalConditionLayout so every block/node shape places its own
// icons the same way.
function headerIcons(rect, editable) {
  if (!editable) return null
  const y = rect.y + (TITLE_AREA_HEIGHT - HEADER_ICON_SIZE) / 2
  const editX = rect.x + STANDARD_PADDING
  const deleteX = rect.x + rect.width - STANDARD_PADDING - HEADER_ICON_SIZE
  return {
    edit: { x: editX, y, size: HEADER_ICON_SIZE },
    delete: { x: deleteX, y, size: HEADER_ICON_SIZE },
  }
}

// The body band's own vertical center -- everything below the header rule, where ports/pins and
// marker chips live. Exported so the View's pins and this module's edge attachment agree on exactly
// the same y, rather than two formulas that happen to match.
export function bodyMidY(rect) {
  return rect.y + TITLE_AREA_HEIGHT + (rect.height - TITLE_AREA_HEIGHT) / 2
}

// markerNames: this block's own marker names, already resolved from ids (see
// MarkerPipelineViewModel's blockLabel). Body width is the wider of two independent requirements:
// the marker chips' own (ordinary BODY_SIDE_MARGIN-padded) width, or the title's own -- and the
// title's is 2 * (HEADER_ICON_SIDE_RESERVE + HEADER_LABEL_ICON_GAP), not one, because the title
// centers on the block's full width (see chipLayout), not just the space between the icons: clearing
// each side's own icon (plus HEADER_LABEL_ICON_GAP's own minimum breathing room) while staying
// centered means both sides need the same reserve, regardless of which one actually binds (see
// HEADER_ICON_SIDE_RESERVE's own comment, and titleCandidateWidth, shared with selectBodyWidth/
// filterNodeDimensions/signalNodeDimensions so the four block/node shapes can't disagree on what
// "enough room" means).
function titleCandidateWidth(title) {
  return estimateLabelWidth(title) + 2 * (HEADER_ICON_SIDE_RESERVE + HEADER_LABEL_ICON_GAP)
}

function bodyWidth(label) {
  const chipWidest = Math.max(0, ...label.markerNames.map((n) => estimateLabelWidth(n) + 2 * CHIP_PADDING_X))
  const chipWidth = chipWidest + 2 * BODY_SIDE_MARGIN
  return Math.max(titleCandidateWidth(label.title), chipWidth, MIN_BODY_WIDTH)
}

// A row's own content height: stacked marker chips, same formula as the ordinary chips-under-title
// case. The "(Otherwise)" row (see blockLabel) has no chips of its own -- just its own text line. An
// optional condition-branch caption (see blockLabel's SELECT case -- set only when this row's gate
// traces back to one member of a ConditionGroup this <select> wholly owns) sits inline, beside the
// row's own first marker chip rather than stacked above it (see selectRowLayout) -- it only ever
// adds width, never height.
function rowContentHeight(row) {
  if (row.otherwise) return CHIP_LINE_HEIGHT
  const chipHeight = CHIP_LINE_HEIGHT + CHIP_PADDING_Y
  return row.markerNames.length * chipHeight + CHIP_GAP * Math.max(0, row.markerNames.length - 1)
}

function rowHeight(row) {
  return Math.max(ROW_MIN_HEIGHT, rowContentHeight(row) + 2 * ROW_PADDING_Y)
}

// <select> is the one carrier block whose body isn't "title + stacked chips over a single shared
// output pin" -- it's a stack of independently-gated rows (see Select's own doc comment), each
// needing its own output pin at its own row's y. blockLabel represents that as label.rows: one entry
// per select.adds[i], plus a synthetic trailing row (otherwise: true) whose id is the Select block's
// own id -- unifying "the block's own output pin" and "a row's output pin" into one list, since
// that's exactly what the Otherwise pin is: a row with no chips of its own. propertyLabel (optional):
// "property: X" header for a ConditionGroup this <select> wholly owns and therefore renders inline
// rather than as its own standalone signal node -- see inlinedConditionGroupFor. See selectRowLayout,
// which lays these rows out the same way chipLayout lays out an ordinary block's chips.
// A row's own required width: its condition-branch caption (see rowContentHeight's own comment),
// then CRITERIA_RIGHT_MARGIN, then the widest of its own marker chips -- all three sit on one line,
// which is what tends to make a <select> with inlined branch captions wider than most other blocks.
// The one shared width every row's own condition chip (if it has one) reserves -- like a table
// column's width, driven by its widest cell, not each row's own -- so every row's marker chip(s)
// start at the same x regardless of how long *that* row's own branch caption happens to be. Shared
// by selectBodyWidth (sizing the block) and selectRowLayout (actually placing the chips) so the two
// can't drift apart. 0 when no row has one at all (an ordinary <select>, no inlined ConditionGroup).
function predicateColumnWidth(rows) {
  return Math.max(0, ...rows.map((row) => (row.conditionLabel ? textChipWidth(row.conditionLabel) : 0)))
}

function rowRequiredWidth(row, predicateColWidth) {
  const markerWidth = Math.max(0, ...row.markerNames.map((n) => estimateLabelWidth(n) + 2 * CHIP_PADDING_X))
  return predicateColWidth > 0 ? predicateColWidth + CRITERIA_RIGHT_MARGIN + markerWidth : markerWidth
}

function selectBodyWidth(rows, title, propertyLabel) {
  const predicateColWidth = predicateColumnWidth(rows)
  const contentWidest = Math.max(
    propertyLabel ? textChipWidth(`property: ${propertyLabel}`) : 0,
    ...rows.map((row) => (row.otherwise ? 0 : rowRequiredWidth(row, predicateColWidth))),
    estimateLabelWidth('(Otherwise)'),
    0,
  )
  const contentWidth = contentWidest + 2 * BODY_SIDE_MARGIN
  return Math.max(titleCandidateWidth(title), contentWidth, MIN_BODY_WIDTH)
}

// The gap a "property: X" header reserves above the rows it precedes -- one chip's own height plus
// the breathing room on either side of it, the same amount selectRowLayout actually uses to push its
// rows down by.
function propertyHeaderHeight(propertyLabel) {
  return propertyLabel ? CONTENT_TOP_GAP + TEXT_CHIP_HEIGHT + TEXT_CHIP_GAP : 0
}

function selectHeight(rows, propertyLabel) {
  return TITLE_AREA_HEIGHT + propertyHeaderHeight(propertyLabel) + rows.reduce((sum, row) => sum + rowHeight(row), 0) + BOTTOM_PADDING
}

// <filter> has two distinct left-side input ports -- its carrier "input" and its "if" gate
// reference -- rather than the single shared strip every other carrier block gets (see
// filterPortLayout and CarrierBlockNode's own comment on why that split exists). Both still fit the
// same row-stacking rhythm as everything else (ROW_MIN_HEIGHT each), just two of them instead of a
// single bodyMidY-centered pin.
// Sized tightly to its own two ports, not padded out to MIN_BODY_WIDTH the way every other block
// is -- a <filter> has nothing else to grow for (no title-adjacent content, no marker chips), so
// that floor would only ever add dead space. Left section: STRIP_WIDTH (the pin, already with its
// own 10px margin on each side -- see filterPortLayout's own x) plus the wider of "input"/"if"
// plus one trailing STANDARD_PADDING past it, sharing STRIP_WIDTH's own right margin as the gap
// before the label rather than stacking a second gap on top of it. Right section: STRIP_WIDTH again,
// for the output pin alone.
function filterNodeDimensions(label) {
  const labelWidth = Math.max(estimateLabelWidth('input'), estimateLabelWidth('if'))
  const leftSectionWidth = STRIP_WIDTH + labelWidth + STANDARD_PADDING
  const portWidth = leftSectionWidth + STRIP_WIDTH
  // <filter> has nothing else (no marker chips, no rows) that could otherwise end up wider than its
  // own title's requirement -- it's the one block type titleCandidateWidth alone usually decides
  // (see that function's own comment, and bodyWidth's).
  const width = Math.max(portWidth, titleCandidateWidth(label.title))
  // No separate BOTTOM_PADDING term here, unlike an ordinary block's chip stack -- each ROW_MIN_HEIGHT
  // row already bakes in its own 10px margin above *and* below its own pin (that's what centers the
  // pin within it), so the "if" row's own bottom margin already is the block's bottom margin. Adding
  // BOTTOM_PADDING on top of that doubled the space below the "if" pin for nothing.
  const height = TITLE_AREA_HEIGHT + 2 * ROW_MIN_HEIGHT
  return { width, height: Math.max(height, PSEUDO_DIAMETER) }
}

function nodeDimensions(node, blocks) {
  if (isPseudo(node)) return { width: PSEUDO_DIAMETER, height: PSEUDO_DIAMETER }
  const label = blocks.get(node.id)
  if (label?.isFilter) return filterNodeDimensions(label)
  if (label?.rows) {
    return {
      width: 2 * STRIP_WIDTH + selectBodyWidth(label.rows, label.title, label.propertyLabel),
      height: Math.max(selectHeight(label.rows, label.propertyLabel), PSEUDO_DIAMETER),
    }
  }
  const markerNames = label?.markerNames ?? []
  const chipHeight = CHIP_LINE_HEIGHT + CHIP_PADDING_Y
  const chipsHeight = markerNames.length === 0 ? 0 : markerNames.length * chipHeight + CHIP_GAP * (markerNames.length - 1)
  const height = TITLE_AREA_HEIGHT + CONTENT_TOP_GAP + chipsHeight + BOTTOM_PADDING
  const width = 2 * STRIP_WIDTH + bodyWidth(label ?? { title: '', markerNames: [] })
  return { width, height: Math.max(height, PSEUDO_DIAMETER) }
}

function allNodes(pipeline) {
  return [pipeline.start, ...pipeline.carrierBlocks, pipeline.end]
}

function gatesOf(block) {
  if (block.type === 'SELECT') return [block.gate, ...block.adds.map((add) => add.gate)]
  if (block.type === 'FILTER') return [block.signal]
  return [block.gate]
}

// The signal domain: a top-level Condition/ConditionGroup is read, not routed -- it has no carrier
// input/output of its own, just one addressable pin per Condition it contains (a bare Condition is
// one pin; a ConditionGroup is several, one per member condition -- see Pipeline's own doc comment
// on topLevelSignals). A ConditionGroup has no id of its own to key a node/position by (only its
// member Conditions are individually addressable -- see ConditionGroup.java), so it's keyed by its
// first condition's id instead.
function signalNodeId(signal) {
  return signal.type === 'CONDITION_GROUP' ? `group-${signal.conditions[0].id}` : signal.id
}

function signalPinIds(signal) {
  return signal.type === 'CONDITION_GROUP' ? signal.conditions.map((c) => c.id) : [signal.id]
}

// A ConditionGroup renders inline inside the one <select> that owns every one of its member
// conditions (see blockLabel's SELECT case), rather than as its own standalone pulled-from node -- a
// <select> already shows each member's own branch as a row, so a separate box plus dashed lines back
// into the very rows that already say the same thing would be pure noise. Only applies when every
// reference to the group's pins, anywhere in the pipeline, belongs to that one <select>'s own gate
// structure (its own gate, or one of its rows'); a group referenced from more than one place -- or a
// bare Condition, always individually addressable -- still gets its own standalone node.
function inlinedConditionGroupOwner(pipeline, signal) {
  if (signal.type !== 'CONDITION_GROUP') return null
  const pinIds = new Set(signal.conditions.map((c) => c.id))
  let owner = null
  for (const block of pipeline.carrierBlocks) {
    const references = gatesOf(block).some((gate) => (gate?.conditionOutputPinIds ?? []).some((id) => pinIds.has(id)))
    if (!references) continue
    if (owner !== null && owner !== block.id) return null
    owner = block.id
  }
  const ownerBlock = pipeline.carrierBlocks.find((b) => b.id === owner)
  return ownerBlock?.type === 'SELECT' ? owner : null
}

// The ConditionGroup a given block wholly owns (see inlinedConditionGroupOwner), if any -- what
// MarkerPipelineViewModel's blockLabel uses to build a <select>'s own "property: X" header and each
// row's branch caption.
export function inlinedConditionGroupFor(pipeline, blockId) {
  return (pipeline.topLevelSignals ?? []).find((signal) => inlinedConditionGroupOwner(pipeline, signal) === blockId) ?? null
}

// Every topLevelSignal that gets its own standalone node -- i.e. every one except a ConditionGroup
// inlined into the one <select> that wholly owns it (see inlinedConditionGroupOwner). Carries the raw
// `signal` itself (not just id/title/pinIds) so MarkerPipelineViewModel can describe its text without
// this module -- deliberately schema-free -- needing to know how.
export function signalNodes(pipeline) {
  return (pipeline.topLevelSignals ?? [])
    .filter((signal) => inlinedConditionGroupOwner(pipeline, signal) === null)
    .map((signal) => ({
      id: signalNodeId(signal),
      title: signal.type === 'CONDITION_GROUP' ? 'Condition Group' : 'Condition',
      pinIds: signalPinIds(signal),
      signal,
    }))
}

// A predicate box's own minimum content width (see pipelineConditionText.describePredicateBox for the
// {label, subjectText, testText, children} shape), bottom-up: the widest of its own subject/test chip
// and its own children's boxes, each of which needs BODY_SIDE_MARGIN clear on every side of ITS
// widest content just like this box does around its own (a child is itself a bordered box, not bare
// content) -- it isn't enough to reserve only a child's own inner width, or that child's box ends up
// exactly BODY_SIDE_MARGIN too narrow to actually contain itself.
function predicateBoxMinWidth(box) {
  if (!box) return 0
  return Math.max(
    box.subjectText ? textChipWidth(box.subjectText) : 0,
    box.testText ? textChipWidth(box.testText) : 0,
    ...box.children.map((child) => predicateBoxMinWidth(child) + 2 * BODY_SIDE_MARGIN),
    0,
  )
}

// A predicate box's own height, bottom-up: its header, an optional subject chip, each child's own box
// stacked top to bottom, then an optional test chip for its own test -- last, since a compound
// predicate's own test (e.g. CountedPredicate's count quantifier) describes the *result* of
// everything above it, not a detail of any one child (see MarkerPipelineView.css's own comment).
// BOX_CONTENT_GAP separates consecutive pieces only (never trails after the last one) -- the box's
// own bottom padding is ROW_PADDING_Y alone, the same way every other block in this module reserves
// exactly one bottom padding, not a padding plus a leftover inter-piece gap.
function predicateBoxHeight(box) {
  if (!box) return 0
  const pieceCount = (box.subjectText ? 1 : 0) + box.children.length + (box.testText ? 1 : 0)
  let height = BOX_HEADER_HEIGHT + Math.max(0, pieceCount - 1) * BOX_CONTENT_GAP
  if (box.subjectText) height += TEXT_CHIP_HEIGHT
  for (const child of box.children) height += predicateBoxHeight(child)
  if (box.testText) height += TEXT_CHIP_HEIGHT
  return height + ROW_PADDING_Y
}

// description: this node's own {propertyLabel, rowLabels} (a ConditionGroup) or a predicate box (a
// bare Condition's own root box -- see pipelineConditionText.describeCondition) from
// MarkerPipelineViewModel. No predicate detail at all when absent (schema still loading, or a shape
// this module doesn't know how to size yet) -- still a valid, if minimal, box. Only a right-side
// strip: unlike a carrier block, a signal block has no input of its own to feed, so there's nothing on
// the left to reserve room for.
// titleCandidateWidth (see its own comment) competes against the node's own content width in both
// branches below, same as bodyWidth/selectBodyWidth/filterNodeDimensions -- the title's own
// requirement is never just folded in as one more candidate alongside the content, nor added
// unconditionally on top of it, since the two need different margins (2 * (HEADER_ICON_SIDE_RESERVE
// + HEADER_LABEL_ICON_GAP) vs. 2 * BODY_SIDE_MARGIN) and only the wider actually has to win.
function signalNodeDimensions(node, description) {
  if (node.pinIds.length > 1) {
    const height = TITLE_AREA_HEIGHT + propertyHeaderHeight(description?.propertyLabel) + node.pinIds.length * ROW_MIN_HEIGHT + BOTTOM_PADDING
    const labelWidths = (description?.rowLabels ?? []).map((l) => textChipWidth(l))
    const contentWidest = Math.max(
      description?.propertyLabel ? textChipWidth(`property: ${description.propertyLabel}`) : 0,
      ...labelWidths,
      0,
    )
    const contentWidth = contentWidest + 2 * BODY_SIDE_MARGIN
    const width = STRIP_WIDTH + Math.max(titleCandidateWidth(node.title), contentWidth, MIN_BODY_WIDTH)
    return { width, height }
  }
  const box = description ?? null
  // The root box is itself a piece of content at the outer level, needing BODY_SIDE_MARGIN clear on
  // every side of its own content just like a nested box does within it -- see predicateBoxMinWidth.
  const boxMinWidth = box ? predicateBoxMinWidth(box) + 2 * BODY_SIDE_MARGIN : 0
  const boxWidth = boxMinWidth + 2 * BODY_SIDE_MARGIN
  const boxHeight = box ? predicateBoxHeight(box) : 0
  const height = Math.max(
    TITLE_AREA_HEIGHT + CONTENT_TOP_GAP + boxHeight + BOTTOM_PADDING,
    TITLE_AREA_HEIGHT + ROW_MIN_HEIGHT + BOTTOM_PADDING,
  )
  const width = STRIP_WIDTH + Math.max(titleCandidateWidth(node.title), boxWidth, MIN_BODY_WIDTH)
  return { width, height }
}

// A standalone ConditionGroup's own stacked pin-rows -- same shape as before text existed (one row
// per member Condition, each with its own output pin), now also carrying that member's own short
// label (see pipelineConditionText.describeConditionShort) and an optional "property: X" header for
// the group's own shared subject. Rare in practice -- our one real pipeline's only ConditionGroup is
// inlined into its <select> (see inlinedConditionGroupOwner) -- but kept correct for a group that
// ever is referenced from more than one place.
// editable: see headerIcons -- only governs whether the icons themselves are drawn, never this
// node's own size or where anything centers (both are now constant regardless of edit state -- see
// bodyWidth's own comment). centerX is full-width throughout, including the title (see chipLayout's
// own comment on why that can never overlap the icons, via titleCandidateWidth in
// signalNodeDimensions).
export function signalRowLayout(rect, pinIds, description, editable) {
  const headerBottom = rect.y + TITLE_AREA_HEIGHT
  const titleY = rect.y + TITLE_AREA_HEIGHT / 2 + 4
  // Centered on the whole box, not just its body left of the output strip -- a signal node has a
  // strip on its right only (no left input strip to balance it, unlike a carrier block's own
  // symmetric two-strip body), so centering within the body alone would read as off-center against
  // the box an admin actually sees (see CarrierBlockNode/chipLayout for the symmetric case this
  // otherwise matches).
  const centerX = rect.x + rect.width / 2
  let propertyChip = null
  if (description?.propertyLabel) {
    const text = `property: ${description.propertyLabel}`
    const width = textChipWidth(text)
    propertyChip = { kind: 'subject', text, x: centerX - width / 2, y: headerBottom + CONTENT_TOP_GAP, width, height: TEXT_CHIP_HEIGHT }
  }
  const rowsTop = headerBottom + propertyHeaderHeight(description?.propertyLabel)
  const rows = pinIds.map((id, i) => {
    const top = rowsTop + i * ROW_MIN_HEIGHT
    const label = description?.rowLabels?.[i] ?? null
    const labelChip = label ? textChip('predicate', label, rect.x + CHIP_PADDING_X, top + (ROW_MIN_HEIGHT - TEXT_CHIP_HEIGHT) / 2) : null
    return { id, top, midY: top + ROW_MIN_HEIGHT / 2, labelChip }
  })
  return { centerX, titleY, headerBottom, propertyChip, rows, icons: headerIcons(rect, editable) }
}

// Top-down: places one predicate box at (x, y), stretched to `width` -- already decided by the
// outermost caller (signalConditionLayout, from the signal node's own established rect, which
// signalNodeDimensions sized off this same box's own predicateBoxMinWidth) -- so every nested level
// reads as a uniform-width frame inset from its parent by BODY_SIDE_MARGIN, not sized to its own
// narrower content. Order matches the mockup this was built from: subject chip (what's being read),
// then each child box (what's being tested on it), then this box's own test chip last (what the whole
// box resolves to -- see predicateBoxHeight's own comment on why the test comes after the children).
// BOX_CONTENT_GAP is only ever added *before* a piece that has a piece above it (mirroring
// predicateBoxHeight's own pieceCount - 1 rule), never trailing after the last one. The test chip is
// horizontally centered on the box's own width, unlike the subject chip and every nested child box
// (both left-inset by BODY_SIDE_MARGIN) -- a box's own test is its single, standalone verdict, not
// one more line of stacked content reading left-to-right with the rest.
function placePredicateBox(box, x, y, width) {
  const rect = { x, y, width, height: predicateBoxHeight(box) }
  let cy = y + BOX_HEADER_HEIGHT
  let hasPriorPiece = false

  let subjectChip = null
  if (box.subjectText) {
    subjectChip = textChip('subject', box.subjectText, x + BODY_SIDE_MARGIN, cy)
    cy += TEXT_CHIP_HEIGHT
    hasPriorPiece = true
  }

  const childWidth = width - 2 * BODY_SIDE_MARGIN
  const children = box.children.map((child) => {
    if (hasPriorPiece) cy += BOX_CONTENT_GAP
    const placed = placePredicateBox(child, x + BODY_SIDE_MARGIN, cy, childWidth)
    cy += placed.rect.height
    hasPriorPiece = true
    return placed
  })

  let testChip = null
  if (box.testText) {
    if (hasPriorPiece) cy += BOX_CONTENT_GAP
    const testWidth = textChipWidth(box.testText)
    testChip = textChip('test', box.testText, x + width / 2 - testWidth / 2, cy)
  }

  return { rect, label: box.label, labelY: y + BOX_LABEL_HEIGHT / 2 + 6, subjectChip, children, testChip }
}

// A standalone bare Condition's own content: one recursive predicate box (see placePredicateBox),
// rooted at headerBottom + CONTENT_TOP_GAP and stretched to the body's own available width (the same
// width signalNodeDimensions already reserved for it via predicateBoxMinWidth, so the two can't drift
// apart). Exactly one output pin, centered on the whole body the same way an ordinary carrier block's
// single pin is (see bodyMidY) -- a bare Condition is always exactly one addressable pin (see
// signalPinIds).
// editable: see headerIcons -- only governs whether the icons are drawn. The predicate box below the
// title sits well clear of the icons' own band regardless (same reasoning as chipLayout's own
// comment), so its own width is untouched by titleCandidateWidth -- only signalNodeDimensions' own
// sizing needs to know about it, not where this box is actually placed.
export function signalConditionLayout(rect, description, editable) {
  const headerBottom = rect.y + TITLE_AREA_HEIGHT
  const titleY = rect.y + TITLE_AREA_HEIGHT / 2 + 4
  const bodyRight = rect.x + rect.width - STRIP_WIDTH
  // Centered on the whole box -- see signalRowLayout's own comment on why this can't be
  // (rect.x + bodyRight) / 2 the way it used to be (a signal node's only strip is on the right, so
  // that formula reads as off-center against the box itself).
  const centerX = rect.x + rect.width / 2

  let box = null
  if (description) {
    const boxX = rect.x + BODY_SIDE_MARGIN
    const boxWidth = bodyRight - boxX - BODY_SIDE_MARGIN
    box = placePredicateBox(description, boxX, headerBottom + CONTENT_TOP_GAP, boxWidth)
  }

  return { centerX, titleY, headerBottom, box, pinY: bodyMidY(rect), icons: headerIcons(rect, editable) }
}

// An edge always connects horizontally at both ends -- an output pin's own line always leaves it
// heading right, an input pin's own line always arrives heading right too (approaching from its own
// left side), no matter where the other end actually sits. When the target is comfortably to the
// right, that's a single two-bend "S" path straight across, with exactly one adjustable bend: the
// vertical run's own x. When it's at or behind the source -- the common case for a signal edge,
// since the signal column sits to the right of whatever it's gating -- the path can't just zigzag:
// it has to leave the source, double back below both ends, and come into the target from its own
// left side the same as any other edge does, with exactly one adjustable bend: the detour lane's
// own y. `bend` is that one saved override (a plain number, from PipelineLayout.edgeBends -- see
// MarkerPipelineViewModel), undefined for an edge the admin never touched, in which case this picks
// the same default it always has. No collision avoidance either way -- this stays a deliberately
// cheap router, not the rank-distance one stateMachineLayout.js needed; an admin drags the one bend
// each shape actually has, not an arbitrary polyline.
//
// Returns both the drawable `points` and the single draggable `handle` for that one bend -- its own
// position, and which axis dragging it is allowed to move along -- so the View never has to
// re-derive either from the path shape itself.
export function orthogonalPath(from, to, bend) {
  const dx = to.x - from.x
  if (dx >= 2 * EDGE_STUB) {
    const midX = bend ?? from.x + dx / 2
    return {
      points: [from, { x: midX, y: from.y }, { x: midX, y: to.y }, to],
      handle: { x: midX, y: (from.y + to.y) / 2, axis: 'x' },
    }
  }
  const exitX = from.x + EDGE_STUB
  const entryX = to.x - EDGE_STUB
  const laneY = bend ?? Math.max(from.y, to.y) + EDGE_STUB
  return {
    points: [from, { x: exitX, y: from.y }, { x: exitX, y: laneY }, { x: entryX, y: laneY }, { x: entryX, y: to.y }, to],
    handle: { x: (exitX + entryX) / 2, y: laneY, axis: 'y' },
  }
}

// Longest-path rank from start, via carrier Routes only (never a signal gate -- gates are pulled,
// not pushed, so they carry no carrier-flow edge; see Route's own comment). A node unreachable from
// start (shouldn't happen in a valid pipeline, but drawn rather than dropped if it ever does) gets
// rank 0, same column as start.
function rankByLongestPath(pipeline) {
  const outEdges = new Map()
  for (const route of pipeline.routes) {
    outEdges.set(route.outputPinId, [...(outEdges.get(route.outputPinId) ?? []), ...route.inputPinIds])
  }
  // A <select> row has no Route pointing to it from outside -- it's fed internally by the block's own
  // input, not addressed by any route's own inputPinIds (see Select's own doc comment) -- so without
  // this, a row's id never gets enqueued at all, and anything the row itself feeds (e.g. a downstream
  // <filter>) never gets ranked either, silently falling back to rank 0: the same column as Start.
  // Seeded at the *same* rank as the owning Select, not rank+1, since a row is logically part of the
  // Select's own slot (its own output pin lives at the Select's own column too), not a further
  // downstream step.
  const rowIdsBySelectId = new Map(
    pipeline.carrierBlocks.filter((b) => b.type === 'SELECT').map((b) => [b.id, b.adds.map((add) => add.id)]),
  )
  const rank = new Map([[pipeline.start.id, 0]])
  const queue = [pipeline.start.id]
  while (queue.length > 0) {
    const id = queue.shift()
    const r = rank.get(id)
    for (const rowId of rowIdsBySelectId.get(id) ?? []) {
      if (!rank.has(rowId) || rank.get(rowId) > r) {
        rank.set(rowId, r)
        queue.push(rowId)
      }
    }
    for (const nextId of outEdges.get(id) ?? []) {
      if (!rank.has(nextId) || rank.get(nextId) < r + 1) {
        rank.set(nextId, r + 1)
        queue.push(nextId)
      }
    }
  }
  return rank
}

// positions: Map<UUID, {x, y}> decoded from the pipeline's own PipelineLayout.positions (empty map
// if nothing saved yet, never null). blocks: Map<UUID, {title, markerNames}> from
// MarkerPipelineViewModel -- see nodeDimensions' own comment on why sizing needs this.
// signalDescriptions: Map<signal node id, {propertyLabel, rowLabels} | {lines, nested}> from the same
// place -- see signalNodeDimensions. edgeBends: Map<edge key, number> decoded from
// PipelineLayout.edgeBends (empty map if nothing saved yet, never null) -- see orthogonalPath.
// No `editable` param -- every node's own size always reserves room for its edit/delete icons (see
// HEADER_ICON_SIDE_RESERVE), whether or not the diagram is currently in edit mode, so a block never
// resizes just because editing was toggled. Edit mode only ever affects whether the icons themselves
// are drawn (see headerIcons), decided where each node's own layout is actually placed (the View's
// own chipLayout/filterPortLayout/selectRowLayout/signalRowLayout/signalConditionLayout calls), not
// here.
export function computeLayout(pipeline, positions, blocks, signalDescriptions = new Map(), edgeBends = new Map()) {
  const nodes = allNodes(pipeline)
  const rank = rankByLongestPath(pipeline)
  const { sortedRanks, dimsByNodeId, rankX } = computeColumns(nodes, rank, blocks)

  const { rects, ...carrierBounds } = layoutCarrierNodes(sortedRanks, dimsByNodeId, rankX, positions, pipeline)

  // Signal nodes have no Route to derive a rank from (see rankByLongestPath's own comment), so they
  // get one trailing column of their own, past every carrier rank -- stacked the same fallback way
  // as any other same-rank column, with a saved position overriding it exactly like a carrier
  // block's.
  const signalNodesList = signalNodes(pipeline)
  const bounds = layoutSignalNodes(signalNodesList, positions, signalDescriptions, rects, carrierBounds)

  const { outputPinPositions, inputPinPositions, gateAnchors } = computePinPositions(
    nodes,
    signalNodesList,
    rects,
    blocks,
    signalDescriptions,
  )

  const edges = computeRouteEdges(pipeline, outputPinPositions, inputPinPositions, edgeBends)
  const signalEdges = computeSignalEdges(pipeline, outputPinPositions, gateAnchors, edgeBends)

  return {
    rects,
    edges,
    signalNodes: signalNodesList,
    signalEdges,
    // Where each pin is: carrier outputs and condition pins by pin id, carrier inputs by block id, and
    // the gates conditions connect to by their block's (or <select> row's) id.
    outputPinPositions,
    inputPinPositions,
    gateAnchors,
    // minX/minY: the top-left corner of the diagram's own bounding box, PADDING clear of the
    // tightest content actually needs -- exported so the View's fit-zoom can center the viewport
    // over exactly this box instead of assuming it starts at (0, 0), which a saved position is
    // never guaranteed to produce (see layoutCarrierNodes' own comment on minX/minY).
    minX: bounds.minX - PADDING,
    minY: bounds.minY - PADDING,
    width: bounds.maxX - bounds.minX + 2 * PADDING,
    height: bounds.maxY - bounds.minY + 2 * PADDING,
  }
}

// Column X per rank is cumulative off the widest node in every prior rank -- fixed-width columns
// would either waste space or clip a wide block, now that width is content-driven instead of a
// constant.
function computeColumns(nodes, rank, blocks) {
  const byRank = new Map()
  for (const node of nodes) {
    const r = rank.get(node.id) ?? 0
    byRank.set(r, [...(byRank.get(r) ?? []), node])
  }
  const sortedRanks = [...byRank.entries()].sort((a, b) => a[0] - b[0])

  const dimsByNodeId = new Map(nodes.map((n) => [n.id, nodeDimensions(n, blocks)]))
  // Rank 0 is always exactly Start alone (nothing ever routes back into it -- see rankByLongestPath),
  // so bumping its own starting x to clear the "start" label's own overhang (see its own comment)
  // only ever affects Start's fallback position, never any other node's.
  const rankX = new Map([[sortedRanks[0]?.[0] ?? 0, Math.max(PADDING, START_LABEL_OVERHANG)]])
  for (let i = 1; i < sortedRanks.length; i++) {
    const [prevRank, prevNodes] = sortedRanks[i - 1]
    const [r] = sortedRanks[i]
    const prevMaxWidth = Math.max(...prevNodes.map((n) => dimsByNodeId.get(n.id).width))
    rankX.set(r, rankX.get(prevRank) + prevMaxWidth + RANK_GAP)
  }
  return { sortedRanks, dimsByNodeId, rankX }
}

// minX/minY track the tightest bounding box actually needed, the same way maxX/maxY always have --
// not just assumed to be 0. That assumption held while only the fallback layout existed (rank0
// always starts at PADDING, never negative), but an admin's own saved position can put a node
// anywhere, including left of or above where the fallback ever would -- and Start's own "start"
// label (see START_LABEL_OVERHANG) extends further left still, well past Start's own rect, exactly
// the same way End's "end" label extends right past its own rect (see maxX below). Without
// tracking this properly, the View's own fit-zoom -- which centers the viewport over exactly this
// box -- has no way to know content starts before 0, and clips whatever does.
function layoutCarrierNodes(sortedRanks, dimsByNodeId, rankX, positions, pipeline) {
  const rects = new Map()
  let minX = Infinity
  let minY = Infinity
  let maxX = 0
  let maxY = 0
  for (const [r, rankNodes] of sortedRanks) {
    let fallbackY = PADDING
    for (const node of rankNodes) {
      const { width, height } = dimsByNodeId.get(node.id)
      const saved = positions.get(node.id)
      const x = saved ? saved.x : rankX.get(r)
      const y = saved ? saved.y : fallbackY
      rects.set(node.id, { x, y, width, height })
      fallbackY = y + height + SIBLING_GAP
      minX = Math.min(minX, x)
      minY = Math.min(minY, y)
      maxX = Math.max(maxX, x + width)
      maxY = Math.max(maxY, y + height)
    }
  }
  // Start/End's own "start"/"end" labels overhang past their own rects -- on the left for Start, on
  // the right for End -- the only content in this diagram ever drawn outside its own node's rect,
  // whether that rect came from the fallback layout or an admin's own saved position.
  const startRect = rects.get(pipeline.start.id)
  minX = Math.min(minX, startRect.x - START_LABEL_OVERHANG)
  const endRect = rects.get(pipeline.end.id)
  maxX = Math.max(maxX, endRect.x + endRect.width + END_LABEL_OVERHANG)
  return { rects, minX, minY, maxX, maxY }
}

function layoutSignalNodes(signalNodesList, positions, signalDescriptions, rects, bounds) {
  let { minX, minY, maxX, maxY } = bounds
  const signalX = maxX + RANK_GAP
  let signalFallbackY = PADDING
  for (const node of signalNodesList) {
    const { width, height } = signalNodeDimensions(node, signalDescriptions.get(node.id))
    const saved = positions.get(node.id)
    const x = saved ? saved.x : signalX
    const y = saved ? saved.y : signalFallbackY
    rects.set(node.id, { x, y, width, height })
    signalFallbackY = y + height + SIBLING_GAP
    minX = Math.min(minX, x)
    minY = Math.min(minY, y)
    maxX = Math.max(maxX, x + width)
    maxY = Math.max(maxY, y + height)
  }
  return { minX, minY, maxX, maxY }
}

// A pseudostate's attach point is its own center (a circle has no header/body split). An ordinary
// carrier block's is its output/input pin center: STRIP_WIDTH/2 inset from that side's edge,
// vertically centered in the body band below the header (see bodyMidY), exactly where
// CarrierBlockNode draws the pin itself. The shared input pin uses this same formula even for a
// <select> block -- there's exactly one input, centered on the whole body, that broadcasts
// internally to every row (see Select's own doc comment).
function outputPoint(rect, node) {
  const y = isPseudo(node) ? rect.y + rect.height / 2 : bodyMidY(rect)
  return isPseudo(node) ? { x: rect.x + rect.width / 2, y } : { x: rect.x + rect.width - STRIP_WIDTH / 2, y }
}
// A pseudostate's input point lands on its own left edge, not its center -- unlike its own output
// point (still dead center, since nothing ever targets Start and an edge leaving End doesn't
// exist), this one is a real edge's own target, and landing it at the circle's exact center used
// to bury the edge's own terminal arrowhead inside End's own filled dot instead of leaving it
// visible just before the shape it's entering, the same way any other carrier input pin's arrow
// already sits at its own block's own left edge rather than somewhere inside the block.
function inputPoint(rect, node) {
  const y = isPseudo(node) ? rect.y + rect.height / 2 : bodyMidY(rect)
  return isPseudo(node) ? { x: rect.x - PSEUDO_INPUT_ARROW_GAP, y } : { x: rect.x + STRIP_WIDTH / 2, y }
}

// outputPinPositions has one entry per *pin*, not per node -- an ordinary block contributes one
// (keyed by its own id), a <select> block contributes one per row (keyed by each row's own id,
// including the synthetic Otherwise row keyed by the Select's own id -- see nodeDimensions' own
// comment on label.rows), and a signal block contributes one per Condition it contains (see
// signalNodes above). inputPinPositions stays one-per-node: nothing ever routes to a select row
// from outside (it's fed internally), so a route's target is always an ordinary node id or a
// Select's own id, never a row id.
//
// gateAnchors is the pull-edge counterpart: one point per *gate owner* (an ordinary carrier
// block's own id, or -- inside a <select> -- a specific row's own id, since each row carries its
// own independent gate, including the synthetic Otherwise row standing in for the block's own
// gate) to land a reference edge on. Always on the body's own left edge, the same side (and for a
// <select> row, the same y) its carrier input already enters from -- a reference edge connects
// horizontally at both ends the same as a carrier one does (see orthogonalPath), which a
// top-of-block or top-of-row point never could.
function computePinPositions(nodes, signalNodesList, rects, blocks, signalDescriptions) {
  const pins = { outputPinPositions: new Map(), inputPinPositions: new Map(), gateAnchors: new Map() }
  for (const node of nodes) {
    placeCarrierNodePins(node, rects.get(node.id), blocks.get(node.id), pins)
  }
  for (const node of signalNodesList) {
    placeSignalNodePins(node, rects.get(node.id), signalDescriptions.get(node.id), pins.outputPinPositions)
  }
  return pins
}

function placeCarrierNodePins(node, rect, label, { outputPinPositions, inputPinPositions, gateAnchors }) {
  if (label?.isFilter) {
    const { input, ifPort } = filterPortLayout(rect)
    inputPinPositions.set(node.id, input)
    gateAnchors.set(node.id, ifPort)
    outputPinPositions.set(node.id, outputPoint(rect, node))
  } else if (label?.rows) {
    inputPinPositions.set(node.id, inputPoint(rect, node))
    const { rows } = selectRowLayout(rect, label.rows, label.propertyLabel)
    for (const row of rows) {
      outputPinPositions.set(row.id, { x: rect.x + rect.width - STRIP_WIDTH / 2, y: row.midY })
      gateAnchors.set(row.id, { x: rect.x + STRIP_WIDTH, y: row.midY })
    }
  } else {
    inputPinPositions.set(node.id, inputPoint(rect, node))
    outputPinPositions.set(node.id, outputPoint(rect, node))
    if (!isPseudo(node)) gateAnchors.set(node.id, { x: rect.x + STRIP_WIDTH, y: bodyMidY(rect) - PIN_DIAMETER })
  }
}

function placeSignalNodePins(node, rect, description, outputPinPositions) {
  const pinX = rect.x + rect.width - STRIP_WIDTH / 2
  if (node.pinIds.length > 1) {
    for (const row of signalRowLayout(rect, node.pinIds, description).rows) {
      outputPinPositions.set(row.id, { x: pinX, y: row.midY })
    }
  } else {
    outputPinPositions.set(node.pinIds[0], { x: pinX, y: signalConditionLayout(rect, description).pinY })
  }
}

function computeRouteEdges(pipeline, outputPinPositions, inputPinPositions, edgeBends) {
  const edges = []
  for (const route of pipeline.routes) {
    const from = outputPinPositions.get(route.outputPinId)
    if (!from) continue
    for (const targetId of route.inputPinIds) {
      const to = inputPinPositions.get(targetId)
      if (!to) continue
      const key = `${route.outputPinId}->${targetId}`
      edges.push({ key, outputPinId: route.outputPinId, targetId, ...orthogonalPath(from, to, edgeBends.get(key)) })
    }
  }
  return edges
}

// Signal (pull) edges: dashed, drawn straight from everywhere a Condition's own pin is read, not
// from a Route -- a gate is consulted, never fed (see rankByLongestPath's own comment). Walks the
// raw carrierBlocks rather than `blocks`/labels, since gate data is already right there and this
// needs no marker-name resolution at all. A pin belonging to an inlined ConditionGroup (see
// inlinedConditionGroupOwner) has no entry in outputPinPositions at all -- it's shown inline inside
// its owning <select> instead -- so it's silently skipped here rather than drawing a dashed line to
// nowhere.
function computeSignalEdges(pipeline, outputPinPositions, gateAnchors, edgeBends) {
  const signalEdges = []
  function addGateEdges(gate, ownerId) {
    if (!gate) return
    const to = gateAnchors.get(ownerId)
    if (!to) return
    for (const pinId of gate.conditionOutputPinIds ?? []) {
      const from = outputPinPositions.get(pinId)
      if (!from) continue
      const key = `signal:${pinId}=>${ownerId}`
      signalEdges.push({ key, pinId, ownerId, ...orthogonalPath(from, to, edgeBends.get(key)) })
    }
  }
  for (const block of pipeline.carrierBlocks) {
    if (block.type === 'SELECT') {
      addGateEdges(block.gate, block.id)
      for (const add of block.adds) addGateEdges(add.gate, add.id)
    } else if (block.type === 'FILTER') {
      addGateEdges(block.signal, block.id)
    } else {
      addGateEdges(block.gate, block.id)
    }
  }
  return signalEdges
}

// Body-relative geometry the View needs to place the title and each stacked chip inside a carrier
// block's rect -- kept here so the pixel math backing "does this fit" (nodeDimensions, above) and
// "where exactly do I draw it" (the View) can't drift apart. markerNames: this block's own marker
// names (plain strings, already resolved from ids). editable: see headerIcons -- governs only
// whether the icons are drawn; the *title* centers on the body's own full width regardless (see
// titleCandidateWidth -- the block is always wide enough that this can never overlap the icons,
// rather than the title narrowing its own centering to dodge them), same as the chips below it,
// which sit below the header band the icons live in and so never had to dodge them either.
export function chipLayout(rect, markerNames, editable) {
  const bodyX = rect.x + STRIP_WIDTH
  const bodyWidthPx = rect.width - 2 * STRIP_WIDTH
  // A chip is inset from the body's own strip dividers by BODY_SIDE_MARGIN on each side -- the
  // space nodeDimensions reserved for this, actually applied here rather than left as unused slack.
  const chipX = bodyX + BODY_SIDE_MARGIN
  const chipWidth = bodyWidthPx - 2 * BODY_SIDE_MARGIN
  const headerBottom = rect.y + TITLE_AREA_HEIGHT
  const titleY = rect.y + TITLE_AREA_HEIGHT / 2 + 4
  const chipHeight = CHIP_LINE_HEIGHT + CHIP_PADDING_Y
  const chipsHeight = markerNames.length === 0 ? 0 : markerNames.length * chipHeight + CHIP_GAP * (markerNames.length - 1)
  // nodeDimensions reserves CONTENT_TOP_GAP + BOTTOM_PADDING of vertical space around the chip stack
  // in total (that's exactly what's left over between the body band's own height and chipsHeight) --
  // centered here rather than split as a fixed 6px-above/10px-below, which read as an inconsistent
  // margin once there was only a single chip to notice it against.
  const bodyHeight = rect.y + rect.height - headerBottom
  let y = headerBottom + (bodyHeight - chipsHeight) / 2
  const chips = markerNames.map((name) => {
    const chip = { x: chipX, y, width: chipWidth, height: chipHeight, name }
    y += chipHeight + CHIP_GAP
    return chip
  })
  return { centerX: bodyX + bodyWidthPx / 2, titleY, headerBottom, chips, icons: headerIcons(rect, editable) }
}

// <filter>'s own port geometry: two stacked left-side pins (carrier "input", then "if") instead of
// the single shared strip every other carrier block gets -- see filterNodeDimensions. The "if" port
// doubles as the gate anchor a dashed signal-reference edge lands on (see computeLayout), landing
// precisely on the labeled port it actually belongs to instead of the block's top-center the way an
// ordinary gated block's does.
export function filterPortLayout(rect, editable) {
  const headerBottom = rect.y + TITLE_AREA_HEIGHT
  const titleY = rect.y + TITLE_AREA_HEIGHT / 2 + 4
  const rightStripX = rect.x + rect.width - STRIP_WIDTH
  // Full-width centering -- see chipLayout's own comment (the same reasoning applies here: a
  // <filter>'s own width always already has enough room for the title to center without reaching
  // the icons, via titleCandidateWidth in filterNodeDimensions).
  const centerX = (rect.x + STRIP_WIDTH + rightStripX) / 2
  const inputTop = headerBottom
  const ifTop = headerBottom + ROW_MIN_HEIGHT
  return {
    centerX,
    titleY,
    headerBottom,
    icons: headerIcons(rect, editable),
    input: {
      x: rect.x + STRIP_WIDTH / 2,
      y: inputTop + ROW_MIN_HEIGHT / 2,
      labelX: rect.x + STRIP_WIDTH,
      labelY: inputTop + ROW_MIN_HEIGHT / 2 + 4,
    },
    ifPort: {
      x: rect.x + STRIP_WIDTH / 2,
      y: ifTop + ROW_MIN_HEIGHT / 2,
      labelX: rect.x + STRIP_WIDTH,
      labelY: ifTop + ROW_MIN_HEIGHT / 2 + 4,
      top: ifTop,
    },
  }
}

// chipLayout's counterpart for a <select> block: rows.map, not markerNames.map, since each row needs
// its own top/height/midY (for its own divider and its own output pin) in addition to its own
// chips -- see nodeDimensions' own comment on label.rows and rowHeight above, which this mirrors
// exactly so a row's reserved space and its actual drawn position never drift apart. propertyLabel
// (optional): the "property: X" header for a ConditionGroup this <select> wholly owns (see
// inlinedConditionGroupFor) -- pushes every row down to make room, same as a signal node's own
// property-label header (see signalRowLayout).
// editable: see headerIcons -- governs only whether the icons are drawn. centerX is full-width
// throughout, including the title (see chipLayout's own comment on why that can never overlap the
// icons, via titleCandidateWidth in selectBodyWidth).
export function selectRowLayout(rect, rows, propertyLabel, editable) {
  const bodyX = rect.x + STRIP_WIDTH
  const bodyWidthPx = rect.width - 2 * STRIP_WIDTH
  const chipX = bodyX + BODY_SIDE_MARGIN
  const centerX = bodyX + bodyWidthPx / 2
  const headerBottom = rect.y + TITLE_AREA_HEIGHT
  const titleY = rect.y + TITLE_AREA_HEIGHT / 2 + 4
  const chipHeight = CHIP_LINE_HEIGHT + CHIP_PADDING_Y
  let propertyChip = null
  if (propertyLabel) {
    const text = `property: ${propertyLabel}`
    const width = textChipWidth(text)
    propertyChip = { kind: 'subject', text, x: centerX - width / 2, y: headerBottom + CONTENT_TOP_GAP, width, height: TEXT_CHIP_HEIGHT }
  }
  // One shared x for every row's own marker chip(s) -- the predicate column's own width (see its own
  // comment) plus a gap, the same offset for every row regardless of how long *that* row's own
  // condition chip happens to be, so every marker lines up in its own column the way a table's own
  // cells would, rather than trailing whatever text sits to its left.
  const predicateColWidth = predicateColumnWidth(rows)
  const markerColumnX = predicateColWidth > 0 ? chipX + predicateColWidth + CRITERIA_RIGHT_MARGIN : chipX
  let top = headerBottom + propertyHeaderHeight(propertyLabel)
  const laidOutRows = rows.map((row) => {
    const height = rowHeight(row)
    const conditionChip = row.conditionLabel ? textChip('predicate', row.conditionLabel, chipX, top + ROW_PADDING_Y) : null
    let chipY = top + ROW_PADDING_Y
    const chips = row.otherwise
      ? []
      : row.markerNames.map((name) => {
          const chip = { x: markerColumnX, y: chipY, width: estimateLabelWidth(name) + 2 * CHIP_PADDING_X, height: chipHeight, name }
          chipY += chipHeight + CHIP_GAP
          return chip
        })
    const laidOut = {
      id: row.id,
      otherwise: row.otherwise,
      top,
      height,
      midY: top + height / 2,
      labelY: top + height / 2 + 4,
      conditionChip,
      chips,
    }
    top += height
    return laidOut
  })
  return { centerX, titleY, headerBottom, propertyChip, rows: laidOutRows, icons: headerIcons(rect, editable) }
}
