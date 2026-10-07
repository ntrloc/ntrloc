# ntrloc Marker Decision Pipeline — Design Notes

Status: **exploratory, nothing built yet.** Captures a long design conversation so it survives a
disconnect. Supersedes the two-separate-mechanisms approach described in
`ntrloc-marker-admin-ui-design-notes.md` (item-type-level "Marker Assignment Rules" + per-state
"Entry Markers," built and admin-UI'd separately) — this doc is what replaces that split.

## Why this exists

The legacy UI split marker automation into two disconnected mechanisms: `RuleAppliedMarker`
(item-type-scoped DMN rules, own-properties-only, triggered on any property/state change) and
`StateAppliedMarker` (per-state entry decisions, point-in-time at state entry, edited inside the
state machine editor instead of the item type's own Access Control area). Investigating both showed
they already share the same underlying mechanism (`MarkerDecisionSupport`, same DMN output
convention, same provenance-scoped add/remove reconciliation) — the only real difference is *when*
each evaluates, not what kind of thing it fundamentally is. That, plus a concrete real-world case
(Scholastic's embargoed-product/cover access rules) that needed facts from a *linked* item, not just
the item's own properties/state, is what drove the redesign below.

## Core premise

Markers and grants are fully decoupled, and stay that way. This entire design is about reaching the
decision of **which markers apply to an item (or link)** — never about what access holding a marker
confers. Grants remain a separate mechanism/UI (the Access screen), untouched by anything here. The
whole mechanism is referred to as the **marker decision pipeline**. Concretely: the carrier *is* the
decision — see **Marker actions: carriers and virtual blocks** below.

## Key architectural decisions

1. **Event-driven, not continuous — but idempotent, full re-derivation, not imperative set.** Nothing
   in this domain changes "continuously" (no frame clock); every change is a discrete mutation
   (property set, state transition, link add/remove). But when a watched fact changes, a pipeline
   fully re-derives its output from current facts — it never applies an incremental delta. This makes
   the mechanism self-correcting: redundant or out-of-order watch matches converge to the same correct
   answer, rather than drifting the way an imperative "apply this delta" model could.
2. **Full re-run, not partial.** The system decides *when* the entire pipeline needs to run (any
   mutation to a watched fact re-runs it), and it always runs *fully* from the top — no targeted
   "which part of the graph is affected" recomputation. Simpler, and categorically avoids
   partial-recompute correctness bugs.
3. **No assumed processing order.** A single `EntityManager.mutate()` call is one transaction that
   can produce multiple ledger entries touching multiple items together (e.g. create a Cover, link
   it to a Product, and change that Product's `embargoed` property, all at once). The pipeline must
   evaluate against the fully-resolved end-state of the *entire* transaction, not evaluate entries
   one at a time in whatever order they happen to be processed — conceptually a **mutation
   pre-processor** sitting in the same seam `LedgerRegisterCoordinatorImpl.prepare()` already
   occupies today, generalized to resolve facts across every entry in one transaction, not just
   within one entry.
4. **No denormalization.** A step can only assign or remove *markers* — never write a derived
   property onto an item or a link. The underlying fact always stays exactly where it's actually
   true (e.g. a Cover is never given its own `embargoed` property mirroring its Product's; a
   decision just reads Product's `embargoed` live, through the link, every time it evaluates).
5. **Explicit, admin-declared watches — never derived or inferred.** A pipeline only re-runs on facts
   an admin has deliberately declared as watches; nothing is inferred automatically from what the
   decision logic happens to reference, even though the two inevitably name the same facts. This is a
   deliberate governance choice, not a DRY tradeoff: automatically deriving watches would let the set
   of things causing re-evaluation grow invisibly as an admin builds out decision logic, with no
   moment where they're forced to consciously own the growing scope — and for an access-control system
   specifically, "the system inferred this automatically" is never an acceptable answer to "why did
   this happen." See **Watches** below for the full mechanism, including how this stays safe against
   drift without falling back to derivation.

## Visual / authoring language

Landed on a branching graph (Blueprint-inspired: pins as explicit connection points, not a flat DMN
table and not literal BPMN, which mixes data-fetch plumbing with actual policy). Plumbing (link
traversal, fact-fetching) is always invisible to the admin; only decision points are ever drawn.
Node kinds identified so far:

- **`<decision>`** — branches on one fact's value (own property/state, or a fact reached via a link —
  see **Watches** below for how that fact has to already be declared). Binary (yes/no) or multi-way
  (switch on a value). Every outbound arrow starts at that branch's own output pin, not at the node's
  border.
- **`<collect>`** (settled — replaces the earlier `<select>`, echoing DMN's COLLECT hit policy) —
  reduces a to-many link into a single value via an explicit quantifier: any / all / count (the
  complete set for now; more may turn out to be needed later). Resolves "which one do I mean" when a
  link can point at several items, rather than leaving it ambiguous.
- **`<assign>`** — adds a marker to the currently-collected set.
- **`<remove>`** — takes a marker away from the currently-collected set.
- **`<overwrite>`** ("set") — replaces the *entire* currently-collected set (regardless of which
  earlier step, including the root action, contributed to it) with whatever follows. **Only valid
  outside an open fork/join region** — inside one, "currently collected" is ambiguous relative to
  sibling branches that haven't rejoined yet, which would make its effect order-dependent.
- ~~**`<fork>` / `<join>`**~~ — retired, see **Fork/join doesn't survive this** under **Marker actions:
  carriers and virtual blocks** below. Fan-out and implicit carrier merge already covered both jobs with
  no dedicated node needed.
- ~~**`[ RESULT ]`**~~ — superseded by the single-end model in **Marker actions** below: exactly one end
  per pipeline, the union of every carrier that ever arrives there.
- **`<<group>>`** — purely presentational and collapsible, for legibility at depth. Carries no
  processing semantics; never load-bearing for evaluation.

**Link traversal rule:** reading a fact through a link always needs an explicit exists / does not
exist branch with its own fallback — *unless* the schema's own cardinality already guarantees the
link exists, in which case an existence-check branch is invalid (dead code; the schema already
forecloses that path). This generalizes to properties too: an existence/presence-check branch is
only ever valid for a fact the schema allows to be absent.

~~**Exhaustiveness rule (asymmetric by design)**~~ — superseded, see **Virtual blocks** under
**Marker actions: carriers and virtual blocks** below. The carrier-continues-unconditionally principle
made this a structural non-issue rather than a semantic one; what's left of the controlled/uncontrolled
distinction is a narrow authoring convenience (`(Otherwise)`), not a correctness rule.

## Signals: reading facts

Supersedes the `<decision>`/`<select>`/`<match>`/`<switch>`/`<filter>`/`<aggregate signal>` line of
exploration referenced in **Visual / authoring language** above, for the specific job of *reading a
fact*. Arrived at by stress-testing against worked scenarios covering single/multi-valued properties,
state machines, link existence, link properties, multi-hop traversal, aggregate-of-values, and a
self-referential "coincidence" case (see **Facts beyond item properties** below, now resolved). A
**signal** is the pipeline's only mechanism for turning a fact, or combination of facts, into a single
boolean a decision step can act on. Markers themselves (`<add>`/`<set>`/`<remove>`, fork/join, the
terminal) are a separate concern, still using the node vocabulary below — signals only ever produce a
pin to gate those nodes, never a marker directly.

**Implicit context.** Every signal reads relative to a current context, which defaults to the item the
pipeline belongs to ("self") until something establishes a different one. A context changes when a
signal steps to a linked item, steps to a link itself, or iterates a to-many relationship with
`each(...)` — each of those establishes a new current context for everything nested inside it. Nesting
is what guarantees correlation: two conditions nested inside the same established context are
automatically evaluated against the same instance, with no separate mechanism needed to keep them in
sync. `self` is the one context name that's always available without being established explicitly,
since the root is potentially reachable from any depth of nesting and nesting alone can never reach it
on its own.

**Four node kinds:**

- **`<signal>`** — the base case. Exposes exactly one output pin. Holds either a single predicate, or
  several predicates explicitly combined with a nested, pin-less `any`/`all` (itself with no pin — only
  the enclosing `<signal>` has one). A subject may be declared once (`property: X`, `link: X`, …) when
  it would otherwise be repeated across predicates; a single predicate can just name its subject inline
  and skip the separate declaration.
- **`<predicate signal>`** — groups several predicates that share a declared (or implicit) subject but
  are independently consequential — each predicate keeps its *own* pin, wired separately. This is the
  shape for "one property, several distinct outcomes" (a single-valued property picking between several
  different markers, or several independent conditions on a multi-valued property). Predicates, whether
  inside a `<signal>` or a `<predicate signal>`, never have pins of their own except in this one case.
- **`<compound signal>`** — a fixed-arity `any`/`all` across several *distinct*, named sub-signals, each
  evaluated exactly once (never repeated). Combines different kinds of facts about one instance — e.g. a
  flat predicate on a link's own property, combined with a nested sub-signal about the item on the other
  end of that same link.
- **`<counted signal>`** — wraps exactly *one* sub-signal, evaluated repeatedly (once per instance of a
  to-many relationship via `each(...)`), and compares how many of those evaluations fired against how
  many ran, via an ordinary predicate (`signal count > 0`, `all signals on`, `signal count > 5`, etc.).
  This is how `any`/`all`/arbitrary-threshold quantification over a to-many link is expressed — not as
  three separate combinator types, just different thresholds on the same count. The wrapped sub-signal
  can itself be a `<compound signal>` or another `<counted signal>`, nested arbitrarily deep.

**Composability is what makes correlation work, not either node type alone.** A `<counted signal>`
nested *inside* another `<counted signal>`'s per-instance scope stays correlated to the specific
instance the outer one is currently evaluating; two independently-scoped top-level `<counted signal>`s
combined by an outer `<compound signal>` are **not** correlated — each already collapsed to a boolean
before the outer combinator ever sees them, so "both true" doesn't mean "true of the same instance."
Concretely: a photographer's photo-link `createDate` and that *same* photo's portfolio-link `dateAdded`
has to be expressed as a `<counted signal>` (`link: photos`) whose per-photo scope holds a
`<compound signal>` combining the flat `createDate` predicate with a *nested* `<counted signal>`
(`link: item.portfolios`) — stepping through `item` to stay tied to the current photo, not flattening to
`photos.portfolios` from the top. Two independent `<counted signal>`s side by side will silently produce
false positives (two different instances, each satisfying one half) rather than an error, so getting the
nesting right matters.

**Link properties vs. linked-item properties** are disambiguated by context, not by a separate node
type: a predicate declared directly against a link's own subject (`link: photos`) reads the link's own
properties; stepping to `item` first reads the properties of the item the link points to. The same
`<signal>`/`<predicate signal>`/`<compound signal>`/`<counted signal>` vocabulary applies either way.

**Aggregate-of-values** (`sum`, presumably `avg`/`min`/`max`) is a distinct operation from counting
matching instances — it has no `any`/`all` ambiguity to resolve (there's no "any" or "all" version of a
sum), so it's expressed as an aggregate function directly inside a predicate against a context
established with or without `each(...)`: binding with `each` keeps per-group structure ("every catalog
individually holds $10,000 of its own products"), omitting it flattens to one combined figure ("all
catalogs' products combined exceed $10,000") — same underlying mechanism as everywhere else, just
without a quantifier predicate layered on top, since `sum` doesn't need one.

**`self` as a comparand**, not just a literal, closes the Featured Portfolio / "coincidence" scenario in
**Facts beyond item properties** below.

**Named contexts were considered and found unnecessary.** Every case that seemed to need one (notably
disambiguating link vs. item properties) turned out resolvable by nesting position alone; `self` remains
the only context ever referenced by a fixed name rather than by position.

**Status:** this is settled for *reading a fact*. See **Marker actions: carriers and virtual blocks**
below for how a signal's output actually drives marker mutation.

### Object shape

Formalizes the above into concrete structures. Four context kinds (item, link, property, state
machine), fully-typed predicates split only where an operator set carries real type-safety risk, four
signal-block kinds with a strict arity table, and pins as plain outgoing-reference lists.

**Context** — recursive, each step wrapping its own continuation; state machine is structurally
terminal (no `next` field), rather than enforced by a validation rule:

```
Context =
    Self
  | StepToLink          { UUID linkPerspectiveId, bool each, Context next }
  | StepToItem           { UUID linkPerspectiveId, bool each, Context next }   // link→item, or item→item skip
  | StepToProperty       { UUID propertyId, bool each, Context next }
  | StepToStateMachine   { UUID stateMachineId }   // no `next` — always terminal
```

**Predicate** — split per base type only where the operator set isn't uniformly safe (string, list
membership/presence, boolean, state machine, identity); collapsed to one fielded class wherever every
operator is safely interchangeable (number, date, and any list/link reduced to a size, count, sum, avg,
min, or max — all of which are themselves just numbers or dates):

```
Predicate = StringPredicate | NumberPredicate | DatePredicate | BooleanPredicate
          | StringListPredicate | NumberListPredicate | DateListPredicate
          | LinkPredicate | StateMachinePredicate | IdentityPredicate

NumberPredicate { ComparisonOperator operator, Number value }   // =, >, <, >=, <=, between
DatePredicate   { ComparisonOperator operator, Date value }      // =, before, after, between

StringPredicate = StringEqualsPredicate | StringNotEqualsPredicate | StringLikePredicate
                 | StringStartsWithPredicate | StringExistsPredicate | StringNotExistsPredicate
BooleanPredicate = IsTruePredicate | IsFalsePredicate
StateMachinePredicate = StateEqualsPredicate | NoStatePredicate
IdentityPredicate = EqualsSelfPredicate   // "creator == self"

StringListPredicate = StringListIncludesPredicate | StringListExcludesPredicate
                      | StringListIsEmptyPredicate | StringListIsNotEmptyPredicate
                      | StringListSizePredicate { ComparisonOperator operator, Number value }

NumberListPredicate = NumberListIsEmptyPredicate | NumberListIsNotEmptyPredicate
                      | NumberListSizePredicate { ComparisonOperator operator, Number value }
                      | NumberListSumPredicate  { ComparisonOperator operator, Number value }
                      | NumberListAvgPredicate  { ComparisonOperator operator, Number value }
                      | NumberListMinPredicate  { ComparisonOperator operator, Number value }
                      | NumberListMaxPredicate  { ComparisonOperator operator, Number value }

LinkPredicate = LinkIsPresentPredicate | LinkCountPredicate { ComparisonOperator operator, Number value }
```

`DateListPredicate` mirrors `NumberListPredicate` (size/min/max meaningful; sum/avg less so). `like`/
`startsWith` stay distinct rather than collapsing into one general pattern operator (avoids forcing an
admin to write `like "test%"` for the common prefix case), and `includes` (list membership) stays
distinct from `like` (string substring) despite both reading as "contains" in plain English — same
word, different operand types, different semantics.

**Signal blocks** — a strict arity table, no overlap between the four kinds:

| Block | Predicates | Sub-signals | Pin (top-level) | Pin (nested) |
|---|---|---|---|---|
| `Signal` | exactly 1 | 0 | yes | no |
| `PredicateSignal` | 2..n (each has own pin) | 0 | no (predicates do) | no (predicates do) |
| `CompoundSignal` | 0 | 2..n | yes | no |
| `CountedSignal` | 0 | exactly 1 | yes | no |

```
// anything reducible to exactly one boolean; PredicateSignal is excluded — it's multi-pin and can't
// stand in wherever "a signal" is expected, so it's only ever valid as a top-level block.
SingleOutputSignal = Signal | CompoundSignal | CountedSignal

// No signal block has an input pin of any kind — see "Pull, not push" below.
Signal {
  Context context
  String? subject        // DRY-only declaration; a lone predicate can just name its subject inline
  Predicate predicate
}
PredicateSignal {
  Context context
  String? subject
  Predicate[2..n] predicates
}
CompoundSignal {
  Combinator combinator          // ANY | ALL
  SingleOutputSignal[2..n] subSignals   // ordered; evaluated left to right, short-circuits
}
CountedSignal {
  Context context                 // must include an `each` step somewhere in the chain
  SingleOutputSignal subSignal
  Predicate countPredicate        // "signal count > N", "all signals on", etc. — compares against the total
}
```

**Pins** — pure identifiers, not owners of their own wiring; connectivity lives in one flat,
pipeline-level list instead of being embedded in each block, which separates block definitions
(inherently tree-shaped — `CompoundSignal`/`CountedSignal` embed their sub-signals directly) from
pipeline connectivity (inherently graph-shaped, crossing between top-level blocks). Fan-out (one pin,
many consumers) and fan-in (several sources feeding one gate) both fall out of this for free:

```
OutputPin { UUID id, PinKind kind }
InputPin  { UUID id, PinKind kind }
PinKind = CARRIER | SIGNAL   // see Marker actions below — a route's kind must match on both ends

Route { UUID outputPinId, UUID[] inputPinIds }   // one entry per output pin
```

## Marker actions: carriers and virtual blocks

How a signal's boolean output actually drives marker mutation. Two kinds of pin run through the whole
pipeline, never connecting to each other directly: a **signal pin** (boolean, from the Signals section
above — gates things) and a **carrier pin** (a marker payload, flowing toward accumulation, gated *by*
signal pins where needed). Naming lands here after an earlier false start calling the latter a
"control-flow pin" — it isn't execution sequencing, it's specifically the payload side of the pipeline,
closer to the "carrier signal vs. control signal" split floated earlier in this doc's history than to
BPMN-style sequence flow.

**The carrier *is* the decision.** Not a representation of it, not an input to computing it elsewhere —
the carrier's final state at whatever end it reaches *is* which markers apply to the item. Signals and
gates only ever shape what happens to the carrier along the way; there's no decision computed anywhere
else in the pipeline. This is the concrete, mechanistic form of this doc's own founding sentence (see
**Core premise** above) once the signal/carrier architecture actually existed to realize it, and it's
why the carrier's flow has to be unconditional: if the carrier can stop, there can be a non-decision,
which this design can't allow any more than it can allow a silently-skipped watch.

**This splits every block in the pipeline into exactly two domains that never blend.** Signal blocks
(`Signal`/`PredicateSignal`/`CompoundSignal`/`CountedSignal`) carry `Context`/`Predicate`-shaped fields
and never touch a carrier. Carrier blocks (`Add`/`Remove`/`Set`/`Filter`, start, end) carry carrier
fields and an optional incoming gate, and never read a fact directly. The only point of contact anywhere
in the model is a signal block's output pin wiring into a carrier block's gate input — never the
reverse. This is the same split an earlier pass at this design tried to draw (as "signal blocks" vs.
"marker blocks") and couldn't quite make hold, because `<select>`/`<filter>` as *real* primitives back
then were genuine hybrids straddling both. The hybrids didn't get resolved, they got eliminated —
`<select>`/`<switch>` reduced to virtual compositions, `<filter>` reclassified as pure carrier-domain —
which is what makes the split total now instead of leaky.

**Action blocks always have both carrier pins; the signal pin is optional.** A carrier input and a
carrier output are mandatory — a block missing either is invalid, which is what structurally enforces
the existing "every step leads to another step or a result, no dangling paths" completeness rule at the
block level rather than leaving it as a separate check. The signal/gate input is optional per block;
many blocks don't use it and simply always fire.

**`Add`** — the first real action block:

```
Add {
  Pin carrierIn          // mandatory
  Pin carrierOut         // mandatory
  Pin? gate              // optional signal input
  MarkerRef[1..n] markers
}
```

Minimum is `1..n`, not `2..n` like `<compound signal>`/`<predicate signal>` — adding a single marker is
already a complete, meaningful operation, not a trivial case of "combining" something.

**`Remove`** — same shape as `Add`, opposite operation (set difference instead of union):

```
Remove { Pin carrierIn, Pin carrierOut, Pin? gate, MarkerRef[1..n] markers }
```

**`Set`** — same shape again, but a meaningfully different role: it discards whatever carrier it
receives entirely and replaces it outright, rather than building on it. It's simultaneously a *sink*
for its predecessor's carrier and a fresh *source* for whatever follows, functionally a second implicit
start node sitting mid-pipeline. Its `carrierIn` is still mandatory for structural completeness (no
dangling paths) even though the value received never affects the output. An earlier version of this doc
flagged `Set`/`<overwrite>` as invalid inside an open fork/join, reasoning that "currently collected" is
ambiguous relative to sibling branches that haven't rejoined yet — that reasoning assumed a procedural
model with a notion of execution order and "not yet." This architecture doesn't have one: carriers push
unconditionally, signals pull lazily, and merge is pure order-independent union, so `Set` only ever
looks at its own single, always-well-defined incoming pin (itself just a value — the union of whatever
fed it, even if that's already the product of an earlier merge) regardless of what any sibling branch is
doing. There's no case where that's ambiguous. The restriction has been dropped entirely, and with it
goes fork/join's last remaining job (see **Fork/join** below).

```
Set { Pin carrierIn, Pin carrierOut, Pin? gate, MarkerRef[1..n] markers }
```

**`Filter`** — structurally distinct from the other three, not just a variant: no `markers` field at
all (it never touches the carrier's content), and its signal input is *mandatory* rather than optional
— a `<filter>` with no signal would just be a meaningless straight wire, so forcing one keeps it from
existing in a degenerate form, same principle as `<compound signal>`/`<predicate signal>` requiring 2+.
It always fires and always produces a carrier — never conditionally skipped — passing its carrier
through unchanged when the signal is on, or producing an empty carrier (same sink-and-become-a-source
role as `Set`, just with an empty marker list) when it's off. No transform either way, purely binary.

```
Filter { Pin carrierIn, Pin carrierOut, Pin signal }   // carrierOut = carrierIn if signal else empty
```

An empty carrier isn't a special "stop" value needing its own handling — it just merges as a no-op
wherever it ends up, since implicit carrier merge is already union and union-with-empty is a no-op. That
resolves the original doc's "how does a path terminate without adding or changing anything" question
without needing a dedicated terminal/"stop here" node: an off `Filter` already does exactly that, and
the universal "every block always fires, always produces a carrier" invariant never needs a carve-out
for the empty case.

**Start / end.** Exactly one implicit start per pipeline — a pure carrier source (no carrier input,
only output), feeding the first action block(s) with the empty marker set. Exactly one end, too —
revising an earlier version of this doc, which allowed any number of ends, each scoped to "whatever
accumulated along that particular path." That made "the decision" ambiguous the moment carrier fan-out
could reach two different ends in the same evaluation, which is exactly the harder-to-reason-about
shape the single-end revision avoids. The end is a pure carrier sink (carrier input, no output) and is
the union of every carrier that ever arrives there, full stop — consistent with "the carrier *is* the
decision" above, which only holds unambiguously if there's exactly one place the decision could be. A
diagram may still *draw* the end's icon more than once for legibility (so a far branch doesn't need a
long line crossing the whole canvas), but every drawn instance refers to the same singleton end.

**Implicit carrier merge.** Multiple carrier outputs can converge directly on one carrier input (an
end, or any other block) with no combining node in between — marker-set union is commutative and
idempotent, so there's never a transformation the merge itself needs to perform. This is what let the
old `<sum>` node disappear entirely (see **Virtual blocks** below).

**Pull, not push: no signal block has an input pin, not even a gate.** A signal isn't "triggered" by an
incoming connection — it's a pure, self-contained computation over its own `Context`/`Predicate`, which
only ever gets evaluated because something downstream needs its value: a `CompoundSignal` combining it
with others, a `CountedSignal` iterating it, a carrier block reading its gate. This corrects an earlier
version of this doc, which gave `Signal`/`PredicateSignal`/`CompoundSignal`/`CountedSignal` an optional
`gate` field, treating "signal X gates signal Y" as Y receiving X through a wire. That has it backwards
— Y receives nothing. What's actually happening is that whatever *consumes* Y also needs X, pulls X
first, and short-circuits before touching Y's internals if X is false. `CompoundSignal.subSignals` is
ordered specifically so this works with no extra field: evaluation proceeds left to right and stops as
soon as the `ANY`/`ALL` result is determined, the same way ordinary boolean short-circuiting works
anywhere else. The performance motivation from before still holds exactly as stated — full re-run on
every watched-fact change means the only lever against an expensive nested `<counted signal>`'s cost is
making sure it's skipped outright, not computed and discarded — it's just that the mechanism is ordering
within a consumer, not a pin on the thing being skipped.

This sharpens the two-domain split into push vs. pull, too: carrier blocks are genuinely pushed
(a carrier arrives through a real wire, unconditionally), while signals are genuinely pulled (computed
on demand, never pushed into). Carrier blocks' own `gate` pins (`Add`/`Remove`/`Set`'s optional `gate`,
`Filter`'s mandatory `signal`) are unaffected by this correction and stay exactly as specified — they're
the one legitimate boundary where a pull-computed signal's already-resolved result enters the push-based
carrier domain, consistent with "the only point of contact... is a signal block's output pin wiring into
a carrier block's gate input." One line summarizes the whole execution model: **carriers push into
carrier blocks; carrier blocks pull signals on demand.** Carrier blocks are the only place the two
domains touch — pushed into from upstream carrier flow, and the ones who initiate a pull into the signal
domain, at the exact moment they need a gate's value to decide what to do with the carrier that just
arrived. Signals never push anything; nothing on the signal side ever initiates contact with the carrier
side.

**A signal runs at most once per evaluation, possibly never, and its result is reused by every
consumer.** Not an optimization added on top — a direct, necessary consequence of a signal block having
exactly one output pin, pulled on first demand. Once computed, that *is* the pin's value for the rest of
this evaluation; every consumer wired to its Route (fan-out) reads the same resolved value rather than
separately triggering the work again — concretely, when one `<counted signal>` gates two different
`<filter>` blocks (as in the embargo example), it still only ever runs once. If nothing downstream ever
demands a given signal's value — a short-circuit upstream, an unreachable branch — it simply never runs
at all. Scope is strictly one evaluation: this has nothing to do with, and doesn't soften, "full re-run,
not partial" (**Key architectural decisions** above) — the next trigger re-evaluates everything from
scratch, pulled fresh. This is about not repeating work *within* one pass, never about caching across
passes.

### Virtual blocks

A **virtual block** has no runtime or execution identity — the pipeline engine only ever evaluates real
primitives (`Signal`/`PredicateSignal`/`CompoundSignal`/`CountedSignal`, `Add`/…). A virtual block is a
named, recognized shorthand that expands to a fixed arrangement of real primitives the moment it's
placed, sparing the admin from hand-wiring something extremely common. It may need to persist at the
authoring/UI layer (so the tool can recognize an expanded cluster and re-collapse it for editing), but
that's a tooling convenience, not a second implementation living alongside the real one. Two identified
so far, both arising from re-deriving the original `<select>`/`<switch>`/`<filter>`/`<sum>` embargo
example entirely in terms of signals and `Add`:

- **`<select>`** = a `PredicateSignal` wired to *N* single-marker `Add` blocks, one per branch, each
  carrying a different literal marker. This was `<select>`'s entire job as an independent primitive —
  "read a property, emit a different marker depending on which value matched" — now just a recognized
  composite of two things that already exist, not a third thing. Two carrier-output modes: **shared**
  (every branch's `Add` converges on one output for the whole block) or **per-branch** (each branch gets
  its own independent output, needed when different branches require genuinely different downstream
  treatment). This also retired the old asymmetric exhaustiveness rule (see **Visual / authoring
  language** above): the carrier-continues-unconditionally principle makes "did we handle every value"
  a structural non-issue rather than a semantic one. In shared mode it's automatic — every branch
  converges on the one output regardless, matched or not. In per-branch mode, an optional `(Otherwise)`
  branch exists purely as an authoring convenience (never a structural requirement — an admin can always
  enumerate every remaining value explicitly instead) to avoid forcing full enumeration; `(Otherwise)`
  is meaningless/forbidden in shared mode, since there's nothing left for it to catch. The
  controlled-vs-uncontrolled distinction survives only as a matter of how *useful* that convenience is
  (close to indispensable for an uncontrolled property, merely tedious to skip for a controlled list),
  not as a correctness rule.
- **`<switch>`** = even more minimal — just a bare `PredicateSignal` on a boolean-typed property with
  exactly two predicates (`IsTruePredicate`, `IsFalsePredicate`). No second primitive gets combined in;
  `<switch>` *is* a `PredicateSignal`, and its "virtual block" status is purely a naming/legibility
  convention for a common shape, not a template over two different things.

Redrawing the original embargo example with both virtual blocks in place, with each branch's predicate
pin pulled directly as the `Add` it gates needs it, and implicit carrier merge, needs no `<filter>` at
all for this simple case (the branch pins already gate their `Add`s directly) and no `<sum>` entirely
(covered by implicit merge) —
collapsing four separate real node kinds down to two virtual blocks built from nothing but `Signal`
primitives and `Add`. The result is maximally expressive where it needs to be and no more complex than
the problem actually requires anywhere else.

**Convenience, not handcuffs.** Every virtual block and authoring convenience here (`<select>`,
`<switch>`, `(Otherwise)`) is strictly additive — it never narrows what's expressible, only saves effort
on the common case. If a situation doesn't fit the shortcut's assumptions, the admin drops to the fully
decomposed, explicit primitive form (a `PredicateSignal` wired to individual `Add`/`Filter`/`Set` blocks
by hand) and loses nothing. This is the same "expressivity first, concision only within that constraint"
principle that's governed this entire design, now holding up at the authoring-convenience layer too.

**Fork/join doesn't survive this — retired.** Its two original jobs — branch into independent parallel
paths, reconverge them later — are both already free consequences of things established above: branching
is just ordinary carrier-pin fan-out (one output, multiple route targets), reconverging is just implicit
carrier merge, now backstopped further by single-end meaning everything reconverges there regardless.
Neither ever needed a dedicated node to mark or enable it. The one job that seemed like it might still
need something — disambiguating `Set`'s "currently collected" relative to sibling branches that haven't
rejoined — turned out not to be a real constraint at all once stress-tested (see `Set`'s own entry
above): that reasoning assumed a procedural model with a notion of execution order this architecture
never had. `Set` only ever looks at its own single, always-well-defined incoming pin, regardless of
sibling branches, with no validation rule required. Nothing fork/join did was ever a capability; it was
a boundary drawn around capabilities (fan-out, merge) that already existed implicitly, plus a
restriction that turned out not to be necessary. Full retirement, not a pending question.

**Status:** `Add`/`Remove`/`Set`/`Filter`, start/end (single, not multiple), virtual blocks, implicit
merge, and the push/pull execution model are all settled. Fork/join is retired. Nothing from the
original node list remains open — this whole area of the design is closed.

## Watches

Explicit by design (decision 5 above) — never derived from the decision graph, even though the two
inevitably reference the same facts. ("Watch," not "trigger" — these declare what the pipeline is
watching for changes, not an event-handler-style callback.)

**Shape** — watches nest the same way links do, rather than each one carrying a flat array of
link-hops:

```
PropertyWatch { UUID propertyId }                        // one of the item type's own properties
StateWatch    { UUID stateMachineId }                     // one of the item type's own state machines
LinkWatch     { UUID linkPerspectiveId, PipelineWatch[] linkItemWatches }
```

A link is itself a watch kind, not a structurally separate "participant" wrapper concept (an earlier
`PipelineParticipant`/`ParticipantLink` draft is superseded by this). `LinkWatch` alone means "re-run
when this link is added or removed"; nesting a `PropertyWatch`/`StateWatch` inside it means "...or
when this fact changes on whatever's on the other side"; nesting a further `LinkWatch` reaches a
second hop, recursively, with no separate type needed for "item type + its own facts + its own
links."

`StateWatch` names a **state machine**, not a specific state — it fires on *any* transition within
that machine, the same way `PropertyWatch` fires on *any* change to a property's value rather than
being scoped to one specific target value. The actual value comparison (e.g. "is the current state
now `APPROVED`") belongs entirely to the decision node that reads it, never to the watch declaration
— a watch that were scoped to one specific state would be smuggling a piece of the decision's own
logic into the "when," which is exactly the conflation watches are designed to avoid everywhere else.

**Bidirectional, load-bearing validation** — this is what makes "explicit, not derived" safe rather
than just stricter:
- A decision node cannot reference a fact that hasn't already been declared as a watch — the
  authoring tool simply doesn't allow building a decision against an undeclared fact.
- The inverse also holds: a declared watch that nothing in the pipeline actually reads is itself
  invalid, not merely unused — otherwise an admin could defensively over-declare watches "just in
  case," which recreates the same opacity problem from the other direction (a watch list that looks
  like it explains the pipeline's behavior but doesn't correspond to anything it does, and wastes a
  full re-run on a change nothing downstream cares about).

Together these make declared watches and referenced facts an *exact*, enforced correspondence — not
an approximation the system merely tries to keep roughly in sync. This is also another concrete
save-time validation rule, alongside completeness and the existence-check rules below.

**Where the DRY tradeoff actually is, precisely.** The same fact-identifier (a property/state ID, or
a link perspective with a nested identifier) is named in two places — once as a declared watch, once
in the decision node that reads it. That's a real, narrow repetition, but not the kind DRY actually
guards against: DRY's concern is duplicated *logic or behavior* silently drifting out of sync, and
the bidirectional validation above makes that specific risk zero — a watch and a reference can never
disagree, because disagreement is unsaveable. What's left is purely a small amount of *authoring
friction* — declaring a fact is a separate, deliberate step from referencing it — and that friction
is the intended feature, not a cost paid reluctantly: it's the moment that forces an admin to
consciously own a new dependency, rather than it accruing silently as a side effect of building
decision logic. The "repetition" here is an admin stating their own intent twice, in two different
senses (*when* vs. *what*) — not two copies of the same fact that could drift apart, which is what
DRY actually guards against in code.

## Facts beyond item properties

Three more fact categories beyond an item's own properties/state and a linked item's properties/state
reached via `LinkWatch`, surfaced while stress-testing the Watch model against a photographer/agency
example:

- **Link existence, standalone.** Already representable today: a bare `LinkWatch` with no nested
  `linkItemWatches` already means "re-run when this link is added or removed," independent of
  anything about the linked item itself. No shape change needed — worth calling out explicitly so it
  isn't mistaken for an unsupported case.
- **Link properties.** Links themselves carry properties (schema already supports this), but
  `LinkWatch`'s current shape only nests further watches scoped to the *item* on the other side of the
  link — there's no way yet to watch a property that lives on the link itself. `LinkWatch` likely needs
  its own optional set of `PropertyWatch`es for the link's own properties, alongside `linkItemWatches`.
- **Coincidence (resolved).** A fact that's only meaningful because two independently-reached entities
  turn out to be the *same* entity, not just independently true. Concrete motivating case: a "Featured
  portfolio" marker — a photographer has a photo in an APPROVED portfolio that contains *no photos from
  any other photographer*. Resolved via the **Signals** section above: a reverse sub-traversal is just
  more `each(...)` nesting (link perspectives are navigable both directions), and `self` is now a valid
  predicate comparand, not just a literal — so "every photo in this candidate portfolio resolves back to
  the photographer this pipeline started from" is `each(photos.portfolios)` → nested `each(photos)` →
  `creator == self`, with `all signals on` enforcing the universal quantifier. No new mechanism needed
  beyond adding `self` as a comparand.

## Completeness & validation

- "Every step leads to a result" is enforced **at save time**, as the primary gate — the UI should
  refuse to save a pipeline it can already tell is incomplete, not just warn.
- **Runtime enforcement remains a necessary backstop, not a redundant weaker copy.** Completeness is
  checked against an enumerable fact's *current* value set, which can change after save (a new
  controlled-list value added later, or a schema cardinality relaxed later — see below). That's
  schema/data drifting out from under an already-valid pipeline, which save-time validation cannot
  catch by definition, since it hasn't happened yet at save time. If an unhandled case is ever
  actually reached by real data at runtime, the mutation that would depend on it is refused outright
  — an uncovered case can never silently leak through with an incomplete marker set.
- **Dry-run simulation**: admins should be able to plug in a hypothetical fact combination and see
  the resulting markers before saving. This is the same evaluation engine as the completeness
  checker, just invoked once against a specific combination instead of exhaustively against every
  combination — building the checker effectively yields the simulator for free.

## Schema ↔ pipeline consistency

Schema (item types, properties, links, controlled lists) and marker decision pipelines are stored
and edited as separate things, which means a schema edit can silently invalidate a previously-valid
pipeline without the pipeline itself ever being touched:

- **Tightening** a link/property from optional to required orphans any existence-check branch for it
  (now dead code) — safe, pipeline stays structurally complete, lint-level warning only.
- **Loosening** required to optional, or **adding a value to a controlled list** a pipeline switches
  on, can introduce a genuine gap — unsafe, should block the schema edit or force immediate
  pipeline remediation, since otherwise a real item could be created that the pipeline has no
  defined behavior for.

Because watch declarations already give a reverse index ("which pipelines depend on this fact"),
this is checkable at the moment of the schema edit, not just discovered later at runtime:
look up every dependent pipeline, re-validate against the *proposed* combined end-state, and only
allow the edit through if the result stays complete.

This requires schema changes and their coupled pipeline fix-ups to be **submitted, validated, and
committed as one atomic bundle** when they're genuinely coupled — not two sequential edits that could
land out of sync, and not a new durable, independently-versioned entity. Concretely: validation has
to see the proposed end-state of the *whole* bundle together (a loosened cardinality plus its new
fallback branch, evaluated as the one state they'd produce together, not each half checked against
the other's *old* state) — an extension of whatever already governs atomic, validated schema
mutation, now also covering pipeline definitions as a first-class part of "schema" in the
transactional sense. Versioning/audit trail for this bundle is a separate, later concern, not a
consequence of the atomicity requirement itself.

## Item creation flow implications

A non-admin user creating a new item needs to know which properties/links they can and must set —
and the answer can't be marker-mediated in the naive way, because markers are instance-level and
don't exist until the item does (chicken-and-egg). It also must **not** be a separate, parallel
direct-grant system for creation-time writes: that would let a user set a value at creation they
could never legitimately write afterward, and since that value can itself determine the item's
resulting markers (and thus their own access), it's a gameable governance hole, not just a confusing
UX wrinkle.

**Resolution** — evaluate the *same* marker-derived grants, computed atomically within the creation
transaction, staged so the user is never asked for information the system can't yet act on:

1. Resolve every fact the pipeline has declared as a watch first — not just schema-required links,
   *any* declared watch, since an optional-but-pipeline-relevant fact left unresolved makes the next
   step meaningless.
2. Once resolved, evaluate the pipeline for real against those facts to get the resulting markers.
3. Compare the properties/links the user *can* set (per their grants under those resulting markers)
   against the properties/links that *are* required (schema `usage = REQUIRED`).
4. If any required field isn't in the "can set" set, refuse the creation outright — early and honest,
   not a mid-form surprise after the user has filled out the whole thing.
5. Otherwise, ask for the required fields plus whichever optional ones the user is also permitted to
   set.

A user can end up structurally unable to create a given item type at all, if some required field's
grant is categorically unavailable to them under every possible marker outcome — likely correct
(you shouldn't be able to create something you could never legitimately hold a stake in), but worth
deciding on purpose. The dry-run simulator is what lets the creation form preview this live, before
submission, rather than only failing after the fact.

### Link creation requires `link:create` on both sides

Creating a link should require the existing `link:create` permission on **both** items it connects,
not just the one actively being created/edited. Without this, a user could link an item they control
to an arbitrary target they have no standing on — and since the target's facts can feed straight into
the marker pipeline, that's a way to shop for a favorable outcome (link a new Cover to whichever
Product happens not to be embargoed, with no legitimate relationship to that Product at all). This is
the same shape of gameable hole the creation-time property-write design above was built to close, just
one hop over, via a link instead of a property.

It folds into the same mechanism rather than needing anything new, with the two sides checked
differently because of timing:

- The side still being created in this same transaction (the new Cover) has the same chicken-and-egg
  problem as property writes — no markers yet. Same resolution: `link:create` for that side is
  checked against the *resulting* markers, computed atomically alongside the property-write checks in
  the same creation-transaction evaluation, not a separate check.
- The side that already exists (the Product) already has real, current markers, so `link:create`
  there is checked against grants on those markers right now — no staging needed. If forming the link
  should also cause the existing item's own pipeline to re-evaluate (some other pipeline cares about a
  fact this link affects), that's not new either — it's just the ordinary "a declared watch fired,
  re-run dependent pipelines" mechanism, firing as a normal consequence afterward.

### Related, separate mechanism: filter item search by link-create permission

Not part of the pipeline or the creation-transaction check above — a UI-side need on the **general
item search/projection system** (the same search surface used elsewhere in the app), not something
specific to item creation. Motivating example: a user starts creating a Cover, the system determines
it needs a linked Product, and the fastest way to find one is an ordinary Product search — but that
search needs a filter for "principal has `link:create` on this Product via its `cover` perspective,"
composable with whatever other search criteria (name, category, etc.) the user also supplies, not a
bespoke picker built just for this flow.

This only pre-filters the side that already exists and has real current markers to check grants
against (Product) — it narrows the search to valid candidates, but doesn't replace the atomic
`link:create` check at actual submission time (which also has to verify the *new* side, per above).
Any place in the app where a user searches for a candidate link target — not just during item
creation — is a caller of this same filter, not a one-off.

## Open / undecided

- The concrete UI/API shape of a "bundled atomic schema+pipeline change" workflow.
- The concrete backend/API shape for authoring and persisting watch declarations — the conceptual
  shape in **Watches** above still needs a real JSONB/table design pass.
- ~~How a path terminates without adding or changing anything~~ — resolved in **Marker actions**: an
  off `Filter` produces an empty carrier, which merges as a no-op; no dedicated "stop here" node needed.
- ~~Whether `<remove>` is a genuinely distinct node~~ — resolved: `Remove`/`Set` are both real,
  `Add`-shaped action blocks (see **Marker actions**), with `Set` carrying an extra fork/join
  restriction `Add`/`Remove` don't need.
- Whether execution continues to something shared *after* a fork/join's branches all finish, or every
  branch always resolves independently to its own terminal with nothing reconverging — and whether
  `<fork>`/`<join>` nesting (a fork inside a fork) needs explicit handling for the "`overwrite` invalid
  inside any open fork/join" rule, not just one level of nesting.
- **The fact-reading side of the node vocabulary is now settled** — see **Signals: reading facts**
  (includes "coincidence" facts, resolved there and in **Facts beyond item properties** above).
  **Marker actions: carriers and virtual blocks** settles `Add`/`Remove`/`Set`/`Filter`, single
  start/single end, virtual blocks (`<select>`/`<switch>`), implicit carrier merge, the push/pull
  execution model, and the carrier-is-the-decision principle. `<fork>`/`<join>` is fully retired
  (fan-out, implicit merge, and single-end already cover everything it did). Nothing from the original
  node list in **Visual / authoring language** remains open.

## Parked, tracked separately

- `docs/ntrloc-provenance-and-liability-requirements.md` — the forensic/audit-timeline requirement
  (data provenance, decision-logic provenance, access provenance, unified per-item timeline). Should
  *inform* this design (whatever a step does needs to leave an attributable trace) without being
  built as part of it.
