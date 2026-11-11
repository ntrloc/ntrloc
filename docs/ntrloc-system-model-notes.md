# ntrloc System Model — Working Notes

Status: working notes, 2026-11-07. A checkpoint of the first round of a whole-system review, done to set
the ground for drafts and preview (`ntrloc-system-change-preview-design-notes.md`). Expect revision.

Related: `ntrloc-architecture-rationalization.md` (layers, stores, the refactor just finished),
`ntrloc-schema-evolution-design-notes.md` (versions, conformance, the schema ledger).

Each point is marked **Agreed**, **Proposed** (raised, not yet settled) or **Open**.

**Hard constraint (agreed 2026-11-09): nothing in this design may hurt live projection performance.**
Live projections are the critical path for the system as a whole, and every proposal here — partitions,
stores, services, views, drafts — is judged against that first. Projections within a draft are exempt:
they may be slower.

Earlier design docs (the design summary's "Staging and Changesets" section, schema evolution, system
change preview) are largely provisional: historical context to weigh as this review goes, not decisions it must follow.

---

## 1. The foundations

**Agreed.** Three foundational bodies of data, each with a lifecycle of its own:

- **Schema** — can exist before any users or data.
- **Users/Groups** — can exist before any schema or data.
- **Inventory** — stores what it's given without interpreting it. (Internal term, agreed 2026-11-07;
  was "Item Data": items, links, their history, binaries, and what ntrloc concludes about them.)

"Data is data" — one bucket for everything — is ruled out: every change, above all once drafts exist,
would have an unbounded blast radius.

**Agreed.** Some things will always cross whatever boundaries are drawn (a ledger entry's author, a
schema change's author, a grant's group and marker). Boundaries can't remove crossings; they can only
govern them.

---

## 2. Partitions

The working diagram has five partitions:

| Partition | Holds |
|---|---|
| **Schema** | item types, traits, links, properties, state machines, controlled lists, markers, marker decisions, permissions (what's grantable), schema ledger |
| **Users/Groups** | users, groups, group membership (incl. group-in-group), personal access tokens, IdP configurations |
| **Inventory** | ledger, register, binaries, item markers, dynamic binary properties, conformance ledger |
| **Access** | grants |
| **Process** | definitions, process instances, tasks, assignments, assignment candidates (process group membership) |

Placement notes:

- **Conformance ledger in Inventory** (agreed, by placement). Each entry is about one item and is written
  with it. Open: whether it stays a separate ledger or becomes item-ledger entries with a cause, as marker
  changes already are (`PipelineAppliedMarker`, `ManuallyAppliedMarker`). The latter was recommended;
  schema evolution §5.7 currently says separate.
- **Item markers in Inventory** (agreed, by placement). Like conformance, a conclusion ntrloc reaches
  about an item under the schema.
- **Dynamic binary properties in Inventory** (agreed, by placement): knowledge about the data, closer to
  an index than to schema; the schema stays wholly designed.
- **IdP configurations in Users/Groups** (agreed, by placement).
- **Process definitions and process groups** moved out of Schema into Process (latest diagram). See §5
  for the cycle this creates with today's bindings.

**Open: what a partition is.** The concept has become vague as the system grew. **Proposed:** a partition
is a **unit of change** — one owner writes it, one history records it, a draft or change set lives in it,
its consistency rules are checked inside it.

---

## 3. Crossing between partitions

**Proposed** rules:

1. **By id only.** No shared tables; no partition contains another's data.
2. **Directional.** The referring side depends on the referred side; the referred side never looks back
   across the boundary to check its own rules.
3. **Retire, don't delete.** Anything referenced from another partition stays resolvable — needed for
   honest history in any case (a user who made changes stays resolvable after they leave).
4. **Two kinds of reference:**
   - **Attribution** — who did this (item ledger author, schema ledger author, later a process instance
     as a "by proxy" cause). Meaning doesn't depend on the other side.
   - **Semantic** — meaning depends on the other side (property values on property ids, a grant on its
     marker, permission and group, a task on its item).
5. **Semantic references must be acyclic; attribution references may cycle.** An attribution cycle only
   requires the referenced thing to stay resolvable.

**Proposed:** the semantic references are the **blast-radius map.** A change can only reach the
partitions whose semantic references point at it; attribution carries no change. Previewing a change
means following semantic references back from the changed partition.

References in the working diagram:

| From | To | Kind |
|---|---|---|
| Schema | Users/Groups | attribution (schema ledger) |
| Inventory | Users/Groups | attribution |
| Inventory | Schema | semantic |
| Access | Schema | semantic (markers, permissions) |
| Access | Users/Groups | semantic (grantee) |
| Process | Schema | semantic |
| Process | Inventory | semantic |
| Process | Users/Groups | semantic (assignees, candidates) + attribution (run-as) |

Users/Groups references nothing.

Problems this explains, in today's code:

- **Orphaned grants** (rationalization §8): a semantic reference to a group with no rule for its deletion.
- **Schema delete checks for data** ("not in use"): the referred side looking back across the boundary
  (rule 2). Under schema versioning, removal becomes a new version and the look-back goes away.
- **`WorkflowReferenceScanner`**: the same look-back, for process deletes (§5).

**Open: cross-partition foreign keys.** `register_item.item_type_id → schema_item`,
`register_item.created_by/updated_by → security_user`, `ledger_entry.created_by → security_user`. Today
they're integrity; they also bind lifecycles together. With rule 3 they may be redundant, or the
enforcement of rule 3 itself.

---

## 4. Edge artifacts

**Proposed:** when something's meaning *is* a relationship between two partitions, it lives in a
partition that refers to both, not inside either. Grants (Access) are the clear case. Process bindings may
be another (§5).

**Open: versioning of edge artifacts.** A grant or binding refers to elements that will be versioned. Pin
the versions, or follow "latest" and be rechecked when either side changes? Too early to tell; decide it
once for every edge artifact.

---

## 5. Storage versus representation

**Agreed.** Where data is stored is a separate question from how it's presented. Three layers:

- **Stores** — each partition's tables.
- **Raw models** — each partition's data on its own terms (a state machine and its states; a set of
  process definitions), no cross-partition references resolved.
- **Synthetic models** — compositions across partitions (a state machine with its entry and exit
  processes), built for a purpose.

An admin looking at a state machine expects to see its processes; the synthetic model provides that
wherever the binding is stored. Reads may compose anything, so a synthetic model creates no dependency
between partitions — only writes and consistency rules do.

The code already has this shape: `SchemaStore` rows → `AdminSchemaView`; `RegisterRows` → projections.
It's also the shape proposed for the schema cache (rationalization §10 q11): the store keeps a raw
snapshot; the service builds synthetic views from it.

**Open: where process bindings are stored.** Today a state names its processes
(`schema_state.entry_process_id` / `exit_process_id`) and marker decisions name DMN decisions. With
definitions in Process, that's a semantic Schema → Process reference, and with Process → Schema, a cycle.
Options:

1. Bindings in Process — everything points Process → Schema; the schema no longer records its own
   behavior.
2. Definitions back in Schema — Process becomes runtime only; BPMN/DMN changes become schema changes.
3. Bindings as edge artifacts (§4) — a binding record referring to both; Schema and Process stay clean.

Option 3 follows the grants pattern; option 2 matches schema evolution's current notes. Not settled.

---

## 6. Drafts and preview

**Agreed.** Drafts and preview are the major new capability driving this review. (Called "staging"
until 2026-11-09; see §8.4.)

**Agreed.** Live traffic must always have an unambiguous path to live data; not every request goes
through a draft.

**Agreed (2026-11-09): drafts are strictly opt-in.** No change requires a draft; any change may still be
made directly to live. So live keeps moving while drafts are open — including schema, grants and groups —
and rebasing (§9.2) is a normal case, not an edge case.

**Agreed (2026-11-09): anything running inside a draft writes only draft data.** Processes, marker
decisions and every other element that modifies data, when run in a draft, can never modify live data.
It may read live (a draft reads live plus the draft). With draft services wired only to draft stores
(§9.3), this is structural rather than a check. **Open:** effects outside ntrloc (a process sending
email or calling an external system) — suppressed, redirected or allowed in a draft.

**Agreed** (now a hard constraint for projection, see top): drafts may add cost to preview, never to live. A preview is its own context — live data
with a draft overlaid, read at the raw-model level — so synthetic models are built the same way in both
contexts, and nothing on the live path asks whether a draft exists.

**Proposed:** a draft may span partitions (a schema change with the grants it needs), be
previewed as a whole and committed as one.

**Open:**

- How many drafts may exist at once (§9.2).
- Inventory. A drafted schema or grant change implies derived changes to items (conformance, marker
  decisions, visibility), potentially millions. These can't all be stored; they must be computed on
  demand, sampled or summarized. The blast-radius map (§3) says which to compute.
- Cross-partition commits today: a schema change creates register tables in the same transaction, via
  events. Whether dependents react afterwards or commit with the change is for the drafts design.

---

## 7. Drafts per partition

**Agreed.** A major reason for organizing the data into partitions: each will manage draft versus live
information in its own way.

**Proposed:** that is what a partition is — the data that is drafted together, in one way. (A sharper form of
"unit of change", §2.)

Prior context: `ntrloc-design-summary.md` §6 ("Staging and Changesets") already gave each level of the
system its own staged (draft) form, live form and switch.

### 7.1 A first sketch

**Proposed**, from the current code and notes; each row is a starting point, not a design.

| Partition | Draft form | Read in preview | Switch to live |
|---|---|---|---|
| **Schema** | a draft: the uncommitted schema-ledger change set (schema evolution §5.4–5.5) | live schema with the draft applied; synthetic views built from it | append the change set (a new version) |
| **Users/Groups** | pending users, groups, memberships | live rows overlaid with pending | apply |
| **Access** | pending grants | a grant snapshot built from live plus pending rows (cheap: grants are schema-sized) | apply; rebuild the snapshot |
| **Inventory** | (a) an item edit in flight — partly exists: ledger entries carry `state` and `commit_id`, and the coordinator prepares then commits; (b) effects of another partition's draft, computed and never stored | live, plus what's computed from the draft | (a) commit; (b) nothing to switch: the effects follow when the cause commits (conformance, marker re-decisions) |
| **Process** | new definition versions deployed beside the current ones; preview instances tagged (rationalization §6.6, §10 q6) | the tagged instances, kept out of live task lists | bindings move to the new version |

Inventory is the odd one out: the other partitions draft deliberate changes, while Inventory mostly shows
the *consequences* of other partitions' drafts — derived, and potentially enormous (§6).

### 7.2 A shared contract, not a shared mechanism

**Proposed:** each partition handles drafts in its own way, but answers the same four questions:

1. **What does a draft hold?**
2. **How is live-plus-draft read?** A preview has to see one consistent picture across every partition
   the draft touches.
3. **How does a draft commit?** Including taking part in a commit spanning partitions, if a change set
   may span them (§6).
4. **What does a draft change for the partitions that depend on this one?** The blast-radius map (§3) in
   use: Schema reports "property P is now required"; Inventory works out which items are affected.

**Open:** whether item edits in flight (7.1, Inventory (a)) and admin drafts are the same kind of thing —
both are "not yet live" — or different enough to keep apart. Partly answered by §7.3: items authored
*inside* a draft are draft data, distinct from the two-phase commit of a live edit.

### 7.2.1 Grants (decided 2026-11-09, Phase 1.0)

Grants reach the rest of the system only through the in-memory grant snapshot (renamed **`GrantsView`**
in step 1.1: every grant, shaped for permission checks, one instance per context, replaced whole on
change). No projection SQL reads the grant tables. So a draft needs its own `GrantsView` and nothing more
on the read path.

**Agreed:**

1. **What a draft holds:** overrides, not a copy -- one row per permission the draft changed: the
   principal, the permission, the value the draft sets (on or off), and the live value when it was set
   (its *base*). Everything else comes from live.
2. **Live-plus-draft:** `DraftGrantStore` yields the live grant records with the overrides applied; a
   per-draft `GrantsView` built from them feeds that draft's permission checks. It's rebuilt when the
   draft changes or live grants change (the existing cluster topic). The live path is untouched.
3. **Apply:** in one transaction, each override is compared with live. Live still at base: written
   through `GrantService`. Live already at the draft's value: nothing to do. Live changed to something
   else: a **conflict** -- apply stops and lists them, resolved per permission (keep the draft's value
   or take live's). Rebasing is implicit: live changes to permissions the draft didn't touch flow
   straight through, so grants need no draft-wide base version. *Found in step 1.7:* a permission is
   on or off, so live can't hold "something else" -- grants never conflict on value. Apply reports each
   override as applied, already live, or dropped (point 4). The sync conversation after Phase 1 may
   revisit what else should count as a conflict.
4. **Dependents:** only permissions (what projections show and what actions are allowed in that draft);
   no stored data depends on grants. An override whose target live has since deleted is ignored on read
   and dropped on apply.

**Physical:** one table, `draft_grant_override`, keyed by draft and the permission descriptor the Access
UI already uses (principal; kind; item type, marker, area, target; op), plus `value` and `base_value`.
No foreign keys on the target (hence the orphan rule above).

### 7.3 Items authored inside a draft

**Agreed (2026-11-09):** Inventory has draft stores too. Example: a draft narrows an existing item type,
so a new version of the type sits in the draft item type store. To try it out, someone creates an item of
that type — and that item lands in the draft's ledger and register, never live ones. So a draft's
Inventory holds two different things: (a) consequences of the draft for existing items, computed (§6);
(b) items authored inside the draft, stored.

**Open:**

- Does a draft also hold edits to *existing* live items (copy-on-write overlays, with tombstones for
  deletes), or only new items?
- What happens to draft-authored items when the draft is applied: discarded (try-out data), promoted to
  live, or chosen per item? Promoting means re-appending to the live ledger, with its own attribution.
- References only run from draft to live (a draft item may link to a live item, never the reverse), so a
  draft item's link can go stale if the live item is later deleted — part of rebasing (§9.2).
- Side effects of authoring in a draft — marker pipelines, state machine entry processes, notifications
  — must stay inside the draft (Process: preview instances, §7.1).
- Binaries are content-addressed and shared; a draft upload may share storage with live content, so
  cleanup on discard needs care.
- Physical form. One candidate: a Postgres schema per draft holding the same table shapes as live,
  so segregation is structural and discarding a draft is `DROP SCHEMA ... CASCADE`; reads within the
  draft combine live and draft tables (allowed to be slower: the projection constraint covers live only).

---

## 8. Vocabulary and layers

### 8.1 "Model"

**Agreed.** Use "model" only in the MVC sense: data **plus** the actions on it, not data alone. "Raw
model" and "synthetic model" (§5) are retired pending better names. ntrloc isn't assumed to be MVC; it may
be closer in places than expected.

**Open:** whether thinking in terms of data + operations is worth adopting, and what to call it.

**Agreed (2026-11-09):** the logical layer's per-concept components are **not** called models (§8.5).

Observation: the code currently splits each concept's data and its operations — the data in records
(`ControlledListStore.ControlledList`, `AdminStateMachineView`), the operations in services
(`ControlledListService`, `StateTransitionMutationAdvisor`). The `Admin*View` records also serve twice:
as what the system reasons with and as what the admin API sends, so an admin-screen need changes the
internal representation.

### 8.2 Logical and physical layers

**Agreed.** External interaction with the system happens against a **logical layer**, which translates it
into operations on a **physical layer** (the stores). Callers speak the system's terms — item types,
items, transitions, grants, drafts — never storage shapes.

The logical layer, as sketched:

- one service per partition: User, Inventory, Grants, Schema, Process;
- services spanning partitions: **Inventory Conformance** (Inventory × Schema; stored in Inventory, reasoned
  across both) and **Access** (the decision "may this principal do this", from grants, the item's markers
  and the principal's groups).

**Proposed**, more cross-partition services: process bindings (Schema × Process), marker decisions
(Schema × Inventory), projections (Inventory × Schema × Access — mostly a view for a consumer, though
guards and marker decisions reason over the same composition).

**Proposed:** cross-partition services are where the work of drafts concentrates — "which items does this draft
affect" is a Conformance question. A preview is a logical concept; each partition realizes it physically
in its own way (§7).

**Agreed:** the rules change from "anything may read a store" to **only the logical layer touches stores**.
Current mismatches, to fix later:

- admin controllers translating store rows themselves (`UserGroupAdminController`,
  `AccessAdminController`, `MarkerAdminController`, `MarkerDecisionPipelineAdminController`);
- `DomainInitializer.initData(JdbcClient, BinaryStore)` — a domain seeds inventory with raw JDBC, skipping
  validation, markers and the ledger;
- `WorkflowReferenceScanner` reading schema and access tables directly.

Fixing the admin controllers isn't just tidying: once drafts land, a controller that reads a store
directly sees only live data and can't show a draft or a preview. Anything that has to be
preview-aware has to go through the logical layer.

**Open:** one store per partition, or a family (Inventory today is the register, ledger and binary
stores; Schema is `SchemaStore` and `ControlledListStore`; Users/Groups includes the IdP stores; Process
is Flowable's tables plus `ProcessGroupStore`). Stores may not divide cleanly by partition, and that
may be fine: the partition boundary binds the logical layer, not necessarily the physical one. Today's
store packages don't follow partitions either (`MarkerDecisionPipelineStore` sits in authorization;
Process storage is mostly Flowable's persistence, not a store). We'll find out as drafts are designed.

### 8.3 "Service"

"Service" is used for at least four different things in the industry (deployable unit, operation with no
natural owner, use-case coordinator, wherever logic goes), and in ntrloc it mixes them: `SchemaService`
runs a use case, answers queries, holds schema knowledge and keeps a cache.

**Proposed:** the logical layer is the architectural concept. Inside it, models (per partition and
cross-partition) hold the knowledge; **coordinators** handle one requestable operation each — gather,
call models, commit, own the transaction and authorization — with no domain knowledge of their own. If
"service" survives, it means only that coordinator role.

**Superseded 2026-11-09** by §8.5: "service" names the per-concept components (what this section called
models). Whether coordinators remain a separate role, and under what name, is open.

Risks noted: the logical layer is a layer, not a unit, and without the models inside it becomes a new
catch-all; moving operations into models is design work, not a rename, and waits until the vocabulary
settles. No code changes until then.

### 8.4 Draft, preview, apply

**Agreed (2026-11-09):** **draft** is the term for a set of changes held on the server and not yet live.
"Staging" is retired: many organizations use it for their last non-production environment (UAT,
perf), so it would be ambiguous. "Changeset", "workspace" and "proposal" were considered; "workspace"
stays the runner-up if a draft comes to feel more like a place than a document. "Proposal" could later
mean a draft submitted for approval.

**Proposed:** a **preview** is reading live data through a draft. **Agreed (2026-11-09):** **applying** a
draft takes it live; the alternative is **discarding** it.
"Apply" rather than "commit", which already means a database transaction commit; `applyMutations`
already does this for a single batch.

Collision to resolve: the admin UI uses "draft" for an editor's unsaved, browser-side state
(`stateMachineDraft.js`, `pipelineDraft.js`, the edit dialogs). Those take another name ("edits",
"unsaved changes") when the code work starts.

### 8.5 Store, Record, Service, View

**Agreed (2026-11-09):**

- **Store**: physical access to one body of stored data.
- **Record**: data as a store yields it. Data only.
- **Service**: the logical-layer component for one concept *as a whole* (all users, not one user): it
  gathers records from stores, holds the rules and operations, and produces views. `UserService`,
  `DraftUserService`. "Service" is generic but not misleading; "model" would mislead, since a model reads
  as one thing (or as plain data), and these handle a whole body of data.

**Proposed:** **View**: data shaped for a consumer; a service may have several. Data only.

**Open:** whether coordinators (one requestable operation, owning the transaction and authorization,
§8.3) stay a separate role, and what to call them if so.


### 8.6 Granularity

**Proposed tests:**

- **Store**: one way of storing data and one way of drafting it (draft data lives at the store level,
  §9.3). Stores needn't match partitions or tables.
- **Every table and column has exactly one owning store.** Current violation:
  `ControlledListStore.setPropertyControlledList` writes `schema_property.controlled_list_id`.
- **Service**: one set of rules that must be checked together. Merge when an operation checks both in one
  transaction; split when they never consult each other's data, or when a concept's sides have very
  different dependencies (Inventory's write and read sides).
- **Each store has exactly one service that writes it**; services may read more widely. So a service is at
  least as large as the stores it writes. Opposing force: every service has a draft twin. Agreed
  (2026-11-10): services needn't match stores one to one — `AccessService` writes both `GrantStore` and
  `MarkerStore`, since a marker's rules (its grants go before it) span both.
- **View**: one per kind of consumer, named for what it carries (`MarkerPermissionsView`), rather than one
  view everyone picks from (`SchemaView`).

Where the code stands: Inventory already has a single writer (the coordinator writes the ledger and the
register); Schema nearly does (`SchemaStore` is written through the appliers inside `SchemaService`);
`UserStore` has 19 dependents, many of them writers, and no service in front of it.

**Proposed (2026-11-09):** **partitions name services, not stores.** "Schema" is a partition and a
service; a store named for a whole partition says nothing. Controlled lists are part of the schema, so a
`ControlledListStore` next to a `SchemaStore` is incoherent: either the schema's stores are per element
(item types, traits, properties, links, state machines, markers, permissions, controlled lists, the schema
ledger) under one `SchemaService`, or `SchemaStore` absorbs controlled lists. The diagrams already draw
per-element stores (`MarkerStore`, `PermissionStore`, `GrantStore`). Same question for `UserStore`
(users, groups, membership, tokens). Inventory's stores divide by storage shape (ledger, register,
binaries), not element.

**Proposed (2026-11-09): stores own their storage, not transactions.** Some overlap between stores is
unavoidable: keys and foreign keys have to line up, and a constraint can reach across (deleting a
controlled list detaches properties through `ON DELETE SET NULL`). So coordinating transactions across
stores is inevitable, and the transaction boundary — the unit of consistency for an operation — belongs
to the logical layer: the service for a one-service operation, a coordinator for a write spanning
services (applying a draft is the decisive case). Store methods that need atomicity internally (a
read-modify-write, `createList`'s insert plus `CREATE TABLE`) would *require* a transaction without
starting one (`Propagation.MANDATORY`), which makes the rule checkable. Post-commit effects follow
locality (agreed 2026-11-10): one that concerns only a store's own data (rebuilding `GrantsView`, telling
other nodes) is the store's, run when the caller's transaction commits; one that depends on the outcome
across stores (events about a whole operation) is the calling service's or coordinator's. Previews need one consistent read
across many stores — again a logical-layer transaction.

Today, transactions are already mostly in the logical layer (`MutationRequestProcessor`,
`LedgerRegisterCoordinatorImpl`, `SchemaService`, `ExternalUserProvisioner`, `SamlProviderService`).
Exception: `BinaryStore` — `updateMetadata` starts its own transaction, and the binary insert relies on
running outside one so its event fires after its own statement commits.


### 8.7 A first sketch of stores

**Agreed (2026-11-09): why separate stores at all.** Separate stores let us reason about drafts area by
area, within reason. What a store can settle on its own is its draft *mechanism*: how draft rows are held,
read alongside live, and discarded. A draft's *implications* — a narrowed item type's effect on
properties, lists and items; applying atomically; references from draft to live; side effects — cross
stores by nature and belong to the logical layer. This is not a test of a store's boundaries: if stores
had to contain their implications, the whole of Schema and Inventory would collapse into one store.

**Proposed (2026-11-09)**, from the 58 tables in the baseline migration plus the dynamic ones (one value
table per controlled list; the register's per-type tables). Tables written together as one thing share a
store; every table has one owner. A starting point, not a design.

| Partition | Store | Tables | Draft twin? |
|---|---|---|---|
| Schema | ItemTypeStore | `schema_item`, `schema_item_trait` | yes |
| | TraitStore | `schema_trait` | yes |
| | PropertyStore | `schema_property`, `schema_property_group` (incl. `controlled_list_id`) | yes |
| | LinkTypeStore | `schema_link`, `schema_entity_link_perspective` | yes |
| | StateMachineStore | `schema_state_machine`, `schema_state`, `schema_state_transition` | yes |
| | ControlledListStore | `schema_controlled_list` + one value table per list | yes |
| | MarkerStore | `authorization_marker` | yes |
| | MarkerDecisionPipelineStore | `marker_decision_pipeline`, `marker_decision_pipeline_watch` | yes |
| | SchemaLedgerStore | (planned: the schema ledger) | — |
| Access | GrantStore | `authorization_item_type_grant`, `marker_grant` and its six child tables | yes |
| Users/Groups | UserStore | `security_user`, `security_local_credentials`, `security_personal_access_token` | probably not (below) |
| | GroupStore | `security_user_group`, `security_user_group_member`, `security_user_group_member_group` | likely |
| | LdapConfigurationStore, OidcProviderStore, SamlProviderStore | `security_ldap_config`; `security_oidc_provider`; `security_saml_provider`, `security_saml_provider_key` | no |
| Inventory | LedgerStore | `ledger_entry` | yes |
| | RegisterStore | `register_item`, `register_link`, `register_item_link_perspective`, `register_item_marker`, `register_binary_property` + per-type tables | yes |
| | BinaryStore | `binary_content` | yes |
| | BinaryDynamicPropertyStore | `binary_dynamic_property`, `binary_property_dynamic_property` | yes |
| Process | (Flowable persistence, as is) | `process_*`, `decision_*` | per §7.1 |
| | ProcessGroupStore | `process_group`, `process_group_member` | likely |

Notes:

- About 19 stores against today's 14; most of the growth is `SchemaStore` splitting and `UserStore`
  shedding groups.
- **Agreed (2026-11-09):** the only stores with no draft twin are `UserStore` and the IdP configuration
  stores (LDAP, OIDC, SAML). IdP configs map only email and display-name attributes today; if they ever
  map group claims to groups, that changes. `UserStore` still matters to drafts the other way round: a
  live change (a user deleted or deactivated) can invalidate a draft that refers to them — rebasing
  (§9.2). Inventory's ledger and
  register do (§7.3), corrected 2026-11-09.
- No `PermissionStore`: permissions are defined in code, not stored.
- **Credentials are never drafted (agreed).** And a draft user couldn't log in: authentication is always
  live, and there's nothing to authenticate against until the user exists live. Users mostly arrive
  through IdP provisioning, which is live too. **Proposed:** what's worth drafting in Users/Groups is
  groups and membership (they change access); to try out access in a draft, an admin previews *as* a
  user — the draft evaluates that principal's access without authenticating as them.
- **Agreed (2026-11-09):** credentials stay in `UserStore` rather than a separate `CredentialStore`;
  revisit if that becomes a problem. (The case for splitting was least privilege: password and token
  hashes reachable by fewer classes.)

**Open:**

- Whether `ItemTypeStore` and `TraitStore` merge (traits mirror item types closely).
- Whether markers and marker decision pipelines are Schema (as drawn) or Access.
- Whether IdP configuration and credentials belong to Users/Groups, or to a system-configuration area
  that's never drafted.
- `RegisterStore` stays whole: projection queries join across all of it, and the projection constraint
  (top of this document) rules out anything that would slow them. Any split would be in code
  organization only, never in how projection queries reach the data.
- Where process bindings live (`schema_state`'s process ids, transitions' `process_id`, pipelines naming
  DMN tables): with `StateMachineStore` as today, or as an edge artifact (§5).
- Whether `binary_dynamic_property` is Inventory or Schema: it defines property paths, which is
  schema-like, but it's discovered from content.

---

## 9. Separating draft requests from live requests

Starting with controllers.

### 9.1 Controllers

**Proposed:**

- **The context is part of the address.** Live URLs stay as they are; everything under a draft lives
  under `/api/drafts/{draftId}/...`. Not a header: a request that loses a header silently becomes live,
  it's invisible in logs and links, and caches can mix the two.
- **Live controllers know nothing about drafts.** No optional draft parameter, no branches. Draft
  requests get their own controllers (or at least their own mappings); both call the same logical layer.
- **The context is passed explicitly** to the logical layer as a typed value (live, or a draft), not in
  a thread-local or the security context, so no request reaches a service without saying where it
  operates.
- **Apply is the only draft request that writes live.**

| Kind | Reads | Writes |
|---|---|---|
| Live | live | live |
| Draft authoring (incl. preview) | live + draft | the draft only |
| Apply | the draft | live |

Fits the current code: schema changes already arrive as `DefinitionMutation` batches, so authoring a
draft could be posting the same mutations to the draft, which holds them instead of applying them.

**Open:** which controllers need a draft counterpart (admin, certainly; search and projection, for
preview reads; item writes and MCP tools likely live-only for now — see §7.2's open question); draft
permissions (author, preview, apply); a schema stream (SSE) per draft for the admin UI.

### 9.2 How many drafts at once

**Open.** Unclear how practical several drafts at once would be.

Observations:

- Conflict handling is needed even with a single draft: live keeps changing underneath it (IdP
  provisioning changes users and groups, inventory changes constantly), so applying a draft means
  revalidating it against live as it is then. With several drafts, once one is applied the rest face the
  same check, so draft-against-draft conflicts largely reduce to draft-against-live.
- What several drafts really cost: preview work multiplied (Inventory's computed effects, per draft);
  each partition's draft storage keyed by draft (Process: definition versions and preview instances
  tagged per draft); and the UI needing a way to choose and switch drafts.
- A middle path: key everything by draft id from the start (`{draftId}` in URLs, draft id in storage),
  but allow only one open draft at first. Lifting the limit later changes no addresses or tables.

**Agreed (caveat):** in a several-drafts world, applying one draft means every other open draft has to
be **rebased** onto the new live state: re-checked, and possibly needing its author to resolve conflicts.
That implies each draft records what it's based on (the live version of each partition it touches), so
a draft that's out of date can be detected, not just assumed current.


### 9.3 The draft path

From diagrams 2026-11-09 (Store → Record → Model → View; then the same with drafts). The diagrams said "Model"; per §8.5 these
are services.

**Agreed:**

1. **Draft data is expressed at the store level**, each draft store implementing it however suits that
   store (`DraftUserStore` over `UserStore`, `DraftGrantStore` over `GrantStore`). A draft store yields
   the same records as the store beneath it: live plus the draft.
2. **Live and draft services differ only in which stores they talk to**; the logic in them is identical
   (`UserService` reads `UserStore`; `DraftUserService` reads `DraftUserStore`). Both produce the same views.
3. The purpose is to keep the **draft path separate from the live path**. The live path has no draft code
   in it at all.

Consequences, **proposed**:

- Dependencies run one way: draft stores read their base stores, never the reverse. Checkable with
  ArchUnit (nothing on the live path refers to `Draft*`).
- Live services stay plain singletons; draft services are built per draft (a factory keyed by draft id).
- Parity test, nothing faked: a draft service over an empty draft produces exactly the view the live service
  does.
- Applying a draft is the one flow from draft to live (draft store → base store), kept outside both
  services as its own operation.
- **Agreed (2026-11-09):** whether a service reads another concept through its service or through its
  stores is largely situational. Example: the inventory mutation service takes schema views from
  `SchemaService` rather than reading the schema's individual stores. **Proposed** rule of thumb — go
  through the owning service when the answer must be assembled from several stores, is interpreted by
  the owner's rules (e.g. controlled-list value normalization), or comes from a snapshot the owner keeps;
  read a store directly only when one raw record is the whole answer. Writes always go through the
  owning service (§8.6). Service-to-service reads must not form a cycle.
- Services may read records from other partitions' stores (`UserService` reads markers and permissions):
  the draft overlay in the stores keeps previews correct either way. Whether to restrict it anyway, for
  coupling and blast radius, is a weaker preference, left **open**.

**Open:**

- How the identical logic is shared — a shared body of operations over records, with the two services
  differing only in what they gather; a base class; or composition.
- Queries pushed down to SQL (search and projection over the register) don't fit a record overlay
  easily; they may need query rewriting, sampling or another approach.
- Cached snapshots (`GrantSnapshot`, now `GrantsView`, §7.2.1; the schema cache) need an instance per draft — which suggests caches
  belong in the store layer too (rationalization §10 q11).

Vocabulary from the diagrams: see §8.5.

---

## 10. Outbound integrations

**Proposed (2026-11-09), new concept:** **outbound integrations** — system-level definitions of where data
leaves ntrloc: transmission endpoints, email recipients and the like. Processes (and anything else that
sends) refer to an integration by id rather than holding addresses inline.

- **Draftable**, like any other definition: a draft can add or change integrations.
- **Suppressed within a draft.** Anything running in a draft that sends through an integration sends
  nothing. In theory entirely and silently suppressed; one variant is suppressed but *recorded*, so the
  preview can show what the draft would have sent ("this change would send 3 emails").
- Brings a side benefit: one inventory of everything that leaves the system, for audit and security.

**Open:**

- Where it sits: its own partition, or part of Process. Its lifecycle is largely independent of
  processes; Process → Integrations would be a one-way semantic reference (§3).
- Secrets for integrations (API keys, SMTP credentials): stored with the definitions, and how a draft
  changes them.

**Agreed (2026-11-09): the guarantee covers integrations, not scripts.** Script tasks will be open to
admins, with full Java access (`ImportingFlowableScriptEngine` leaves Nashorn's `Packages`/`Java` globals in
place; Groovy is configured at the classloader), so a script can call out directly and bypass
suppression. That's the admins' decision; ntrloc can warn against it but can't prevent it. **Proposed:**
warn where a script is authored or deployed, and let scripts know they're running in a draft (a binding),
so admins can guard their own outbound code.

---

## 11. The draft itself

**Agreed (2026-11-09):** everything above hinges on a draft existing, so a draft needs a lifecycle of its
own:

1. **Start** a draft.
2. **Add participants.** Non-admins take part in a draft by invitation only.
3. **Apply or discard** the draft.

**Agreed (2026-11-09):**

- **Admins** start drafts, invite participants, and apply or discard drafts. Admins have access to every
  draft by definition; they are never invited.
- **Non-admins** have no draft access until an admin adds them to a draft.
- **Participation only shifts mode** — the participant works with that draft's data instead of live. It
  confers no permissions of its own: inside the draft, a participant can do exactly what their
  permissions allow. (Those permissions are evaluated against the draft's own groups and grants, so a
  draft that changes them changes what the participant can do there — the draft's content does that, not
  the participation.)
- Confirmed: a user's permissions in a draft are those **effective within the draft**, including any an
  admin changed in it.
- **UI:** a user with draft access gets a control to switch into and out of draft mode, and a clear
  indication whenever they're in it.
- **UI (2026-11-09):** the control sits at the right of the header. Admins always see it, with "New
  draft" (a draft has a name only, for now). A non-admin sees it only while invited to at least one
  draft, never with "New draft". A tab whose draft is no longer available goes back to live.

**Proposed:** the mode is the UI's choice of address, not server state — in draft mode the client sends
requests under `/api/drafts/{draftId}/...` (§9.1). Nothing on the server remembers which mode a user is
in, so one browser tab can be live while another is in a draft, and a request can never land in the
wrong mode because of stale session state.

**Agreed (2026-11-11):** the tab holds its mode in memory only, so a reload returns it to live. Restoring
the mode across a reload (perhaps from server-side client state, alongside user preferences) is left
**open** for a later phase.

**Proposed:**

- A draft is itself live data — a draft registry (id, name, owner, status, participants, and the base
  version of each partition it touches, for rebasing, §9.2). The registry is never drafted.
- Participants refer to users by id (draft → Users, one way).
- Status: open → applied or discarded; perhaps an "applying" step while it's rebased and validated.

**Agreed (2026-11-09, Phase 1.0):** registry tables `draft` (id, name, created by/at, status, closed
by/at) and `draft_participant` (draft, user). One open draft at a time for Phase 1, enforced by the
service; everything keyed by draft id, so lifting the limit is policy, not schema. Base versions are per
partition and only where a partition needs them (grants don't, §7.2.1).

**Open:**

- Whether a participant can belong to several drafts at once (§9.2), and so whether the switch picks a
  draft or just toggles.
- What a participant's writes in a draft are, beyond try-out items (§7.3).

## 12. What Phase 1 taught us (grants, 2026-11-10)

The first slice (`ntrloc-drafts-plan.md` Phase 1) took grants through the whole draft design. What it
confirmed, changed or raised:

1. **Conflicts depend on the value's domain.** A permission is on or off, so live can never hold a value
   that conflicts with a draft's override (§7.2.1 point 3): apply reports each override as applied,
   already live, or dropped. Each later partition has to say what its values are before it can say what
   a conflict is; the sync conversation after Phase 1 takes this up.
2. **Views carry their rules** (§8.5). `UserGrantsView` -- one user's grants, built once per request
   from a `GrantsView` -- answers `mayReadItemType`, `mayReadItem(markers)` and the rest. Live and a draft
   build the same view from different `GrantsView`s, so the rules exist once and the live path has no
   draft code (§9.3, confirmed; ArchUnit enforces it).
3. **Preview needs no draft projection engine.** Draft projection and schema endpoints read live's items
   and schema with the user's draft grants, through the same `EntityReadService` and `SchemaService`
   methods live uses. Answers part of §9.1's open question: grants admin, projection and schema have
   draft counterparts; item writes stay live-only and the UI turns them off in a draft.
4. **Caching per draft, invalidated after commit.** A draft's `GrantsView` is cached against the identity
   of the live view it was built from, so a live change rebuilds it without being told; a draft change or
   a draft closing evicts it on every node through a cluster topic. The same shape is planned for group
   memberships in Phase 2 (plan, Phase 2 groundwork).
5. **Closing races writing.** A write into a draft holds the draft open (`FOR SHARE`) and apply or
   discard locks it (`FOR UPDATE`), so nothing lands in a closed draft. Every draft store will need the
   same hold.
6. **No foreign keys, so validity is checked twice:** when an override is written (`GrantsApi.requireValid`)
   and at apply, where an override whose principal or target is gone is dropped and reported.
7. **Apply lives with the lifecycle.** `DraftService` applies, writing through the services that own live
   data (`AccessService` for grants); no apply service of its own. Overrides stay after a draft closes,
   as the record of what it changed.
8. **A user's groups are fixed at sign-in** for session logins (personal access tokens and processes
   resolve them each time). Fine while groups aren't drafted; Phase 2 reads memberships from a cache
   instead, never a query per request (plan, Phase 2).
9. **Impersonation is needed.** The admin UI is admin-only and an admin's grants allow everything, so
   what a non-admin sees in a draft can only be checked through the API. Seeing the system as a given
   user is needed in a later phase (plan, "Impersonation").
10. **Live projections held their speed.** Benchmark `20261110-220149-phase1-close` against the Phase 0
    baseline: every median within the +15% threshold, most slightly faster. Largest rise: a single item
    with links for an admin, 4.43 → 5.00 ms (+12.9%), matching a marker lookup step 1.6a added for admins
    on single-item reads. Removed afterwards: the view's marker-scoped rules take the lookup and make it
    only for a non-admin.
