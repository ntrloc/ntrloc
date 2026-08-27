# Marker/Permission Admin UI — Design Discussion (in progress)

Status: **exploratory, nothing decided or built yet.** This doc captures a design conversation
in progress so it survives a disconnect. It is not a spec.

## Where things stand (built and verified)

As of this conversation, the full marker read/write path is real and covered by the integration
test suite (629 tests green at last check). See `docs/ntrloc-acl-design-notes.md` for the full
design history; the short version:

- **Ledger**: `ItemCreateEntry`/`ItemUpdateEntry` (and link equivalents) carry marker changes as
  `Set<MarkerAttribution>` (`initialMarkers`/`markersAdded`/`markersRemoved`), written to
  `ledger_entry`.
- **Register**: `LedgerRegisterCoordinatorImpl.commit()` applies those to
  `register_item_marker`/`register_link_marker` via `RegisterPartitionManager.postItemMarkerAdd`
  etc.
- **Enforcement at projection time**: `PermissionService`/`RequestPermissionContext` gate
  `EntityManagerImpl.project()`; instance-level existence filtering is a SQL semi-join, per-property
  filtering is in-memory post-fetch; both are cached through `AuthorizationCacheManager`.
- **Marker attribution**: `MarkerAttribution` is a sealed interface — `RuleAppliedMarker(markerId,
  ruleId, ruleVersion)` (all mandatory; no producer exists yet) and `ManuallyAppliedMarker(markerId,
  userExternalId, reason)` (all mandatory; the only real producer today, via
  `MarkerAssignmentService`).

Not built: anything that actually *produces* a `RuleAppliedMarker` (the rule engine itself),
transition validity enforcement on state changes, mutation manipulation, and any admin UI for
markers/grants beyond raw service calls.

## Agreed next two steps

1. **Admin UI for markers/permissions management.**
2. **Actual marker assignment via rules (DMN, most likely).**

Sequencing rationale (agreed): the admin UI is the more bounded, lower-risk piece, built mostly as
screens over APIs that already exist (`MarkerAssignmentService`, `AuthorizationRepository`,
`AccessAdminController`), and it gives a way to *see* markers on an item before an automated rule
engine starts producing them — much easier to trust/debug the rule engine once there's a working
surface to verify its output against.

**Correction to note**: the user's intent is to approach **rule-applied markers before manual
ones** — rule-applied markers will be used far more often in practice. This may affect how much
the admin UI's first pass needs to support manual assignment (a full-featured manual marker editor
may not be the priority) versus surfacing/inspecting rule-applied markers once step 2 exists.

## Local dev environment (running as of this conversation)

- `domain-1` backend started via `mvn -q -pl runtimes/domain-1 spring-boot:run`, port **9090**.
  Depends on Postgres on 5432 (already running via Docker).
- Admin UI served by the backend itself at **`http://localhost:9090/admin`** (static resources
  mapped from `domain-graph-starter/src/main/resources/static/admin-ui/` — not a separate process,
  see `runtimes/domain-1/src/main/resources/application.yml`).
- Login: seeded local account **`admin` / `admin`** (`LocalAccountSeeder`, active when
  `graph.security.seed-local-accounts: true`, which it is in `runtimes/domain-1`'s config).
- Admin UI top nav observed: Search, Schema, Processes, Tasks, Access. The Access tab is presumably
  where marker/grant management would live, but as of this conversation it has not been inspected
  in depth, and instance-level marker assignment has no UI at all yet (only ever driven directly
  through `MarkerAssignmentService`).

## The core design challenge: markers/permissions are cross-cutting

Raised by the user: policy markers/permissions connect three things that live in different parts
of the system — users/groups, the permissions those principals hold for a marker, and (for some
operations) the item type whose schema those permissions actually reference. This makes a simple
flat CRUD table an awkward fit.

### The actual current data model (verified against migrations)

- **`authorization_marker`** (`V1_0_0_1__baseline.sql`): just `id / name / description`. No
  `item_type_id` column — nothing in the schema itself ties a marker to a type.
- **`authorization_grant`** (`V1_0_0_1__baseline.sql`, extended by `V1_0_1_7`): one row per
  `(marker_id, principal_type, principal_id, operation, property_id)`. `operation` is one of
  `item:create/read/delete`, `link:create/read/delete`, `property:read/write`,
  `link_property:read/write`, `binary:download`, `security:override`, `marker:apply/remove`.
  `property_id` is **required** for the four property-scoped operations
  (`property:read/write`, `link_property:read/write`) and **forbidden** (must be `NULL`) for every
  other operation — enforced by a CHECK constraint
  (`authorization_grant_property_scope_matches_operation`).
- **`authorization_item_type_grant`** (`V1_0_1_6__item_type_direct_grants.sql`): a wholly separate
  surface — `(item_type_id, principal_type, principal_id, permission)` where `permission` is
  `item-type:read` or `item-type:create`. **No marker involved at all.** This is type-level
  visibility, orthogonal to instance-level marker grants.

So the precise shape of the crossing: **principal → marker → operation**, and *only* when the
operation is one of the four property-scoped ones, **→ which property, which lives on some item or
link type's schema.** For `item:read`/`item:delete`/`link:read`/`link:delete`/creates, there is no
item-type dimension in the grant row at all today.

### The correction (user pushed back, correctly)

Initial framing ("markers carry no item-type scoping") was too literal — true of the raw schema,
but misses the marker's actual *purpose*: a marker exists to express one coherent bundle of "on an
item like this, here's exactly what this user can see and touch" (e.g. "read these 10 properties,
view these 3 links, edit these 2 properties, never edit links"). That bundle is only meaningful
against one item type's actual property/link set.

**Agreed**: that's the intended semantics. **Not yet resolved**: nothing in the schema or write
path enforces it today. Nothing stops someone from attaching a `property:read` grant for a Book
property and another for a Vehicle property under the same marker — nonsensical in practice, but
not caught anywhere (not a DB constraint, not validated in `AuthorizationRepository`, and there's
no admin UI yet to guide against it).

### Open question, unresolved as of this doc

**Should a marker's item-type scope become an enforced constraint, or stay a soft convention the
admin UI just guides toward?**

Complicating wrinkle raised: type inheritance is already live (`[[project_type_inheritance_consideration]]`
memory; schema inheritance + cross-type query engine shipped 2026-08-06). If a property is defined
on a supertype and inherited by subtypes, a marker granting read on that one `property_id` would
coherently apply to items of *any* inheriting subtype. So "item-type scoped" for a marker is likely
closer to **"one point in the type hierarchy, plus whatever inherits from it"** rather than "exactly
one concrete leaf type" — but this has not been confirmed with the user; the question is open.

A tentative, **not agreed**, admin UI shape was floated: a composed grant-builder flow (pick
principal → pick marker → pick operation → conditionally show a property picker scoped to schema
browsing) rather than a flat table with all columns always visible, plus a separate, simpler screen
for type-level grants (no marker in that flow at all). This has not been validated against the
scoping-enforcement question above, which should probably be settled first since it affects whether
the property picker in that flow needs to filter/restrict by the marker's already-established type
scope.

## Resolution: enforced marker scoping (decided direction, not yet built)

The open question above is resolved: **marker scope will be enforced, not just UI-guided.**

### Scope declaration

`authorization_marker` gains a mandatory scope: `scope_kind` (`ITEM_TYPE` / `TRAIT` /
`LINK_PERSPECTIVE`) + `scope_id`, CHECK-constrained so exactly one interpretation is valid.
Declared at marker creation time — not optional, not inferred later — matching the
"illegal states unrepresentable" pattern already used for `RuleReference`/`MarkerAttribution`.

### What scope governs, precisely (the asymmetry that matters)

- **Item eligibility** (which items/links this marker can be *assigned* to) goes **downward/outward**:
  - `ITEM_TYPE` scoped at X: eligible = X + all descendants of X (`resolveSupertypeInclusiveItemTypeIds`).
  - `TRAIT` scoped at T: eligible = any item type implementing T (`resolveTraitImplementerItemTypeIds`).
  - `LINK_PERSPECTIVE` scoped at perspective P (owned by item type X, resolving to link definition L):
    eligible = any link instance of link type L.
- **Property eligibility** (which properties a `property:read`/`write`/`link_property:read`/`write`
  grant under this marker can reference) goes **upward/inward only**, to whatever's guaranteed
  present on every eligible item/link:
  - `ITEM_TYPE` at X: X's own-declared properties + everything X inherits from its ancestors.
    *Not* anything declared only on a subtype of X (not guaranteed present on every eligible item).
  - `TRAIT` at T: exactly T's own declared property/link set.
  - `LINK_PERSPECTIVE` at P (→ link type L): L's own properties (link properties are symmetric —
    the same "role" property means the same thing regardless of which side you view the link from).

### The link-perspective resolution (settled)

Link markers are scoped via a **perspective**, not a link type directly, so every marker stays
rooted at one item type's vantage point (directly, via a trait, or via one of its perspectives) —
links don't get a structurally different scoping axis.

A link definition has exactly two perspectives (one per connected item type side). Scoping via
perspective P vs. the link type's *other* perspective P′ resolves to the **same eligible-link set
and the same valid-property set** either way — both belong to the same link definition, and link
properties are symmetric. So the perspective choice is **purely organizational/framing** (which item
type's marker list this shows up under in the admin UI), not a functional restriction. Confirmed:
nothing about the current query mechanics needs to change for this — `fetchLinksByItem` already
traverses per-item, so it's already perspective-aware at read time. This interpretation assumes
visibility does *not* need to differ asymmetrically by which side a link is viewed from; if that
turns out to be wanted later, it would need its own, separate design (not yet raised as a need).

### Enforcement points (unchanged from before, now including the link-perspective case)

1. Marker creation — scope is mandatory and validated against real schema/trait/perspective ids.
2. Grant creation (`property:read`/`write`/`link_property:read`/`write`) — reject if the referenced
   property isn't in the marker's scope-derived property set.
3. Marker assignment (`MarkerAssignmentService`/`postItemMarkerAdd`/`postLinkMarkerAdd`) — reject if
   the item/link's actual type isn't in the marker's scope-derived eligible set.

### Database-level implementation (converged, not yet built)

`authorization_marker` gains `scope_kind TEXT NOT NULL CHECK (scope_kind IN ('ITEM_TYPE', 'TRAIT',
'LINK_PERSPECTIVE'))` + `scope_id UUID NOT NULL`, **deliberately no FK** on `scope_id` — it
references one of three different tables depending on `scope_kind`
(`schema_item`/`schema_trait`/`schema_entity_link_perspective`), and Postgres can't express a
polymorphic FK. This matches an **existing precedent already in the codebase**:
`schema_entity_link_perspective.entity_id` is itself already polymorphic between `schema_item.id`
and `schema_trait.id`, with no FK, validated only at the application layer
(`SchemaMutationValidation.requireKnownItemOrTrait`). A new sibling validation function does the
same for the marker's three-way scope.

### Open sub-thread: "in use" gate correctness — parked, circle back later

Raised in the course of designing the scope-deletion guard (does deleting an item type/trait/
perspective a marker is scoped to need to be blocked?), and **deliberately parked** — not resolved,
not blocking marker-scope implementation, but worth returning to:

- **`isTraitInUse`** already checks both `schema_item_trait` (an item type implements the trait) and
  `schema_entity_link_perspective.entity_id` (a perspective targets the trait). No known gap.
- **`isItemTypeInUse`** currently checks *only* `register_item` for the exact type id — two possible
  gaps raised:
  1. Instances of a *subtype* of the type being deleted — likely **already moot**: `schema_item.
     supertype_id` has no `ON DELETE` clause (defaults to `NO ACTION`/`RESTRICT`), so Postgres
     already refuses to delete a type while *any* subtype schema row exists, regardless of whether
     that subtype has instances. Unconfirmed: whether any deletion path bypasses
     `SchemaMutationValidation` and could reach this state anyway.
  2. The type (or a subtype) being targeted by a link perspective — **a real, live, currently
     unhandled gap**, no FK protects against it the way `supertype_id` does for subtype existence.
     `isItemTypeInUse` needs the same kind of `schema_entity_link_perspective.entity_id` check
     `isTraitInUse` already has, expanded across the type and its descendants.
- Once fixed, the new marker-scope "in use" check (does an existing marker reference this
  item-type/trait/perspective as its scope) slots in as a third branch — but as an **exact**
  `scope_id` match, not descendant-expanded, since (per the point above) a supertype can't be
  deleted while any subtype exists anyway, so a descendant-scoped marker doesn't need to separately
  block an ancestor's deletion.

## The marker/grant ontology (worked out step by step; this is the load-bearing part)

This section is the result of a careful, deliberately slow conceptual walk-through — captured in
full because it's meant to be load-bearing for everything built afterward, and because getting the
direction of each relationship precisely right (not just approximately right) is the whole point of
having done it this slowly.

### Marker vs. scope vs. grant — three separate concerns, not one

- **A marker is inert.** `authorization_marker` is just `id/name/description` (+ scope columns,
  below). By itself it conveys **zero** permission. It's a label/tag, nothing more.
- **A marker's scope is about eligibility, not permission.** `scope_kind`/`scope_id` answer two
  questions and only two: which item instances is this marker *allowed to be assigned to*, and
  which properties may a grant under this marker *reference*. Scope says nothing about what anyone
  can actually do.
- **A grant is the only thing that conveys permission.** Default-deny: the absence of a grant for a
  given `(marker, principal, operation)` *is* the denial — there's no separate "deny" record.
  Grants are, in your words, "where the rubber meets the road."

Terminology correction made along the way: markers only ever *apply to* (are assigned to) item/link
**instances**, via `register_item_marker`/`register_link_marker` — never to a type. "Item-type
scoped marker" was shorthand for a marker whose *definition* is constrained to a type (eligibility +
referenceable properties), not a marker that attaches to the type itself. That's a different thing
from `authorization_item_type_grant` (`item-type:read`/`item-type:create`), which really is a
type-level, non-marker mechanism.

### Marker ↔ item-type association: direct vs. effective

- **Direct**: one item type can be the `scope_id` of many markers — `schema_item (1) —— (*)
  authorization_marker`. Nothing prevents multiple markers being scoped to the same type.
- **Effective**: the full set of markers assignable to an item of type Y is broader than Y's direct
  associations — it's Y's direct associations **union** the direct associations of every ancestor
  in Y's supertype chain. A marker scoped to X is eligible for item Y **iff X is Y's own type or one
  of Y's ancestors** (equivalently: X's eligible-item set is X plus all its descendants). Same fact,
  described from either end.

### The operation taxonomy — verified against actual enforcement code, not just the DB enum

`authorization_grant.operation` allows 14 values by CHECK constraint, but only some are real. Grepped
the whole codebase (`src/main` and `src/test`) for each:

**Live and marker-gated** — defined as constants in `PermissionService`, actually enforced, all of
them describing actions on an item/link that **already exists**:
`item:read`, `item:delete`, `property:read`, `property:write`,
`link:read`, `link:delete`, `link_property:read`, `link_property:write`.

**`link:create` — also live and marker-gated, but reasoned through separately.** At first glance it
looks like it belongs with `item:create` (both are "creation," both would seem to need a marker on
a not-yet-existent thing — impossible, chicken-and-egg). But it doesn't: a link always connects two
items that **already exist**, so `link:create` can be anchored to the *source* item's already-present
marker (checked via a specific perspective) at the moment of creation — no chicken-and-egg problem,
because the anchor (an existing item) is real. This is also what settles the `LINK_PERSPECTIVE` scope
kind's actual purpose: it's not just an organizational label for how link-property/link-visibility
grants get displayed — it's the natural, necessary anchor for `link:create` specifically, since
creating a link is inherently an action taken *from* an item, *through* a named perspective.

**`item:create` — genuinely different in kind, not just missing.** No pre-existing entity to anchor
a marker check against (the item doesn't exist yet — there's no "parent" whose marker could gate
this). Structurally can't be marker-gated. Lives entirely at the type level
(`authorization_item_type_grant` / `item-type:create`), which is a completely separate,
non-marker mechanism.

**Correction to `binary:download`**: not actually vestigial as a *concept*, only as the standalone
global operation the DB enum currently defines it as (that specific form has zero references and
stays dropped). Re-examined via the fuller object/verb pass below: `download` is a real, meaningful
**third verb on properties** (alongside read/write), not its own operation category — a binary
property's existence/metadata and its actual byte content are legitimately different permission
levels (you might see "this item has an attached PDF" without being allowed to pull the file). It
only applies when the property is binary-typed; binary properties don't support ordinary `write` at
all (`MutationRequestProcessor`: *"binary properties cannot be set via mutation"* — uploads are a
separate mechanism), so `read`/`download` is the real pair there, not `read`/`write`.

**Genuinely vestigial — zero references anywhere in `src/main` or `src/test`, defined only in the DB
CHECK constraint, and not reclassified by anything below**: `security:override`, `marker:apply`,
`marker:remove`. Not implemented, not enforced, not tested. Left as-is for now.

### The object/verb pass — five object kinds, not four

Working through every object a permission can act on for a given item, systematically:

| Object | Verbs |
|---|---|
| the item itself | view (`item:read`), delete (`item:delete`) — no create, no write |
| item properties | read, write, **download** (download only meaningful for binary-typed properties, replacing write there) |
| item links | read, create, delete — no write (existence-object, like the item itself) |
| link's own properties | read, write, download — same shape as item properties |
| **state transition** (new) | **execute** |

The state-transition row is new territory, not previously addressed anywhere in the marker/grant
work. A transition belongs to a state machine, which is scoped to an item type
(`schema_state_machine.item_definition_id`) — so it needs its own identifier, structurally analogous
to `property_id`, and inherits the *same* upward-scoping rule established for properties: a marker
scoped to X can reference transitions belonging to a state machine defined on X or one of X's
ancestors, never a subtype-only state machine (same "guaranteed present on every eligible item"
reasoning that governs property scoping).

Worth naming explicitly: this is a *third*, previously unaddressed dimension of the state-machine
work, distinct from the two already-known-deferred pieces — **transition validity** (is X→Y a legal
edge in the schema's transition graph; still deferred, not built) and **marker evaluation triggered
by a state change** (also still deferred, not built). "Who's allowed to execute a transition at all"
is a permissions question, independent of whether the transition would even be legal.

### Grant restructuring under consideration (not yet decided/built)

Raised, not yet finalized: split the current flat `authorization_grant` (one row per
`marker × principal × operation[× property]`, repeating the `(marker, principal)` pair once per
operation) into a normalized shape:

```
marker_grant(id, marker_id, principal_type, principal_id,
             item_can_read, item_can_delete)
marker_grant_property(marker_grant_id, property_id, can_read, can_write, can_download)
marker_grant_link_perspective(marker_grant_id, perspective_id, can_create, can_read, can_delete)
marker_grant_link_property(marker_grant_id, property_id, can_read, can_write, can_download)
marker_grant_transition(marker_grant_id, transition_id)   -- presence = can execute; no verb needed
```

**The governing principle, settled precisely**: whether a verb-set needs its own join table depends
entirely on whether the *object* those verbs apply to has real multiplicity for a given grant, not
on which object kind it is.

- **Item-level (`view`/`delete` on the item itself) stays flat on `marker_grant`, no join** — the
  object those verbs reference is always exactly one thing: *the item that carries the marker in
  question*. Strictly 1:1, so there's nothing to join against.
- **Link existence verbs (`create`/`read`/`delete`) need a real join, keyed by perspective** — this
  reverses an earlier mistake in this doc. The original reasoning ("a marker's scope fixes at most
  one link type, so these can be flat columns too") only holds for a marker scoped directly to
  `LINK_PERSPECTIVE`. It doesn't hold for an `ITEM_TYPE`-scoped marker, which can expose *several* of
  its own perspectives, each needing independently different verbs — exactly the original example
  ("can view these 3 links") requires three separate, independently-configurable connections, not
  one flat flag set.
- **Properties (item's own, and separately a link's own) and transitions were already correctly
  modeled as joins** — each is genuinely one-to-many per grant. Properties now carry three verb
  flags each (`can_read`/`can_write`/`can_download`) instead of a single read/write enum, per the
  object/verb pass above — `can_download` only meaningfully applies when the referenced property is
  binary-typed. `marker_grant_transition` is a plain existence join (like `schema_item_trait`) since
  `execute` is a single boolean-shaped verb, no read/write/download distinction needed.

`item:create` doesn't appear on `marker_grant` at all (it isn't marker-gated, per above); `link:create`
does, as one of the three verbs on `marker_grant_link_perspective`. The three genuinely-vestigial
operations (`security:override`/`marker:apply`/`marker:remove`) aren't reflected in this shape yet
since nothing implements them — revisit if/when they become real.

## Decision: markers apply to items only — link-targeted markers eliminated

Reconsidered and settled: **markers never apply to links.** `register_link_marker`, the marker
facets on `LinkCreateEntry`/`LinkUpdateEntry` (`initialMarkers`/`markersAdded`/`markersRemoved`),
`postLinkMarkerAdd`/`postLinkMarkerRemove`, and `LINK_READ`/`LINK_DELETE` as *marker-gated*
operations are all real, already-built, already-tested code from earlier this session — and all of
it is now slated for removal as part of the refactor. This is a genuine reversal, not just skipping
unbuilt work.

Reasoning:
- **No concrete use case ever motivated it.** Every example throughout this whole conversation
  frames links as things reachable *from* an item, governed by that item's own marker — never as an
  independently-classified instance in its own right. `register_link_marker` was originally built as
  a structural mirror of item markers (symmetry for its own sake), not from a validated need.
- **It's structurally incoherent given how link perspectives actually work.** A link-targeted marker
  would have to be scoped via one of the link's two perspectives — but both perspectives of a given
  link type resolve to the *identical* eligible-link-set and property-set (established earlier: the
  perspective choice is purely organizational). So a link-targeted marker would be forced to
  arbitrarily commit to one of two functionally-identical framings of something inherently symmetric,
  for no underlying reason.
- **It would force `marker_grant` to bifurcate** between item-shaped flags (`item_can_read`/
  `item_can_delete`) and link-shaped flags (`link_can_read`/`link_can_delete`), depending on
  `scope_kind` — real, avoidable complexity that only existed to support the capability above.
- **Nothing is actually lost.** `link:read`/`create`/`delete` don't disappear — `marker_grant_link_
  perspective` already fully covers them, anchored to the *item's* own marker. Link visibility
  becomes: item:read on the source, item:read on the target, and `can_read` on the source item's
  marker for that specific perspective. No independent link-level marker needed anywhere.

Net effect on the shape from the previous section: `marker_grant` is *only* ever item-shaped —
`item_can_read`/`item_can_delete` flat, plus the four child join tables (`marker_grant_property`,
`marker_grant_link_perspective`, `marker_grant_link_property`, `marker_grant_transition`). No
link-vs-item branching anywhere in the grant shape.

## Pre-refactor checklist — all confirmed closed

- **Clean-break migration, confirmed.** No real data exists anywhere; no backward compatibility
  needed for the old `authorization_grant` shape or `register_link_marker`. Drop and rebuild, same as
  every other restructuring this session.
- **Transition table verified.** `schema_state_transition(id, from_state_id, to_state_id, name, ...)`
  is real; `marker_grant_transition.transition_id REFERENCES schema_state_transition(id)` is a clean,
  unambiguous FK. Owning item type resolves via `schema_state_transition → schema_state →
  schema_state_machine.item_definition_id`, so the same upward-scoping rule as properties applies.
- **Link-marker elimination, confirmed** (see decision above).

## Next step

Ontology is fully converged; no open conceptual questions remain. Ready to move to implementation:
migration for `authorization_marker.scope_kind`/`scope_id`, the `marker_grant` + four child tables
(`marker_grant_property`, `marker_grant_link_perspective`, `marker_grant_link_property`,
`marker_grant_transition`), removal of `register_link_marker`/link marker ledger facets and their
tests, validation wiring (marker creation, grant creation, marker assignment against scope), and only
after that, the admin UI surface (currently nonexistent for anything marker/instance-level).
