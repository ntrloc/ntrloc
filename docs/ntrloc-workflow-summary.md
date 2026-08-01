# ntrloc Design Summary: Workflow Support (BPMN / DMN / CMMN)

## Topics: Why Workflow, Engine Choice, Custom Persistence, Event-Triggered Processes, Module Placement

This is a checkpoint of an in-progress design conversation, not a finished spec. Revise/refine in
place as the thinking develops further. Split out from `ntrloc-ui-hosting-summary.md` since it's
an independent topic, not a continuation of the UI-hosting conversation.

---

## 1. Why Workflow Support Belongs in ntrloc

Same "platform capability, not app choice" framing already used to justify first-class UI hosting
(`ntrloc-ui-hosting-summary.md` Section 1): almost every non-trivial system built on ntrloc
eventually needs approval chains, multi-step processes, task routing, and escalations. Without
platform support, every consuming team hand-rolls their own ad hoc state machine and task-assignment
logic — exactly the kind of universally-needed infrastructure a platform should own once, correctly,
rather than forcing every consumer to reinvent it.

Beyond that general argument:

- **DMN formalizes business rules that would otherwise be buried in imperative code.**
  "If this item is in state X and property Y exceeds threshold Z, require manager approval" has no
  natural home in ntrloc today except scattered validation code. Decision tables make that logic
  declarative, visible, and editable by business analysts without a developer/release cycle.
- **CMMN fits this platform's data model unusually well.** CMMN is built around ad hoc, non-linear
  "cases" rather than strict sequential flows — which maps naturally onto a graph where a case is
  just an item with a rich web of related items/links, and stages/milestones are process metadata
  layered on top rather than a separate rigid structure fighting the graph model.
- **It extends the platform's existing audit rigor to process execution, not just data mutation.**
  The ledger already gives a verifiable history of every data change (`ntrloc-mutations-ledger-summary.md`).
  Who approved what, when, under which conditions is the same kind of question organizations need
  answered for compliance, and there's currently no first-class answer for it.
- **It composes with what's already here rather than sitting beside it.** Process/decision
  definitions can reference existing schema (item types, properties) by name the same way mutations
  do, and task/process authorization can reuse the existing marker-based permission model instead of
  a workflow engine's own separate identity concepts.
- **Admin-configurable behavior with no code/test/deploy cycle — not just no downtime.** The value
  isn't merely that redeploying a process definition skips a server restart (though it does, the
  same cross-cutting "no restart to ship a change" property already identified as a deliberate theme
  in `ntrloc-ui-hosting-summary.md` Section 6 — static SPA mounts, non-cached server-rendered
  templates, and the intended-future JSR223 scripts all share it). It's that this kind of
  customization never enters the Java code/test/deploy pipeline at all — no source control PR, no
  CI run, no release/rollback plan. A BPMN/DMN definition edited in the visual editor and saved *is*
  the deployment. That's the real "hook" a platform gives admins: organization-specific
  customization to system behavior that bypasses engineering process entirely, which the no-restart
  property only partially captures.

**Scope, stated explicitly (July 2026)**: process/DMN/CMMN *definition authoring* — the visual
editor, deploying new versions — is strictly an admin function; it's the hook itself, and hooks are
a platform-configuration concern, not a general end-user capability. *Participating* in a running
process (user tasks, and — see Section 4 — potentially manual process starts) is a general-user
capability, gated by the same permission-marker model as everything else in ntrloc. This mirrors
admin's existing relationship to schema: admins define item types, general users create/edit items
of those types.

### Concrete driving use cases (not hypothetical)

1. **The company-specific app built on ntrloc** (separate repo, in development) needs:
   - Human approval workflows.
   - A fully-automated process triggered by data changes — e.g. "transmit product data to a
     downstream system when a Product item is created or updated."
   - A synchronous ID-generation hook — e.g. computing a canonical MDM product ID from a custom
     incrementing-counter table and setting it as a property as part of the product's own creation,
     not after the fact.
2. **ntrloc itself, once multimedia support exists**, would benefit from automated processes
   triggered on item creation — e.g. "generate renditions/thumbnails when a Photo is created,"
   "transcode and extract a transcript when a Video is created."

Both scenarios need **both** fully-automated processing *and* human-decision steps — this isn't a
"pick one" situation.

---

## 2. Engine Choice: Flowable

**Decided**: embed **Flowable** rather than build custom BPMN/DMN/CMMN interpreters. Flowable is
Apache 2.0, provides all three engines (BPMN, DMN, CMMN) as plain embeddable Java libraries with no
separate server required, and gets correct, standards-compliant execution semantics essentially for
free — hand-rolling gateways/boundary-events/DMN hit-policies correctly is a large, error-prone,
multi-month undertaking on its own.

**Tradeoff, stated directly**: this is a large third-party dependency with its own persistence
model that needs to coexist with (or be redirected into) ntrloc's own data model — addressed in
Section 3.

**Kept entirely behind ntrloc's own APIs**: Flowable's internal model must never leak into the
platform's public contract, same principle as "API Reuse" (Section 1 of the UI hosting doc) applied
here.

**Revised (July 2026): not bpmn-js.** `bpmn-js`'s license (the bpmn.io license) mandates a
permanent, unremovable "powered by bpmn.io" watermark on the rendered canvas, with no open-source
path around it — a non-starter, discovered before any vendoring work started. The actual editor is
built directly on **diagram-js** (the generic diagramming engine bpmn-js itself is built on) plus
**bpmn-moddle** (BPMN 2.0 XML read/write) — both plain MIT, no watermark clause. Both vendored the
same static way as the rest of `admin-ui` (`ntrloc-ui-hosting-summary.md` Section 9), consistent
with the no-build-step constraint: `bpmn-moddle` ships a ready-to-use browser bundle; `diagram-js`
does not (raw ES module source, no pre-built `dist/`), so it's vendored via the same recursive
import-rewriting approach used for Material Web, adapted for a real shared-DI-container library
rather than independent custom elements (each diagram-js feature module must share one Injector/
EventBus/ElementRegistry instance, so per-module `+esm` bundling — fine for Material Web's fully
independent components — would silently break Didi's cross-module wiring here). The BPMN-specific
layer bpmn-js would otherwise provide (palette, shape rendering, connection rules, XML import/
export) is hand-written against a reduced element set — see Section 9.

---

## 3. Persistence: Custom DataManagers, Not Flowable's Defaults

**Investigated and corrected a wrong initial assumption**: Flowable's `ContentStorage` interface
does **not** cover diagrams, tasks, or executions — it's scoped to the separate Content Engine
module (binary attachment/document blobs only, e.g. a file attached to a task). The actual
extension point for broader custom persistence is Flowable's **pluggable DataManager / Session /
SessionFactory architecture**: the default `DataManager` implementations talk to MyBatis, but they
can be swapped per entity type. This isn't hypothetical — Flowable has a published example of
running entirely on MongoDB by replacing `ProcessDefinitionDataManager`, `ExecutionDataManager`,
`TaskDataManager`, etc. with custom implementations backed by a custom `Session`/`SessionFactory`.

**Decided: go straight to custom DataManagers** rather than starting with Flowable's own
MyBatis-backed tables. Reasons, in order of weight:

1. **Direct control over storage** — stated preference, independent of the reasons below.
2. **Ledger/audit unification.** Every graph mutation goes through `LedgerRegisterCoordinator` —
   append-only ledger entries, then a synchronized register update. If Flowable manages its own
   `ACT_RU_*`/`ACT_HI_*` tables independently, workflow state changes (a task completed, a process
   instance advanced) never touch the ledger — two disconnected audit trails instead of one.
3. **Transactional atomicity between workflow steps and graph mutations.** A BPMN service task that
   creates/updates an item has to be atomic with Flowable's own execution-state transition. If
   Flowable commits on its own MyBatis session boundary, separate from the coordinator's
   prepare/commit, a crash between the two can leave a completed task whose graph-side effect never
   happened (or vice versa).
4. **Uniform querying, security, and projection.** Every other kind of ntrloc data goes through the
   same permission-marker checks and the same generic projection API. Workflow instances/tasks
   should be subject to the same model, not a parallel one built on Flowable's own identity/
   authorization concepts.

**Scope, not yet finalized**: executions, tasks, and variables are the entities that most need this
treatment (they're the ones that should flow through the ledger/coordinator and be subject to the
permission model). History entities and lower-level engine bookkeeping (jobs, timers, MyBatis
session internals) arguably don't carry the same weight — proposed as a smaller initial scope, not
yet agreed.

---

## 4. Event-Triggered Process Start

Both driving use cases (Section 1) share a pattern: **a process should start automatically when an
item of a given type experiences a lifecycle event** (created/updated), with the item's own data
flowing in as process variables.

**Decided (revised, July 2026): support both BPMN message and signal start events, split by use
case**, not a bespoke ntrloc-side trigger registry either way. Both keep the trigger visible and
editable *in the diagram itself* — a business analyst opening the process can see exactly what
starts it — so that rationale doesn't by itself favor one over the other. What does is a verified
difference in Flowable's engine behavior (confirmed by disassembling
`org.flowable.engine.impl.bpmn.deployer.EventSubscriptionManager` in `flowable-engine-8.0.0.jar`
via `javap -c`, not assumed from BPMN spec knowledge alone):

- `insertMessageEvent` looks up existing event subscriptions by message name and throws a
  `FlowableException` if another deployed process definition already has a start-event-level
  subscription (a subscription with no `processInstanceId`) to that same name. **Only one process
  definition can own a given message start-event name at a time** — a second deployment attempt
  fails hard.
- `insertSignalEvent` has no equivalent check at all — it unconditionally inserts the subscription.
  **Unlimited process definitions can share a signal start-event name**, all instantiated when the
  signal is dispatched.

That maps cleanly onto two different trigger categories:

- **Signal start events for lifecycle / "well-known system activity" triggers** (this section's
  original driving use case — `item.created.Photo`, `item.updated.Photo`, etc.). This is inherently
  a broadcast domain: several independently-authored processes (thumbnailing, validation,
  notification...) may legitimately want to react to the same lifecycle event, and message's
  uniqueness constraint would turn a second admin's independent process into a hard deployment
  failure. ntrloc's mutation pipeline publishes these as signals after a commit, keyed on item type
  + lifecycle event, with the item's data as process variables — same mechanism as before, just
  signal instead of message.
- **Message start events for admin-defined, named business-event triggers** where single ownership
  is the actual intent — e.g. "start the approval workflow when a PurchaseRequest is submitted."
  Here Flowable's uniqueness constraint is a guardrail, not a limitation: it catches "someone
  already built a process against this name" at deploy time instead of two competing processes
  silently both firing. These aren't driven by the mutation pipeline at all; they're a named event
  an admin defines and something else targets explicitly by name.

**Naming governance follow-on**: because signals have no engine-level collision protection, a typo'd
signal name doesn't error — it silently never fires (or silently double-fires if two unrelated
features coincidentally pick the same string). ntrloc should own name governance for the lifecycle
category itself: a fixed, admin-UI-presented list of well-known activity names
(`item.created.<Type>`, `link.created.<Type>`, ...) rather than freeform text entry when wiring up a
signal start event.

**Processes triggering other processes**: BPMN gives two mechanisms, and both come for free once
the above is built — no separate feature needed. **Call Activity** invokes another process
definition synchronously, with explicit in/out variable mappings, blocking the parent until the
child completes. **Throwing a signal or message** (intermediate throw event or end event) triggers
matching start events on other process definitions through the *identical* subscription mechanism
described above — a process step throwing `photo.thumbnails-regenerated` fans out to any process
definitions with a matching signal start event, exactly as if the mutation pipeline had published it.

**Atomicity, resolved**: confirmed via the same disassembly technique
(`IntermediateThrowSignalEventActivityBehavior.execute()`) that signal dispatch branches on
`SignalEventDefinition.isAsync()`, which defaults to `false` unless a modeler explicitly marks the
throw event `flowable:async="true"`. By default, dispatch is **synchronous** — newly-triggered
process instances' initial execution happens in the same command/transaction as the throwing step,
not a deferred job. Applied to the mutation pipeline: if the lifecycle-signal publish call is made
from inside the same Flowable command/transaction as the item-creation commit (via the
custom-DataManager path from Section 3), triggered process instances start atomically with the
mutation by construction — the same guarantee BPMN already gives for free when one process signals
another. This resolves the strict-atomicity-vs-best-effort question in favor of atomicity; the
remaining work is an implementation detail (make sure the coordinator's publish call happens inside
the same transaction as the commit), not an open design question.

**Manual triggering, handled without a new mechanism**: BPMN supports multiple start events on one
process definition. The same process can have a signal start event (automatic) *and* a plain (none)
start event usable for on-demand starts via Flowable's ordinary `startProcessInstanceByKey` — e.g.
"I fixed my photo rendition code, now reprocess this photo." Same diagram, two ways in. Implication
for the admin UI (not yet designed): manually starting a process needs a way to pick the *existing*
item to reprocess and feed its data in as starting variables — likely reusing the item-picking
machinery already in the Search screen, but the exact shape (a generic "run a process" screen vs. a
contextual "reprocess" action on an item's own detail view) is explicitly undecided — deferred
deliberately ("start with simpler cases and see where it leads").

**Authorization scope for manual starts, decided**: treated as admin-only for now — there's no
concrete driving use case yet for a general user manually starting a process — but not structurally
hardcoded that way. It's gated through the same permission-marker model as everything else (Section
1's scope point), just with the grant currently held only by the admin role/group. Opening it to
general users later, if a use case shows up, is then a permission-grant change, not a
re-architecture. Applies equally to `runProcess` (MCP tool, already built) — worth verifying its
current authorization check follows this same marker-based rule rather than an admin-identity
assumption baked into the tool itself, next time that tool is touched.

It also needs a way to distinguish
authoring a lifecycle-bound trigger (always signal, admin picks item/link type + created/updated)
from authoring a custom named business-event trigger (message, admin types/picks a name) — two
different pickers, not one dropdown with a message/signal toggle, since the *source* of the trigger
differs, not just the delivery semantics.

---

## 5. Pre-Commit Hook Processes

A third trigger category, distinct from Section 4 in kind, not just in sync/async config: **not
event-triggered at all**. Where Section 4 is "react to a commit that already happened" (fire a
signal or message *after* the item exists), this is "run synchronously *before* the item is
finalized, and feed the result back into the very data being written" — e.g. computing a canonical
MDM product ID from a custom counter table and setting it as a property as part of the product's own
creation (Section 1's third use case), not as a follow-up mutation.

**Mechanism, verified via bytecode**: `RuntimeService.startProcessInstanceByMessage(messageName,
variables)`. `StartProcessInstanceByMessageCmd implements Command<ProcessInstance>`; its `execute()`
runs inline within the calling `CommandContext` and returns the resulting `ProcessInstance`
directly — no job, no queue. Called synchronously by the mutation pipeline itself (not "triggered"
by anything), it reads a specific output variable (e.g. `mdmProductId`) off the returned
`ProcessInstance` and merges it into the item's property set before finalizing the write.

**Why message, not signal, and why that's the *right* framing (revises this morning's rationale)**:
a signal has no return value and no coherent single answer if N processes are listening — which
computed ID would win? A hook needs exactly one owner and something to hand back. Message start
events' engine-enforced uniqueness constraint (Section 4) isn't a guardrail here, it's a correctness
requirement the engine happens to enforce for free. Naming follows the same well-known-name
convention as signals (e.g. `preCreate.<ItemType>`, resolved from item type schema, visible in the
diagram as a message start event) but needs no admin-governed name list the way signals do — message
names get free collision protection from the engine itself.

**Transactional coupling is stricter here than Section 4's atomicity finding.** Section 4 only
needed the *dispatch* to happen inside the same command as the throwing step (sufficient for
fire-and-forget signals). This needs the hook's own execution to genuinely be part of the same
database transaction as the coordinator's write — a hook failure must roll back the whole item
creation, and the hook's output becomes part of the data being committed. This is only possible
because of Section 3's custom-DataManager decision; Flowable's own MyBatis-session boundary
couldn't participate in the coordinator's transaction.

**Modeling constraint, not yet enforced anywhere**: a pre-commit hook process must contain **no**
`flowable:async="true"` steps — hitting one defers to a job on a separate transaction, breaking the
synchronous return-value contract entirely. Needs a deploy-time or admin-UI validation, not just
documentation, once this is built.

**Superseded in part, see Section 6**: this constraint was derived from the assumption that a hook
runs inline in the ledger/register's own database transaction. That assumption itself was later
revisited — process execution is decoupled from ledger/register transactions by design — which lifts
the *reason* for banning async steps outright. Whether to still restrict async steps in specifically
the pre-create-validation/enrichment hooks discussed in this section (as opposed to hook processes
generally) is now a narrower, unresolved question rather than a settled constraint.

**Timeout, resolved: not modelable in the diagram, needs a config value instead.** The natural BPMN
idiom for a configurable "first to finish wins" timeout — an interrupting boundary timer event
racing a normal path — fundamentally can't apply here. A race needs two things genuinely progressing
concurrently: the wrapped steps, and a timer job polled/fired by Flowable's async job executor on a
separate thread, in a separate transaction, later. `StartProcessInstanceByMessageCmd.execute()` is
one synchronous call on one thread that doesn't return until it hits the end event or a real wait
state — there's no second thread to race against, and even the timer job row itself would be part of
the same *uncommitted* transaction as the hook and the mutation, invisible to the job executor until
commit. A slow hook holds that transaction open the whole time; nothing external can interrupt it.
So a diagram-modeled race is the right tool for Section 4's async category (e.g. "generate
thumbnails, escalate if it's taking too long") but not for a synchronous hook whose entire purpose is
guaranteeing its output is available before the item commits. Per-process configurability, which is
still wanted, has to live outside the diagram instead: a plain numeric timeout alongside the
message-name reference on the item type's hook association (not yet designed), enforced by ntrloc's
own call site wrapping `startProcessInstanceByMessage` — not a BPMN element.

**Revisit flagged, see Section 6**: the "no second thread to race against" premise behind this
conclusion depended on the hook sharing the ledger/register's transaction. With that decoupled, a
real timer job on its own thread/transaction genuinely could race a hook running in its own
transaction, which reopens (but does not resolve) the diagram-modeled-race option.

### Lifecycle hook boundaries, named precisely (July 2026 follow-on)

The mutation pipeline actually has three phases: (1) build ledger entries from a `MutationRequest`,
(2) `prepare` those entries into the register, (3) `commit` the register. Hook points are the
boundaries *between* phases, not the phases themselves:

- **pre-create** — before phase (1); no ledger entries exist yet. Both examples in this section (ID
  generation, and the delete-guard example below) land here.
- **post-create / pre-prepare** — entries built, register untouched.
- **post-prepare / pre-commit** — register touched, not yet committed.
- **post-commit** — after phase (3); this is where Section 4's signal-triggered processes already
  live.

Two functional axes cut across these boundaries: **validation** (go/no-go plus a reason, no data
touched) and **enrichment** (no veto power, produces/modifies data). Both are pre-create-only, and
that's structural, not incidental. Enrichment can only be pre-create: a ledger entry is append-only
once built, so an enriched value has to be baked in before the entry exists, not after. Validation is
likewise effectively pre-create-only: post-commit validation makes no sense (nothing left to veto
once committed — at best a post-commit hook could compensate, a weaker thing than rejecting outright),
and pre-prepare/post-prepare validation doesn't obviously add anything pre-create doesn't already
have, since nothing register-side has happened by either of those boundaries either.

### Second driving example (validation flavor): cross-item delete guard

E.g., a Photo linked to an active Marketing Campaign can't be deleted while the campaign is active —
an organization-specific rule with no natural home in ntrloc's own schema/validation model.
Structurally distinct from the ID-gen example: it's triggered by a delete (still "pre-create" in the
ledger-phase sense above — before the `ItemDeleteEntry` is built), produces no data (a reject-or-allow
decision plus a human-readable reason, not a property to merge in), and requires **cross-item register
reads** — traversing the Photo→Campaign link and reading the Campaign's `active` property, not just
the item's own data. Confirms hook processes need register read access beyond the single item being
mutated (Section 6).

### Ledger pre-create validation, mechanism sketched (not yet built)

1. `MutationRequestProcessor` receives a `MutationRequest` (potentially several entries in one
   batch).
2. For each entry whose type has a validation hook configured — no hook configured means approved by
   default, no process fired at all — start that hook's process, passing the **complete mutation
   request** as context, not just the owning entry. Needed because a hook may have to reason about
   *other pending, uncommitted entries in the same request*: e.g. a batch that deletes both a Photo
   and its Photo–Campaign Link together — a register read alone can't see that the Link is also going
   away, since nothing's committed yet.
3. All fired decisions run concurrently (Layer 1 fan-out, Section 6) and are joined before proceeding.
4. Any "not allowed" rejects the whole mutation — explaining **all** problems, not just the first,
   each attributed to the entry it came from. All "allowed" → proceed with create/prepare/commit for
   the whole batch.

Which schema entity owns a hook's configuration for the cross-item case (item type vs. link type) is
not yet resolved (Section 9).

---

## 6. Process Subsystem Boundaries & Distributed Execution

Follow-on design discussion (July 2026), triggered by working out the pre-create validation
mechanism above.

### Layering: who knows about whom

**Decided**: neither the ledger nor the register has any knowledge of the process subsystem — if
anything does, it's the orchestration layer above both. Concretely, that's `MutationRequestProcessor`,
not `LedgerRegisterCoordinator`: the coordinator only speaks `LedgerEntry` (`prepare`/`commit`), with
no notion of items or properties; `MutationRequestProcessor` is the class that already builds
`ItemCreateEntry` etc., and is therefore the natural place to invoke pre-create hooks. (There may be
an opportunity to revisit the coordinator/`MutationRequestProcessor` role split more generally —
deferred, Section 9.)

In the other direction: **a process has no insight into the ledger at all**, and **read-only**
insight into the register — e.g. a validation hook's service-task delegate queries the register the
same way any other generic projection/query caller would, but never reads ledger history. If a
process needs to change graph state (e.g. Section 4's post-commit EXIF-enrichment example), that has
to go back in through the front door as an ordinary new mutation via `MutationRequestProcessor` —
never a direct register write — otherwise the ledger stops being a complete record.

### Transactional decoupling

**Decided**: process execution is **not** in the same database transaction as ledger/register
mutations, on principle — not merely as a fallback. The two subsystems can be made *effectively*
atomic from a system-behavior perspective (a hook's answer is fully resolved, in sequence, before the
ledger/register phases proceed) without being atomic at the database level (no shared commit/
rollback). Motivation: a shared transaction couples the ledger/register's primary-write path to
Flowable's engine internals — including whatever isn't fully custom-persisted — and a slow/stuck hook
would hold that transaction open for the whole system, not just its own work.

**Consequence for Section 3's scope question**: this closes the "not yet agreed" custom-DataManager
scope question from Section 3 outright — every Flowable entity, including jobs/timers/history, needs
full custom persistence, not just executions/tasks/variables. There's an explicit **zero-MyBatis**
requirement: no Flowable default (MyBatis-backed) table is acceptable for anything.

**Consequence for Section 5's async constraint**: decoupling also lifts Section 5's "no
`flowable:async="true"` steps in a hook process" modeling constraint — that constraint existed only
because a hook was assumed to run inline in the ledger's own transaction, and an async continuation
would break out of it. With process execution genuinely independent, hook processes are free to use
real async continuations — which also **reopens** the boundary-timer timeout question Section 5
declared closed ("no second thread to race against"): with two independent transactions (the hook's,
and a timer job's), a diagram-modeled race becomes structurally possible again. Not redesigned yet,
just no longer ruled out.

### Two layers of process parallelism

Motivated by pre-create validation specifically: it sits in the latency-critical path of every
mutation of a given type, so running several independent checks concurrently instead of serially is a
real win, not a cosmetic one.

- **Layer 1 — cross-entry fan-out, `MutationRequestProcessor`-orchestrated.** The fork/join above: N
  entries needing validation → N independent process instances, started concurrently and joined
  before the mutation proceeds. This is plain application-level concurrency (e.g. `Flux.merge`/
  `flatMap` with a concurrency bound, or a thread pool plus `CompletableFuture.allOf`) — nothing
  BPMN-specific, and it needs **no new infrastructure**. As long as an individual hook process itself
  has no async steps, `StartProcessInstanceByMessageCmd.execute()` runs inline and returns
  synchronously regardless of which node issues the call — Flowable's persistence is fully
  DB-centralized with no per-JVM state, so "don't care which node handles it" is already true for
  free at this layer.
- **Layer 2 — in-process concurrency, within a single process instance's own execution graph** (a
  BPMN parallel gateway with async branches). Orthogonal to hook type — not particular to validation,
  though validation is unlikely to be where anyone actually reaches for it (no reason to design it
  away, just no reason to design *for* it here specifically). This is the layer that actually needs
  Flowable's async job executor, and therefore the layer where cross-node completion detection is a
  real unsolved problem, below.

### Flowable's concurrency mechanisms, ground-truth verified

Verified via `javap -c` disassembly of `flowable-engine-8.0.0.jar` / `flowable-job-service-8.0.0.jar`
(same methodology as Section 4), not assumed from BPMN-engine convention. Both the parallel-gateway
join and async job claiming use the **same technique: plain optimistic locking via a `REV_` column**
(`UPDATE ... WHERE id=? AND rev=?`, check rows-affected, throw `FlowableOptimisticLockingException` on
0 rows) — never pessimistic locking (`SELECT ... FOR UPDATE`), never a `lock_owner IS NULL`-style
guard predicate on its own.

- **Parallel gateway join** (`ParallelGatewayActivityBehavior.execute()`): an arriving execution is
  marked inactive, then compared — `findInactiveExecutionsByActivityIdAndProcessInstanceId(...).size()`
  vs. the model's static incoming-flow count — to decide "am I last." Concurrency safety comes from
  `lockFirstParentScope()` forcing the nearest scope-ancestor execution into the dirty set (via a
  `forceUpdate()` flag that fakes a changed `persistentState`), so two concurrently-arriving branches
  race on that ancestor's `REV_` at flush time; the loser throws and **its whole command fails
  outright** — Flowable's generic `RetryInterceptor` is dead code except on CockroachDB, so nothing
  retries a losing join for us. **We'd need our own retry wrapper** around any command that can race
  at a join — true even single-node with a multi-threaded job executor, not just across a cluster.
  **Correction, live-verified (see "Branching/forking" below):** this whole analysis assumed the
  join-counting read (`findInactiveExecutionsByActivityIdAndProcessInstanceId`) would actually see an
  arriving branch's own `inactivate()` write in the first place — untested until a real branching
  process existed to exercise it. It didn't: a more basic, previously-latent flush-ordering gap in
  `ProcessSession` meant the query never saw its own command's write at all, so *every* single-node,
  non-concurrent join hung forever, before the REV_-column race above was ever reachable.
- **Job claiming** (`AcquireJobsCmd`): selects jobs where `LOCK_EXP_TIME_ IS NULL`, then claims via
  the same optimistic-lock `UPDATE` (setting `LOCK_OWNER_`, `LOCK_EXP_TIME_` — default 1-hour TTL).
  Unlike the join case, **Flowable already handles this conflict for us**: if any job in an
  acquisition batch was claimed elsewhere first, the whole batch's flush fails, is caught, logged as
  expected clustered behavior, and retried next cycle (default 10s) — explicitly designed for
  multi-node contention. An opt-in `globalAcquireLockEnabled` mode trades this for a real distributed
  lock (zero contention, lower acquisition throughput). Dead-node reclaim is a separate scheduled
  sweep (`ResetExpiredJobsRunnable`, default every 1 minute) that resets expired locks via a
  `REV_`-check-free unconditional update.
- **Implication**: our custom Job/Execution DataManagers must replicate this exact optimistic-locking
  pattern to stay correctness-compatible with Flowable's own engine logic (which is storage-agnostic
  and calls through the DataManager interface either way) — real, non-trivial correctness work, harder
  than the CRUD-shaped DataManagers already built for the single-node walking skeleton, which never
  had two writers touching the same execution concurrently.

**Correction (July 2026, verified via the same disassembly technique on `ExecuteAsyncRunnable` /
`DefaultAsyncRunnableExecutionExceptionHandler` / `JobRetryCmd` in `flowable-job-service-8.0.0.jar`):
the "we'd need our own retry wrapper" conclusion above does not hold for the case that actually
matters.** It was derived from the generic command-level `RetryInterceptor` being dead code, which is
true but only relevant to a fully-synchronous, non-job-backed execution path. A losing parallel-gateway
join only happens when two branches' continuations are genuinely concurrent — which requires
`flowable:async="true"`, i.e. the race only ever occurs *inside an async job's execution*. At that
layer, `ExecuteAsyncRunnable.executeJob()` catches **every** exception generically (its exception
table has a `FlowableOptimisticLockingException` handler and a `Throwable` handler that both call the
identical `handleFailedJob(...)` path — the only difference is a DEBUG-level log line noting it's
"expected behavior in a clustered environment"). `JobRetryCmd` special-cases exactly one thing —
`FlowableUnrecoverableJobException` in the cause chain — and `FlowableOptimisticLockingException` is
not it, so a losing join is retried exactly like any other transient job failure: **3 attempts by
default, 10 seconds apart** (`asyncExecutorNumberOfRetries` / `asyncFailedJobWaitTime`, both
hardcoded engine defaults), configurable per-activity via `flowable:failedJobRetryTimeCycle`, before
landing in `ACT_RU_DEADLETTER_JOB`. **No custom retry wrapper is needed** — as long as the custom
Execution/Job DataManagers correctly implement the `REV_`-based optimistic locking, Flowable's
existing job-retry machinery already resolves join races correctly for free.

### Boundary error events, ground-truth verified

Investigated to support the post-commit fan-out design below (isolating one enrichment's failure from
another's success, or making both-or-nothing joint outcomes possible). Verified via disassembly of
`ClassDelegate`, `ErrorPropagation`, `ContinueProcessOperation`, and `AsyncContinuationJobHandler` in
`flowable-engine-8.0.0.jar`.

- **What's actually caught**: only a deliberately-thrown `org.flowable.engine.delegate.BpmnError`, or
  an arbitrary exception explicitly configured via `<flowable:mapException errorCode="..."
  [andChildren="true"]>com.foo.SomeException</flowable:mapException>` (matched by exact class-name
  string equality, or by ancestor-class assignability if `andChildren` is set). There is **no**
  generic "catch any `RuntimeException`" behavior and no `errorCodeVariable`-style catch-all — a
  delegate's exception table lists `BpmnError` before a fallback `RuntimeException` handler, but that
  fallback handler only succeeds if a `mapException` entry matches; otherwise it rethrows the original
  exception unchanged.
- **The catching mechanism is byte-identical whether the activity runs inline or as an async job** —
  it lives in the delegate wrapper (`ClassDelegate.execute()` et al.), not in `ContinueProcessOperation`.
  But **where that try/catch sits relative to the job-execution boundary changes everything in
  practice**: `ContinueProcessOperation.executeAsynchronous()` doesn't run the delegate at all — it
  just persists a job and returns; the delegate only actually runs later, inside
  `AsyncContinuationJobHandler.execute()`, itself invoked from within the job's own command/
  transaction. A `BpmnError` thrown there is still caught and propagated to a boundary event
  correctly (same mechanism). But a *plain, unmapped* exception thrown there is **never seen by
  `ErrorPropagation`/boundary events at all** — it escapes the job's execution entirely and is instead
  caught purely by the job-retry layer above (previous subsection), which retries 3 times and then
  moves the job to `ACT_RU_DEADLETTER_JOB` **silently, with no BPMN-level routing attempted** — the
  process instance's execution just sits wherever it was, invisible to the diagram.
- **Practical implication for post-commit enrichment delegates** (e.g. the video keyframe/transcript
  example below): a delegate calling an external tool cannot rely on Flowable's automatic
  retry-then-dead-letter to eventually reach a boundary event — it never does. To get the
  diagram-visible isolate-or-abort routing designed below, the delegate itself must own the "give up"
  decision (whether that's on first failure or after its own internal retry logic) and explicitly
  `throw new BpmnError("someErrorCode")` once it does. Leaning on Flowable's default job retry is fine
  for genuinely transient failures the delegate wants to just retry silently, but it is a dead end for
  anything that needs to surface as a modeled diagram path.

### `ProcessManager`: a single front door for process invocation

Proposed (not yet built): `MutationRequestProcessor` and other external callers shouldn't talk to
Flowable's `RuntimeService` directly — a `ProcessManager` facade sits between them, so a caller says
"run this process, give me the result" (or "dispatch this event") without knowing or caring whether
the process resolved inline, via an async job on this node, or via an async job claimed by a different
node in the cluster. This is the seam that hides the Layer 1/Layer 2 distinction from callers
entirely, and extends Section 2's "Flowable's internal model must never leak into the platform's own
contract" principle to internal callers, not just external ones.

Not one generalized method, but (at least) two, because the underlying Flowable operations have
genuinely different cardinality guarantees (see the taxonomy below):

- **`runByMessage(messageName, variables, timeout) -> ProcessResult`** — blocking, single-owner
  (message start events are engine-enforced unique per name), always returns a value (never throws for
  a normal reject/error/timeout outcome — a uniform result type covers success-with-output,
  rejected-with-reason, failed, and timed-out, so a caller fanning out N of these, as
  `MutationRequestProcessor` does for pre-create hooks, can collect all N outcomes before deciding
  anything, rather than having some paths throw and abort the collection early). Timeout is a
  caller-supplied parameter — `ProcessManager` has no schema/config knowledge of its own; the caller
  (which already looked up whether a hook is configured) supplies it.
- **`dispatchSignal(signalName, variables)`** — fire-and-forget, void, multi-owner by design (zero,
  one, or many process definitions may react; the caller neither knows nor waits for how many).

Beyond pre-create hooks, this is also the natural front door for Section 4's manual/on-demand starts
and the `runProcess` MCP tool — and the natural place to finally close that tool's flagged
authorization gap (Section 9) with one check instead of several scattered call sites.

### Three call shapes, taxonomized

Restating Sections 4/5's mechanisms in terms of the `ProcessManager` split above, since the same
`MutationRequestProcessor`-orchestrated fork/join shape (Section 6, Layer 1) applies to two of the
three, but with different join semantics, and the third is structurally different:

1. **Pre-create validation** — `MutationRequestProcessor`-orchestrated fork/join, one `runByMessage`
   call per entry whose type has a validation hook configured. The *join* aggregates: collect all N
   outcomes, and if any rejects, reject the whole batch with all reasons (not just the first).
2. **Pre-create enrichment** — same fork mechanism, one `runByMessage` call per entry whose type has
   an enrichment hook configured. The *join* differs from (1): it's N independent per-entry merges —
   entry A's result only ever affects entry A's own properties, there's no batch-wide pass/fail
   concept the way validation has.
3. **Post-commit enrichment/reaction** — one `dispatchSignal` call per item, fire-and-forget, **not**
   a caller-orchestrated fork/join at all. This isn't just async-instead-of-sync: it's structurally
   different because it uses a signal, not a message, and Section 4 already established *why* —
   signals are the only start-event type Flowable lets multiple process definitions subscribe to under
   the same name. Whatever fan-out happens (zero, one, or many triggered instances) happens entirely
   inside Flowable's own subscription matching, invisibly to the caller — there's no "join N results"
   step because there's no defined N and no coherent single result to wait for even in principle.

### Post-commit fan-out: isolation vs. all-or-nothing

Worked through via a concrete example: a Video upload should get both extracted keyframes and a
transcript, via two different external tools, asynchronously after the video is committed. Two
structural options, and the choice has a real, verifiable consequence for how a partial failure
behaves.

**Revisiting "independently authored," honestly.** Section 4's original rationale for choosing signal
over message for lifecycle triggers leaned on "several independently-authored processes... may
legitimately want to react to the same event" — which is a weaker argument once a single admin team
authors everything; a coordinated team could just avoid name collisions. But a second,
authorship-independent justification holds regardless: **raw cardinality**. Even one team may
genuinely want two separately-deployed, separately-evolved process definitions reacting to the same
lifecycle event (keyframes, transcript) rather than one growing diagram — and message start events
make that *physically impossible* regardless of how well-coordinated the authors are, since Flowable
throws on a second deployment claiming the same message name no matter who's deploying it.

**The genuine alternative this opens up**: one message-triggered process with parallel keyframe/
transcript subprocess branches, instead of two independently signal-subscribed process definitions.
This trades cardinality flexibility for message's collision protection, but costs two things: (a)
**coupled deploy lifecycle** — every future reaction has to be added by editing and redeploying the
one existing diagram, since only one process definition can own the message name, so diagrams
accumulate branches indefinitely rather than being added as new, isolated units; (b) real concurrent
execution of the branches requires Layer 2 (async continuations, the join-safety machinery above) to
be built at all, whereas N independently signal-subscribed processes get equivalent effective
concurrency for free via Layer 1, which needs no new infrastructure.

**Failure-isolation vs. all-or-nothing is the same join mechanism, evaluated against opposite
requirements — not a limitation to design around, a genuine either/or.** With two independent process
definitions: keyframe failing has zero effect on transcript succeeding — each writes back
independently and eagerly the moment it succeeds, via its own fresh mutation through
`MutationRequestProcessor` (Section 6's read-only-register-access rule). With one process and a plain
parallel gateway: the transcript branch's *data* effect is equally safe if it already wrote back
(nothing rolls that back), but the bytecode-verified strict AND-join (Section 6) means the *process
instance* hangs indefinitely waiting for a keyframe branch that keeps failing — anything watching
process-instance completion sees it as permanently stuck, even though the transcript data landed fine.
Conversely, if **both** branches defer their write-back until *after* the join (compute into process
variables, issue one combined mutation only once both arrive), that same strict AND-join gives you
true all-or-nothing for free — keyframe never arriving means the combined write never happens at all.

**Boundary error events let a single diagram deliberately choose either semantic, visibly.** Attach a
boundary error event to the keyframe branch (the delegate must explicitly `throw new BpmnError(...)`
on giving up, per the verified findings above — Flowable's automatic job retry/dead-letter never
reaches this). Route its outgoing flow to the *same* join the success path feeds → isolated semantics
(keyframe's failure still lets transcript's already-computed result get written). Route it to a
*separate* "abort, write nothing" end event that bypasses the join/combined-write step entirely →
all-or-nothing (transcript's already-computed-but-not-yet-written result is simply discarded). Same
one-process structure, same underlying join machinery — which behavior you get is a genuine,
diagram-visible modeling choice rather than an accident of how many process definitions happen to be
deployed, which fits the platform's existing "visible and editable in the diagram" ethos (Section 4)
well. This doesn't remove the need for Layer 2's underlying infrastructure (join-safety, the
now-confirmed-unnecessary-but-still-relevant-to-understand retry behavior) — boundary events control
routing *at* the join, they don't replace what makes the join itself correct under concurrent/
cross-node arrival.

### Existing cluster infrastructure (`domain-graph-starter`, `org.ntrloc.graph.cluster`)

Investigated as a possible answer to Layer 2's cross-node completion problem. It's a thin Spring
wrapper around an embedded Hazelcast instance (`ClusterServiceImpl`), exposing only: cluster
membership (`getClusterMembers`/`getLocalMember`), a naive non-quorum leader flag (`isLeaderNode()` —
oldest member wins, no `CPSubsystem`/`FencedLock`), and a shared `IMap` (`getMap(name)`). No
distributed executor, no real lock, no RPC/task-submission, despite Hazelcast bundling
`IExecutorService` — exactly a "submit a `Callable`, get a `Future`, don't care which member runs it"
primitive — unused and unexposed. `domain-graph-experimental` doesn't currently depend on
`domain-graph-starter` at all, so using any of this means new module wiring either way.

Real usages today are all local-reaction/broadcast, not work-dispatch: `GatewayRegistrar`
(self-registration via `getLocalMember()`), `GraphQLSchemaRegistrar` (local reaction on cluster join),
and — the closest existing precedent for Layer 2's join-wait problem — `SchemaManagerImpl`, which uses
a shared `IMap` plus an entry listener (filtering out self-originated events) as an
eventually-consistent "something changed elsewhere, refresh locally" broadcast (flagged in that code's
own comment as a hack that should really be a `ReliableTopic`). `ScheduledExecutorConfig` beans are
already wired into `AbstractClusterConfiguration` but nothing calls `getScheduledExecutorService()` —
half-built, unused, possibly anticipating exactly this need.

**Not yet decided**: whether Layer 2's join-wait notification should be DB polling against the custom
persistence layer (simple, no new infra, cluster-safe by construction, but a latency floor and query
volume under load) or reuse/extend the Hazelcast `IMap`+listener pattern `SchemaManagerImpl` already
established (push-based, precedent already in this codebase, but needs new wiring since
`domain-graph-experimental` doesn't depend on `domain-graph-starter` today).

### Clustering, generally

Flagged as a cross-cutting concern (Kubernetes multi-pod deployment) rather than resolved: it shapes
the custom Job DataManager's claim/locking semantics and Layer 2's join-wait mechanism, both already
open questions above, so nothing currently decided is incompatible with it — but neither should be
finalized without it in view.

### Zero-MyBatis, implemented (July 2026)

The custom-DataManager scope from Section 3 (deployments/resources/process-definitions/executions/
variables/tasks/task-identity-links) is now joined by every other Flowable entity this engine can
actually reach during normal execution, closing out this section's own zero-MyBatis requirement:

- **Optimistic locking retrofitted** onto `ExecutionDataManagerImpl` and
  `ProcessDefinitionDataManagerImpl` (`revision`-column check-and-increment, throwing
  `FlowableOptimisticLockingException` on a losing write) — previously absent entirely, despite
  being what Flowable's own join/job-claim safety depends on. `ProcessSession.registerFlush` was
  also fixed to only write back entities whose `getPersistentState()` actually changed, rather than
  unconditionally re-writing (and revision-bumping) every entity a command merely read.
- **Job/Timer**: one discriminated `process_job` table (`job_kind` column) backs all six of
  Flowable's job entity types (`JobDataManagerImpl`, `TimerJobDataManagerImpl`,
  `SuspendedJobDataManagerImpl`, `DeadLetterJobDataManagerImpl`, `HistoryJobDataManagerImpl`,
  `ExternalWorkerJobDataManagerImpl`), sharing a common base (`AbstractJobDataManager`) for the five
  that share `AbstractRuntimeJobEntity`'s field surface. Claim safety needed no bespoke logic for
  the *generic* CRUD path — it falls out of the same cache/registerFlush/revision-checked-update
  pattern every other DataManager here already uses. **Correction (see "Job control: AsyncExecutor"
  section's July 2026 addendum): that generic-path claim does not cover the actual batch-lock-
  acquisition method Flowable's own `AcquireTimerJobsCmd`/`AcquireJobsCmd` call** —
  `bulkUpdateJobLockWithoutRevisionCheck` — which this class implements as a plain per-id `UPDATE`
  with no revision or `lock_owner IS NULL` guard of its own. The async executor was left inactive at
  the time this was written (nothing in ntrloc emitted async continuations yet); `insert()` still
  worked correctly regardless, since a timer-start-event's job row would need to land correctly the
  moment it's created, independent of whether anything polled it. It's since been activated for a
  single-node demo — see the addendum for what that does and doesn't mean for claim safety.
- **Event subscriptions**: `EventSubscriptionDataManagerImpl` backs message/signal start-event
  subscriptions against a new `process_event_subscription` table — this closes the last real gap in
  Section 4/5's trigger/hook mechanism actually working end-to-end on custom persistence.
- **Activity instances**: `ActivityInstanceDataManagerImpl`, discovered mid-implementation to be
  mandatory rather than optional — `ContinueProcessOperation` writes a row unconditionally on every
  activity transition, no history-level gate, confirmed via bytecode. Missing this would have hard-
  failed the very next process start once schema creation was suppressed.
- **IDM and Event Registry disabled** (`setDisableIdmEngine`/`setDisableEventRegistry`) rather than
  given custom persistence — confirmed via bytecode that disabling prevents either sub-engine (and
  its own schema management) from being built at all, and via repo-wide grep that neither is used
  anywhere in ntrloc.
- **Schema management suppressed**: `DB_SCHEMA_UPDATE_FALSE` turned out not to be viable (it still
  issues a real version-check `SELECT` against `ACT_GE_PROPERTY` and hard-fails since that table no
  longer exists) — `setSchemaManagementCmd(commandContext -> null)` is the actual no-op extension
  point. Also required: `setIdGenerator(new StrongUuidGenerator())`, since Flowable's default
  `DbIdGenerator` independently reads/writes `ACT_GE_PROPERTY` at arbitrary runtime points whenever
  its in-memory id-block is exhausted — a second path to the same now-nonexistent table.
- **Remaining Flowable entities** (`Model`, `EventLogEntry`, `ProcessDefinitionInfo`, `EntityLink`,
  Batch) confirmed via bytecode to each be gated behind a config flag this app never sets — genuinely
  unreachable, not just unused, so no DataManager was needed for any of them. History stays off for
  the same reason: schema suppression means its ten tables are simply never created.

**Live-verified (July 2026)** against a real Postgres via the existing Testcontainers-based
integration tests, plus a new permanent regression test
(`ProcessEngineIntegrationTest.noFlowableDefaultTablesExist`, querying `information_schema.tables`
for any `act_%`/`flw_%` table) asserting the actual acceptance criterion directly — it passes. Two
real bugs only surfaced at this live-verification step, both invisible from compilation or static
review alone:

- `ValidateExecutionRelatedEntityCountCfgCmd` looks up a config property
  (`cfg.execution-related-entities-count`) against `ACT_GE_PROPERTY` unconditionally on every
  `buildEngine()` call — a third, independent path to that table beyond schema management and the
  id generator, needing its own `PropertyDataManagerImpl` (`process_property` table; notable
  wrinkle: a `PropertyEntity`'s id *is* its name — `setId()` throws — so no `assignIdIfMissing()`).
- `BpmnDeployer.createLocalizationValues()` looks up `ACT_PROCDEF_INFO` unconditionally on every
  deploy (including the hello-world resource loaded at boot) — **not** actually gated behind
  `isEnableProcessDefinitionInfoCache()` the way static bytecode analysis of the flag's own call
  sites had suggested; needing a `ProcessDefinitionInfoDataManagerImpl` (`process_definition_info`
  table) after all, correcting that entity's earlier "genuinely unreachable" classification.

Both are now built the same way as everything else in this section. Separately confirmed as
pre-existing and unrelated to this work: `ProcessEngineIntegrationTest.helloWorldProcessRunsToCompletion`'s
`instance.isEnded()` assertion fails identically on the pre-session code (verified via `git stash`),
so it's a distinct, already-existing issue, not a regression from any of the above.

### User Task claim/complete and message/signal dispatch, live-verified (July 2026)

The prior pass's regression test only exercised deployment and a service-task-only happy path.
Three new `ProcessEngineIntegrationTest` cases close that gap: `userTaskClaimAndCompleteAdvancesProcessToCompletion`
(claims and completes helloWorld's own `reviewGreeting` user task, driving the process the rest of
the way to completion), `messageStartEventDispatchesToWaitingProcessDefinition`, and
`signalEventReceivedResumesWaitingProcessInstance`. All three found real bugs, none of them visible
from compilation or the earlier happy-path test alone:

- **Event subscriptions were silently broken for every message/signal.** `createMessageEventSubscription()`/
  `createSignalEventSubscription()`/`createCompensateEventSubscription()` in
  `EventSubscriptionDataManagerImpl` constructed their entities via the no-arg constructor, which
  (confirmed via bytecode) never sets `eventType` — only the `EventSubscriptionServiceConfiguration`
  constructor overload does that, and that configuration isn't available at this call site. Every
  insert hit `process_event_subscription`'s `event_type NOT NULL` constraint. Fixed by setting
  `eventType` explicitly from each entity's own `EVENT_TYPE` constant right after construction.
- **Task claim/complete could throw a false optimistic-lock error.** `deleteActivityInstancesByProcessInstanceId`
  issued a bulk `DELETE` without evicting the affected rows from `ProcessSession`'s cache first. If
  an activity instance had already been read and mutated in-memory earlier in the same command (e.g.
  the completing user task's own instance, marked ended), its deferred flush-update would fire
  *after* this bulk delete had already removed the row, and 0-rows-affected looks identical to a
  real concurrent update. Fixed generally: `ProcessSession.evictAll(type, ids)` evicts every id a
  bulk delete is about to remove first — the same fix would apply to any other bulk-delete method
  that develops this pattern.
- **`RepositoryService.deleteDeployment()` crashed on `ACT_RE_MODEL` not existing.** The earlier
  "Model... genuinely unreachable" classification was wrong for this one path:
  `DeploymentEntityManagerImpl.deleteDeployment()` unconditionally calls `updateRelatedModels()`,
  which queries `ModelQuery.list()` regardless of whether modeling is enabled — the same shape of
  gap as `BpmnDeployer.createLocalizationValues()`'s unconditional `ACT_PROCDEF_INFO` lookup above.
  Nothing in ntrloc ever creates a Model, so `ModelDataManagerImpl` is a real read path (always
  empty) backing write paths that stay genuinely unreachable — wired via `config.setModelDataManager(...)`.

Two other leads from this pass turned out to be dead ends, worth recording so they aren't
re-investigated: disabling `setEnableExecutionRelationshipCounts`/`setEnableTaskRelationshipCounts`
(Flowable's related-entity-count cache) was tried as a fix for a signal subscription row that
appeared to survive process completion, and made no difference — reverted. The actual cause was a
test-fixture mistake: a BPMN `<signal>` element with no `flowable:scope` attribute is not globally
scoped by default (confirmed via bytecode: `SignalEventSubscriptionEntityImpl.isGlobalScoped()`
requires `configuration` to be absent or explicitly `"global"`), so the broadcast
`RuntimeService.signalEventReceived(name)` overload correctly skipped it — real Flowable behavior,
not a persistence bug. Once the fixture declared `flowable:scope="global"`, the subscription and its
owning execution were both cleaned up correctly with no additional code, confirming Flowable's own
normal-completion path (not the deployment-cascade path) already handles this.

`noFlowableDefaultTablesExist` was re-run after all three fixes, alongside the full module test
suite (53 tests) — the only remaining failure is the pre-existing `isEnded()` issue noted above.

### Branching/forking, live-verified (July 2026): two more zero-MyBatis gaps

Every prior verification pass (this section, above) exercised only single-token, non-branching
processes — nothing in ntrloc had ever run a process where one flow node has more than one outgoing
sequence flow until the BPMN editor gained Parallel Gateway/Call Activity/Sub-Process support
(Section 10). Actually running one surfaced two more real bugs in the custom persistence layer, same
category and same discovery method (invisible to compilation or static review, only found by
executing the path live) as everything else in this section — **not** Parallel-Gateway-specific in
either case, confirmed by reproducing both with a plain activity that just has two outgoing sequence
flows and no gateway at all:

- **Read-your-own-writes within a command.** `ParallelGatewayActivityBehavior.execute()` calls
  `execution.inactivate()` — an in-memory field set only — then immediately queries
  `findInactiveExecutionsByActivityIdAndProcessInstanceId` to count arrived branches, a raw SQL
  `SELECT ... WHERE is_active = FALSE`. Nothing had flushed that mutation to the database first:
  `ProcessSession.flush()` only ran once, at command-close, and nothing between the two calls
  triggered it. The query never saw its own write, the join-count target was never reached, and the
  gateway hung forever — silently, no exception, every single time, independent of the REV_-column
  concurrency race documented above (which was never even reached). Fixed: `session().flush()` added
  at the top of `ExecutionDataManagerImpl.findInactiveExecutionsByActivityIdAndProcessInstanceId` and
  its sibling `findInactiveExecutionsByProcessInstanceId` — cheap to call, since `ProcessSession.flush()`
  already skips any entity whose persistent state hasn't actually changed since first-seen.
- **`insert()` must be idempotent per entity per command.** Flowable's own
  `TakeOutgoingSequenceFlowsOperation.leaveFlowNode` (unmodified vendor code) calls
  `executionEntityManager.insert()` a second time on a child execution that
  `ExecutionEntityManagerImpl.createChildExecution()` already persisted internally moments earlier.
  Real Flowable's MyBatis-backed `DbSqlSession` silently no-ops a second `insert()` of an entity it's
  already tracking; this custom layer didn't, so the second call re-ran a raw SQL `INSERT` and
  collided on the primary key — any fork past the first branch failed outright. Fixed:
  `ExecutionDataManagerImpl.insert()` now checks `session().getCached(ExecutionEntity.class,
  entity.getId()) == entity` (object identity, not just id equality — the precise signature of "this
  exact object already went through insert() this command") and no-ops if so.

Both fixes verified live: an isolated Parallel Gateway split/join, a plain gateway-free multi-outgoing
fork, and a combined process (Parallel Gateway + Call Activity + collapsed Sub-Process + expanded
Sub-Process with real nested children, all in one diagram) each now run to `ended: true` with every
branch's own script task actually executing. Same implication as the rest of this section: **this
class of gap (a hand-rolled session not fully replicating `DbSqlSession`'s unit-of-work contract) is
specifically a concurrent/branching-execution risk, not a general one** — anything that introduces a
genuinely new execution-graph shape (Inclusive Gateway, multi-instance, boundary-event cancellation)
is a plausible place for the next instance of it, worth checking first if it "hangs with no exception"
rather than assuming it's something new.

### Cross-cluster process execution: framework and scope decisions (July 2026)

Follow-on design discussion, working out how "which node runs this" actually gets decided and
governed once ntrloc runs as more than one instance.

- **Hazelcast retained** as the clustering provider, not Ignite and not a new framework. Ignite is
  a distributed *database* (SQL, ACID transactions, its own data grid) — adopting it would create a
  second place data "lives," directly undermining the goal below. Hazelcast is naturally just a
  coordination/notification layer (maps, topics, locks) with no ambition to be a system of record,
  and it's already embedded (`domain-graph-starter`). Explicit governing principle: only adopt
  clustering machinery to the extent it adds real value (the concrete bar: eliminating DB polling),
  and keep it strictly encapsulated so it could be swapped later without disruption.
- **Event Registry explicitly ruled out**, at least for now. Confirmed it's 100% Flowable-specific,
  not part of the BPMN 2.0 spec — the spec defines *Message*/*Signal* as abstract concepts but
  deliberately leaves external delivery unspecified; the Event Registry (channels, event models,
  `SendEventServiceTask`) is Flowable's own proprietary answer, no more portable than any other
  vendor's equivalent (Camunda's External Tasks, etc.). Governing principle stated plainly: not here
  to support Flowable-specific capability. Distinguished from `flowable:async="true"`, which stays in
  bounds — it doesn't add proprietary *capability*, it's a vendor hook for *execution strategy* (which
  thread, which transaction), a question the spec is silent on for every engine, not just Flowable;
  there's no standard alternative to reach for instead.
- **Consequence, verified via bytecode**: BPMN message *throw* (a Send Task, or a message
  intermediate/end throw event) does not work in ntrloc today, and won't without reopening the Event
  Registry question. `SendEventTaskActivityBehavior` depends directly on `EventRegistry`/
  `EventRepositoryService`/`EventRegistryEngineConfiguration` (all disabled via
  `setDisableEventRegistry(true)`), and `ActivityBehaviorFactory` has no classic,
  registry-independent message-throw behavior at all — only the registry-dependent one. Message
  *receive* (`messageEventReceived`, `startProcessInstanceByMessage`) is unaffected; it's purely
  internal (`eventsubscription-service`) and already live-tested. If a use case needs to throw
  something, prefer signal (already fully verified end-to-end, no Event Registry involved) unless a
  concrete need for message's uniqueness semantics specifically arises.

### Component architecture and the encapsulation principle (July 2026)

- **Four components identified**, not three: **process execution** (start or resume via a whole
  synchronous Flowable command), **job acquisition/execution** (Flowable's `AsyncExecutor`), **task
  completion** (`TaskService.complete`), and **result notification** (telling a caller what happened
  once work resolves, possibly on a different node than the one it asked). The last one is easy to
  let get silently absorbed into whichever of the other three happens to trigger it; worth naming
  explicitly so it doesn't.
- **Governing principle**: the decision of *how*/*where* a given piece of work actually executes must
  be encapsulated behind one place, never duplicated across the four components or leaked to callers.
  This generalizes `ProcessManager`'s original "hide Layer 1/Layer 2 from callers" principle
  (Section 6, above) to job execution and task completion too — duplicating placement-decision logic
  across components is itself a form of leaking, even when none of it reaches an external caller.
- Deciding *where* something runs and telling a waiting caller *what happened* are kept as two
  distinct responsibilities, despite being tightly coupled — the placement decision determines *how*
  notification has to happen (in-process callback if local, cross-node pub/sub if remote), but
  "decide" and "notify" remain two different jobs.

### The two-lever execution model, ground-truth verified (July 2026)

Restates the four components above in terms of what actually governs which node does the work —
two levers, not four, once traced to their actual Flowable mechanics.

- **Lever 1 — whole-command dispatch.** All four of Flowable's "resume" API calls
  (`TaskService.complete`, `RuntimeService.trigger`, `signalEventReceived`, `messageEventReceived`)
  converge on one identical internal primitive, confirmed via bytecode across all four command
  classes: `FlowableEngineAgenda.planTriggerExecutionOperation`, scheduling a
  `TriggerExecutionOperation` on the current command's agenda. It runs synchronously, inseparably
  from whatever call triggered it — there is no separate "which node runs the
  `TriggerExecutionOperation`" question beyond "which node received the original call."
  `TaskService.complete()` has no async variant at all (every overload checked) and ntrloc's own
  code is provably its only caller — the cleanest, fully-guaranteed case, no exceptions. `trigger`/
  `signalEventReceived`/`messageEventReceived` each have an explicit sync and async pair; the async
  form is a genuinely different mechanism (see Lever 2) — the caller picks which lever applies by
  which overload it calls.
- **Lever 2 — job claim/execution.** Governed by Flowable's `AsyncExecutor`; see below for the
  control surface. Job *creation* itself is universal and diagram-position-agnostic, not tied to
  parallel gateways specifically — `ContinueProcessOperation`'s dispatch is a single
  `FlowNode.isAsynchronous()` check applied identically to any flow node. A parallel-gateway branch
  marked async is just one instance of this same rule, not a separate mechanism. Timers are
  unconditionally job-based regardless of any async marking — nothing can synchronously block a
  command waiting on a clock.
- **Flowable can internally bypass ntrloc's own dispatch layer via diagram constructs**, confirmed
  via bytecode in three concrete cases, all sharing the same shape and the same remedy:
  - **Signal throw** (`IntermediateThrowSignalEventActivityBehavior`) delivers directly via
    `EventSubscriptionUtil.eventReceived`, entirely inside whatever command is already executing —
    never through `RuntimeService.signalEventReceived()`. **Signal end events reuse the identical
    class** (`ActivityBehaviorFactory.createIntermediateThrowSignalEventActivityBehavior` takes a
    generic `ThrowEvent`, not an intermediate-specific type), so this covers both.
  - **Call Activities** (`CallActivityBehavior`) start their sub-process instance via
    `ExecutionEntityManager.createSubprocessInstance()` directly, bypassing `ProcessManager`
    entirely.
  - **Signal start events** can be triggered by this same internal throw path too, not just an
    external `dispatchSignal` call — confirmed directly from ntrloc's own
    `EventSubscriptionDataManagerImpl.findSignalEventSubscriptionsByEventName` query, which has no
    `execution_id`/`process_instance_id` filter and so matches a deploy-time start subscription
    exactly like a running catch.
  - **The remedy is the same in every case**: mark the element `flowable:async="true"`, turning the
    internal delivery into a real job (Lever 2, fully controllable) instead of an uncontrolled
    inline execution on whatever node happened to be running the throwing process. Two ways to make
    this a guarantee rather than diagram-author discipline, **not yet chosen between**: deploy-time
    validation/rejection (visible and honest — the diagram reflects what it actually does), or a
    custom `BpmnParseHandler` (a real, verified extension point —
    `ProcessEngineConfigurationImpl.setPreBpmnParseHandlers`/`setPostBpmnParseHandlers`) forcing it
    silently regardless of what the diagram author wrote (foolproof, but changes diagram behavior
    invisibly, cutting against the "visible and editable in the diagram" principle from Section 4).

### Job control: `AsyncExecutor`, ground-truth verified (July 2026)

- **Three tiers of control confirmed via bytecode**: (1) roughly 40 tuning knobs on the default
  `DefaultAsyncJobExecutor` (pool sizing, acquisition batching/wait times, lock durations, retry
  counts, reset-expired-jobs cadence); (2) component-level swaps without replacing the whole executor
  (`setAsyncExecutorExecuteAsyncRunnableFactory`, `setFailedJobCommandFactory`, custom
  `AsyncRunnableExecutionExceptionHandler`s, a custom `TaskExecutor`); (3) full replacement via
  `JobServiceConfiguration.setAsyncExecutor(AsyncExecutor)` — a clean, small interface
  (`start()`/`shutdown()`/`executeAsyncJob(JobInfo)`), and the seam already exists in ntrloc's own
  `JobServiceConfigurator`.
- **The mechanism this design actually needs**: acquisition and execution are independently
  toggleable. `setAsyncExecutorAsyncJobAcquisitionEnabled(false)` stops a node's own blind polling
  race for jobs while leaving it able to execute a specific job the instant
  `ManagementService.executeJob(jobId)` is called on it (a real, verified API) — replacing Flowable's
  undirected any-node-claims-it default with deliberate routing, while still relying entirely on
  Flowable's own proven optimistic-locking claim underneath, so even a race between a targeted push
  and a (disabled, but theoretically still-possible) default poll resolves safely via the same
  machinery already verified correct (Section 6, above).
- **Job creation** (via `JobDataManager.insert()`, code ntrloc already owns) is a legitimate
  interception point for a load-aware "should this be pushed to a specific node" decision — but the
  claim columns (`lock_owner`/`lock_expiration_time`) can't be pre-set as a routing hint:
  `AcquireJobsCmd` only polls rows where `lock_expiration_time IS NULL`, so pre-claiming would hide
  the job from *everyone's* poll, including the intended target. The correct mechanism is a separate,
  out-of-band push (Hazelcast `IExecutorService`) triggering `executeJob(jobId)` on the chosen node
  afterward, not a DB-column trick.

**Addendum: activated for a single-node demo (July 2026), one open item not yet resolved.** Turned
on via the simplest tier-1 knob, `setAsyncExecutorActivate(true)` — a node polls, claims, and
executes its own due jobs, not yet the acquisition-disabled/targeted-push scheme designed above
(that's still future work, and still the right design for real multi-node routing when it's
needed). Motivated by a concrete, minor thing surfacing it: a Timer Start Event process
(`tradeProductExport`) deployed with a repeating cycle and nothing fired automatically, since
nothing had ever turned this on before — confirmed live (manually running the process worked and
its script task's output reached the log; waiting past the timer's due date without the executor
running produced nothing further).

Everything above in this section about Flowable's *own* claim safety (`AcquireJobsCmd`'s
optimistic-locking `UPDATE`, "Flowable already handles this conflict for us") describes Flowable's
real, bytecode-verified default behavior *when backed by its own MyBatis persistence* — it does not
by itself establish that ntrloc's custom `JobDataManager`/`TimerJobDataManager` reproduce the same
guarantee, and re-reading them while chasing the above turned up a reason to doubt it rather than
assume it: `TimerJobDataManagerImpl.bulkUpdateJobLockWithoutRevisionCheck` — the method
`AcquireTimerJobsCmd` actually calls to lock a batch of due jobs — issues a plain `UPDATE
process_job SET lock_owner = ..., lock_expiration_time = ... WHERE id = :id` per job, with no
`lock_owner IS NULL` guard and no revision check in that SQL itself (its own name says as much).
That's a fact about the code as written, confirmed by reading it directly — **not yet re-verified
via bytecode/live-testing the way the rest of this section's claims are**, so it's recorded here as
an open item rather than a settled correction: it's not established whether Flowable's surrounding
command structure (e.g. something upstream of this call already serializing access, or a later
revision-checked flush elsewhere in the same command) closes the gap this method's SQL alone leaves
open, the way it might for the generic entity-update path (Section 6's "Zero-MyBatis, implemented"
Job/Timer bullet, corrected above to point here). **Doesn't matter for today's single-node
deployment** — nothing else is racing to lock the same row — but this is exactly the kind of gap
"cluster-wide processing, tabled for later" would need to close (with the same
`javap -c`-against-the-real-jar rigor used everywhere else in this section) before trusting this
against genuine multiple nodes, not just re-flipping the same activation switch.

**A second, load-bearing bug was found and fixed while chasing this — first real exercise of the
custom Job DataManagers turned up something the design missed, not just the open item above.**
Live-testing (a short-cycle timer, watching for its script task's log line with no manual trigger)
initially showed the timer *never* firing: every `AcquireTimerJobsRunnable` poll cycle logged
`duplicate key value violates unique constraint "process_job_pkey"`. Root cause, confirmed by
reading `flowable-job-service-8.0.0-sources.jar` directly: `DefaultJobManager.moveTimerJobToExecutableJob`
(and every sibling `move*` method — timer→executable, job→timer, job→suspended, job→dead-letter)
implements a job-kind transition as *insert the new kind, then delete the old kind*, deliberately
reusing the same id across the two (`copyJobInfo`: `copyToJob.setId(copyFromJob.getId())`) — safe
in stock Flowable because each kind lives in its own MyBatis-managed table, so the same id can
transiently exist in both. `process_job` (`ProcessPersistenceInitializer`) intentionally shares one
table across all six kinds via a `job_kind` discriminator, with its own comment stating a kind
change was meant to be "a plain UPDATE of job_kind" — but `AbstractJobDataManager.insert()` was a
bare `INSERT`, so Flowable's insert-first call collided with the still-present old-kind row on the
`id` primary key, every cycle, and the timer job could never be promoted. Fixed by making `insert()`
an upsert (`ON CONFLICT (id) DO UPDATE`, restoring the table's own originally-intended semantics)
and scoping `delete()` to the calling manager's own `job_kind`, so the old kind's follow-up delete
can't remove the row a concurrent move just upserted to the new kind. Re-verified live after the
fix: same timer, same log-watching method, zero `process_job_pkey` errors, script task output
appeared unprompted at the timer's actual due time. This is now genuinely exercised and working for
the single-node case; it hadn't been caught earlier only because the async executor — the one thing
that ever calls these `move*` paths — had never been turned on before this addendum.

**A third bug, same root cause (a query path assumed unused, actually load-bearing once async
continuations exist), found chasing the principal-propagation work below.**
`VariableInstanceDataManagerImpl.findVariablesInstancesByQuery`/`findVariablesInstanceByQuery` (the
`InternalVariableInstanceQuery` path) were unconditionally stubbed to return empty/null, under the
same "no user tasks, not CMMN" reasoning that correctly applies to the *other* stubs in that class.
It doesn't apply here: `ExecutionEntityImpl.loadVariableInstances()` and `getSpecificVariable()` —
what backs every `execution.getVariable(...)` call not already served by the current command's
in-memory cache — call exactly this path via `VariableService`'s default
`findVariableInstancesByExecutionId`. That in-memory cache covers a variable read in the same
transaction it was set in, which is all prior manual testing had ever exercised; it does *not* cover
a read after any async continuation (timer, async job, external worker) resuming in a fresh command
context, since the cache doesn't survive across those. Confirmed live: a process with a two-second
intermediate timer, reading a variable in a script task before vs. after the wait — before the fix,
`execution.getVariable(...)` returned `null` post-timer for every variable, silently, no error.
Fixed by implementing the query for real (translate `executionId`/`executionIds`/`processInstanceId`/
`id`/`name`/`names` into a `SELECT ... FROM process_variable WHERE ...`, matching the
`whereClause`-builder-plus-`cacheOrMap` pattern already used by `ProcessDefinitionDataManagerImpl`),
with `task_id`/`scope_id`/`sub_scope_id`/`scope_type` criteria — columns that don't exist in
`process_variable` at all — still handled correctly by delegating final filtering to
`InternalVariableInstanceQueryImpl.isRetained(entity, query)`, the same predicate object Flowable's
own cache-matching path already trusts: since a loaded entity never has those fields set, a query
*requiring* one of them correctly excludes everything, and a query requiring its absence
(`withoutTaskId()`, `withoutSubScopeId()`) correctly keeps everything, with no bespoke SQL needed for
task/CMMN-scope support this app doesn't use. Re-verified live after the fix: the same two-second-
timer process now reads its variable correctly on both sides of the wait.

### Principal propagation into processes, ground-truth verified (July 2026)

A process that needs to run a projection (`EntityManager.project(...)`, Section on `EntityManager`
consolidation below) needs an `NtrlocPrincipal` to run it as. `ProcessAdminController.startProcessInstance`
resolves the caller's principal once, up front (via `PrincipalResolver.resolve`, itself fast-pathed
off `Authentication.getPrincipal()` when the session already carries one — `NtrlocUserDetails` for
local-credential logins, the PAT-issued principal for token auth), then hands the whole object to a
script task as a single process variable (`"principal"`) — not just an externalId a script would
otherwise have to re-resolve against `SecurityRepository` on every call site.

This is backed by a real custom Flowable `VariableType` (`NtrlocPrincipalVariableType`, registered via
`setCustomPreVariableTypes` in `ProcessEngineConfig` — *pre*, not *post*, so it's tried before any of
Flowable's ~20 built-in types, though moot today since `ResolvedPrincipal` isn't `Serializable` and
so was never actually at risk of being silently swallowed by `SerializableType`), serializing to JSON
into the same `text_value` `TEXT` column every other variable type already persists through — no
schema change, and confirms the earlier "no blob/JSON column" constraint (Section on principal
threading) was about there being no *purpose-built* column, not that `TEXT` can't hold JSON.

**A real security bug was found and fixed via live verification before this shipped.** First
implementation's `setValue(Object value, ...)` serialized `value` exactly as handed in. Live-tested
via a script dumping the raw JSON: the object `PrincipalResolver`'s fast path actually hands out for
a local-credential session is `NtrlocUserDetails`, which — because it also implements Spring
Security's `UserDetails`, for the unrelated reason of doubling as the object `Authentication.getPrincipal()`
carries — exposes `getPassword()`/`getAuthorities()`/`isEnabled()`/etc. as ordinary bean-getter-shaped
methods. Plain Jackson reflection on that concrete runtime class serialized all of it, including the
real bcrypt password hash, straight into `process_variable.text_value` — confirmed by querying the
row directly mid-flight (a live process parked on a timer wait state) before the fix, and finding
`"password":"{bcrypt}$2a$10$..."` sitting in the database. Fixed by having `setValue` always
re-wrap the incoming value into a bare `ResolvedPrincipal` — built from the five `NtrlocPrincipal`
interface accessors only, never trusting whatever extra fields the concrete implementation happens to
carry — before serializing. Re-verified live after the fix, querying the same row mid-flight: only
`id`/`externalId`/`displayName`/`groupIds`/`isSuperuser` present. `NtrlocPrincipal.getName()` (the
`java.security.Principal` default method) is separately `@JsonIgnore`d, since its `get`-prefixed shape
would otherwise round-trip as a spurious `"name"` field no record component can absorb on read.

`PrincipalResolver.resolveByExternalId` (the DB-lookup-by-externalId method this design was meant to
replace) and its `@ProcessAccessible` marking were removed as dead weight once the single-variable
design made them unnecessary — a script now reads a fully-resolved principal straight off the
variable, no bean call needed.

### Process script unqualified class names, ground-truth verified (July 2026)

`ntrloc.process.script.import-packages` (`application.yml`) lets a process script (Groovy or
JavaScript) reference an ntrloc class by simple name — `new SingleItemProjectionSpec(...)` instead
of the fully-qualified form — without hardcoding a per-class list. The two languages needed
genuinely different mechanisms, confirmed by reading each engine's actual resolution code rather
than assumed:

- **Groovy** (`groovy-jsr223:5.0.2`): `new X(...)` is resolved by the *compiler*, at compile time,
  against the `GroovyClassLoader`'s import list — the JSR-223 `Bindings` map (what
  `ResolverFactory`/`@ProcessAccessible` populate) has no influence over this at all, confirmed by
  tracing the real call path (`ScriptingEngines.evaluate()` → `scriptEngine.eval(script, bindings)`,
  `JSR223FlowableScriptEngine.java:200`). The fix is `ImportCustomizer.addStarImports(packages)` —
  the programmatic equivalent of `import pkg.*` — on a `CompilerConfiguration` used to build a
  custom `GroovyClassLoader`, wrapped in a `ScriptEngineFactory` subclass
  (`ImportingGroovyScriptEngineFactory`) whose `getScriptEngine()` returns
  `new GroovyScriptEngineImpl(thatClassLoader)`.
- **JavaScript** (`nashorn-core:15.7`, not JDK-bundled — removed from the JDK itself at 15):
  Nashorn's own `JavaImporter` (`NativeJavaImporter.createProperty`, read directly) does exactly
  the same lazy `pkgName + "." + name` / `Context.findClass(...)` resolution Groovy's star-import
  does, but only inside a `with (new JavaImporter(...)) { ... }` block — nothing a script author
  would write unprompted. Made transparent by wrapping each JavaScript script's source server-side
  (`ImportingFlowableScriptEngine`, a `FlowableScriptEngine` decorator intercepting
  `FlowableScriptEvaluationRequest.script(String)`) before it reaches `eval()`. `with` only shadows
  lookups that match the importer's own resolved packages, so `execution`/`entityManager`/etc. and
  Nashorn's own `Packages`/`Java` globals fall through unaffected — verified live, not assumed.

**Registration bug found and fixed via a wrong assumption about JSR-223 lookup keys.**
`JSR223FlowableScriptEngine.addScriptEngineFactory(factory)` registers under
`factory.getEngineName()` — for Groovy's own factory class, `"Groovy Scripting Engine"`, not
`"groovy"` (confirmed by reading `GroovyScriptEngineFactory.java` directly: `SHORT_NAME = "groovy"`
is a *different* field, used for `getNames()`, never for `getEngineName()`). Using that convenience
method to register the custom factory silently registered it under a key nothing ever looks up,
so `getEngineByName("groovy")` (Flowable's actual lookup, `request.getLanguage()`) kept falling
through to the auto-discovered stock factory — no error, just silently no effect. Fixed by calling
`flowableScriptEngine.getScriptEngineManager().registerEngineName("groovy", customFactory)`
directly, keyed exactly to what `scriptFormat="groovy"` looks up.

**A second, unrelated bug found the same way — proven via an isolated reproduction, not
guessed.** Registering the custom Groovy factory correctly still didn't work: `new
SingleItemProjectionSpec(...)` kept failing to resolve. An isolated two-file reproduction (a bare
`ScriptEngineManager` + the same factory/classloader code, no Spring or Flowable involved) proved
the core JSR-223 mechanism itself was correct. The actual cause was `ProcessScriptProperties`
binding to an empty list every time despite `application.yml` having real values — this codebase's
other record-shaped `@ConfigurationProperties` class (`UiHostingProperties`) is registered via
`@EnableConfigurationProperties`, while `ProcessScriptProperties` had instead copied the bare
`@Component` self-registration style of `SecurityProperties` (a *plain class*, with setters) — a
record has no default constructor for ordinary component-scan instantiation to call before the
`ConfigurationPropertiesBindingPostProcessor` gets a turn, so constructor-binding needs the
explicit `@EnableConfigurationProperties` path instead. Fixed by moving that annotation onto
`ProcessEngineConfig` (its only consumer). Re-verified live after both fixes: unqualified
`new SingleItemProjectionSpec(...)` resolved to the correct class in both a Groovy and a
JavaScript script task; a deliberately-constructed two-package collision (two throwaway classes
both named `Widget`, one per package) failed fast at `ProcessScriptEngineFactory.build()` with a
clear message, while the real configured package pair built cleanly.

### Run-as-user for processes with no HTTP caller (July 2026)

A timer/signal/message start event never goes through `ProcessAdminController` — Flowable's own
internal job machinery starts the process instance directly — so nothing was ever setting the
`principal` variable (Principal propagation, above) for a process triggered that way. A script
task in a timer-started process needing `entityManager.project(...)` had no principal to use.

**Declaration**: a process author sets a new "Run As User" field in the process editor's Process
panel (nothing selected) — an external ID, stored as `flowable:runAsUser` on `<process>`, the same
raw `$attrs` passthrough `renderUserTaskFields`'s `candidateUsers`/`candidateGroups` already relies
on (bpmn-moddle has no Flowable extension schema loaded). Deliberately **no implicit fallback** —
if a process declares nothing, it starts with no principal at all rather than silently defaulting
to some engine-wide "system" user nobody configured; ntrloc prefers an explicit per-process
statement over an assumed default.

**Injection**: `ProcessRunAsUserListener`, a `FlowableEventListener` registered the same way
`TaskEventListener` already is (`config.setEventListeners(...)`), listening for `PROCESS_STARTED`
— deliberately not `PROCESS_CREATED`. Confirmed by reading `ProcessInstanceHelper` directly:
`PROCESS_CREATED` fires *before* the caller-supplied variables map is applied to the new instance,
so it can't even see a real HTTP caller's `principal` to avoid clobbering it; `PROCESS_STARTED`
fires after, so `event.getVariables().containsKey("principal")` reliably distinguishes "a real
caller already supplied one" from "nothing has, yet" with no separate trigger-type tracking needed.
If a principal is already present, the listener does nothing — a caller-started run always wins,
even against a *different* declared `runAsUser`, regardless of whether that declared value would
have resolved at all. If nothing declares one and nothing already supplied one, the process simply
starts with no principal. If one is declared but doesn't resolve to a real user,
`isFailOnException()` is `true` (unlike `TaskEventListener`'s best-effort `false`) — the whole
process start fails loudly rather than silently proceeding with a misconfiguration.

A real gap found in the middle of building this: `getVariables()` is `null`, not just missing the
key, for the extremely common case of a start with no variables supplied at all —
`startProcessInstanceByKey(key)` with no map, exactly what a timer/signal/message start actually
does — caught immediately by the existing test suite (4 failures) once wired in, before it ever
reached a live process.

Re-verified live across all three cases: a timer-started process with `runAsUser="admin"` declared
picked it up correctly (`displayName=Local Admin externalId=admin`) with zero HTTP caller
involved; the same process started over HTTP as `admin` while declaring a *different* (in fact
nonexistent) `runAsUser` still resolved to the real caller, proving the declared value is never
even consulted once a caller's principal is already present; and a timer-started process declaring
an unresolvable `runAsUser` failed the start with a clear `IllegalStateException` — confirmed by
the fact that the script task's own log line never printed, not just by the presence of an error.

`PrincipalResolver.resolveByExternalId` — removed earlier this session as dead weight once the
principal-as-single-variable design made the *original* HTTP-facing use of it unnecessary — is
back, for this new, genuinely different, purely-internal caller (not process-accessible, no HTTP
context to attach a 401 to). `ProcessRunAsUserListener`'s own `RepositoryService` dependency needed
`@Lazy`: it's wired into `ProcessEngineConfig.processEngineConfiguration(...)` itself via
`setEventListeners(...)`, but `RepositoryService` is `processEngine.getRepositoryService()`, and
`ProcessEngine` is built *from* that same configuration bean — a real circular dependency without
deferring resolution until first actual use.

### Ledger actor attribution + per-property edit history (July 2026)

The ledger (Storage partitions: ledger and register, above) had `created_at` per entry but no
notion of *who* made a change at all — `ItemUpdateEntry`/`ItemCreateEntry` are pure "what
changed" diffs, and nothing in the write path (`MutationController` → `EntityManager.mutate()` →
`MutationRequestProcessor` → `LedgerRegisterCoordinator.prepare()` → `LedgerPartitionManager.append()`)
ever threaded a caller through. Fixed by adding a nullable `actor_external_id` column to
`ledger_entry` (one value per `append()` batch, not per entry — everything in one `mutate()` call
shares one actor) and threading an `NtrlocPrincipal` down that whole chain, mirroring how
`project()` already threads one through for read permission checks — except this one is
`@Nullable` throughout: unlike `project()`'s principal (a hard permission-check requirement),
this is attribution-only (mutations still don't enforce write permissions, `EntityManager`'s own
documented gap), so an unresolvable/absent principal is a real, displayable state ("Edited by"
blank), never a reason to refuse the mutation.

Three real callers needed three different resolution paths, each verified live end-to-end
(create + two updates + a read of `LedgerPropertyHistoryService.history()`, confirming both the
correct actor and correct most-recent-first ordering each time):

- **REST** (`MutationController`): resolves via `PrincipalResolver.resolve(request, authentication)`
  — the same call `ProcessAdminController.startProcessInstance` already makes.
- **Process scripts**: already have a real principal on hand (Principal propagation, above) — a
  script just passes it straight through, `entityManager.mutate(request, principal)`.
- **MCP** (`MutationService.executeMutation`): confirmed live with a real `ntrloc_pat_...` bearer
  token (the same shape `mcp-remote` sends, per the client config that prompted this work) driven
  by hand over the SSE transport (`initialize` → `notifications/initialized` → `tools/call`) — not
  simulated. Getting there needed two things verified by reading Spring AI's own source, not
  assumed:
  - MCP requests already flow through the normal Spring Security filter chain (nothing in
    `SecurityConfig` exempts `/mcp`/`/sse`), but Spring AI's own `McpTransportContext` per request
    is `McpTransportContext.EMPTY` unless the app supplies a `McpTransportContextExtractor` — none
    was, so nothing an `@McpTool` method could read ever saw the `Authorization` header.
  - **SSE, not Streamable HTTP, is what's actually active by default** in this app: confirmed by
    reading `EnabledSseServerCondition`/`EnabledStreamableServerCondition` directly — SSE's own
    `protocol` property check is `matchIfMissing=true`, Streamable's is `matchIfMissing=false`, and
    ntrloc's `application.yml` never sets `spring.ai.mcp.server.protocol` — even though
    `McpServerSseWebFluxAutoConfiguration` is itself `@Deprecated(forRemoval=true)` upstream.
  
  `McpStreamableTransportContextConfig`/`McpSseTransportContextConfig` each override their
  target's transport-provider bean (`@ConditionalOnMissingBean`, exactly for this) to add a
  `contextExtractor` capturing the raw `Authorization` header — only the raw header, not a
  resolved principal, since this runs synchronously inside a WebFlux route handler and resolving a
  PAT is a blocking JDBC call. Two hard-won, non-obvious fixes along the way:
  - Both conditions (`AllNestedConditions(ConfigurationPhase.PARSE_CONFIGURATION)`) only evaluate
    correctly as *class-level* `@Conditional`s — applying them at the `@Bean` *method* level
    (tried first, one shared config class) let both transport-provider beans get created
    simultaneously, `NoUniqueBeanDefinitionException` for `McpServerTransportProviderBase`
    downstream. Fixed by splitting into two separate `@Configuration` classes, each conditional at
    the class level exactly like Spring AI's own two autoconfiguration classes.
  - `McpServerSseWebFluxAutoConfiguration`'s own `@ConditionalOnMissingBean(McpServerTransportProvider.class)`
    sits at the *class* level, not just on its transport-provider bean — so once
    `McpSseTransportContextConfig`'s replacement bean exists, Spring skips that whole original
    class, including the router-function bean that actually mounts `/sse` and `/mcp/message`.
    Found live: `/sse` 404'd despite "Registered tools: 8" showing the MCP server itself built
    fine. Fixed by also defining that router-function bean in `McpSseTransportContextConfig`
    itself. Streamable's own autoconfiguration doesn't have this problem (no class-level
    `@ConditionalOnMissingBean` there), so `McpStreamableTransportContextConfig` doesn't need the
    equivalent — confirmed by reading, not guessed by symmetry.

  `MutationService.executeMutation` takes an injected `McpSyncRequestContext` parameter (Spring AI
  auto-injects it for any `@McpTool` method that declares one, confirmed by reading
  `AbstractMcpToolMethodCallback.buildMethodArguments` directly), reads the captured header back
  out of `requestContext.transportContext()`, and resolves it via
  `PersonalAccessTokenService.authenticate(rawToken)` — the exact same resolution
  `SecurityConfig`'s own PAT bearer-auth filter uses, just invoked directly rather than through
  that filter, since MCP tool invocation sits outside the normal HTTP request/response cycle it
  runs in.

`LedgerPropertyHistoryService` (top-level, alongside `EntityManager`, not inside the `ledger`
package — same reasoning as `EntityManagerImpl`: it crosses the ledger/security boundary
`LedgerRegisterCoordinator`'s own "only component permitted to import both ledger and register"
rule was never meant to extend to) filters `LedgerPartitionManager.readItemStreamWithMetadata`
(new — `readItemStream`'s existing callers never needed the row metadata `readItemStream` itself
discards) down to entries touching one property, resolving each entry's `actor_external_id` to a
display name best-effort at read time (never stored — reflects whoever holds that identity now,
same as any other "who did this" attribution), exposed via
`GET /api/admin/items/{itemId}/properties/{propertyId}/history`.

No client in this repo yet -- a `ntrloc-search.js` UI on top of the history endpoint (a
per-property table with a history icon, replacing the pane's raw `JSON.stringify` results dump)
was built and verified live, then explicitly reverted: not needed for this admin UI, the user's
own separate POC project will consume the endpoint directly instead. The endpoint itself
(`LedgerPropertyHistoryService`/`LedgerPropertyHistoryController`) and everything upstream of it
(actor attribution end to end) stayed -- only the admin-UI display layer was rolled back.

### Result notification design: `FlowableEventListener` + advisory pub/sub (July 2026)

- **`FlowableEventListener`/`FlowableEngineEventType`** verified as the mechanism for observing
  lifecycle points, with real event types confirmed for every example asked about: parallel-branch
  start = `ACTIVITY_STARTED`, branch/join completion = `ACTIVITY_COMPLETED`/
  `MULTI_INSTANCE_ACTIVITY_COMPLETED`, task completion = `TASK_COMPLETED` — the last one not
  hypothetical, already proven live by the existing `TaskEventListener` (feeds the `/tasks/events`
  SSE stream today).
- The dispatcher is **per-engine-instance** — local to whichever node's engine performed the
  transition, synchronous by default — but also supports firing only after a specific
  **transaction-lifecycle event** (`isFireOnTransactionLifecycleEvent`/`getOnTransaction`, e.g.
  `COMMITTED`, confirmed via bytecode; `AbstractFlowableEventListener` defaults to inline/synchronous
  unless this is set). This is the safe mode for any listener that publishes to the cluster — it
  guarantees a remote node only ever hears about durably-committed state, never a transaction that
  might still roll back.
- **Governing invariant for the whole notification layer**, matching `TaskEventListener`'s existing
  `isFailOnException()=false` precedent: Postgres is the only source of truth; the cluster pub/sub
  layer is advisory only — a "doorbell, not a ledger." A node writes state to Postgres first, *then*
  rings the doorbell; nobody trusts the message payload itself as fact, everyone re-checks the DB. A
  coarse poll stays as the fallback net regardless of listener reliability, so the system degrades to
  slower under cluster failure, never wrong. Not yet built — this is the design for Layer 2's
  still-open join-wait mechanism (Section 9), now with a concrete implementation plan rather than an
  open question.

### Hazelcast, consolidated into `domain-graph-experimental` (implemented, July 2026)

- `domain-graph-starter` is being retired in favor of `domain-graph-experimental`; its
  `org.ntrloc.graph.cluster` package (`ClusterService`/`ClusterServiceImpl`, the four join-strategy
  factories, `AbstractClusterConfiguration`, `ClusterAutoConfiguration`) was lifted into
  `domain-graph-experimental` verbatim, under the same package, plus `hazelcast`/`hazelcast-spring`
  5.5.0 added to its `pom.xml`. `domain-graph-starter` itself was left untouched rather than
  surgically fixed — it's on its way out, not worth more engineering investment — after confirming
  no real consumer outside it depends on the package (`runtimes/domain-2` is an empty scaffold
  module with no source at all; `pmdm-update-poc` doesn't reference it). Its other two internal
  consumers, `GatewayRegistrar` and `GraphQLSchemaRegistrar`, were judged irrelevant and not
  replicated. There is now no dependency relationship between the two modules in either direction.
- `ClusterService` gained one new method, `getTopic(String)`.
- **`SchemaManager` is the first real consumer**, and the first live proof of the notify-then-verify
  pattern above: it publishes to a `schemaChanged` `ITopic` after every `applyMutations()` call, and
  every node's own `SchemaManager` — including the publisher, filtered out by comparing
  `Message.getPublishingMember()` against `getLocalMember()` — rebuilds its cache on receipt. Two
  deliberate corrections versus the old `domain-graph-starter` version: an `ITopic` instead of the
  `IMap.put()`-as-a-side-channel trick the old code used (its own comment already flagged that as a
  hack that should really be a topic), and the remote-receipt handler now actually calls
  `rebuildCache()` — the old version fired local `reactions` but never refreshed its own cache on a
  remote change, a real latent bug, not just a style fix.
- Live-verified: full module test suite (62 tests, including the new `ClusterServiceTest`) green with
  Hazelcast now part of every integration test's boot path.
- **Side effect of this pass**: exercising `helloWorldProcessRunsToCompletion` end-to-end (claim +
  complete its user task, rather than asserting synchronous completion the diagram no longer provides)
  surfaced a real, previously-latent bug — process variables were never cleaned up on normal
  completion. Same shape as the already-documented "participant" identity-link gap: real Flowable
  relies on a DB-level FK (`ACT_RU_VARIABLE` → `ACT_RU_EXECUTION`) this schema deliberately doesn't
  have, and `deleteVariables` (confirmed via bytecode, called only from `deleteRelatedDataForExecution`)
  is only reached via the explicit-cascade delete path, never normal completion. Fixed with a matching
  cascade-delete in `ExecutionDataManagerImpl.delete()`.

### `ProcessExecutor` and load distribution — working notes, checkpointed mid-thought (July 2026)

Follow-on discussion, deliberately more tentative than the sections above — working through
implications as they surface rather than a settled design. Recorded here so the thread isn't lost,
not because every open question below has an answer yet.

**`ProcessExecutor`'s role, reconnected to everything settled since it was tabled.** It's the single
placement-decision point the encapsulation principle (above) requires — for *both* levers, not one
decision-maker each. For Lever 1 it takes a small serializable command (not a closure — a captured
closure over live Flowable beans can't cross the wire to another node) and returns a `Mono<T>`, either
by calling locally or by having the chosen node's own `ProcessExecutor` decode the command and make
the same call on itself. For Lever 2 the payload is simpler — just a job id, nothing to serialize
beyond a string. It composes with, rather than owns, the result-notification mechanism (Section 6,
above) — deciding *where* and reporting *what happened* stay separate responsibilities. Resolves the
earlier-open question of whether `TaskManager` shares it: yes, since "one place for everything
distributable" is the whole point of the principle. Still open: whether it's one class or decomposes
into a command-dispatcher plus a job-router sharing the same decision logic.

**Targeted assignment vs. whiteboard/claim, leaning toward a hybrid.** Targeted assignment (dispatcher
picks a specific node) needs the dispatcher to know remote capacity, which means broadcast/heartbeat
machinery with real staleness risk. Whiteboard/claim (post work, let nodes self-select) sidesteps that
by having each node judge its own local, zero-staleness state — and Lever 2 already works exactly this
way (`AcquireJobsCmd` is, in substance, already a form of distributed work stealing). Pure poll-based
claiming would reintroduce the latency floor the notification design was built specifically to avoid,
though — Lever 1 dispatch is explicitly latency-critical (Section 6). Proposed resolution: post to a
claimable spot, then push a lightweight wake-up via the same `ITopic` mechanism so eligible nodes act
immediately rather than waiting for a poll tick — the notification *is* the trigger, not just an
announcement after the fact. This would make "post work" and "report a result" the same primitive used
in both directions.

**Single-node fast path.** When `ClusterService.getClusterMembers().size() == 1`, skip all of the
above entirely — no load check, no serializable-command requirement (a plain closure is fine when
nothing crosses the wire), no queue post, no topic subscription — just call local Flowable directly.
Checked live at dispatch time, not cached, so it activates/deactivates correctly as membership actually
changes. A pure performance optimization, not a correctness gate: staleness in cluster membership can
only cause a missed distribution opportunity or harmless self-talk, never a wrong answer.

**Correction: Hazelcast's CP Subsystem (`FencedLock`, `IAtomicLong`, etc.) is Enterprise-only,
confirmed via bytecode against the exact 5.5.0 jar this app depends on** — `CPSubsystemStubImpl`
throws `UnsupportedOperationException("CP subsystem is a licensed feature...")` on every method. An
earlier point in this same discussion described `FencedLock` as an available-but-unused primitive;
that was wrong for the OSS edition actually in use, and is corrected here. Nothing currently designed
depends on CP — the claim mechanism uses Postgres's own optimistic locking, notification uses `ITopic`,
dispatch uses `IExecutorService`, leader election was already documented as a naive non-quorum scheme
— all regular (AP) Hazelcast structures. `IMap.lock(key)`/`tryLock`/`unlock` *are* real and available
(not CP-gated), but deliberately not reached for here: using a Hazelcast lock as the actual claim
safety mechanism would make Hazelcast correctness-critical for the first time, breaking the
"Postgres is truth, Hazelcast is advisory" invariant (Section 6, above) for no real benefit over the
DB-level conditional update already built and proven.

**Correction: `ManagementService.executeJob(jobId)` performs no atomic claim of its own**, confirmed
via bytecode all the way through `ExecuteJobCmd` → `DefaultJobManager.execute()` →
`executeMessageJob()`/`executeTimerJob()` → `executeJobHandler()` — a plain lookup and direct
execution, no `lock_owner`/`lock_expiration_time` check anywhere in the path. An earlier point in this
discussion claimed a targeted push "still goes through Flowable's own optimistic-locking claim
internally"; that was asserted without checking and is wrong. Consequence: whatever orchestration layer
decides to call `executeJob` must perform its own atomic claim first (the same `revision`/`lock_owner`
pattern already built into `JobDataManager`) — `executeJob` is a dumb "run this now" trigger for a job
already safely won, not a safe claim-and-run primitive in its own right. This isn't just a safety
nicety layered on top of something already safe; it's the *only* safety that exists on this path.

**Why disabling Flowable's own acquisition matters, restated precisely**: not a correctness concern
(Flowable's own claim/retry machinery is already trusted) but a load-*concentration* concern —
`AcquireJobsCmd` claims a batch per poll cycle (`getAsyncExecutorMaxAsyncJobsDuePerAcquisition`), so a
node that happens to poll first or more often can out-compete a less-active node for available work.
Flowable's mechanism guarantees safety, never fairness. Disabling acquisition cluster-wide and routing
everything through one's own layer turns load spreading from an accident of poll timing into a
deliberate, controllable choice.

**Load signal: round-robin vs. least-loaded, and why "just count claimed jobs" isn't good enough.**
A free, zero-new-infrastructure least-loaded signal for Lever 2 exists in principle —
`SELECT lock_owner, COUNT(*) FROM process_job WHERE lock_owner IS NOT NULL GROUP BY lock_owner`, reusing
data the claim mechanism needs anyway — but it doesn't hold up once nodes have heterogeneous hardware
and are also carrying volatile, unrelated load (mutations, projections, etc.). Two nodes with equal job
counts aren't equally loaded if their hardware differs; a node with zero claimed jobs isn't necessarily
free if it's saturated with other work. A signal that actually reflects true capacity (normalized CPU
load average, thread-pool/executor queue depth) only exists locally, in-memory, per node — getting it
to a *dispatcher* elsewhere reopens the broadcast/staleness problem. This is a second, independent
argument (beyond the notification-latency one above) for self-selection over targeted assignment: a
node judging its own true, current capacity locally is strictly better-informed than any dispatcher
could be about a heterogeneous, volatile cluster. Doesn't yet resolve which specific local signal to
check, or whether Lever 1 needs an equivalent at all (it has no free, Postgres-derived count the way
jobs do, since its work is short-lived and synchronous rather than claimed-and-lingering).

**FIFO/submission-order priority is compatible with self-selection, not in tension with it.**
Self-selection governs *whether and when* a node looks for work; an `ORDER BY create_time ASC` claim
query governs *what* it picks once it looks. Concurrent claim losers naturally fall through to the
next-oldest available row, so ordering survives contention without extra machinery.

**Crash recovery — real, but asymmetric across the two levers, and one piece is a known, named gap.**
Lever 1 is mostly covered for free by Flowable's own transaction boundary: a crash before commit means
nothing was persisted (as if the call never happened); a crash after commit but before the caller
learns about it is a notification problem already covered by the poll-fallback, not an execution
problem. A genuinely orphaned *claim* (a node dies after claiming a queue row but before finishing) is
the real gap, and needs our own equivalent of Flowable's `ResetExpiredJobsRunnable` — a periodic sweep
releasing claims held past some expiration, since we bypass Flowable's own version entirely.
`ClusterService.isLeaderNode()` is a natural (not mandatory) place to run it. **Not yet designed:** once
a Lever-1-shaped claim is released and re-picked-up, blindly retrying is *not* safe the way retrying a
job is — Flowable's own job semantics already tolerate re-execution, but re-issuing "start process by
message X" or "complete task Y" a second time could double-start or double-complete something that
actually already succeeded before the original node died. Needs an idempotency-key-style check against
Postgres before retrying, distinguishing "genuinely never ran" from "ran, node just died before
reporting it" — flagged as open, not designed.

**Per-step completion tracking exists and is relevant to recovery, confirmed live rather than assumed.**
`process_activity_instance` (Section 6, Zero-MyBatis writeup) records each activity's `start_time`/
`end_time` individually, mandatory on every transition — so for a process still in-flight when its node
dies, exactly which steps completed and which was active is directly queryable, not just "the process
instance exists somewhere." Re-verified live (not just inferred from an existing code comment) that
`deleteActivityInstancesByProcessInstanceId` correctly fires on normal completion — unlike the
variables/event-subscription gaps found earlier, this one was already clean, no fix needed. The
detail only persists while a process is still running, though — history is off, so it's discarded once
the instance finishes, same as every other runtime-only entity in this system.

**A naive worked example, to make the above concrete: a single queue, a single topic.** One queue
table holding any "executable" work — a process command or a job id — discriminated by kind, claimable
via the same optimistic-lock pattern already built, ordered by submission time. One `ITopic` any node
can subscribe to for completions. The claim-and-execute mechanics converge more than expected: either
kind resolves to "claim a row, make exactly one local synchronous Flowable call, mark done, publish
completion" — a job claim just calls `executeJob(jobId)`, a process-command claim decodes its payload
and makes the corresponding call, both synchronous, no separate async hop either way. **One real
asymmetry surfaced, not yet resolved into a payload schema:** not every completion has the same
audience. A job finishing is mostly an internal signal (chaining, recovery bookkeeping); what an
external `runByMessage` caller actually waits for is the *process instance* reaching a terminal state,
which may depend on a whole chain of jobs finishing, not any single one — and that completion needs to
carry a real result, where a job's doesn't need to carry anything beyond an identifier.

---

## 7. Module Placement

**Decided: co-located inside `domain-graph-experimental`**, as a new package alongside the existing
partition packages (`schema`, `register`, `ledger`, `security`, `authorization`) — e.g.
`org.ntrloc.graph.db.partition.process` (renamed from `workflow` — "process" is the more accurate
term for what's actually in that package: the embedded BPMN process engine). **Not** a separate
module, unlike `ui-hosting`.

Rationale: `ui-hosting` is deliberately generic — static file serving plus configuration, no
dependency on any domain's internals, meant to be reusable across different engines/domains.
Workflow is the opposite: it needs deep integration with things that only exist inside
`domain-graph-experimental` (`EntityManager`, `LedgerRegisterCoordinator`, `PermissionService`).
Making it a separate module would mean inventing a dependency back into internals that were never
designed to be exposed that way — co-locating avoids that entirely.

---

## 8. Resolved So Far

- Embed Flowable (BPMN + DMN + CMMN) rather than build custom interpreters; keep its model entirely
  behind ntrloc's own APIs.
- Go straight to custom Flowable DataManagers (not Flowable's default MyBatis-backed tables), driven
  by direct-storage-control plus ledger/audit unification, transactional atomicity with the
  coordinator, and uniform querying/security.
- Event-triggered process start uses BPMN **signal** start events for lifecycle/"well-known system
  activity" triggers, published from ntrloc's mutation pipeline, keyed on item type + lifecycle
  event, with item data as process variables — chosen over message start events because Flowable
  enforces a hard one-process-definition-per-name uniqueness constraint on message start events
  (verified via bytecode) that would block the N:1 fan-out this category needs; signal has no such
  constraint.
- BPMN **message** start events are reserved for a separate category: synchronous, single-owner,
  value-producing hooks (Section 5) and admin-defined named business-event triggers more generally —
  cases where Flowable's uniqueness constraint is a correctness requirement, not a limitation to
  route around.
- Processes triggering other processes needs no new mechanism: Call Activity (synchronous,
  blocking) or a throw signal/message event (fire-and-forget, same subscription machinery as
  mutation-pipeline-published triggers) both work today conceptually once the above is built.
- Pre-commit hooks (Section 5) — synchronous, before-the-fact processes whose output feeds back into
  the entity being created (e.g. generating a canonical MDM product ID) — use
  `RuntimeService.startProcessInstanceByMessage`, verified via bytecode to run inline in the calling
  transaction and return the completed `ProcessInstance` directly. Structurally different from
  Section 4's event-triggered start: a direct synchronous call, not a reaction to a commit.
- Signal dispatch is synchronous by default (confirmed via bytecode), so triggered process
  instances start in the same transaction as the throwing step — applying the same discipline to
  the mutation pipeline's own publish call resolves the item-creation/process-start atomicity
  question in favor of strict atomicity.
- Manual/on-demand process starts use a second (none) start event on the same process definition —
  no separate triggering mechanism needed.
- Process/DMN/CMMN definition authoring is strictly an admin function; process participation (user
  tasks, manual starts) is a general-user capability gated by the existing permission-marker model.
  Manual starts are admin-only *by grant*, for now, not by architecture — deliberately left open to
  extend to general users later without redesign.
- Workflow support is co-located inside `domain-graph-experimental` as a new partition-style
  package, not a separate module (unlike `ui-hosting`, which is deliberately generic/reusable).
- Admin UI authoring will use bpmn-js/dmn-js, vendored the same static way as the rest of
  `admin-ui`, once that work starts.
- Lifecycle hook points are the boundaries between the mutation pipeline's three phases (ledger-entry
  creation, prepare-into-register, commit) — pre-create, post-create/pre-prepare, post-prepare/
  pre-commit, post-commit — not the phases themselves. Enrichment hooks are structurally pre-create
  only (ledger entries are append-only, so enriched data must exist before the entry is built).
  Validation hooks are effectively pre-create only too (nothing left to veto post-commit).
- Ledger pre-create validation mechanism sketched: per-entry hook processes (only for entry types with
  a hook configured), each given the complete `MutationRequest` for context, run concurrently and
  joined; any rejection aggregates all reasons, attributed per entry, before the whole mutation is
  rejected — no ledger entries are created if anything is rejected.
- Process subsystem layering decided: the ledger and register have no knowledge of the process
  subsystem; `MutationRequestProcessor` (not `LedgerRegisterCoordinator`) is the integration point.
  Processes have zero ledger access and read-only register access; any process-initiated data change
  must re-enter as an ordinary new mutation, never a direct register write.
- Process execution is deliberately decoupled from ledger/register database transactions —
  "effectively atomic" via sequencing, not shared commit/rollback. This closes Section 3's
  custom-DataManager scope question in favor of a hard **zero-MyBatis** requirement (jobs/timers/
  history included, not just executions/tasks/variables), and lifts Section 5's original
  no-async-steps-in-a-hook constraint (which reopens, without resolving, the boundary-timer timeout
  question Section 5 had declared closed).
- Two layers of process parallelism identified: Layer 1 (cross-entry fan-out, orchestrated by
  `MutationRequestProcessor`, plain application-level concurrency, no new infrastructure needed) and
  Layer 2 (in-process BPMN parallel-gateway concurrency, needs Flowable's async job executor).
- Flowable's own concurrency mechanisms verified via bytecode: both the parallel-gateway join and
  async job claiming rely on optimistic locking via a `REV_` column, never pessimistic locking. Job
  claiming has built-in conflict handling (catch, log, back off, retry); gateway joins do not (no
  default retry outside CockroachDB) — a losing join command simply fails.
- Existing cluster infrastructure (`domain-graph-starter`, `org.ntrloc.graph.cluster`) surveyed: a
  thin Hazelcast wrapper providing membership, a naive leader flag, and a shared `IMap` — no
  distributed executor, lock, or RPC despite Hazelcast providing exactly that. Not currently a
  dependency of `domain-graph-experimental`.
- **Correction**: no custom retry wrapper is needed for parallel-gateway join races. Verified via
  bytecode that Flowable's own async job-retry machinery (`ExecuteAsyncRunnable` →
  `DefaultAsyncRunnableExecutionExceptionHandler` → `JobRetryCmd`) catches `FlowableOptimisticLockingException`
  generically, with no special-casing, and retries it exactly like any other transient job failure (3
  attempts, 10s apart by default) — this fully covers the only case that matters, since a join race
  can only happen inside an async job's execution in the first place.
- Boundary error events verified via bytecode: catch only a deliberately-thrown `BpmnError` (or an
  explicitly configured `flowable:mapException` class mapping) — never a generic/automatic catch.
  Critically, Flowable's automatic job-retry-then-dead-letter path never reaches BPMN error
  propagation at all; a delegate must itself decide to give up and explicitly throw `BpmnError` for a
  boundary event to fire.
- `ProcessManager` proposed as the single front door for process invocation, hiding Layer 1/Layer 2
  execution mechanics from callers via two methods with genuinely different cardinality contracts:
  `runByMessage(...)` (blocking, single-owner, uniform result type) for pre-create hooks, and
  `dispatchSignal(...)` (fire-and-forget, multi-owner) for post-commit triggers.
- Post-commit fan-out semantics (isolation vs. all-or-nothing) worked out concretely via a video
  keyframes+transcript example: both are achievable with one process/parallel-branch structure,
  selected via where a boundary error event's caught-failure path is routed relative to the join —
  same underlying join machinery, opposite outcome depending on the diagram's routing choice.
- Clustering framework: **Hazelcast, kept** (not Ignite, not something new) — it's a coordination
  layer, not a second data store, and it's already embedded. Flowable's Event Registry (the only path
  to BPMN message *throw* in Flowable 8): **explicitly ruled out**, being 100% Flowable-specific with
  no BPMN-spec backing, unlike `flowable:async` which stays in bounds as unavoidable
  execution-strategy plumbing every engine needs some vendor mechanism for.
- Cross-cluster execution reduces to exactly two levers, both ground-truth verified: whole-command
  dispatch (process start/resume, decided *before* the call happens) and job claim/execution
  (decided *after* a job already exists, via `AsyncExecutor` acquisition toggling +
  `ManagementService.executeJob(jobId)` targeted pushes). Four components — process execution, job
  acquisition/execution, task completion, result notification — sit behind one encapsulated
  placement-decision point, generalizing `ProcessManager`'s original hiding principle.
- `org.ntrloc.graph.cluster` consolidated into `domain-graph-experimental` (implemented, July 2026,
  see Section 6) — `domain-graph-starter` is being retired and no longer shares any dependency with
  `domain-graph-experimental`. `SchemaManager` is the first live consumer of the resulting
  notify-then-verify pattern (Hazelcast `ITopic`, Postgres remains the only source of truth).
- Visual BPMN editor's element set extended well past the original five (Script/User/DMN Task,
  Parallel Gateway, Call Activity, Sub-Process with real containment and a collapse/expand toggle —
  Section 10). Live-verified at the runtime level, not just editor/XML — which surfaced two more
  zero-MyBatis persistence bugs (Section 6), both specific to branching/forking execution, a code
  path nothing in ntrloc had exercised before: a read-your-own-writes gap in Parallel Gateway's
  join-counting query, and a missing insert()-idempotency guard that broke any fork past the first
  branch. Both fixed and confirmed via a combined process exercising every new element type at once.

## 9. Explicitly Open / Deferred

- ~~Immediate next step: a minimal walking skeleton~~ — **done**. Flowable is embedded in
  `domain-graph-experimental` with fully custom DataManagers (Deployment, Resource,
  ProcessDefinition, Execution, VariableInstance) backed by plain JDBC against ntrloc's own
  `process_*` tables — not Flowable's default MyBatis persistence. History is off. One trivial
  hand-authored "hello world" BPMN process (start → service task calling a Spring-bean delegate →
  end) runs to completion end-to-end, proven via `ProcessEngineIntegrationTest`.
- **Admin UI, first slice — done (July 2026)**: a read-only "Processes" tab
  (`admin-ui/components/ntrloc-processes/`) lists deployed process definitions, hitting a new
  `GET /api/admin/process/definitions` (`ProcessAdminController`) backed by Flowable's real
  `RepositoryService.createProcessDefinitionQuery()` — which required actually implementing
  `ProcessDefinitionDataManagerImpl.findProcessDefinitionsByQueryCriteria()` /
  `findProcessDefinitionCountByQueryCriteria()` for real (id/ids, deploymentId, key/keyLike,
  name/nameLike, version comparisons, latest-per-key); these were previously stubbed to always
  return empty, since the walking skeleton only needed the exact lookups the hello-world process
  itself makes at runtime. Verified live in a browser: UI → REST → RepositoryService → the custom
  DataManager → `process_definition` table, end to end.
- Exact scope of which Flowable entities get custom DataManagers (executions/tasks/variables vs.
  also history/jobs/timers) — proposed split not yet agreed. History-entity DataManagers still not
  built at all (history stays off).
- Admin UI shape for manually starting/re-triggering a process (generic "run a process" screen vs.
  contextual action on an item's own detail view) — deliberately deferred. Viewing/starting running
  instances not started.
- Admin UI shape for authoring a lifecycle-bound signal trigger (item/link type + event picker,
  drawing from a governed name list) vs. a custom named message trigger (free name entry) — two
  distinct authoring flows per Section 4, neither designed yet.
- The governed list of "well-known system activity" signal names itself doesn't exist yet — nothing
  in ntrloc emits any lifecycle signal today; Section 4's naming-governance point needs an actual
  registry/source of truth once the mutation pipeline starts publishing them.
- Whether `runProcess` (MCP tool)'s current authorization check already follows the marker-based,
  admin-only-by-grant rule from Section 4, or assumes admin identity some other way — not verified,
  flagged for next time that tool is touched.
- No concrete use case yet for a general user manually starting a process — admin-only by grant
  until one shows up (Section 4).
- Pre-commit hook processes (Section 5) are a design sketch, not built: no item-type schema field
  for a hook message-name reference, no mutation-pipeline call site invoking
  `startProcessInstanceByMessage`, no deploy-time/admin-UI validation rejecting async steps inside a
  hook process.
- Pre-commit hook timeout is config, not diagram-modeled (Section 5, resolved) — but the config
  surface itself (where the numeric timeout lives on the item-type/hook association, and what
  happens on expiry: fail the mutation outright vs. some fallback) isn't designed yet.
- Which schema entity owns a hook's configuration for cross-item validation cases (item type vs. link
  type — the Photo/Campaign delete-guard example) — not resolved.
- Whether `LedgerRegisterCoordinator`'s and `MutationRequestProcessor`'s roles should be reshuffled,
  now that the latter is confirmed as the process-subsystem integration point — flagged, deferred.
- ~~A retry wrapper for commands that can race at a parallel-gateway join~~ — **resolved: not
  needed.** Verified via bytecode that Flowable's own async job-retry machinery already catches
  `FlowableOptimisticLockingException` generically and retries it like any other transient failure,
  which fully covers the only case a join race can actually occur in (inside an async job).
- ~~Layer 2's join-wait/completion-notification mechanism~~ — **design settled (July 2026, see
  Section 6), not yet built.** Not DB polling vs. `IMap`+listener as originally framed — an `ITopic`
  (not `IMap`) firing on transaction-commit, Postgres as the only source of truth, the topic purely
  advisory, a coarse poll retained as the fallback net. `SchemaManager`'s new implementation is a live
  proof of the same pattern, just not yet wired to a running process's completion specifically. Still
  blocked on Layer 2 parallelism (real in-diagram concurrent branches) not existing yet.
- ~~Custom Job/Execution DataManager locking implementation~~ — **done and live-verified (July
  2026)**: every Flowable entity now has real `revision`-checked optimistic locking; see Section 6's
  zero-MyBatis implementation writeup.
- ~~Clustering's effect on job-claim tuning~~ — **mechanism identified (July 2026, see Section 6)**:
  `setAsyncExecutorAsyncJobAcquisitionEnabled(false)` plus targeted `ManagementService.executeJob(jobId)`
  pushes via Hazelcast `IExecutorService`, replacing undirected any-node-claims-it polling with
  deliberate routing while keeping Flowable's own optimistic-locking claim safety underneath. Not yet
  implemented — no `IExecutorService` wiring exists yet, and the load signal that would trigger a push
  (what counts as "this node is overloaded") is still undecided.
- Whether the placement-decision component (routing "where does this run" for process
  execution/job execution/task completion) is a single unified thing or several — principle agreed
  (one encapsulated decision point, never leaked or duplicated, per Section 6), concrete shape/name
  explicitly set aside mid-discussion and not yet re-opened.
- Whether async-marking enforcement (needed wherever Flowable can internally bypass ntrloc's own
  dispatch — signal throw/end, Call Activities, signal start; see Section 6) should be deploy-time
  validation/rejection (visible, honest) or a silent `BpmnParseHandler` override (foolproof, but
  invisible to the diagram author) — real extension points confirmed for both, not chosen between.
- BPMN constructs not yet traced through the same "who calls what, is it ours" verification as
  everything in Section 6: event subprocesses (signal/message/error/escalation/timer-triggered,
  scoped to a running process), compensation (boundary/throw, transaction subprocesses — BPMN
  compensation semantics are often deferred/batched, possibly a genuinely different mechanism than
  job-vs-inline), conditional events, ad-hoc subprocesses (has its own concurrency model, possibly a
  third mechanism distinct from parallel gateways), complex gateway, and escalation/error/
  multi-instance treated only by structural analogy rather than independently confirmed. Deliberately
  tabled — several of these may simply be outside what ntrloc's diagrams ever use, in which case
  closing the gap matters less than this list makes it look; worth checking relevance before spending
  verification effort on any of them.
- Whether/how much of Section 5's original "no async steps in a hook process" constraint should still
  apply specifically to pre-create validation/enrichment hooks, now that the blanket rationale for it
  no longer holds — narrower question, not yet answered.
- ~~Authoring UI (bpmn-js/dmn-js)~~ — **done for both.** BPMN editor: Section 10, built without
  bpmn-js (license issue, see Section 2). DMN: a decision-table editor plus Decision Requirements
  Diagram support for linked/dependent tables within one deployment (multiple tables, dependency
  arrows, always bundled into a single Flowable `decisionService` on save) — built the same way,
  reusing the BPMN editor's diagram-js infrastructure rather than a separate implementation; a
  fuller write-up of that piece lives outside this document. CMMN authoring not started.
- CMMN integration specifics (how cases map onto items/links concretely) — not yet designed, flagged
  as a good area to explore once there's a concrete driving use case for it (Section 1's DMN/CMMN
  motivation was written before either was built; DMN's is now resolved, CMMN's is not).
- `ProcessExecutor`: whether it's one class or decomposes into a command-dispatcher plus a job-router
  sharing the same placement-decision logic — see Section 6's working notes.
- The stale-claim recovery sweep (this app's own equivalent of `ResetExpiredJobsRunnable`, needed
  because Flowable's own acquisition is being disabled) — not built. Same section.
- **A named, currently-open correctness gap**: safely retrying Lever-1-shaped work (start-by-message,
  task completion) after its claiming node dies mid-flight, without risking a double-execution — needs
  an idempotency-key-style check against Postgres, not designed yet. Not the same problem as job
  retry, which Flowable's own semantics already tolerate.
- Which specific local signal a node should check about itself before claiming work (normalized CPU
  load average vs. thread-pool/executor queue depth vs. something else) — named as the right *kind* of
  signal (local, not broadcast), exact choice still open.
- The completion topic's message payload/schema — needs to distinguish a job finishing (internal,
  no result to carry) from a process instance reaching a terminal state (external caller waiting,
  needs a real result) — noted, not designed.

## 10. Visual BPMN Editor (July 2026)

Click a process definition in the "Processes" tab → view/edit it as an actual diagram → Save
deploys a new version (Flowable's deployments are immutable/versioned; there's no "edit in
place" — confirmed this matches what was wanted before building around it).

- **Element set, deliberately reduced** (original scope; substantially extended since — see this
  section's own later subsection): Start Event, End Event, Task, Exclusive Gateway, Sequence Flow —
  agreed scope, not full BPMN. Existing content using a more specific type our palette doesn't create
  (our own hello-world process's `bpmn:ServiceTask`) still renders/behaves as a generic task, so real
  pre-existing processes don't just fail to display.
- **New endpoints**: `GET /api/admin/process/definitions/xml?id=...` (raw BPMN XML for a
  definition) and `POST /api/admin/process/definitions/{key}/versions` (deploy a new version from
  posted XML). `id` is a query param, not a path variable — Flowable's definition ids are shaped
  like `helloWorld:1:1`, and a literal colon in a path *segment* 404s even URL-encoded (confirmed
  empirically; not a Spring `PathPattern` issue specific to this route, more likely Reactor
  Netty's own URI parsing ahead of routing).
  `ProcessDefinitionDataManagerImpl`'s query-criteria methods (Section 8) already covered listing;
  no further custom-persistence gaps turned up building this.
- **No context-pad/palette icon font**: bpmn-font (the glyph icons bpmn-js's palette uses) belongs
  to that same licensed distribution. Palette entries use small original SVG glyphs instead (plain
  geometric shapes matching each element's own on-canvas rendering, not a reproduction of bpmn-font's
  specific artwork); context-pad entries stay plain text/symbol labels ("×", "→", ...).
- **Import**: BPMN XML → bpmn-moddle model → diagram-js shapes/connections, positioned from the
  BPMNDI (`bpmndi:BPMNDiagram`) section when present. Processes with no BPMNDI at all — like our
  hand-authored hello-world.bpmn20.xml — fall back to a simple left-to-right auto-layout, with
  connections docked edge-to-edge (not center-to-center, which drew straight through each shape's
  own label).
- **Export**: rebuilds a fresh `bpmn:Definitions` from whatever's currently on the canvas every
  time (not a patch against the original XML) — each shape/connection already carries its real
  bpmn-moddle `businessObject`, attached at creation time (import, or the palette/rule provider).
  Must declare `xmlns:flowable="http://flowable.org/bpmn"` on the exported root explicitly:
  bpmn-moddle preserves unknown-namespace attributes like our service task's
  `flowable:delegateExpression` in `businessObject.$attrs`, but silently drops them at
  serialization time if the corresponding namespace isn't declared on the document — confirmed by
  a save that round-tripped the diagram fine but got rejected by Flowable's own
  `flowable-servicetask-missing-implementation` validation, since the attribute had gone missing
  from the XML actually POSTed. A real, fixable bug, not a scope limitation.
- Renaming a selected element re-renders it by removing and re-adding it to the canvas rather than
  a targeted in-place redraw — the diagram-js modules that would give us that
  (`label-editing`/`change-support`) are deliberately not part of the reduced module set.
- Verified live end-to-end in a browser, not just against Node-level round-trip tests: opened the
  real hello-world process, renamed its service task, saved, confirmed version 2 appeared in the
  Processes list with the rename intact and the original `delegateExpression` preserved.

### Extended element set (through July 2026)

The "deliberately reduced" set above grew substantially across several follow-on passes, each adding
a full round trip (palette → canvas → panel fields where relevant → export/import → live run):

- **Script Task** (Groovy/JavaScript inline scripts, no external delegate needed) and **User Task**
  (assignee + candidate groups/users, `flowable:*` attributes preserved via `businessObject.$attrs`
  the same way the original service task's `delegateExpression` already was).
- **DMN Task** — not a distinct BPMN type at all, a plain `bpmn:ServiceTask` carrying
  `flowable:type="dmn"` (confirmed by decompiling `DmnActivityBehavior` vs. `BusinessRuleTaskActivityBehavior`
  — `<businessRuleTask>` is unrelated legacy Drools/KIE integration, not what Flowable's own DMN
  dispatch actually looks for). Panel offers a decision-table picker backed by the (separately built)
  DMN decision-table editor and its own Decision Requirements Diagram support for linked/dependent
  decision tables within one deployment — a large enough slice of work to warrant its own write-up
  elsewhere rather than duplicating it here in full.
- **Parallel Gateway** — same diamond shape/color family as Exclusive Gateway (a "+" mark instead of
  "X"), no condition, every outgoing path taken. Standard BPMN construct, no dispatch ambiguity to
  verify the way DMN Task needed.
- **Call Activity** — invokes another deployed process by key and waits for it to finish.
  `calledElement` is set as a real core-schema attribute (`bo.calledElement = key`), not a
  `flowable:*` extension attribute like DMN Task needed — verified against
  `flowable-engine-8.0.0-sources.jar`'s `CallActivityBehavior.getProcessDefinition()`, which defaults
  `calledElementType` to `"key"` whenever unset, so no `flowable:calledElementType` marker is needed
  either. Panel's "Called Process" picker reuses the existing `GET /api/admin/process/definitions`
  listing endpoint verbatim — no new backend work.
- **Sub-Process — a real container, not just another shape.** The one genuinely new architectural
  piece: everything before this was a flat, single-container process. diagram-js's `Create`/`Modeling`
  modules already have generic parent/child containment support built in (confirmed by reading the
  vendored source directly, not assumed) — `shape.create`/new `elements.move` rules gate valid
  drop/move targets to root or an expanded Sub-Process; `connection.create` gained a same-parent
  constraint (a plain sequence flow can't cross a Sub-Process boundary — that needs a boundary event,
  out of scope); deletion cascades to children for free via diagram-js's own `DeleteShapeHandler`.
  Import/export both walk `flowElements` recursively (both `bpmn:Process` and `bpmn:SubProcess` expose
  that array — the BPMN metamodel's `FlowElementsContainer`), so arbitrary nesting depth (a Sub-Process
  inside a Sub-Process) works for free, not just one level.
  - **Collapsed vs. expanded is purely a BPMNDI-level view toggle on one element** (`bpmndi:BPMNShape`'s
    `isExpanded` attribute), never a semantic distinction between two kinds of Sub-Process — an early
    design (two separate palette tools, one collapsed-by-default with no way to ever receive content)
    got this wrong and was corrected mid-session once that produced a dead-on-arrival shape (Flowable's
    `SubProcessActivityBehavior.getStartElement()` throws `"No initial activity found"` if a subprocess
    has no internal start event, and a collapsed one built that way could never be given one). Rebuilt
    as a single "Sub-Process" palette entry, always expanded on drop with an auto-seeded internal
    Start→End already connected, plus a real collapse/expand toggle. The toggle itself is diagram-js's
    own built-in `modeling.toggleCollapse` command (already vendored in `ModelingModule`, undo-safe,
    recursively hides/shows children via a plain `hidden` flag) — no new dependency, just previously
    unused. Import needed one fix of its own: a freshly re-imported collapsed Sub-Process's children
    weren't being marked `hidden`, so after a save/reload they'd render floating next to the (correctly)
    collapsed box instead of staying tucked away — fixed by propagating `hidden` down the parent chain
    during import.
  - **Interaction convention**: clicking the small +/- marker drawn directly on the shape toggles it —
    the standard convention real BPMN tools (Camunda Modeler, bpmn.io) use for this, chosen over a
    context-pad entry or double-click (no established meaning for that here). diagram-js's
    `element.click` event only carries the raw native `MouseEvent` (verified directly against
    `InteractionEvents.js` — no pre-converted diagram-space coordinates), so the click position is
    derived from the shape's own live `getBoundingClientRect()`, which handles the canvas's current
    zoom/pan for free without touching `Canvas`'s viewbox state directly.
  - **No interactive resize**: this vendored diagram-js subset has no `resize` feature module at all
    (confirmed by directory listing) — an expanded Sub-Process auto-grows to fit its children instead
    (listening for `commandStack.shape.create.postExecuted`/`elements.moved`, growing right/bottom only
    when something would overflow), a different mechanism reaching the same practical goal.
- Live-verified end-to-end at the *runtime* level, not just the editor/XML level — this is what
  surfaced the two zero-MyBatis persistence bugs documented in Section 6's own "Branching/forking"
  subsection: a combined process using all of the above (Parallel Gateway fork/join, a Call Activity
  actually invoking a second deployed process, a collapsed Sub-Process, and an expanded Sub-Process
  with real nested children) runs to completion with every branch's own logic actually executing, not
  just deploying without error.

---

*Document generated from ntrloc design session — July 2026. Section 9 updated after the first
admin UI-to-database slice (deployed process definitions list) was built and verified. Section 10
added after the visual BPMN editor (view/edit/save-as-new-version) was built and verified. Section 5
added after a design discussion distinguishing pre-commit hook processes from event-triggered ones,
then extended with a precise lifecycle-hook-boundary taxonomy and a second (validation-flavored)
driving example. Section 6 added after a follow-on discussion covering process/ledger/register
layering, the decision to decouple process execution from ledger/register transactions, the resulting
zero-MyBatis requirement, two layers of process parallelism, ground-truth bytecode verification of
Flowable's own join/job-claim concurrency mechanisms, and a survey of existing cluster infrastructure
(`org.ntrloc.graph.cluster`) as a candidate building block for cross-node coordination. Section 6
further extended with a `ProcessManager` front-door proposal, a three-call-shape taxonomy unifying
Sections 4/5's mechanisms, a worked post-commit fan-out example (isolation vs. all-or-nothing), and
bytecode-verified boundary-error-event/job-retry semantics that corrected an earlier conclusion (no
custom retry wrapper needed for join races). Section 6's zero-MyBatis requirement implemented and
live-verified (Job/Timer, event subscriptions, activity instances, config properties,
process-definition-info, optimistic locking retrofit, IDM/Event Registry disabled, schema management
suppressed) — two additional live-only bugs (config-property and process-definition-info lookups,
both invisible to static analysis) found and fixed during verification. Section 10 extended (July
2026) with the BPMN editor's element set growing well past the original five — Script/User/DMN Task
in an earlier pass, then Parallel Gateway/Call Activity/Sub-Process in this one, the last requiring
genuine containment support and a mid-session correction (collapsed/expanded reframed as a diagram-
level view toggle on one element, not two kinds of Sub-Process, once the original two-palette-tool
design produced a dead-on-arrival shape) plus a UX follow-up moving the collapse/expand interaction
to the standard marker-click convention. Runtime verification of the extended element set surfaced
two more zero-MyBatis gaps, both specific to branching/forking execution (Section 6's own
"Branching/forking" subsection) — a read-your-own-writes gap in Parallel Gateway's join-counting
query, and a missing insert()-idempotency guard breaking any fork past the first branch — both fixed
and live-verified. "Job control: AsyncExecutor" section given a July 2026 addendum: the executor was
turned on (`setAsyncExecutorActivate(true)`) so Timer Start Event processes actually fire on their
own schedule for the current single-node demo, prompted by a deployed timer process that silently
never ran. Re-reading the custom Job DataManagers while chasing that surfaced an unverified gap in
`bulkUpdateJobLockWithoutRevisionCheck` (the real batch-lock-acquisition method, not the generic
revision-checked CRUD path Section 6's own zero-MyBatis note had described) — recorded as an open
item needing the same bytecode-verification rigor as the rest of that section, not yet resolved
either way, harmless for single-node today but exactly the kind of thing "cluster-wide processing,
tabled for later" needs to close first.*
