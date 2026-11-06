# ntrloc — Schema Evolution Design Notes

> **DRAFT — not pinned down.** This captures a design discussion in progress (2026-11-01, revised through 2026-11-04). The
> principle and the two invariants are agreed in spirit; the mechanics, boundaries, and build order are
> open for revision. Nothing here is implemented yet beyond what §7 lists as existing.

Related: `ntrloc-design-summary.md` (§1 Schema Versioning, §2 Schema Evolution Policy, §6 Staging and
Changesets), `ntrloc-marker-decision-pipeline-design-notes.md`, `ntrloc-process-capability-model-design-notes.md`,
`ntrloc-index-reconciliation-summary.md`, `ntrloc-system-change-preview-design-notes.md`.

> **Under revision (2026-11-05):** the system change preview reworks drafts (§5.4) into a preview of any
> change — schema, grants, groups, processes — and revisits two decisions here: grants in drafts, and data
> (transformer output) carried by a draft. See that doc; this one isn't yet updated to match.

---

## 1. Principle: the schema will change, and ntrloc is built for that

Many systems assume there is exactly *one* schema and that all stored data complies with it. The real
world doesn't work that way. Requirements change: a property becomes mandatory, a cover that was only
ever meant for one product gets reused for a box set. Under a "one schema" model every such change
forces a choice between rewriting history (backfilling every existing row, often with invented values —
the classic "add a NOT NULL column" nightmare) and not making the change at all. Admins, who stand on
the front line of every change, bear the cost.

ntrloc takes the opposite position:

- **Stored data is a truthful record** of what was known, under the rules in force when it was written.
- **A new version of a type describes what is required from now on**, not a claim that everything already
  stored satisfies it.
- **ntrloc's job is to make change safe and visible** for the people making it — not to make them pay for
  it up front with migrations, fabricated data, or downtime.

The value proposition: a system that accepts — and embraces — change, because that is how the world
works, and that supports admins through change instead of standing in their way.

**What schema evolution is for, and what it isn't:**

- **It's for changing constraints** (integrity rules, required data, cardinalities) without forcing
  existing data to migrate to them at once.
- **It isn't for changing, after the fact, the constraints that applied at a point in time.** Older
  versions are read-only (§5.1).
- **Data that doesn't conform to the latest version can still exist, be served, and be edited — but it
  doesn't conform.** ntrloc tolerates it; it doesn't endorse it.
- **Non-conforming data is debt.** The recommendation is always to bring data up to the latest version,
  for uniformity. ntrloc won't force that, but it offers tools to make it practical (§2.4), and none to
  make non-conformance easier.

The non-negotiable condition: **embracing change must never compromise the correctness of what ntrloc
decides from the data** — above all, marker decisions. A wrong marker, applied or withheld, undermines
trust in the whole system. Every mechanism below exists to keep that from happening.

---

## 2. Two orthogonal concerns

These are deliberately kept separate. Conflating them is the "one schema" mistake.

### 2.1 Consistency — enforced, always

**The live schema is never internally inconsistent** — not at any version, and not in any state between
versions (§5.3). Every definition, and every rule built on definitions, is valid against every other.

- A change (or set of changes) that would leave the live schema inconsistent is **not committed**. To
  make it, the change set must include whatever repairs it requires (see §5).
- **A draft may be inconsistent** (§5.4). Inconsistency exists only *while a change is being composed* —
  as ntrloc telling the admin what else must change — never in the live schema. Data writes, marker
  decisions, and access only ever use the live schema.

Example: Cover's `product` link is 1..1, and Cover's marker decision reads `product.embargoed` with a
simple (single-value) link test. Loosening `product` to 1..any would leave that test meaningless for a
cover with two products — an inconsistency between two definitions. So the loosening and its repair
commit together as Cover 1.2, whose decision says what it means for several products (e.g. *any* product
embargoed). At no point is the live schema inconsistent (§5.1).

### 2.2 Conformance — never enforced retroactively

**Data conforms to a version of its type, which may not be the newest.**

- Writes must conform to the current version.
- Existing data is never retroactively invalidated. Product 1, written when `embargoed` was optional and
  without a value for it, *conforms to the version it was written under*. It does not conform to a later
  version that made `embargoed` required. That isn't a defect in how Product 1 was written, but it is a
  gap: debt to be paid down (§2.4), not a state to settle into.
- The question to ask is *"which version does this item conform to?"*, not a yes/no against a single
  schema.
- **An item carries the newest version of its type that it satisfies** — not merely the one it was
  written under. When a narrowing creates a new version, items that already satisfy it move to it
  automatically (§5.6); an item left on an older version is one that really doesn't conform.
- Conformance isn't cumulative: a widening (a revision of the newest version, §5.1) can let an item
  satisfy it without satisfying an older one. "Newest satisfied" is still well defined.

Consequence: **every consumer of data must behave correctly for data that conforms only to older
versions** — most critically, rule evaluation (§6).

### 2.3 Writes to existing items: never make correcting a record harder than leaving it wrong

A new requirement must never block an unrelated edit.

Real-world example: an IT system catalog in a commercial service-management product recorded, for every
system, its internal contacts and dependencies. Admins later added *required* vendor fields — vendor
name, vendor contact, contract link — that most users simply didn't have for systems that were often
decades old. After a series of reorganizations, users were asked to correct the (now stale) internal
contacts. They couldn't save a correction without supplying vendor details they didn't have, so they
stopped correcting anything. The catalog ended up with no vendor information *and* no current internal
contacts — useless for its purpose. The new requirement didn't gain the data it asked for, and cost the
data the system already had.

ntrloc's rules for writes:

- **New items must conform to the current version.** Nobody is asked to reconstruct history.
- **Clients don't target a version.** A write says what's known; the server works out which version the
  item now conforms to. Fixing a contact on a v1 item leaves it at v1; supplying the vendor details moves
  it to v2 — the client needn't know or ask. Partial progress is fine: with two new required fields,
  supplying one is accepted, and the item stays at v1 until the other is supplied.
- **A write to an existing item is checked only against what it changes — and against the current
  version, not the item's own.** An older version is no license to keep making a record worse along the
  dimension that was tightened.
  - Correcting a contact on a record from an older version succeeds; the record keeps its version.
  - Clearing a value the current version requires is refused — that write makes the record worse.
  - Editing a newly-required field itself requires giving it a valid value.
- **An item moves up to a newer version when it conforms to it** — when the version is created, if it
  already did (§5.6), or later, when the missing information is supplied — never merely because it was
  touched.
- **An edit can upgrade an item's version, never downgrade it.** A write that would leave the item
  conforming to an older version than it does now is refused; promotion (§5.6) is permanent.
- **Links follow the same rules, for the items at both ends** (an item's conformance includes its links —
  §5.1). A link write is checked only against what it changes; supplying a link's missing property can
  move either end up; a link write that would move either end down is refused.
- **So a write can be refused because of another item.** Deleting Cover 1 deletes its link to Product 1;
  if that would move Product 1 down (v2, where `cover` is 1–1), the deletion is refused.
- **Gaps are visible, and closing them is separate, deliberate work.** "These records predate the vendor
  requirement and have no vendor yet" is a list someone can work through — routed to whoever actually has
  the information, not to whoever happened to be fixing a phone number.

The requirement is enforced where it can be met, and tracked where it can't, without holding the rest of
the record hostage. ntrloc is designed for real-world use, not for an unrealistic "perfection" in the data.

### 2.4 Conformance debt: paying it down

Items left on older versions are debt (§1). ntrloc should make paying it down practical, not make it
easier to live with.

- **A schema change and a data upgrade are separate, and the schema change comes first.** A draft or a
  direct change commits a version; data is upgraded afterwards, against that committed version, by
  ordinary writes. A draft never carries data fixes: like grants (§5.4), they apply after the commit. A
  draft's preview may report the debt a narrowing would create — that's information, not an action —
  but paying it down starts only once the version is live.
- **Upgrade tools are faster ordinary writes, never a side door.** Every change they make goes through
  the same permission checks (including `property:write` on the property being filled), lands in the
  item ledger, and is attributed to whoever applied it.

Tentative tools (not yet prioritized; a likely first set is 1–4):

1. **Debt view per type**: items on each version, grouped by what holds them back ("80 Products lack
   *vendorName*; 30 lack a *cover* link; 12 are in a deleted state"). Grouping by gap turns many items
   into a few jobs.
2. **Search by version and gap** ("Products on v2 missing *vendorName*"), so results feed bulk actions —
   as starting a state machine from search results already does.
3. **Per-item gap list** (§8 #9) wherever a straggler is opened or edited: debt paid down as a side
   effect of normal work.
4. **Bulk fill**: one value for one gap across a chosen set, previewed ("94 items reach v3"). A deliberate
   statement by an admin about items they selected — not an automatic default applied when a narrowing
   commits, which would be the invented-values problem of §1.
5. **Fill from related data**: bulk fill with the value taken from the graph, e.g. a property of a linked
   item.
6. **Gap routing**: gaps become tasks assigned to process groups — the people who have the information
   (§2.3).
7. **Export / re-import** of stragglers, with the missing fields as columns.
8. **Debt trend** over time, from the conformance ledger (§5.7).
9. **Debt in the preview**: "this leaves 110 Products on v2: 80 lacking *vendorName*, 30 lacking
   *cover*."

### 2.5 Reads across versions

- **Reads show the actual data, under the element's current names** (§5.1): never recast into the latest
  version's terms, never under an older version's names.
- **Each item carries its version**, and an item behind the latest carries its gap (§8 #9).
- **Projection specs** (columns, filters, sorts, facets) resolve names one way for every item. Per item,
  the only differences are missing values, the stored shape of a property whose cardinality changed
  (§5.1), and excess values — several where the latest version allows one — on properties and links.
- **Read tolerance, only where needed.** The schema knows which properties have ever changed
  cardinality; only those get shape-aware SQL. Every other property keeps today's simple typed
  expressions.
  - Test the stored shape (`jsonb_typeof`); an array of one is a single value, and a single value is a
    one-element list.
  - Casts and sorts on a single-value column holding several values use the first value (or NULL) and
    flag the row. They never fail the query.
  - A filter on several values, where the latest version allows one, matches if any of them matches. The
    same rule applies to links.
- **Some read tolerance is necessary in any case**; the aim is to keep it confined and uniform, not to
  avoid it.
- **Items are judged independently.** A v2 Product linked to a v1 Cover adds no case of its own: each
  item's side of the link belongs to its own type version (§5.1).

---

## 3. The boundary: what is schema

| Category | Members | Governed by |
|---|---|---|
| **Schema** | item types, traits, properties, links (incl. cardinality), controlled lists, state machines, **markers**, **marker decisions**, **permissions** (what's grantable), processes (BPMN) and decisions (DMN) via declared dependencies | Consistency (§2.1), always; element versions only where data conforms (§5) |
| **Data** | items, links, their property values, state | Conformance (§2.2) |
| **Grants** | permissions given to users / groups under markers | Administered live; checked against the live schema when written |
| **Identity** | users, groups, memberships | Outside the schema entirely |

Notes:

- **Markers and marker decisions are schema.** A marker can't be deleted while a decision applies it —
  that is an ordinary consistency rule.
- **Permissions vs. grants.** A *permission* is what could be given — read on property X, executing the
  transition S → T, creating items of type Y. Permissions exist because their element does: add a
  property and its permissions appear; remove it and they're gone. They're schema. A *grant* is what has
  been given — "group G holds read on property X for items carrying marker M". It ties identity to schema
  (a marker and a permission), so it's neither: grants are administered live, outside schema drafts, and
  checked against the live schema when written (§5.4).
- **Identity is outside, but guarded at the boundary.** Grants reference groups (and users). Deleting a
  group that grants still reference warns, listing those grants, and on acceptance deletes them with the
  group — recorded in the grants' history, citing the group deletion, like grants orphaned by a schema
  commit (§5.4). Membership changes never touch the schema.
- **BPMN / DMN must declare their schema dependencies.** ntrloc can't reliably infer what a script task or
  an expression reads. Declared dependencies (the item types, properties, links, and markers a process or
  decision uses) become ordinary edges in the dependency graph (§4), and the process engine can enforce
  them at runtime (cf. the process capability model).

---

## 4. Dependencies: the "uses" graph

Inconsistency propagates along "uses" edges. In the box-set example, changing `Cover.product`
invalidates the simple link test that relies on its cardinality, which in turn makes the marker decision
using that test inconsistent.

- ntrloc needs a **dependency graph over schema definitions**: types, properties, links, markers,
  conditions, decisions, permissions, state machines, declared BPMN/DMN dependencies — and, outside
  the schema, the grants on those permissions, so a change can tell which grants it orphans.
- **Whether a change breaks a dependent depends on the kind of change.** Each kind of dependent declares
  which changes to what it uses it survives. Examples:
  - A simple link test survives a link's *min* changing, but not its *max* loosening above 1.
  - A counting condition over a link survives either.
  - Anything reading a property survives the property becoming required (the test must already handle
    absence — §6); nothing survives the property being deleted.
- The existing `WorkflowReferenceScanner` (process-delete guard) is a first, partial instance: it answers
  "is this referenced at all?", not "does this change break what relies on it?".

---

## 5. Versions and change sets

> *Emerging from worked examples — provisional; expect revisions as more examples are worked through.*

### 5.1 Versions and revisions

A schema element (an item type, a trait, a controlled list, a link type, a state machine) is identified
as **`<version>.<revision>`**: Product 1.3 is version 1, revision 3.

- **A version is a conformance boundary.** An element gets a new version only when **its definition
  accepts less than before**: a property becomes required, a cardinality narrows, a controlled-list value
  is removed. This depends on the definitions alone, never on what data happens to exist (§5.6). A new
  version starts at revision 1, built on the latest revision of the previous version: Product 1.3 plus
  the narrowing is Product 2.1.
- **A revision is every other change** to what governs data: a widening, a pipeline or state machine
  change, a removal, a rename. Everything that conformed still conforms, so a revision touches no item
  rows.
- **Each revision is a fixed snapshot.** A change creates a revision; it never edits one. "Product 1.2"
  always means one fixed set of rules.
- **Items record their version only** (§2.2), and **are governed by its latest revision**: a version-1
  item is evaluated by version 1's latest pipeline and moves through its latest state machine. A pipeline
  fix in 1.4 applies to every item on version 1 at once.
- **Each revision is consistent with itself.** Its definition, marker decision pipeline, and state machine
  always agree, and a change that would leave the pipeline invalid carries the repair in the same
  revision.
  - Worked example: raising `Cover.product`'s maximum from 1 to any is a widening. It creates Cover 1.2,
    which replaces 1.1's simple link test with a counting condition in the same revision. Every cover
    still conforms, and every cover is governed by 1.2 the moment it commits; no item moves.
- **In short: does the change make the definition accept less?** If so, it's a new version, carrying any
  repairs; if not, it's a new revision of the latest version.
- **Only the latest version gets revisions.** Every change, direct or in a draft, starts from the latest
  revision of the latest version. Once version 2 exists, version 1 is **sealed** at its last revision
  (1.3): its definitions, pipelines, and state machines stay as committed.
  - Editing them would change point-in-time constraints after the fact (§1).
  - It would also let versions drift apart: a fix made to v1 is easily missed in v2 and v3, and someone
    eventually finds out the hard way.
  - Items on older versions are corrected by moving them forward (§2.4), or, failing that, the version is
    retired (§5.6).
- **A change that would leave an older version inconsistent is refused** while that version has items,
  because the older version can't be repaired. E.g. removing a property that v1's pipeline reads is
  refused until v1 is vacated or retired. Repairs the system makes mechanically (renames rewriting label
  patterns) are unaffected.
- **Repairs follow the pipeline that breaks.** Pipelines belong to their own type, so if *Product's*
  pipeline relies on Cover having at most one product, loosening that link gives Cover a revision and
  Product a revision with the repaired pipeline, committed together. The dependency graph (§4) finds them
  all.
- **For a draft:** (1) the consistency check finds every incompatible use; (2) on commit, each element
  the draft changes gets one revision of its latest version — or, if its definition narrows, a new
  version at revision 1; (3) the commit applies them atomically; (4) promotion begins for new versions
  (§5.6). One step per element per commit, never one per edit.
- **Compatible changes create a revision.** Adding an optional property, adding a type, or loosening a
  cardinality: everything that conformed still conforms.
  (Reads already disregard properties a definition doesn't know, so even data written *after* such a
  change still fits the version.)
- **Removing a property creates a revision too.** Existing values are silently ignored when items are
  read, so they don't affect conformance. Setting the removed property is refused on any write. (The latest version's dependents that read it are repaired in the same change
  set — §2.1; if an older version with items reads it, the removal is refused — above.)
- **An item records the version of its own type** — "Product 1" — not the revision, and not a
  whole-schema number; the newest it satisfies (§2.2, §5.6). Revisions only widen, so an item that
  conforms to 1.1 conforms to every later revision of version 1.
- **An item conforms to a type version completely, or not at all** — its properties, its links (counts
  and their properties), and its state.
- **A type version is its own definition plus the versions of what it's built from.**
  - Its own definition: its properties, its side of each link's cardinality, its supertype, whether it's
    abstract.
  - What it's built from: its traits, the controlled lists its properties use, its link types, its state
    machine — each versioned in its own right.
  - **Narrowing any of these gives a new version to everything built on it**; any other change to them
    gives everything built on it a revision. So an item's version always marks the same conformance
    boundary.
- **Which narrowings reach which types:**
  - A controlled list losing a value: a new version of the list, and of every type (and link type) with
    a property using it.
  - A trait narrowing: a new version of the trait and of every type implementing it.
  - A link's cardinality is part of the item type on that side: raising `Product.cover`'s minimum from 0
    to 1 gives Product v2; Cover, at the other end, stays v1.
  - A link type narrowing (its properties, its target types): a new version of the link type, and of the
    item types on *both* sides, since their items' conformance includes their links.
  - Deleting a state, or the state machine: a new version of the item type using it (§5.8).
- **Removing a link type creates a revision, like removing a property.** Existing links of that type are
  ignored; new ones are refused.
- **Renames create a revision.** Nothing stops conforming, so no version — and every rename (property,
  link perspective, state, transition, marker, …) is recorded in the schema ledger: what, when, by whom
  (§5.5). Dependents that use names are repaired in the same change set:
  - **Display label patterns** name properties, so the rename rewrites them — a mechanical repair the
    system makes itself, shown in the preview and recorded in the same entry.
  - **Processes and decisions** (BPMN / DMN) that use the name, via their declared dependencies, can't
    always be rewritten mechanically: they're flagged for the admin to repair (§2.1).
  - Clients address items, types, and properties by name by design; that's unchanged.
- **Names apply to the element as a whole, never per version.** Every name a client or rule can address
  (item types, traits, properties, link perspectives, controlled lists, states, transitions, markers) is
  the same for every version.
  - **This is the one deliberate exception to sealing.** A rename is a revision of the latest version,
    but its effect reaches sealed versions too. The same holds for presentation: descriptions, display
    labels, property groups, `facetable`.
  - Projections can only be consistent if names are universal. Names that differ between versions would
    make every projection spec, filter, sort, saved search, and form ambiguous over items of mixed
    versions.
  - A name that carries meaning (`covers` for 0–any) is renamed in the same change set as the change in
    meaning, so the schema ledger records the two together and history stays honest.
  - No aliases.
  - This could be revisited only alongside consumer views (§10), not before.
- **A cardinality change never rewrites stored values.** Properties are keyed by id, and a SINGLE ↔ LIST
  change must not bulk-update every item in the register. So the register holds whatever was written, and
  reads tolerate both shapes (§2.5):
  - **Widening (SINGLE → LIST / SET):** existing items keep their single values; reads treat one value
    as a one-element list.
  - **Narrowing (LIST / SET → SINGLE)** is a new version of the owner, on the same property. Items holding one value move
    up, even though they still store it as an array of one. Stragglers holding several stay behind, shown
    with their actual values and flagged as a gap ("`genre` allows 1; this Product has 2"). Which value
    becomes *the* value is a decision per item, made by a person: debt (§2.4), not a conversion.
  - **Links work the same way.** A link narrowing (`covers` 0–any → `cover` 0–1) leaves stragglers with
    several links under the narrowed perspective, shown as they are and flagged.

### 5.2 No overall schema version

- **Only elements are versioned.** Making `Product.owner` required creates Product v2; Cover is unaffected
  and stays v1. Conformance is per type, so the type version is what an item records.
- **A schema state is identified by its position in the schema ledger** (§5.5), not by a version number.
  A position identifies every change across the whole schema, so it serves "the schema as of this change"
  (and clients noticing the schema changed) more precisely than an overall version would. An overall
  version would only be a coarser second name for the same thing.
- **An element's revision is its own count of the ledger entries that changed it** within a version.
  `<version>.<revision>` is what admins see; the ledger position is the precise point in time. Nothing
  extra is stored.

### 5.3 Consistency applies to every change; history comes from the schema ledger

- **Every change — versioned or not — goes through the consistency check** against the schema as a
  whole. Consistency is about the schema's *state*, not its version number — a change that would leave a
  pipeline or state machine invalid carries its repair in the same commit (§5.1).
- **Exact history comes from the schema ledger (§5.5).** "What did the schema look like on a given date?"
  is answered by the ledger position; "what rules did Product 1.2 have?" by replaying to that revision.
- **Pipelines belong to type versions**, and only the latest version's pipeline is edited (§5.1).
  Editing a pipeline creates a revision, never a version by itself: adding a Select row that maps a value
  to an existing marker is Product 1.4. Sealed versions' pipelines are never edited.
- Every pipeline edit is a schema change: consistency-checked, and recorded in the schema ledger.

### 5.4 Change sets

- **A change set bundles a change with the repairs it needs** (e.g. loosen `Cover.product` *and* revise
  the marker decision), validated together against the whole schema and committed atomically.
- **Consistency must not mean ceremony.** Per the design summary's existing position, simple changes
  don't need a staged changeset:
  - A single change that passes the consistency check commits immediately (as today) — with a version
    bump only when §5.1 calls for one. (Granting marker X to group Y isn't a schema change at all —
    §3.)
  - Staging, review, approval, and the sparse overlay (design summary §6) are for changes whose blast
    radius calls for them.
- **Impact visibility.** Before a change commits, an admin should be able to see what it affects —
  definitions it touches, and the marker changes it causes (which items gain or lose which markers). Full
  changesets provide this via the staged overlay's diff; a lighter, on-demand preview could come first.

#### Drafts

An admin changing one part of the schema may find it conflicts with others — an item type change that
breaks a marker decision, say — and need time (and other admins) to work out the rest. A **draft** holds
such a change set while it's incomplete.

- **One draft at a time** (the design summary's two-version constraint: live and staged).
- **A draft starts from the latest version** of every element it changes (§5.1), and holds changes, not
  copies of the elements.
- **A draft may be inconsistent; it can't be committed until it isn't** (§2.1). The consistency check's
  findings are the draft's to-do list ("`Product.cover` is now 1–any; decision *Trade embargo* uses a
  simple test on it"); Commit is available once it's empty.
- **A direct change that fails the check can become a draft** — "start a draft with this change" rather
  than only a refusal, so admins needn't know in advance which kind of change they're making.
- **Attribution at two levels.** Each element change in the draft records who last edited that element
  ("admin A changed item type X", "admin B changed the marker decisions for Y"); the commit records who
  applied the change set ("applied by admin C"). Both go into the schema ledger entry.
- **Preview against the draft** — the impact preview and promotion counts (§5.6) run as if it were
  committed: "40 items gain *Trade embargoed*; 12 Products stay on v1."
- **Dates.** A draft shows when it was created and last updated.
- **Discard at two levels**: the whole draft, or one element's changes (keep the changes to these two item
  types, discard the marker decision changes and start over). Discarding part can create a new
  inconsistency within the draft — e.g. a kept marker decision that tests a property whose creation was
  discarded — which simply appears on the to-do list.
- **The draft is the uncommitted schema ledger entry** (§5.5); committing appends it as one change set.
- **Re-checked at commit** against the live schema as it is then, not as it was when the draft began.
- **Grants aren't part of a draft.** A draft changes schema only — including permissions, as elements
  come and go; grants stay live and are applied after the commit. (A draft carrying grants would make it
  a schema-plus-grants commit — a slippery slope.) So:
  - **Grants orphaned by the commit are removed** — grants on a removed property, marker, transition, ….
    The removal is recorded in the grants' history, citing the schema change that caused it ("removed:
    property P removed in schema change N") — never silently.
  - **No initial grants.** A new element arrives with permissions and no grants; nobody can use them until
    grants are applied. That's the safe direction — access withheld, never wrongly given.
  - **The preview covers grants**: "committing removes 3 grants: …"; "new property *vendorName* has no
    grants — nobody can read it". A new *required* property doesn't block item creation (`property:write`
    is checked on updates only), but until someone holds write on it, existing items can't be brought up
    to the new version.
  - Grants on elements the draft doesn't touch are administered live as usual.
- **Live schema changes while a draft is open** are allowed only where the draft doesn't reach. A live
  change is refused if it would change, or create a version of, an element the draft touches — directly
  or through what that element is built on (narrowing a trait the draft's `Product` implements counts).
  The admin is offered "add this to the draft" instead. Changes elsewhere go ahead live.
  - So the draft never goes stale: each element it touches is still at the version the draft started
    from when it commits, and the commit appends the next version. No merge, no rebase; every element's
    history stays a single line.
  - The draft's to-do list and preview are recomputed whenever the live schema changes, so a live change
    elsewhere that breaks the draft (deleting a property the draft's new decision tests) shows up at
    once, not only at commit.

### 5.5 The schema ledger: append, don't replace

Schema changes get the same treatment ntrloc already gives data: the **ledger** is the append-only source
of truth, and the **register** is the materialized current state built from it.

- **Schema ledger:** an append-only log of schema change sets. Each entry records who, when, why (a
  reason — the design summary already calls for one on changesets), and the mutations themselves — which
  already exist as typed objects (`Create…Mutation`, `Update…Mutation`, …).
- **Schema register:** the current schema — today's `schema_*` tables (and, as they become schema, the
  marker and decision tables) — kept as the materialized current state that reads and validation use.
- Grants aren't schema (§3), but their history needs the same append-only treatment — including grants
  removed by a schema commit (§5.4).

What falls out of it, beyond audit:

1. **Explaining a marker decision.** "Why did Cover 1 get *Trade embargoed* on a given date?" can only be
   answered with the rule *and* the schema as they stood then — provenance, in the sense of
   `ntrloc-provenance-and-liability-requirements.md`.
2. **A change set is one ledger entry**, appended atomically. Staging is an entry not yet committed —
   mirroring how item writes already go UNCOMMITTED → committed.
3. **Consistency gates the append.** An entry is validated against the materialized current schema before
   it's appended.
4. **Versions become facts in the ledger.** An entry that narrows an element records the new element
   version; version boundaries are visible in history.
5. **The schema at any point in time** is a replay of the ledger, or a snapshot plus the entries since.

**Every schema change is recorded, versioned or not.** Versions mark only the changes that narrow an
element; the ledger records them all. Making a required property optional creates no version, yet it
must be recorded — who, when, why — because it may be exactly the mistake an audit has to find: a
restriction relaxed that shouldn't have been.

Item conformance has a ledger of its own (§5.7).

### 5.6 Promotion: items move to a new version when they already conform

Worked example: `Product.cover` goes from 0–1 to 1–1, creating Product v2. Product 1 has a cover;
Product 2 doesn't.

- **Promotion is automatic.** When a narrowing creates a version, existing items are checked against it,
  and those that already conform move to it: Product 1 → v2, Product 2 stays v1. Leaving every existing
  item at v1 would make "v1" mean only "predates the change"; with promotion, the items left behind are
  exactly the ones needing attention.
- **It runs in the background**, like marker recalculation, after the change commits — commit the
  version, then promote, never the reverse.
- **Promotion updates each moved item's version, never its data.** This is the one bulk update the design
  can't avoid, because which items move depends on their data. It happens only on narrowings, in batches.
  Widenings, pipeline edits, renames, and cardinality changes touch no item rows.
- **Until an item is promoted, its old version's pipeline decides its markers.** If the two pipelines
  differ, items awaiting promotion carry the old version's markers for a while. That window is made
  visible rather than papered over: the progress view shows how many items await promotion with markers
  that may change, and promotion can take those first.
- **Items staying on an old version keep its pipeline.** It isn't edited (§5.1): to change how they're
  treated, move them forward (§2.4) — or, where that's impossible, retire the version.
- **A change to the latest version says what it doesn't reach.** Its preview names the older versions
  that still have items: "v1 (120 items) and v2 (8) are governed by their own pipelines; this change
  doesn't affect them."
- **Retiring a version** (later, when first needed). A version that still has items can be retired: its
  pipeline stops granting markers, and items on it get none until they reach a newer version.
  - Fail closed, as in §6; nothing in the old version is edited, so nothing can drift.
  - One action, recorded in the schema ledger, with its cost shown first: "120 Products on v1 lose these
    markers until they reach v3."
  - The escape for when an old pipeline grants what it shouldn't and its items can't be brought forward.
  - Retiring a vacated version (below) is the same action with nothing left on it.
- **Promotion recalculates markers** with the new version's pipeline; those changes are recorded in the
  item ledger like any marker change, attributed to the pipeline *version* that made them.
- **Admins can see its progress** — operational, but useful. Per narrowing: items checked so far, how many
  were promoted and how many remain, and, once done, the items left behind (or that none were).
- **It's the impact preview, committed.** "1 of 2 Products will move to v2; 1 stays on v1" is the same
  check, run before the change instead of after.
- **Promotions are recorded in the conformance ledger** (§5.7), not reconstructed later.
- **A narrowing always creates a version, even when every item already conforms.** In that case promotion
  simply finishes with nothing left behind, and the old version is marked **vacated** (the UI can hide
  it). Creating the version anyway, because:
  - Version numbers then follow from schema changes alone, so the schema ledger (§5.5) can reproduce them.
    If a version's existence depended on the data at that moment, replaying the same changes against
    different data would give different versions.
  - On a large type, "does everything conform?" is only known once promotion finishes — after the change
    has committed.
  - Otherwise the same change would behave differently depending on how much data a deployment has.
  - An empty version costs almost nothing.
- **"All data conforms" doesn't make a narrowing free.** A process that creates Products without a cover
  starts failing the moment `cover` becomes required, even though no existing item is left behind. That's
  for the consistency check (§2.1), via the process's declared dependencies (§3).

### 5.7 The conformance ledger

An item's conformance sits where item data and the schema meet, so it belongs to neither's history.
It gets a ledger of its own, alongside the item ledger:

- **The item ledger** is the history of changes to the item itself.
- **The conformance ledger** is the history of the item's conformance: one entry each time the item
  conforms to a new version — when it's created, when promotion moves it (§5.6), or when a write brings it
  up to date.
- **The register** holds each item's current version, as it holds the rest of the item's current state.
- **Items only.** A link's conformance is part of the items at its ends (§5.1), so links have no entries
  of their own; a link write that moves an end up records an entry for that item.

Recorded, not recomputed: for audit, what ntrloc concluded at the time stands on its own, rather than
relying on an interpretation of the data after the fact.

To begin with, an entry holds just:

1. the item's id,
2. the id of the type version it conforms to,
3. a timestamp.

To be elaborated later (e.g. what caused the change).

### 5.8 Deleting a state, or a state machine

Unlike a removed property — whose leftover values can simply be ignored — an item's position in a
process can't silently become "no state". So:

- **Deleting a state creates a new version of the state machine, and of the item type using it.**
- **Items in the deleted state stay on the prior type version** while they're in it — which is just "the
  newest version they satisfy" (§2.2). They aren't stranded: they follow the prior version's state
  machine, where the state and its transitions still exist.
- **Once an item moves to a state the newer version has**, it's promoted — if nothing else holds it back.
- **Its pipeline is the prior version's too**, which knows the state; the newer version's pipeline never
  sees an item in a state it doesn't have.
- **Deleting (or detaching) the whole state machine works the same way**: a new type version; items not
  in the machine are promoted at once; items in it stay on their version until they leave it.
- So older versions stay *usable* — state machines, transitions, guards, pipelines — for as long as any
  item is on them, not merely on record — usable, though not editable (§5.1). Vacated versions can be
  retired (§5.6).

---

## 6. Rules over data from older versions

Pipelines belong to type versions (§5.3), and an item is evaluated by its version's latest pipeline. So a
pipeline only ever sees data that conforms to its revision, and can rely on its constraints — a 2.x
pipeline can rely on a property version 2 requires; Cover 1.1's simple link test could rely on its
maximum of one.

- **A pipeline must be correct for every shape its revision allows**:
  - **Missing values** of optional properties: every test has a defined outcome when its value is absent
    (e.g. a number/date comparison is false except "does not equal").
  - **Revisions**: a revision applies to every item on its version at once, so its pipeline must be valid
    for everything the revision accepts. The repair comes in the same revision (§5.1).
- **Pinning protects an item's own data, not the data it reads elsewhere.** A Product still on a sealed
  version guarantees that version's constraints on the Product — not on the Covers its pipeline reads.
  Repairs commit with the change that needs them (§5.1), so Products on the latest version are revised
  together with Cover. But a sealed version can't be revised: a change elsewhere that would leave it
  inconsistent is refused while it has items (§5.1), and fail-closed (next) covers what slips through.
- **When a rule can't decide, it fails closed and says so.** E.g. a simple link test meeting several
  linked items: the condition does not hold (markers grant access, so withholding is the safe side), and
  the case is recorded somewhere an admin can see — not only a log line.
- **Evaluation never throws out of a marker decision.** (Today it can — §7.)

Rules saying which versions of data they apply to (formerly open) is answered: each pipeline belongs to
one version.

---

## 7. Current state (audit, 2026-11-01)

What exists today, against the above. Not exhaustive.

- **Conformance is forward-only, as intended.** `MutationRequestProcessor.checkLinkPerspectiveCardinality`
  checks min/max only for writes that change a link count; tightened bounds never retroactively flag
  existing data. ✔ matches §2.2.
- **Item updates contradict §2.3.** `MutationRequestProcessor.processItemUpdate` checks required properties
  against the item's *resulting* state, so an edit that never mentions a newly-required property is
  refused if the item still lacks it — the helpdesk failure exactly. To fix: check only what the write
  changes.
- **No schema versions.** Schema mutations apply in place; there is no version identity, and data does not
  record a version.
- **The design summary still speaks of an overall schema version** — "Schema Versioning" (items carrying a
  schema version tag during migration) and the migration table's "schema version flip". To reconcile with
  §5.2. (Its two-version constraint — live vs staged — is about schema *states* and is unaffected.)
- **No schema history.** The `schema_*` tables are updated in place and nothing records the changes —
  `ledger_entry` covers item and link data only. The design summary's intent (schema changes as recorded,
  versioned events) isn't implemented.
- **Marker decisions are not versioned or consistency-checked against the schema.** A pipeline is saved
  as JSON (`MarkerDecisionPipelineAdminController.updatePipeline`) with no validation against the schema
  — e.g. nothing stops a simple link test through a to-many link; it only fails at evaluation.
- **Evaluation can throw.** `ContextResolver` / `SignalEvaluator` throw when a non-`each` link step meets
  several items, and for unsupported steps (`StepToLink`). Where that exception lands during a marker
  decision hasn't been audited — it must not be able to fail an item's write.
- **Marker deletion is unguarded.** `AuthorizationRepository.deleteMarker` is a plain DELETE: grants go
  with it via `ON DELETE CASCADE`, and pipelines that apply it are left referencing a missing id.
- **Grant → principal has no foreign key** (`marker_grant.principal_id`). Behavior on group deletion is
  unverified.
- **Property deletion cascades its property grants** (`marker_grant_property` → `schema_property ON DELETE
  CASCADE`). Removing orphaned grants is the intended behavior (§5.4), but today it's silent — nothing
  records it.
- **Reference guard exists for processes only.** `WorkflowReferenceScanner` blocks deleting a process or
  decision referenced by a state machine / marker rule.
- **Leftover values of removed properties — to verify.** §5.1 relies on them being ignored. Per the
  mutations-ledger summary, the register stores property values keyed by *name* (the ledger by id), so a
  new property later given the same name might surface the old values. Options: clear the values on
  removal, key the register by id, or refuse to reuse a removed property's name.
- **Markers aren't recomputed when rules change.** Decisions run when an item (or something it watches)
  changes; saving a pipeline appears not to re-evaluate existing items, so they keep their old markers
  until next written. Needs confirming — and if confirmed, it's a correctness gap independent of
  versioning.

---

## 8. Open questions

1. ~~**What is one schema element?**~~ Settled (§5.1): item types, traits, controlled lists, link types,
   and state machines are versioned; a type version includes the versions of what it's built from, and a
   narrowing reaches everything built on it. Open only in detail: deleting a state (§5.8).
2. ~~**What an item records.**~~ Settled: only its current conformance — the newest version of its type
   it satisfies (§2.2, §5.6) — with its history in the conformance ledger (§5.7). "Conforms to versions
   X..Y" isn't needed.
3. **Survival rules** (§4): the full table of change kinds × dependent kinds.
4. ~~**Identity boundary guard.**~~ Settled (§3): warn, then delete the grants with the group on
   acceptance, recorded in the grants' history.
5. **Recomputation.** When a rule (or a schema change that affects evaluation) commits, how are existing
   items' markers brought up to date — eagerly, lazily, in bulk with progress? Does the impact preview
   (§5) double as that computation?
6. **Lightweight change sets before full changesets.** What's the minimal "bundle a change with its
   repairs" mechanism that works today, without the staged overlay?
7. ~~**Rule/data version awareness**~~ — answered: pipelines belong to type versions (§5.3, §6).
8. **Link subjects in marker decisions** (the work that prompted this): with §2.1 and §6 in place, a
   simple link test is valid for a max-1 link; loosening that link creates a revision whose pipeline
   uses a counting condition (§5.1); links that reach several items require a counting condition (any / all / none / at least
   N — "all" comparing against the total).
9. **Telling clients what conformance takes.** Clients don't target versions (§2.3), but shouldn't have to
   re-implement the conformance check to help users close gaps. Possibly both:
   - in the schema, what each version requires beyond the previous one — properties newly required, link
     minimums raised, controlled-list values no longer allowed;
   - per item, computed by the server, what it lacks for the next version ("To reach v2: vendor name,
     vendor contact") — the visible gap list of §2.3. Write responses could also return the version the
     item reached.
10. ~~**Live schema changes while a draft is open**~~ Settled (§5.4): refused where the draft reaches
    (offered "add to the draft"), live elsewhere; the draft's to-do list and preview track the live
    schema. Older versions are read-only (§5.1), so a draft always builds on the latest.
11. ~~**Which in-place changes reach older versions.**~~ Settled (§5.1): there are no in-place changes.
    A narrowing creates a new version; every other change creates a revision of the latest version.
    Older versions are sealed. Only names and presentation reach them.

---

## 9. Possible build order (tentative)

1. **Safety first** — evaluation never throws out of a marker decision; fail closed + record (§6, §7).
2. **Recomputation gap** — confirm, then fix: rule changes bring existing items' markers up to date.
3. **Schema ledger** (§5.5) — every schema change (including markers and decisions) appended as an
   entry, with the current tables as its register. Change sets, versions, the consistency gate, and
   provenance all build on it.
4. **Dependency graph + consistency checker** over today's definitions (markers, decisions,
   types/properties/links), gating every ledger append; replaces ad-hoc guards like
   `WorkflowReferenceScanner` and the marker-delete cascade.
5. **Element versions** (§5.1–5.2) — recorded by the entries that narrow an element; data records
   the newest version of its type it satisfies, with background promotion and its progress view (§5.6),
   recorded in the conformance ledger (§5.7).
6. **Conformance debt tools** (§2.4) — a likely first set: debt view, search by version and gap,
   per-item gap list, bulk fill.
7. **Change sets (light)** — one entry bundling a change with its repairs, validated and committed
   together.
8. **Impact preview** — marker changes a proposed change set would cause, and what it doesn't reach.
9. **Full changesets** — staging (uncommitted entries), sparse overlay, review/approval (design summary §6).
10. **Resume marker editor link work** on top of the above.

---

## 10. Consumption evolution (related, deferred)

Schema evolution keeps canonical data free to change. Consumers, especially other systems, are written
against a frozen picture of it. A rename, SINGLE → LIST, a removed property, a new required property, a new
controlled-list value: each is canonical and correct, and each can break an integration. People adapt;
systems break.

That's a separate but related concern: managing how **consumption** evolves alongside the schema. It isn't
part of this design; it's noted here so the schema design doesn't rule it out.

- **Schema evolution stays canonical.** Names belong to the element as a whole; a rename is a revision
  that reaches every version, recorded in the schema ledger (§5.5). No aliases. Projections depend on this (§5.1, §2.5); names that
  differ per version could only be considered alongside consumer views.
- **Visibility of change comes first, and is cheap:**
  - the schema ledger readable by consumers ("what changed since position N");
  - an impact preview naming the consumers that use what's changing ("renaming `title` affects 3
    integrations that read it in the last 30 days"). This needs usage tracked per consumer — cf. the
    `operations` partition in `ntrloc-index-reconciliation-summary.md`.
- **Consumer views, if ever added**: a named contract a consumer binds to, mapping its own names to element
  ids and choosing what it exposes.
  - They apply equally to **pull** (consumers reading, querying, writing) and **push** (ntrloc sending to
    other systems: events, exports, outbound integrations).
  - **Which pushes and pulls use which view must be clearly visible**, so a change to a view shows exactly
    whom it affects.
  - Open: whether a view is schema (a dependent in §4); how views are versioned (immutable once published,
    or versions of their own); writes and queries as well as reads; a default view (the schema's own
    names).
  - Never authorization: reading through a view goes through the same grants.
  - Never reshaping data across type versions: a v1 item seen through a view still lacks what v1 lacks.

---

## Appendix A. Change inventory

Every kind of schema change, by what it requires. *Provisional, like §5.*

- **V** — a new version of the element: its definition accepts less (§5.1). Never dependent on what data
  exists.
- **·** — a new revision of the latest version: everything that conformed still conforms.
- **C** — dependents (rules, label patterns, processes, …) may break and must be repaired in the same
  change set (§2.1).
- **?** — open; see the note.

Every change, whatever its mark, is recorded in the schema ledger (§5.5).

Markers don't need a column: schema changes don't change data, so markers change only when a marker
decision does, when a marker is deleted, or when an item is promoted to a version with a different
pipeline (§5.6).

### A.1 Properties

The same on item types, traits, and link types. The owner gets the version, and with it everything built
on the owner (§5.1): a trait's implementing types, a link type's item types on both sides.

| Change | | Notes |
|---|---|---|
| Add optional property | · | |
| Add required property | V | New items must supply it; existing items stay until they do (§2.3). |
| Optional → required | V | The new version's pipeline can rely on it; the old one's still handles absence. |
| Required → optional | · | Recorded — may be the mistake an audit has to find (§5.5). |
| Deprecate | · | Existing values stay; setting it is refused. |
| Un-deprecate | · | |
| Remove | ·, C | Leftover values ignored; setting it refused (§5.1). Refused while an older version with items reads it. Name reuse to verify (§7). |
| Change type | — | Not allowed: a property's type is fixed. |
| SINGLE → LIST / SET | ·, C | A pipeline treating it as one value is repaired in the same revision. Stored values untouched; reads tolerate both shapes (§5.1, §2.5). |
| LIST → SINGLE | V, C | Same property. Stored values untouched; items holding several values stay behind, flagged as excess (§5.1). |
| LIST → SET | V | Duplicates no longer conform. |
| SET → LIST | · | Order-sensitive rules to check (C). |
| Assign a controlled list | V | Existing free values may not be in the list. |
| Unassign a controlled list | · | |
| Switch to another list | V | |
| Rename | · | Recorded. Label patterns rewritten automatically; BPMN / DMN using the name flagged (C) (§5.1). Marker rules refer by id. |
| Description, facetable | · | |
| Move to another owner | V / · | Into a trait or supertype: types gaining it get V if it's required. Out of a supertype: siblings lose it — like removal (·). |
| Property groups (add, edit, move, delete) | · | Layout only. |

### A.2 Controlled lists

| Change | | Notes |
|---|---|---|
| Create list | · | |
| Add value | · | |
| Relabel value | · | |
| Reorder values | · | |
| Remove value | V, C | The list, and every type and link type with a property using it (§5.1). |
| Change a value's stored value | V, C | As remove + add. |
| Rename list | · | Recorded. |
| Delete list | · | Only when unused. |

### A.3 Links (each side belongs to the type at that end — §5.1)

| Change | | Notes |
|---|---|---|
| New link type, min 0 | · | |
| New link type, min ≥ 1 | V | For the item type on that side, like a new required property. |
| Raise min | V | The item type on that side only. |
| Lower min | · | |
| Lower max | V, C | The item type on that side only. |
| Raise max | ·, C | A pipeline relying on the old max is repaired in the same revision — the box set (§2.1, §5.1). |
| Target → a supertype, or a trait it implements | · | |
| Target → a subtype or another type | V | The link type, and the item types on both sides. |
| Remove link type | ·, C | Leftover links ignored; new ones refused (§5.1). Refused while an older version with items reads it. Today's delete semantics to reconcile. |
| Rename a side | · | Recorded. |
| Link properties | — | As A.1. A narrowing gives the link type a version, and the item types on both sides. |

### A.4 Item types and traits

| Change | | Notes |
|---|---|---|
| Create item type / trait | · | |
| Delete item type / trait | · | Only when unused (existing rule). |
| Rename, description | · | Recorded. |
| Display label pattern | · | Its property names are checked when it's saved. |
| Concrete → abstract | V | Existing instances no longer conform. |
| Abstract → concrete | · | |
| Change supertype | V, C | Gains inherited properties (V if any are required); links targeting the old supertype stop accepting it (V on their side). |
| Implement a trait | V / · | V if the trait brings required properties or link minimums. |
| Remove a trait from a type | V, C | Its properties go (·), but links targeting the trait stop accepting the type. |
| Trait narrows (e.g. gains a required property) | V | The trait and every implementing type (§5.1). |

### A.5 State machines

| Change | | Notes |
|---|---|---|
| Create, rename, describe | · | |
| Attach to an item type | · | Items aren't started automatically. |
| Detach or delete | V | Like deleting a state: items in the machine stay on their version until they leave it (§5.8). |
| Add state | · | |
| Rename state | · | Recorded. |
| Delete state | V, C | The state machine and the item type using it; items in the state stay on the prior version (§5.8). |
| Add transition | · | |
| Remove transition | ·, C | No data stops conforming; items may be left with no way forward. Its permissions go, so grants on them are orphaned (§5.4). |
| Rename a transition | · | Recorded. |
| Move a transition (from / to) | · | |
| Change a guard | · | Latest version only, like a pipeline edit (§5.1). |
| Change the start state | · | New items only. |
| Change a transition's process | C | Via the process's declared dependencies. |

### A.6 Markers, decisions, processes

| Change | | Notes |
|---|---|---|
| Create marker | · | |
| Rename marker | · | Recorded. |
| Delete marker | C | Decisions applying it must be repaired; refused while an older version with items applies it. Grants under it are orphaned (§5.4); items lose it. |
| Change a marker decision | · | A revision of the latest version (§5.1, §5.3); markers recalculated. |
| New process / decision (BPMN / DMN) version | C | Via declared dependencies (§3). |

Grants aren't schema (§3), so they aren't here.
