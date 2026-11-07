# ntrloc — Architecture Rationalization

> **DRAFT (2026-11-05, target settled 2026-11-06).** Written while designing the system change preview
> (`ntrloc-system-change-preview-design-notes.md`), which needs a clean set of seams to build on. Mapping
> the current component dependencies showed the system has grown into a tangle; this doc sets the target
> and the order of the refactor.

---

## 1. Goal, and what this isn't

**Goal:** an architecture in which each component has a clear responsibility, circular dependencies are
eliminated, and — where it simplifies the dependencies between components — classes are reorganized.

**Not a rewrite.** Existing classes are refactored into the target shape in small steps, the full test
suite green after each (`mvn clean install` from the repo root). Steps that change behavior (a security
fix, a new API shape) are called out as such (§9).

---

## 2. Rules

1. **Controller → Service → Store.** I/O (controllers, MCP tools, authentication, event listeners that
   start work) calls services and stores; nothing below I/O calls up into it.
2. **Stores are CRUD on persistent data.** Anything may read a store. Anything may write a store, unless
   a service maintains an invariant across it — then only that service writes it (the register and
   ledger are written only by Entity Write).
3. **A service exists when it adds logic** on top of store data: answers, invariants, orchestration.
   A thin pass-through service is a judgment call (readability, one seam for the preview to decorate);
   when it's just a pass-through, say why it exists.
4. **No cycles among services.** Exceptions are inversions that aren't calls: events, and process
   collaborators registered with Process Service (§6.5).
5. **Reads never depend on writes.** The read side can be called by anything; the write side may read;
   anything run in the middle of a write is given reads only and returns proposals (§5).
6. **A store's SQL touches only its own tables** (target; current exceptions are parked, §8). Reading
   another store goes through that store, so each store can be overlaid independently in the preview.
   When one query genuinely needs another partition's data (to sort or paginate by it), the owning
   store supplies that piece as a SQL fragment, and the querying store embeds it without knowing what's
   inside. Each store authors SQL only for its own tables, and the database still runs one query.
7. **Interface segregation on stores.** Read and write interfaces are separate (`<Name>StoreReader` and
   `<Name>StoreWriter`, both implemented by `<Name>Store`); only the owner of an
   invariant is given the write interface. Enforced by ArchUnit tests, not convention.

---

## 3. Terminology

| Term | Layer | Meaning |
|---|---|---|
| **Controller / Tools** | I/O | REST controllers; MCP tools (named `*Tools` — today's `ai.ProcessService` and `ai.SchemaService` are MCP tool classes and get renamed) |
| **Service** | Behavior | Adds logic on top of store data |
| **Store** | Structure | CRUD on persistent data, no logic (replaces `*PartitionManager` / `*Repository` where those are storage) |
| *Engine, Processor, Coordinator, …* | Inside a service | Descriptive names for a service's internal classes; not architectural roles |

"Projection" means *querying* the read model (the register). In CQRS terms the register is the read model,
the ledger the write model, and *posting* is what CQRS calls projecting; we keep our usage.

---

## 4. Target

```
I/O:  EntityController  SchemaController  …Controller     ProcessTools  SchemaTools  …Tools
      (+ authentication, event listeners that start async processes)
────────────────────────────────────────────────────────────────────────────────────────────
Services:

  Entity Write ──► Schema, Access, Process Service, Register / Ledger / Binary stores
  Entity Read  ──► Schema, Access, Register / Binary stores
  Schema       ──► Schema Store
  Access       ──► Access Store
  Principal Resolver ──► User Store
  Process Service    ──► Process Store
────────────────────────────────────────────────────────────────────────────────────────────
Stores:  Register  Ledger  Binary  Schema  Access  User  Process (Flowable)
```

| Component | Responsibility |
|---|---|
| **Entity Read** | Projections: compiles specs against the schema, filters by permission (marker semi-join), assembles results, adds binary metadata and labels. Reads binaries by item + property (§5.4). |
| **Entity Write** | The one write path: modification → validation → state machine processes → marker decisions → ledger + register transaction. |
| **Schema** | The system's definition, including process bindings (§6.3); consistency checks. |
| **Access** | Permission answers. Group memberships arrive in the principal; reads only grants. |
| **Principal Resolver** | Builds the principal (user, flattened groups, admin flag); provisioning; token lookup. Used by I/O. |
| **Process Service** | Typed boundary around Flowable (§6). |
| **Stores** | Register, Ledger, Binary, Schema, Access (grants, markers), User (users, groups, memberships), Process (Flowable's definitions and instances — our tables, Flowable the only writer). |

There is no BinaryService or UserService: no logic sits on top of those stores beyond what's listed.

---

## 5. The item component: projections and mutations

Item work has two themes; every item concern informs a question or constrains a change.

| Concern | Projection (Entity Read) | Mutation (Entity Write) |
|---|---|---|
| Schema | Compiles specs against it | Validates against it |
| Permissions | Filter reads | Gate writes |
| Markers | Decide visibility | Decided by the pipeline |
| Links | Traversed and nested | Created, updated, deleted |
| Binaries | Metadata added to results | Stored, validated |
| States | Read as data | Changed only by transitions |
| Ledger | Hidden | The record of every change |
| Register | The only thing read | Updated by posting |

### 5.1 Internals

```
 Entity Write                    │  Shared stores   │  Entity Read
                                 │                  │
 MutationProcessor               │  Register Store  │  ProjectionEngine
 MarkerDecisionEngine            │  Binary Store    │  BinaryReader
 LedgerRegisterCoordinator       │                  │
 BinaryWriter                    │  Ledger Store    │
 StateTransitionMutationAdvisor  │  (write side)    │
```

- **ProjectionEngine** (`org.ntrloc.graph.projection`) and **RegisterStore** replaced
  `RegisterPartitionManager` (§9 step 1): the engine resolves and assembles projections, the store owns
  every register table, reads and writes, and depends only on the database. They meet at an
  **ExplicitProjection** (below). Marker names come through the authorization store.
- **Whether Register and Binary should be one store is open (§10).** For now they're separate, and the
  one query that needs both uses rule 6's fragment.

**ExplicitProjection.** ProjectionEngine resolves a client's `ProjectionSpec` against the schema into an
`ExplicitProjection`; RegisterStore compiles that to SQL against its physical layout, runs it, and
returns rows keyed by id.

| | ProjectionEngine | RegisterStore |
|---|---|---|
| Names → ids (properties, group and binary-field paths, state machines, states, perspectives) | ✓ | |
| "All facetable" expansion, page clamping, cross-type representative type | ✓ | |
| Rejecting unknown names, and filter/sort/facet fields the caller can read through no marker (all request errors) | ✓ | |
| Item access ("has any of markers {…}") and link access (source marker grants the perspective; target type and target item readable) | resolves it | applies it |
| Filters, counts, facet GROUP BYs, typed sort, UNION across types, links nested by level | | ✓ |
| Ids → names, property and link-property read grants, controlled-list labels, binary metadata, states, edit permissions, display labels, marker names, state-facet ordering | ✓ | |

- The `ExplicitProjection` carries type ids, a condition tree over ids (`And`/`Or`/`Not`,
  `PropertyExists`, `PropertyCompare`, `InState`, `ItemIdIs`), access, facets and facet filters by
  property / state machine id, a sort key (system column, item type name, typed property, or a
  binary-field fragment from Binary Store, rule 6), a page, a link selection by perspective id (nested),
  link access, and whether to count. A single-item read is `ItemIdIs` with counting off.
- **What a caller can't read is left out, never refused.** A property or link the client names
  explicitly comes back for each item only if that item's markers grant it; otherwise it's absent, as
  if there were none. An error would reveal what it's meant to hide. (Changed 2026-11-06; both used to
  be request errors.) Naming a field to filter, sort or facet by that the caller can read through *no*
  marker is still a request error: that says something about the caller's own grants, not the data.
- Rows come back keyed by id: item id and type id, raw properties and states, audit, marker ids, binary
  ids by property, and links by perspective (each link's properties plus its target as a row).
- RegisterStore then depends on nothing but the database: its `SchemaManager`, `BinaryPartitionManager`
  and `ControlledListManager` dependencies move to ProjectionEngine, and its SQL stops joining
  `schema_item`, `schema_entity_link_perspective` and `schema_property` for names.
- **LedgerStore** (was `LedgerPartitionManager`) implements `LedgerStoreReader` and `LedgerStoreWriter`; only
  the coordinator holds the writer. It's hidden from projections; property
  history (`LedgerPropertyHistoryService`) is an audit-style reader beside the item component.
- **BinaryStore** (was `BinaryPartitionManager`) is one class for now, with no reader/writer split, and the
  BinaryReader / BinaryWriter services are on hold too: both wait on how BinaryStore fits with the
  register (§10 q7).
- **The write side reads its own stores directly, unfiltered.** Validation and marker decisions need the
  true data, independent of who's writing. Projections are always someone's question, so they're always
  filtered.

### 5.2 Transitions are mutations

"Execute transition T on item X" is a kind of mutation. There's no raw "set state" anywhere; the only way
to change state is a transition. MutationProcessor handles it like any other mutation and decides
whether it goes ahead. **StateTransitionMutationAdvisor** only advises what the transition involves (much as
the delete cascade expander says what a delete involves): it never checks permission, decides, or writes.

1. MutationProcessor resolves the item, the machine and T (at X's version), then checks the caller may
   start the machine or execute T. Permission comes first: without it nothing else is relevant.
2. StateTransitionMutationAdvisor checks X is in T's "from" state and the guard passes, then returns the changes that
   put X in the target state: the new state (or the machine ending), plus whatever exit A → T → entry B
   processes propose (§6), each reading through a read facade decorated with the proposals so far, as
   the initiator. Or it returns why T can't apply, which the processor reports as the request's rejection.
3. The processor validates and submits the whole mutation through the normal path — validation, markers,
   ledger, register — once, atomically.

If any process fails or a guard rejects, nothing is submitted. Starting a machine (START → first state) is
a transition like any other.

**Two kinds of check.** *May this caller do this* applies only to what the caller asked for: the
transition, and any changes the caller sent directly. Granting `transition:execute` grants whatever the
transition does; the admin who defined it made that decision, so a transition may set a property the
caller couldn't set themselves. *Is the result valid* (types, required properties not cleared, deprecated
properties not written) applies to every change, whoever proposed it. An optional property that later
becomes required is a narrowing under schema versioning (schema evolution notes), so a transition is
updated for the new version before it applies; no special case here.

**Provenance.** Every change — item properties, links, link properties — records both who initiated it
and what caused it: the caller directly, or "by proxy" through transition T (and later process P at
version N). The ledger already does this for marker decisions (rule, version, reason). The processor
therefore keeps the caller's changes and the expander's changes apart rather than merging them into one
diff. Needed once transitions propose property or link changes (§6); a v1 transition changes only state.

### 5.3 Pipeline order

```
mutation in
  → resolve targets, then permission   (what the caller asked for)
  → modification processes   (reshape; may add changes, incl. to other items)
  → transition expansion, if a transition   ("from" state, guard, state machine processes)
  → built-in + admin validation   (accept / reject; every change, whoever proposed it)
  → marker decision pipeline
  → coordinator: ledger + register, commit
```

Validation now follows transition expansion, so a transition's own changes are validated too.

The marker decision pipeline is the same architectural slot as the processes: admin rules applied in the
write path, returning a result.

### 5.4 Binaries are read by item and property

`GET /api/binary/{id}` today streams any binary to any caller with its id, with no item-permission
check. Target: binaries are read as "property X of item Y" through Entity Read, so item permissions
apply. Binary properties are SINGLE only and properties are leaves (no OBJECT type), so item + property
is a complete address. Uploads stay direct to Binary Store (no item exists yet); an upload is unreadable
until a mutation attaches it. Internal readers (enrichment) read by id.

---

## 6. Processes

### 6.1 Two families

| | Mutation-transactional | Mutation-independent |
|---|---|---|
| Examples | State machine (entry, exit, transition), mutation modification, mutation validation | Binary enrichment, item mutation automations, projection / report processes |
| Lives | Inside Entity Write, as steps of the write path | Above Entity Read / Write, as their clients |
| Runs | Straight through, inside the commit | Started by an event, a schedule, or a person; may wait, retry, pause |
| Can | Read (as the mutation's principal); return a result | Read and write through the normal interfaces, with an identity |

The test is "is it in the triggering commit?" — "also update X in the same commit" is a modification
process. "Synchronous" on the right means only that the caller waits.

### 6.2 Definitions live in Flowable

- A process definition exists once it's **deployed**: Flowable parses, validates, versions it, and
  assigns an id (`K:3:<uuid>`). Definitions are immutable; a change is a new version. Our tables
  (`process_deployment`, `process_resource`, `process_definition`, `decision_*`) hold them; Flowable is
  their only writer.
- **Definitions are deployed as a type** (stored as the deployment's category). Synchronous types are
  rejected at deploy time if they contain wait states (user tasks, timers, message waits) or async steps.
- **Processes are never started by key or "latest version"** — always by exact definition id.

### 6.3 Every process use is a schema binding

Every process depends on the schema, so there's no such thing as a non-schema process. Each use — a
transition's process, binary enrichment, an automation, an admin-runnable process — is a **binding** held
in the schema, pinning an exact definition id. Consequences:

- Staged vs. live comes from the staged vs. live schema, for every type, by one mechanism.
- A process change is a schema revision; sealed versions keep the processes they were committed with.
- The same consistency check covers processes as any other element; a breaking schema change carries its
  process repair in the same commit (schema evolution notes §5.1).
- Callers look up the binding in the schema in effect and hand Process Service the exact id.

### 6.4 Parameters

Processes declare **typed parameters** (e.g. `ownerProperty`: STRING, SINGLE; `productLink`: a link
perspective). A binding maps each parameter to a schema element **by id**. Collaborators accept only bound
parameters, never raw names; outputs use the same parameters, translated to ids by Process Service.

- **Complete compatibility checking.** Renames can't break a process (bindings hold ids); deletions and
  shape changes (STRING SINGLE → LIST) are caught against the staged schema.
- **Least privilege.** The item is a handle; its contents, its links, and the properties a process may
  propose changes to are reachable only if mapped. A process never sets state — the transition does.
  The initiator's grants apply on top.
- **Reuse.** One definition can be bound several times with different mappings.

### 6.5 Process Service and collaborators

- **Typed entry points per type** — `executeStateMachineTransition`, `executeBinaryEnrichment`, … — with
  typed inputs and outputs. Sync types: verify the instance *ended*; if not, fail and clean up. Async
  types: started through the async executor.
- **Collaborators per type:** `@ProcessAccessible(types = …)` on narrow facades (e.g. a read facade over
  Entity Read), replacing today's single global allowlist (`ProcessAccessibleBeansMap`).
- **Collaborators are registered, never injected.** Each facade sits beside the service it fronts and
  registers with Process Service. That's why Process Service depends only on Process Store, and why an
  async item mutation automation (facade → Entity Write → Process Service) isn't a cycle: it runs on
  another thread, in another transaction.
- **Facades are context-aware.** Process Service sets up an execution context — principal, live or
  preview, proposals so far for transactional types — per call for sync runs, rebuilt from instance
  variables on every resume for async runs. Identity is never an input; the collaborators already carry
  it, and a principal is re-resolved from its id on resume (memberships may have changed).
- **Contracts for the transactional family:**

| Type | Inputs | Output |
|---|---|---|
| Modification | mutation list | mutation list (provenance recorded: requested / modified by rule R / added by rule R) |
| Validation | mutation list | verdict (accept, or reject with reasons) |
| State machine | item handle, hook (exit / transition / entry, from, to, transition id) | mutation list |
| All | collaborators: read facade (decorated, bound to the initiator), schema view in effect | |

### 6.6 Previews and running instances

- A staged process is a definition only staged bindings point at; deploying it changes nothing live.
- Preview **instances** are tagged (likely a Flowable tenant id), kept out of live views and task lists,
  given simulated versions of outward-facing collaborators, and discarded on commit or discard.
- **Running async instances on a version a commit makes incompatible:** the admin chooses, per process:
  1. **Drain** — no new instances of the old version start; arriving triggers are queued and replayed
     under the new version after commit; the preview shows the count falling.
  2. **Terminate and restart** — as part of the commit, using each instance's recorded trigger inputs.
     The preview shows what each instance already did (mutations, tasks, reports) so repeats are known.
     Person-started instances are listed for manual restart.

---

## 7. Access and identity

- **Access Service** (`PermissionService` + `AuthorizationCacheManager`): loads all grants keyed by
  grantee (user or group), unions a principal's user and group grants at lookup, intersects with item
  markers. Depends only on Access Store.
- **Grants reference grantees by value** (`principal_type`, `principal_id`, no foreign key), so Access
  Store is independent of User Store.
- **Principal Resolver** (`org.ntrloc.graph.principal`) computes memberships (recursive CTE in User Store)
  when the principal is built, and is the only code that builds one. Membership changes, live or staged,
  need to show up; browser sessions don't yet (§8).
- `NtrlocPrincipal` is in a neutral package (`org.ntrloc.graph.principal`) so authorization doesn't import
  from security. The access admin controllers and startup initializers still look up users and groups in
  security; they're I/O, so that's allowed.
- **Callers never see the grant cache.** Access Store answers grant lookups from an in-memory snapshot it
  owns and rebuilds after each grant write, signalling other nodes the way Schema does.

---

## 8. Parked (known issues, not yet addressed)

- **Browser sessions hold a stale principal.** Local, LDAP, OIDC and SAML logins resolve the principal
  once at login and keep it in the session, so group membership, admin status and user deletion don't
  take effect until the session ends (a revoked admin stays admin; a deleted user keeps working). Tokens
  and processes resolve per request and aren't affected; grants are always current. Likely fix:
  `fromAuthentication` re-resolves by the session principal's id, once per request; a missing user ends
  the session. Found 2026-11-07 in step 7; a behavior fix, to do soon, outside the refactor.
- **Manual marker assignment bypasses MutationProcessor:** `ManualMarkerAssignmentService` prepares and
  commits marker postings through the coordinator directly, so it skips the pipeline (§5.3). The one
  exception to "only MutationProcessor calls the coordinator" in `ArchitectureRulesTest`.
- **Stores whose SQL crosses partitions:** `AuthorizationRepository` joins `schema_item` (item type
  names) and `register_item` / `register_item_marker` (an item's markers). Target: callers pass item
  markers in; names come from Schema.
- **Test names after the register split:** `RegisterPartitionManagerProjectionIntegrationTest` now tests
  ProjectionEngine (and `RegisterPartitionManagerBinaryDynamicPropertyIntegrationTest` the coordinator's
  reconciliation). Renaming means moving them out of the register test package, whose fixtures they share.
- **`BinaryDynamicPropertyRegistrar` reads `register_binary_property`** (how many items reference a
  binary, per property) when a binary is re-extracted. Target: it asks RegisterStore.
- **Store → service cycle:** `AuthorizationRepository` depends on `AuthorizationCacheManager` (`@Lazy`)
  to refresh the cache after writes. Target: the store publishes "grants changed"; the cache listens
  (it already does for other nodes via the cluster topic).
- **Orphaned grants:** deleting a user or group leaves their grants. The agreed behavior — warn, then
  cascade (schema evolution notes §3) — needs a small component above both stores.
- **Schema evolution doc mismatch:** Appendix A.5 treats state machines as attachable to item types; in
  the code a state machine belongs to one item type (`schema_state_machine.item_definition_id`).

---

## 9. Refactor order

The item component first — everything else calls it or is called by it.

1. **Split `RegisterPartitionManager`** into ProjectionEngine (read side) and RegisterStore. Removes the
   register's upward dependencies on Schema and Binary. The largest single move: ~3,100 lines, mostly
   moved, not rewritten. Sub-steps, suite green after each:
   1. State lookups move to Schema. *(Done.)*
   2. Binary attachment reconciliation moves to the coordinator, at commit. *(Done; this also fixed
      aborted transactions leaving reference counts changed.)*
   3. SQL moves behind `ExplicitProjection` (§5.1), one feature at a time: single-item read → filters →
      sort and pagination (with the binary-field fragment from Binary Store) → facets → cross-type →
      links. *(Done.)*
   4. Assembly moves to ProjectionEngine, working on `RegisterRows` directly. *(Done:
      `org.ntrloc.graph.projection.ProjectionEngine`; `RegisterPartitionManager` now depends only on the
      database.)*
   5. The remaining write-side SQL moves into RegisterStore; `RegisterPartitionManager` goes away.
      *(Done.)* Read/write interface segregation on RegisterStore is step 4's.
2. **Rename `LedgerPartitionManager` → LedgerStore**, with read and write interfaces. *(Done.)*
3. **Rename `BinaryPartitionManager` → BinaryStore.** *(Done.)* The reader/writer split (and the
   BinaryReader / BinaryWriter services) waits on §10 q7.
4. **Entity Read and Entity Write.** Split `EntityManager` along the two themes; I/O callers depend on the
   side they need. Add ArchUnit rules for store write interfaces and read/write direction. *(Done:
   `EntityReadService` (process-accessible as `${entityReadService}` for now) and `EntityWriteService`;
   `RegisterStoreReader` / `RegisterStoreWriter`; `ArchitectureRulesTest` checks that only the coordinator
   holds a store writer, stores are used through their interfaces, reads never depend on writes, and stores
   never depend on services.)*
5. **One write path.** Transitions become mutations: MutationProcessor resolves, checks permission, asks
   StateTransitionMutationAdvisor for the changes (§5.2), validates and submits. *(Done:
   `MutationRequestProcessor.processTransition`; `EntityWriteService.commitStateEntry` is gone; the guard
   reads the register; `ArchitectureRulesTest` checks only the processor (and, parked, manual marker
   assignment) calls the coordinator; item create checks permission before the abstract-type check.)*
   Provenance (§5.2) waits until transitions propose more than a state change.
6. **Access:** the grant cache becomes the Access Store's own implementation detail (fixes the `@Lazy`
   cycle); `NtrlocPrincipal` to a neutral package. *(Done: `AccessStore` (was `AuthorizationRepository`)
   with `AccessStoreReader` / `AccessStoreWriter`, answering grant lookups from a package-private
   `GrantSnapshot` it rebuilds after each grant write and on other nodes via the cluster topic;
   `AuthorizationCacheManager` is gone. `NtrlocPrincipal` is in `org.ntrloc.graph.principal` with a public
   constructor that ArchUnit restricts to `PrincipalResolverImpl` (§10 q10). ArchUnit also checks the
   access service and store don't depend on security.)*
7. **Principal Resolver** as the explicit identity component; confirm per-request resolution. *(Done:
   `PrincipalResolverImpl`, its `ExternalPrincipalResolver` / `InternalPrincipalResolver` interfaces and
   `NtrlocPrincipalHolder` moved into `org.ntrloc.graph.principal` beside `NtrlocPrincipal`, whose
   constructor is package-private again (settles §10 q10). Resolution is per request for tokens and
   processes but not browser sessions -- a behavior fix, parked in §8.)*
8. **Renames:** MCP tool classes → `*Tools`; remaining `*PartitionManager` / `*Repository` that are
   storage → `*Store`.

**Behavior changes, scheduled separately:** binaries read by item + property (§5.4); parked items (§8).

**New capability, after the refactor:** Process Service typed entry points, `@ProcessAccessible(types)`,
schema bindings with parameters, start-by-id only (§6). These change how processes are authored and
bound, so they're features, not refactor steps.

---

## 10. Open questions

1. Modification processes: do changes they add get modified too (run to a fixed point, with a loop
   guard), and may a rule remove a requested change?
2. Whose permissions apply to changes a modification adds — the requester's (current assumption), with
   errors that say the rule's change was refused? Transitions settled the other way (§5.2: the grant to run a
   transition covers what it does, recorded with its provenance); decide whether modifications differ.
3. Loops among mutation-independent processes ("item changed" → automation → item changed): no-op filter,
   plus a depth or causation limit.
4. Identity for mutation-independent processes: the triggering user, or their own service identity?
5. Where mutation-independent process bindings live within the schema, and how binary enrichment (likely
   system-supplied) is bound.
6. Preview instance isolation mechanism (Flowable tenant id or other).
7. **How does BinaryStore fit with the register?** One option is a single store. For: binaries are
   reached only through items, and the features that cross the line (sort by binary metadata, reference
   counts) need both. Against: the register is derived (rebuildable by posting the ledger) while binary
   content is source data with its own lifecycle (uploaded before attachment, content-addressed and
   shared, rewritten by enrichment, bytes behind storage adapters). Undecided; separate for now.
   BinaryStore's reader/writer interfaces, and the BinaryReader / BinaryWriter services, wait on this.
8. **Filtering on a property some items don't grant (revisit).** Filter, sort and facets accept a property
   the caller can read through *any* marker, then apply it to every item, including items whose markers
   don't grant it. Filtering `color = 'red'` can return a product whose color the caller can't see,
   revealing its value (sort order and facet counts leak the same way). Likely fix: apply each clause
   only to items whose markers grant that property. Found 2026-11-06; not yet changed.
9. **One predicate semantics for processes and guards.** A transition guard is evaluated in memory
   (`PredicateEvaluator`: values compared as strings or doubles, LIKE as a substring), while the same
   predicate as a projection filter is typed SQL. Decide what a predicate means when a process or guard
   evaluates it, so the two can't disagree. Deferred 2026-11-07, with processes in general.
10. **How is "a principal is only ever resolved" enforced?** Settled in step 7: the resolver and
    `NtrlocPrincipal` share `org.ntrloc.graph.principal`, so the constructor is package-private again
    (step 6 had used a public constructor plus an ArchUnit rule in the interim).

---

## 11. Future steps

- **Whole-item processes** (e.g. sending a full item to an external system): mapping every property
  defeats the purpose. A parameter whose value is a **projection spec** — "this shape of item" — still
  declared and checked against the schema. Overlaps consumer views (schema evolution notes §10).
