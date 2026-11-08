# ntrloc Design Notes: What Processes Are For — A Fresh Look

## Topics: Capability Inventory, Guardrails, Process/Task Fit Within ntrloc

This is a checkpoint of an in-progress design conversation, not a finished spec. Revise/refine in
place as the thinking develops further.

**Context for this reset**: the embedded Flowable work landed under deadline pressure for an
external POC, not as a deliberately-paced platform decision. Two existing docs already carry a lot
of prior thinking on this topic — `ntrloc-workflow-summary.md` (engine choice, persistence,
clustering/execution mechanics, event-triggered starts) and
`ntrloc-process-task-authorization-design-notes.md` (the authorization-role taxonomy: state-entry/
transition-triggered, DMN decisions, manually-initiated, un-anchored batch). Both remain useful
reference and neither is being discarded, but this conversation is deliberately not treating their
conclusions as binding — the point of stepping back now is to re-derive what processes are *for* and
what they should be *capable of*, and only then check the existing machinery and decisions against
that, rather than starting from "Flowable can do X, Y, Z, how do we wire it up."

---

## 1. Starting Premise

Processes/tasks are **not graph members**. Unlike items and links, they are never projected, never
searchable, never marker-bearing in their own right. They are a higher-level orchestration layer that
*acts on* items/links — a mechanism, not domain data. (This is consistent with, and sharpens, the
existing separation between `ProcessService`/`TaskAdminController` and item/link querying, and with
keeping `process_group` separate from `security_group`.)

**Open consequence, not yet decided**: if a process step mutates an item/link, that mutation still
needs real ledger provenance. What does "actor" mean for a process-driven mutation — plain
user-attributed write, or something that names the task/process instance as well as the human who
claimed it? Flagged, not resolved — deliberately set aside until the capability inventory below is
settled, since the attribution model depends on which capabilities actually exist.

---

## 2. Admin-Facing Functional Categories (draft — strawman for discussion)

**Correction from first pass**: "functional bucket" doesn't mean engine-level capability primitives
(read/mutate/human-task/DMN/script/timer/external-call — that list is kept below in Section 2a since
it's still a useful, separate axis). It means the catalog of **high-level jobs an admin reaches for a
process to fulfill** — the product-level menu of "what kind of thing can I set up," not what the
engine mechanically does underneath it.

**Seed example (given)**: run a projection and notify a group of people, under admin-definable
conditions. Candidate categories this suggests, plus the ones already named as real (not
hypothetical) driving use cases in `ntrloc-workflow-summary.md` Section 1:

1. **Conditional notification/reporting.** Run a projection/query (scheduled or event-triggered),
   evaluate admin-defined conditions, notify a group of people. The seed example. **Confirmed
   valid; confirmed trigger — both scheduled and lifecycle-triggered need to be supported**, not
   just one.
2. **Approval/gating workflow.** A mutation or transition doesn't take effect (or doesn't complete)
   until a designated person/group signs off. **Correction (this round, ground-truth checked)**:
   earlier noted as "already built" via `schema_state_transition.process_id` — actually verified
   false. That column is stored, validated, and shown in the admin UI (`StateMutationApplier`,
   `SchemaAdminViewBuilder`, `WorkflowReferenceScanner`'s delete-guard), but
   `EntityManagerImpl.executeTransition`/`commitStateEntry` never reads it or calls Flowable's
   `RuntimeService` — it's inert schema data, zero runtime effect today. No sync/async behavior
   exists because nothing runs at all.
   - **Mutation-side gating is out** — same reasoning as category 8/Section 3: a mutation is
     synchronous/inline in the ledger-commit path, so it structurally can't suspend on a user task.
   - **Transition-side gating is a genuinely open design choice, not a discovered constraint**:
     since nothing is built yet, whether a transition-bound process may suspend (host a user task,
     resolve the transition only once the process completes, possibly much later) or must complete
     inline (inheriting the same no-user-task restriction as mutations) hasn't been decided — it's
     being decided now, not inherited from existing behavior. Preserving the approval-during-
     transition use case requires deliberately choosing the asynchronous/suspending shape.
3. **Automated enrichment on lifecycle events.** No human step at all — e.g. generate thumbnails/
   transcripts when a Photo/Video is created, compute a derived ID at creation time. **Confirmed
   valid** — `ntrloc-dam` has already started down this path (not yet via a process, but will be).
4. **Downstream integration/sync.** Push data to an external system when qualifying items change
   (the "transmit product data downstream" example). **Confirmed valid.**

**Structural observation (from this round)**: 3 and 4 are both, in theory, tied to *lifecycle
events* — which surfaces a second dimension cutting across these categories: **what triggers it**,
orthogonal to **what job it does**. "Lifecycle-bound" is one value on that trigger dimension (item
created/updated/deleted, independent of any state machine); category 2's trigger value is
"transition-bound" (a specific state-machine edge firing); category 1 needs **both** "scheduled"
(a new trigger value, wall-clock-driven, not tied to any item event) and "lifecycle-bound" — so a
single functional category can have more than one valid trigger value, triggers aren't 1:1 with
categories. This trigger dimension partially overlaps the existing role taxonomy in
`ntrloc-process-task-authorization-design-notes.md` Section 6 (roles #1/#2 there are state-machine-
specific — entry/exit and transition — not general item-lifecycle events), and also connects to
`ntrloc-workflow-summary.md` Section 4's already-decided signal-based mechanism for lifecycle-event
triggers. Not yet reconciled into one coherent list — flagged to come back to once the functional
category side is more complete.
5. **Cross-item consistency maintenance.** One item's change drives updates to related items — needs
   a clear line drawn against marker-assignment rules, which already do declarative cross-item
   propagation for *markers specifically*; this category would be the same idea for ordinary
   property/link changes. **Confirmed valid.** Example: a `Product`'s `status` changes to
   `discontinued`, and every linked `Offer` item's `availableForPurchase` property flips to `false`
   automatically.
6. **Scheduled housekeeping.** Periodic work with no single triggering event — nightly reports,
   expiring something, cleanup.
7. **Escalation/SLA monitoring.** If an approval/task hasn't happened within a time window, notify,
   reassign, or escalate.
8. **Mutation validity gate.** A process decides whether a proposed mutation is allowed to proceed at
   all — business-rule validation beyond basic schema/permission checks. **Confirmed valid** (new
   this round). Connects to an existing design sketch, not yet built: `ntrloc-workflow-summary.md`
   Section 5's pre-commit hook processes / "Ledger pre-create validation" mechanism — per-entry hook
   processes run before ledger entries are created, any rejection aggregates reasons and blocks the
   whole mutation. Worth checking whether that existing sketch already covers what's meant here, or
   whether this is a distinct idea (e.g. validating at a different point, or with different scope
   than per-entry).

Not yet confirmed which of these the admin would actually reach for in practice vs. which are
speculative pattern-matching on my part from the engine-level list — this needs your read on which
are real.

### 2a. Engine-level capability primitives (mechanics, kept for reference only)

The original list from the first pass — read/mutate the graph, human tasks, DMN, process-to-process
orchestration, timers, script tasks, external calls, no-anchor batch runs. These are the *mechanisms*
a category above would be built from, not categories in their own right. Kept here because the
guardrail observations from that pass (script tasks and external calls are the two with no existing
guardrail story) still stand and will matter once a category above needs them.

---

## 3. Categories Gate Which BPMN Elements Are Legal, Not Just Label Intent

**Principle (this round)**: a functional category isn't just an admin-facing label for organizing
the process-definition list — it constrains which BPMN/DMN elements are legitimately usable inside a
process authored under it. BPMN itself doesn't care; something can be perfectly valid BPMN and still
be wrong for a given use case's execution context.

**Concrete example given**: a synchronous mutation-enrichment/validity-gate process (category 8)
cannot contain a user task. It runs inline, blocking, as part of the mutation pipeline before a
ledger entry is even created — there's no way to suspend that execution indefinitely waiting on a
human click without holding the pipeline (and whatever transaction/lock context it's in) open the
whole time. This isn't a tuning/performance concern, it's a structural impossibility for that
category specifically; the same user task is perfectly legitimate inside category 2 (approval/gating
on a transition), which is allowed to be long-running because nothing upstream is blocked waiting
for it.

**Connects to an existing open question, not yet resolved**: `ntrloc-workflow-summary.md` Section 5
originally declared pre-commit hooks "no async steps" as a blanket constraint, then Section 8's
resolved list notes that blanket rationale no longer holds given later decisions, reopening (without
resolving) "whether/how much of Section 5's original constraint should still apply." This
conversation's example sharpens that into a real answer for at least the user-task case: not a
blanket async restriction, but a category-specific one — synchronous/inline categories (validity
gates, enrichment hooks) can't host human tasks (or likely anything else that can block
indefinitely), independent of whatever the final answer on async *job* steps turns out to be.

**Implication, not yet designed**: this means each functional category needs its own stated element
palette (allowed/disallowed BPMN constructs), presumably enforced at deploy-time validation in the
process editor — rejecting a user task inside a category-8 definition rather than letting it deploy
and fail at runtime. Which categories allow which elements isn't worked out yet beyond this one
example.

---

## Open Threads (carry forward)

- Which buckets above are real vs. which collapse into others once examined closer.
- Per-bucket guardrails — none designed yet, this section is intentionally just the inventory.
- Ledger attribution for process-driven mutations (Section 1), deferred until the capability model
  settles.
- Reconciliation with the existing authorization-role taxonomy
  (`ntrloc-process-task-authorization-design-notes.md` Section 6) — that doc's five roles are cut by
  *trigger* (what starts a process); this doc's buckets are cut by *capability* (what a process can
  do once running). Both axes probably need to coexist; not yet cross-mapped.
- Per-category legal BPMN element palettes (Section 3) — only one data point so far (category 8 /
  synchronous categories can't host user tasks). Full palette per category not designed; deploy-time
  enforcement mechanism not designed.
- Whether a mutation-validity-gate process (category 8) can modify the mutation (not just veto it),
  and if so whether that's the same process shape as the existing ID-generation pre-create hook
  example, or a distinct one — raised, not yet settled.
