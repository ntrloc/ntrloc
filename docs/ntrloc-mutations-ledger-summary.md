# ntrloc Design Summary: Item/Link Mutations + the Ledger
## Topics: Write-Side Data Mutations, the Ledger, Prior Art from `domain-graph-starter`

This is a checkpoint of an in-progress design conversation, not a finished spec. Revise/refine in
place as the thinking develops further. This is the last major unaddressed piece of the platform
— register, projections, security/authorization, and binaries all have real design (and mostly
real implementation) behind them already; item/link data mutations and the ledger do not yet.

**Scope boundary, confirmed explicitly**: item/link mutations (this document's subject) are
**data** mutations — creating/updating/deleting actual item and link *instances* (a specific
Product, a specific link between a Product and a Contributor). They are a distinct category from
**schema mutations** — the `DefinitionMutation` pipeline (`CreateItemDefinitionMutation` and
siblings, applied via `SchemaManager.applyMutations()`) already built earlier this session, which
change what item types/properties/links/traits *are defined to look like*, not any particular
instance of them. Schema mutations are out of scope here.

**Atomicity contract, confirmed explicitly**: item/link mutations are submitted as a single
request and receive a single response — either complete success or total failure, never partial
application. This matches (and is now an explicitly reconfirmed decision, not just inherited
prose) the philosophy already quoted from the original design doc in Section 1 below.

---

## 1. Context: What Already Exists

Checked before starting, to avoid designing over something already there:

- **`LedgerPartitionManager`** (`domain-graph-experimental`) — an empty one-line interface stub,
  added in a single commit ("Starting to add in mutations/projections"), never fleshed out.
- **No item/link data-mutation endpoints exist** — `EntityController` only has the read-side
  `POST /entity/projection`. Nothing for writes.
- **`ntrloc-security-projections-summary.md`, Section 3 ("Mutations")** has real prior thinking,
  but at the philosophy/lifecycle level, not as a concrete Java shape:
  - Philosophy: a mutation is an atomic, all-or-nothing proposal — the client submits a complete
    unit of work; ntrloc accepts the entire thing or rejects it. No partial application, no
    session state. Omitted properties are left unchanged; explicitly nulled properties are
    cleared.
  - An 8-step lifecycle: authenticate → resolve principal; evaluate create-permission rules;
    validate proposed values against current schema; evaluate permissions for every property/link
    in the mutation (reject the entire proposal if any single check fails); preview marker
    assignment and check creator-visibility coherence (for creates); accept into the write layer;
    commit pipeline (persist to write-layer changelog → evaluate marker assignment rules →
    evaluate cascading rules on linked items/links → update the read layer); return the result
    projected through the caller's permissions.
  - This "write-layer changelog" is presumably the same thing my own memory (from a prior session,
    not yet validated against anything concrete) refers to as "the ledger" — an append-only source
    of truth, connected to the register (materialized current state) by "posting." Nothing in code
    confirms this shorthand yet; treat it as a hypothesis, not settled fact.
- No "posting" concept exists anywhere in code today.

---

## 2. Prior Art: `domain-graph-starter`'s Mutation Language

A real, fairly complete legacy mutation language already exists in
`org.ntrloc.graph.db.language.mutation` (and the sibling `org.ntrloc.graph.db.language.selectors`
package), executed against JanusGraph directly by `ItemManagerImpl`, with real consumers
(`MutationDataFetcher` for GraphQL, an AI-related `ItemService`). **Decision: prior art only** —
same treatment as projections got from the old selector-based query language: keep what's
genuinely useful, discard the rest, adapt nothing wholesale.

### Shape of the old language

- **`MutationRequest`** — a flat `List<ItemMutation>`. No top-level list for link mutations; link
  creates/updates/deletes are always nested *inside* an item mutation
  (`ItemMutationWithLinks<T>`), never submitted standalone. `ItemCreateMutation` nests
  `List<LinkCreateMutation>`; `ItemUpdateMutation` nests `List<LinkMutation>` (any of the three
  link mutation kinds).
- **`MutationResponse`** — flat `List<ItemMutationResponse>` + `List<LinkMutationResponse>`;
  nested link mutations get unpacked into their own response entries alongside item ones.
- **Polymorphism** via `@JsonTypeInfo(use=NAME, property="type")` + `@JsonSubTypes` — the same
  idiom already used repeatedly elsewhere in this codebase (`Predicate`, `FacetFilter`,
  `DefinitionMutation`): `ItemMutation` → `ItemCreateMutation`/`ItemUpdateMutation`/
  `ItemDeleteMutation`; `LinkMutation` → the equivalent three; `Property<T>` → `StringProperty`/
  `IntProperty`/`DoubleProperty`/`BooleanProperty`/`DateProperty`/`BinaryReferenceProperty`.
- **`refId` cross-referencing** (`ReferenceableItemMutation`) — a create mutation carries a
  caller-assigned temporary reference ID, so other mutations in the *same request* can point at an
  item being created in that same batch before it has a real persisted ID (e.g. "create a
  Product, and in the same request link it to a new Contributor" — the link references the
  Product by `refId`, not a real ID, since none exists until commit).
- **A selector/predicate language** for targeting existing items/links in updates and deletes:
  `IdSelector`, `ItemTypeSelector`, `HasPropertySelector`, `HasPropertyValueSelector`,
  `And`/`Or`/`Not` combinators for both items and links, plus comparison predicates
  (`Equals`/`NotEquals`/`LessThan`/`GreaterThan`/`Within`/`Without`).

---

## 3. Decision: Selectors Collapse Into `Predicate`

Direct precedent: selectors were originally used in the *old* projection/query language too, and
were collapsed into the new `Predicate` sealed interface (`AndPredicate`/
`PropertyExistencePredicate`/`PropertyValuePredicate`) when `domain-graph-experimental` was built.
Same move applies here — mutation targeting should reuse `Predicate`, not revive the old
Selector/ItemSelector/LinkSelector hierarchy as a parallel structure.

**Concrete gap this creates**: `Predicate` today only has `AndPredicate` — no `Or`/`Not`. The old
selectors had explicit `Or`/`Not` variants for both items and links. Collapsing into `Predicate`
for mutation targeting isn't free — it requires actually adding `OrPredicate`/`NotPredicate` to
close the expressiveness gap, not just pointing at `Predicate` as-is. **Not yet done.**

---

## 4. Open Question: Typed Property Values

The old `Property<T>` hierarchy is a *typed value wrapper* for what a mutation submits
(`StringProperty`, `IntProperty`, `DoubleProperty`, `BooleanProperty`, `DateProperty`,
`BinaryReferenceProperty`). `domain-graph-experimental` has no equivalent on the write side today
— the read side treats register properties as a loosely-typed `Map<String, Object>` parsed
straight from JSONB, with typing enforced only at the schema-definition level (the `PropertyType`
enum), never on individual submitted values. Projections didn't have an analogous decision to make
(there was nothing to carry forward or discard here), so this is a genuinely new question, not a
repeat of an old one:

- Does a mutation payload want typed value wrappers per property (reviving something like the old
  `Property<T>` shape), or
- Plain JSON values, validated against the schema's declared `PropertyType` only at
  mutation-acceptance time (matching how the read side already works, and requiring no new value
  type hierarchy)?

**Not decided.**

---

## 5. Mutation Kinds

**Item mutations** — Create, Update, Delete. All three settled, no debate.

**Link mutations** — Update settled. Create and Delete are needed, but *how* they should be
expressed was genuinely debated (resolved in Section 6).

---

## 6. Link Create/Delete: Standalone, Symmetric Endpoints

Initial leaning (matching the prior art in Section 2): nest link create/delete *inside* an item
mutation — the item connected to the new/removed link "hosts" it, matching exactly how the old
`domain-graph-starter` language worked. The philosophical basis offered for this: an item has a
lifecycle independent of anything else, whereas a link's lifecycle is bounded by the items it
connects — so link membership could reasonably be treated as part of an item's own mutable state,
the same way a property is.

That framing survives one objection and fails on a sharper one:

- It dissolves the "this forces an awkward, empty update mutation just to carry a link change"
  complaint — if link membership genuinely is part of an item's state, an update with no scalar
  property changes but a new link isn't vacuous; it's an honest statement that the item's state
  changed.
- It does **not** survive the permission question. If a link mutation is hosted under one item's
  mutation, only that item's permissions are naturally checked — a caller could link *their own*
  item to *someone else's* item they have no access to, purely by choosing which side hosts the
  mutation.
- On reflection, the philosophical framing itself was incomplete: a link's lifecycle is bounded by
  **both** connected items, not just the one chosen as "host." Nesting under a single item
  misrepresents that symmetric dependency — and that structural asymmetry is the same root cause
  as the permission gap, not a separate problem needing a separate fix.

**Resolved**: link create/delete are standalone mutations, submitted alongside item mutations in
the same atomic request, naming both endpoints as *peers* — not "host item + a reference to the
other one." This keeps the external mutation API/UI consistent: create/update/delete apply
uniformly to items, links, or a mix, all within one request. Permission checking for link
create/delete must be evaluated holistically across both endpoint items' permissions and the
link itself — there is no "host" to privilege.

---

## 7. The Local/Remote Reference Duality

**Terminology explicitly provisional** — "local," "remote," and `refId` itself are working
vocabulary for this conversation, not settled naming. Revisit before finalizing anything.

`refId`'s original purpose (Section 2): a client-assigned, request-scoped placeholder letting
later mutations in the same batch reference an item created earlier in that same batch, before it
has a real persisted ID. Generalizing that:

- **Local reference** — points at something introduced *within this same request* (via `refId`),
  not yet persisted.
- **Remote reference** — points at something that *already exists* in the persisted store (via a
  selector/predicate).

Applied across the mutation kinds from Section 5:

- **Item create** — always local. No reference at all; it introduces a new entity, optionally
  tagging itself with a `refId` so later mutations in the batch can point at it.
- **Item update / delete** — always remote. Must reference an existing item via selector/predicate
  — can't update or delete something that doesn't exist in the persisted store yet. Update
  additionally carries local *data* (the new property values), but the entity being modified is
  remote; only the payload of changes is freshly submitted.
- **Link create** — this is where local/remote stops being fixed by the mutation type and becomes
  a genuine **per-endpoint choice**. Each of the two peer endpoints can independently be local
  (`refId`, an item created earlier in this same batch) or remote (selector/predicate, an
  already-persisted item). All three combinations are legitimate: both remote (link two existing
  items), one local/one remote (create a new item and link it to an existing one), both local
  (create two new items in the same batch and link them together).
- **Link update / delete** — presumably always remote-referenced to an existing link, same
  reasoning as item update/delete. Not yet explicitly confirmed.

All of the above confirmed as correct in substance ("correct on all counts"); only the naming is
still open.

---

## 8. Ledger Entry Pattern Per Mutation Kind

| Mutation | Ledger entries produced |
|---|---|
| Item create | New entry, item's own stream |
| Item update | Appended entry, item's own stream |
| Item delete | See Section 9 — resolved, not a simple single-stream append |
| Link create | New entry for the link, **plus** appended entries for both connected items |
| Link update | Appended entry, link's own stream **only** |
| Link delete | Appended entry for the link, **plus** appended entries for both connected items |

This confirms (rather than contradicts) the Section 6 resolution: link *existence* changes
(create/delete) ripple out to both connected items symmetrically, matching "a link's lifecycle is
bounded by both items equally" — while a link *update* (a property change on an already-existing
relationship) doesn't ripple to the items at all, because the items' own linkage state hasn't
actually changed, only some attribute of the relationship has. Existence changes are shared
history; attribute changes are private to the link.

---

## 9. Item Delete Cascade

When item A is deleted, and A has an existing link to item B:

- **A** — gets exactly one entry: DELETE. No separate "link removed" entry for A — it would be
  redundant, since A's DELETE entry already supersedes everything about A at that point, links
  included.
- **The A↔B link** — gets a DELETE entry, cascaded as a direct consequence of A's deletion (a
  link cannot outlive an endpoint, per the ontology established in Section 6).
- **B** — gets an UPDATE entry, identical in kind to what an ordinary, directly-requested link
  delete would produce for the surviving side (per Section 8's Link Delete row). B has no other
  way to learn its link disappeared, and B's own state (what it's linked to) genuinely changed —
  from B's perspective this is indistinguishable from someone explicitly deleting the A↔B link.

---

## 10. Synchronicity and Atomicity

**The application of a mutation is never asynchronous, in any way, ever.** Ledger append, marker/
cascading rule evaluation, and register update all happen synchronously, within the same request
— never as a deferred or eventually-consistent background process. There is no window where a
mutation has "succeeded" but the register hasn't caught up; by the time a response returns to the
caller, the register already fully reflects the change.

Consequence: however far a cascade transitively reaches (e.g. item delete → cascaded link delete
→ update on a surviving connected item, potentially reaching further items transitively), all of
it completes before the response returns. A mutation that triggers a wide cascade is a slower
request, never one that finishes in the background. This is an accepted tradeoff, not something
to design around — it takes as long as it takes.

Implementation preference: a single database transaction is favored for achieving this atomicity,
but is not strictly required (see Section 11 — 2PC is the fallback if ledger and register are
ever not co-located in one transactional resource).

---

## 11. Two-Phase Commit: Why, and the Prepare/Commit Split

### The 2PC reference

Found in `docs/ntrloc-design-summary.md` (an earlier, broader design doc, distinct from
`ntrloc-security-projections-summary.md`), Section 6 ("Staging and Changesets"), in a table
mapping staged→live transitions across the system:

| Level | Staged form | Live form | Switch |
|---|---|---|---|
| Item/link write | UNCOMMITTED state in write layer | NORMAL state in read layer | 2PC commit |

This is exactly the ledger→register propagation discussed in this document, already named in
earlier thinking.

### Reconciliation with Section 10

Within a single domain, ledger and register can be tables in the same Postgres database (matching
every other partition in this codebase — schema, security, register are all just tables via one
`JdbcClient`), so a single ACID transaction gives the identical guarantee 2PC would, more simply,
with no actual two-phase protocol needed. **2PC's real justification is federated, cross-domain
supergraph transactions** — a single logical mutation spanning multiple independent domains
(separate deployments/databases by design) requires genuine distributed-transaction coordination,
since there's no shared native transaction across them. Single-domain mutations do not need this;
cross-domain ones will.

### Infrastructure already in place

The *actual, already-built* `register_item` table (in real use by `domain-1`/`pmdm-server` today)
already has `state`, `transaction_id`, and `commit_id` columns, and every existing query already
filters reads to `state = 'COMMITTED'`. Nothing currently writes anything but a hardcoded
`'COMMITTED'` state, since no mutation pipeline exists yet — but this is exactly the shape needed
for an UNCOMMITTED-then-COMMITTED mechanism. Treat this as a real head start: the register schema
appears to have been provisioned for this in advance, not something needing new columns.

### Adjacent, larger system (noted, not pursued now)

`ntrloc-design-summary.md` Section 6 also describes a much bigger "Changesets" system — staged
overlays, bulk schema/security/data changes, a two-version (live + at most one staged) constraint,
approval workflows. Related to this document's subject but broader than per-mutation atomicity.
Flagged for later, not designed here.

### Decision: design for the prepare/commit split now

Cross-domain federation "will come up, relatively soon" — so the split is being built into the
mechanism now, even though only the single-domain case is being exposed today:

- **Prepare** — validate the mutation (its own, deferred conversation) and write its ledger
  entries as UNCOMMITTED. The register is untouched; nothing becomes visible. This is where a
  participant can still say no (abort).
- **Commit** — flip the UNCOMMITTED ledger entries to committed, and synchronously update the
  register to match. Still fully synchronous, per Section 10 — prepare-then-commit is a directly
  coordinated two-step sequence, not silent eventual consistency.
- **Abort** — the UNCOMMITTED ledger entries are discarded/marked aborted; they never become
  visible; the register was never touched, so there's nothing to undo.

### Two-tier API surface

- **Domain-local mutation endpoint** (what this document's design targets): a single call.
  Prepare and commit happen internally, back-to-back; the caller sees only success or failure.
- **Future cross-domain/supergraph-coordinator-facing endpoints** (separate, later work): expose
  prepare, commit, and abort as independently callable operations, so an external coordinator can
  treat each domain's mutation processing as a genuine 2PC participant.
- The underlying mutation-processing mechanism must be built as genuinely separable steps
  (`prepare(request) → handle`, `commit(handle)`, `abort(handle)`) now, even though only the
  single-call door is exposed today — so the second door can be added later without redesigning
  the machinery behind it.

---

## 12. Component Boundaries: Ledger/Register Isolation and the Coordinator

**Hard invariant**: the ledger and register partitions must have **zero knowledge of each
other**. Neither package may import the other. This is a deliberate design constraint, not an
accident of how the code happens to be organized — it matches how every other partition in this
codebase already works (schema, security, authorization, register are each self-contained, with
no partition importing another).

This gives three components, not one:

- **`LedgerPartitionManager`** (already stubbed) — owns ledger-only concerns exclusively:
  appending entries, reading an item's or link's entry stream. Never references anything in
  `register`.
- **`RegisterPartitionManager`** (exists today, read-only so far) — owns register-only concerns
  exclusively: the materialized per-type tables, projection, (eventually) writing committed state.
  Never references anything in `ledger`.
- **A coordinator** (working name `LedgerRegisterCoordinator`) — the *only* component permitted to
  import both. Its job: given a transaction, pull the relevant set of ledger entries, translate
  each into the corresponding register write, and apply it. This is where "calculate and apply
  changes across the two partitions" lives — logic complex enough (per Sections 8-9's ledger entry
  rules and cascades, Section 11's prepare/commit split) to justify being its own class rather than
  folded into either partition manager or crammed into `EntityManagerImpl` directly.

Structurally, this mirrors a role `EntityManagerImpl` already plays on the read side: it composes
`RegisterPartitionManager` + `SchemaManager` + `PermissionService` for reads, and none of those
three reference each other — `EntityManagerImpl` is the one place that knows about all of them.
The coordinator is the write-side analog of that same pattern, specific to bridging ledger and
register. It likely also needs `SchemaManager` itself: translating a ledger entry's property
changes into the correct register columns requires knowing the target item type's shape, which is
schema knowledge, not ledger or register knowledge.

**Validation is explicitly called out as its own component too** — separate from the coordinator,
not a responsibility folded into it. The coordinator's job starts once a mutation is already known
to be valid; how validation itself works remains deferred (Section 4 / open items below).

The resulting call shape, not yet built: something above both (`EntityManagerImpl`'s write-side
counterpart, or a future `MutationController`) checks permissions, calls the validator, then calls
`LedgerRegisterCoordinator.apply(...)` — the coordinator internally drives
`LedgerPartitionManager` to append/read and `RegisterPartitionManager` to write, and is the only
file in the codebase that imports both packages.

---

## 13. Resolved So Far

- Item/link data mutations and the ledger are the one major remaining piece of the platform to
  design — register, projections, security/authorization, and binaries are already substantially
  covered.
- **Scope boundary**: item/link mutations (data instances) are explicitly distinct from schema
  mutations (`DefinitionMutation`, already built). This document is about the former only.
- **Atomicity**: a single request, either complete success or total failure — no partial
  application. Explicitly reconfirmed, not just inherited from old prose.
- The ledger is genuinely unimplemented (an empty stub), not partially built.
- `ntrloc-security-projections-summary.md` Section 3 already has real, usable lifecycle/philosophy
  thinking to build from, even though it's prose-level rather than a concrete shape.
- `domain-graph-starter`'s mutation language is prior art only — informative, not something to
  port wholesale; its JanusGraph-based execution (`ItemManagerImpl` and friends) doesn't carry
  over to the Postgres register/ledger model regardless.
- Selectors collapse into `Predicate`, matching exactly how projections handled the same old
  selector language.
- **Mutation kinds**: Item = Create/Update/Delete (all settled). Link = Update (settled), Create
  and Delete (debated, now resolved below).
- **Link create/delete are standalone, symmetric, peer-endpoint mutations** — not nested under
  either connected item. Permission checking for them must be holistic across both endpoints and
  the link itself.
- **The local/remote reference duality** (terminology provisional): item create is always local;
  item update/delete is always remote; link create is a per-endpoint choice of local or remote;
  link update/delete is presumably always remote (not yet explicitly confirmed).
- **Ledger entry pattern per mutation kind** — confirmed table in Section 8; item delete's cascade
  resolved in Section 9 (A: delete only; link: cascaded delete; B: update, same as an ordinary
  link delete).
- **Never asynchronous, ever** — ledger append, rule evaluation, and register update all happen
  synchronously within one request; a single database transaction is favored but not strictly
  required.
- **2PC's real justification is cross-domain federation**, not single-domain ledger/register
  propagation — reconciled in Section 11. The `register_item` table's existing `state`/
  `transaction_id`/`commit_id` columns appear pre-provisioned for exactly this mechanism.
- **Decision: build the prepare/commit split now**, even though only a single-call domain-local
  endpoint is exposed today — so cross-domain 2PC participation can be added later as a second,
  thinner door onto the same underlying mechanism, without redesigning it.
- **Ledger and register must have zero knowledge of each other** — a hard invariant, not a
  suggestion. A dedicated coordinator (working name `LedgerRegisterCoordinator`) is the only
  component permitted to know about both, translating ledger entries into register writes.
  Validation is likewise its own component, separate from the coordinator.

## 14. Explicitly Open / Deferred

- Whether `Predicate` needs `OrPredicate`/`NotPredicate` added now (required if mutation targeting
  is to fully replace what selectors offered) — identified, not built.
- Typed property values (Section 4) vs. plain JSON validated against schema — undecided.
- Whether "the ledger" and "posting" (from prior-session memory) are the right mental model has
  now been substantially confirmed by Section 11's discovery — but the exact terms "ledger" and
  "posting" themselves are still informal, not confirmed as final naming.
- The mutation request/response envelope itself — the old shape's nested-links-under-items
  structure is now explicitly rejected (Section 6), but the concrete replacement shape (top-level
  lists for both item and link mutations? something else?) hasn't been designed yet. `refId`
  cross-referencing and the create/update/delete-as-polymorphic-subtype idiom are both likely
  keeps, but not formally confirmed.
- Finalizing terminology for "local"/"remote" and `refId` — explicitly flagged as unsettled
  (Section 7).
- Whether link update/delete ever needs a local-reference option, or is always remote as assumed
  — not yet explicitly confirmed.
- Validation itself — explicitly deferred to its own conversation from the very start of this
  execution-focused thread.
- Whether marker-assignment-rule and cascading-rule evaluation happen during prepare, during
  commit, or split across both — not yet addressed.
- The exact shape of `prepare`/`commit`/`abort` (method signatures, what a "handle" actually is,
  how long a prepared-but-not-committed transaction may legitimately stay open) — named as a
  mechanism in Section 11, not yet designed in detail.
- The adjacent "Changesets" system (`ntrloc-design-summary.md` Section 6) — noted as related, not
  pursued.
- The coordinator's concrete interface (what a "transaction" argument actually is, whether
  `apply(...)` takes the prepare-phase handle from Section 11 directly, whether it needs
  `SchemaManager` injected or resolves shape some other way) — named and placed in Section 12, not
  designed in detail.
- The validator's interface and where it lives relative to the coordinator and `EntityManagerImpl`
  — named as its own component in Section 12, still entirely undesigned.

---

*Document generated from ntrloc design session — July 2026*
