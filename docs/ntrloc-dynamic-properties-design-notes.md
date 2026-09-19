# ntrloc Dynamic Properties & Media Processor Integration — Design Notes

## Companion to ntrloc-acl-design-notes.md (permission model) and ntrloc-design-summary.md (traits, property groups)

This is a checkpoint of an in-progress design conversation, not a finished spec. It started from a
narrow, concrete goal — give domain-graph-starter an optional client for the media-processor gRPC
service — and widened considerably once "apply the results back to the item" ran into the schema's
strict, pre-declared-property model: machine-derived metadata (EXIF, IPTC, XMP, and generally
anything a process discovers rather than a human enters) can't be fully anticipated by admins in
advance, and forcing it through the same governance the rest of the schema uses doesn't fit.

Status: only §1 is built. §4–§8 record decisions reached in conversation but not yet implemented —
they amount to a rework of how the *schema* is stored (the ledger and register are untouched, see
§4). Where something is still open it says so, and everything unresolved is collected under "Open
questions" at the end.

**2026-09-19 correction to an earlier draft of these notes:** the first version of §4 claimed read
and write permissions treated object properties asymmetrically (read all-or-nothing at the
container, write leaf-only). That was wrong — register storage is flat by leaf id, so both sides are
already per-leaf. See §4 for the corrected finding; the conclusion it supported (objects are purely
structural) got stronger, not weaker.

---

## 1. Media processor client (built)

- New module, `media-processor-api`, sibling to `domain-graph-starter` in the `ntrloc` reactor —
  owns `media_processor.proto` and its generated classes (moved out of `ntrloc-media-processor`,
  which now depends on the same artifact instead of compiling its own copy). Deliberately
  transport-agnostic: depends on `grpc-stub`/`grpc-protobuf`/`protobuf-java` only, never
  `grpc-netty-shaded` — every `io.grpc.*` import the generated code actually uses was checked
  directly; zero references to `io.grpc.netty.*` anywhere in it. Netty is a runtime transport
  choice made by each consumer, not something the shared module should force on either side.
- `domain-graph-starter` depends on it as a plain, non-optional Maven dependency, plus
  `grpc-netty-shaded` for its own client-side transport. `org.ntrloc.graph.mediaprocessor.
  MediaProcessorClientConfiguration` — a `@Configuration` class gated by a single
  `@ConditionalOnProperty("media-processor.url")` — produces a `ManagedChannel`
  (`destroyMethod = "shutdown"`) plus `ImageProcessorStub`/`VideoProcessorStub` beans. No
  `@ConditionalOnClass` needed: the dependency is hard, not optional, so the classes are always on
  the classpath — same shape as `ClusterConfigurationFactory`'s Hazelcast dependency (also hard,
  also gated by a single `@ConditionalOnProperty`, no class-presence check anywhere in that family
  either).
- `ntrloc-media-processor` (in the `ntrloc-experimental` reactor) needed zero application-code
  changes — `java_package` stayed identical, so every existing `import
  org.ntrloc.mediaprocessor.grpc.*` still resolves against the externally-built jar.
- Verified: `domain-graph-starter` 707/707 tests (including two new tests for the conditional
  bean — no beans without the property, all three beans with it), `ntrloc-media-processor` 7/7
  tests (including a genuine in-process gRPC round-trip exercising the relocated generated
  classes), full `package` build producing all four jars (plain/server/client/video-client).

---

## 2. The processing workflow (proposal)

Worked example: a Photo gets uploaded; admins want an original-size (converted), medium, and
thumbnail rendition generated automatically, asynchronously, after creation.

- **Trigger.** No existing "item created/modified" event exists today — checked directly:
  `RegisterPartitionManager`/`MutationRequestProcessor` (the actual item-instance mutation path)
  publish nothing. The only existing `ApplicationEventPublisher` usage
  (`ItemMutationApplier`) is schema-mutation-level, a different layer entirely. This needs
  building: a new item-lifecycle event published wherever `CREATE`/`UPDATE` mutations actually
  commit, translated by a listener into a Flowable Signal or Message start event
  (`runtimeService.signalEventReceived(...)`).
- This isn't a new pattern for the framework to learn — `ProcessRunAsUserListener`'s own comment
  already names it directly: *"a timer/signal/message start event never goes through
  `ProcessAdminController.startProcessInstance`, so nothing ever sets the principal variable a
  script might need"* — i.e. signal/message-triggered async process starts with no HTTP caller
  behind them were already anticipated and partly built for, just never wired to an emitting side.
- **The work itself.** Maps directly onto the proto's existing `Pipeline`/`nested_pipelines`
  shape: one top-level `Pipeline` does the conversion (its own `Output` = the original-size
  rendition), two `nested_pipelines` branch off that already-converted state, one per resize
  (`Output` = "medium", `Output` = "thumbnail") — exactly the case the proto's own comment
  describes ("shared setup... lives once in a parent's steps rather than being repeated in every
  independent branch").
- **The Service Task.** A new `MediaProcessorDelegate`, same pattern as the existing
  `HelloWorldDelegate` reference implementation: `@Component` + `@ProcessAccessible` +
  `JavaDelegate`, resolved via `delegateExpression` from the BPMN, injecting the conditionally
  present `ImageProcessorStub`.
- **Permission to write results back.** The process declares `flowable:runAsUser`;
  `ProcessRunAsUserListener` resolves it to a real `NtrlocPrincipal` on `PROCESS_STARTED` and sets
  it as a process variable — the delegate uses that principal to issue the mutation writing new
  renditions back, rather than needing a real HTTP caller that doesn't exist for an
  asynchronously-triggered process.
- **Two different kinds of new data come out of this, and they are not solved the same way.** The
  renditions (medium, thumbnail) are a small, finite, admin-decided set — ordinary *static*
  properties/property groups (§5), added once as a deliberate governance decision, exactly like
  `file.content` today. Extracted metadata (EXIF and friends) is the unpredictable part — that is
  what §6 is for.

---

## 3. Dynamic properties — the core problem

Broader than EXIF specifically: any property whose existence and shape can't be predicted at
admin-schema-design-time, because it's discovered by a process rather than entered by a human (or
copied in from a source where a human entered it). The tension: domain-graph-starter's schema is
deliberately strict and enforceable — real, load-bearing value for data governance and integrity —
but this class of data is inherently unpredictable, and admins can't reasonably be expected to
pre-declare every possible EXIF/IPTC/XMP field a camera vendor might ever embed.

Hard requirement that shaped everything below: extracted metadata is of very limited use if it
can't be projected, sorted, and filtered, and it must be facetable/sortable *alongside* static
properties in the same query — not through a second-class parallel mechanism. That ruled out the
obvious cheap option: `binary_content.metadata JSONB` already exists (content-addressed, so
deduped per file) and is read back into every `BinaryPropertyObject`, but nothing writes it
(`BinaryPartitionManager.store` takes no metadata argument) and, more importantly, nothing under it
is reachable by the schema-driven facet/filter/sort machinery.

Why that machinery is hard to extend, established by reading it: every facet/filter/sort field
name funnels through `RegisterPartitionManager.resolveProperty`/`resolvePropertyId`, a hard schema
lookup that throws "Unknown property" for anything undeclared, translating a name to the property
UUID that is the actual JSONB key. Sorting additionally depends on the *declared type*
(`typedSortExpression` picks `::bigint`/`::timestamptz`/etc. per type; without a cast `->>` yields
text and "1","10","2" sorts lexically). The underlying SQL (`->>`, `jsonb_exists`, a GIN index on
the whole `properties` column) doesn't care whether a key is governed — the constraint is entirely
in the Java resolution layer. So the design goal became: give dynamic properties real ids and real
types so that resolution, facets, filters, and sorts work on them unchanged.

---

## 4. Object properties become property groups

Findings from tracing the actual storage and mutation code:

- **Storage is flat by leaf id; nesting is schema-only.** `V1_0_1_4__object_properties.sql`:
  *"Nesting is schema-only: register/ledger storage is untouched, properties inside an object
  property are addressed by their own id at the same flat level as any other property."* The
  nested shape a client sees is rebuilt at read time by `namesForIds(...,
  propertyPathsByIdForItemType(...))`. Ledger entries carry `Map<UUID, Object> properties`
  (`ItemCreateEntry`, `ItemUpdateEntry`) — leaf ids, flat.
- **Write.** `MutationRequestProcessor.resolveObjectPropertyValue` recurses into a nested payload
  and flattens it to individual `(leaf UUID, value)` entries before anything is applied; the "diff"
  semantics ("absent key leaves that property unchanged") apply recursively at every level. A
  non-null object write is pure addressing syntax. The one exception: `null` for an object property
  recursively clears *every leaf currently in the live schema* beneath it (`nullAllLeaves`),
  including leaves the caller never mentioned — a distinct, more dangerous capability, closer to
  item-level `can_delete` than to ordinary per-leaf `can_write`. **Resolved (2026-09-19):** with
  groups instead of object properties, `null` on a group is a validation error and `nullAllLeaves`
  goes away — see "Mutation shape" in §5.
- **Read.** `filterPropertiesByReadGrant` filters the flat, id-keyed map *before* `namesForIds`
  runs, so it is already per-leaf. An object property's own id never appears as a key, so a grant
  row on it is simply never consulted — on read *or* write. (The earlier draft of this section said
  otherwise; see the correction at the top.) It follows that granting read on `exif` while
  withholding `exif.gpsCoordinates` is already expressible today, by granting leaves individually.
- **Conclusion.** An object node contributes structure (paths, nesting) but has no value, no
  independent operation, and no meaningful permission. Nothing in the schema stops a
  `marker_grant_property` row from being created against an object-typed property's id — it is
  just inert, the same confusing-but-possible state `can_download` was in before it was dropped
  (see §7).
- **Decision: drop `PropertyType.OBJECT`; introduce property groups as a distinct kind of schema
  node**, not a variant of the property hierarchy. Every consumer of
  `AdminPropertyDefinitionView` today (`buildEditTree`, `filterPropertiesByReadGrant`,
  `resolvePropertyIds`, `nullAllLeaves`, `collectFacetableFieldNames`) carries its own
  `instanceof ObjectAdminPropertyDefinitionView` special case and must independently remember that
  objects aren't valued or grantable. A separate kind makes the wrong thing structurally
  unrepresentable. "Property group" revives real ntrloc terminology (it predates permissions, so no
  conflicting baggage); it was chosen over "namespace" because a group *of* properties is
  grammatically not itself a property, and it doesn't assume programming-language background.
- **History, and why the earlier objection doesn't apply.** `ntrloc-projection-summary-2.md`
  records an earlier, schema-level, *presentation*-motivated property-group concept (a
  `groupProperties` projection flag, groups assigned to individual properties) that was abandoned
  because real grouped sections mix properties *and links*. That document moved presentation
  grouping into the projection layer instead (`ViewProjectionShape`, `FieldsProjectionShape`,
  `GroupField`), where a group can hold properties and links freely. Property groups here are a different thing: *structural* — they
  carry addressing (paths), name scoping, and the parent scope for dynamic roots, replacing object
  properties that already nest in the schema. So groups do not contain links (decided 2026-09-19);
  link-mixing sections stay a projection-layer concern, and links themselves stay on item types,
  traits, and link definitions. Checked: the old `groupProperties` flag and any schema-level group
  concept no longer exist in `domain-graph-starter`, so nothing collides. The name is reused
  deliberately, but the two concepts should be kept distinct.

---

## 5. Static schema shape (decided, not built)

Because the schema is being reworked anyway, the database is being recreated from scratch: no
Flyway migration path, the baseline is rewritten in place. Register and ledger are unaffected
(flat leaf-id storage). The Flyway dependency stays for future use, but
`FlywayMigrationUpgradeIntegrationTest` is retired until migrations are needed again (it remains in
git history).

**Node kinds.** *Property* — a leaf that carries a value; the only thing that can be granted.
*Property group* — purely structural, its own table, never grantable, never carries a value.

**Ownership, one parent per node.** A property or group has exactly one parent, expressed as parent
columns with a `CHECK` that exactly one is non-null, rather than join tables (join tables cannot
enforce single-parent, and cross-table exclusivity least of all). Linkages:

- `[item type | trait | link type] → property`
- `[item type | trait | link type] → property group`
- `[property group] → property`
- `[property group] → property group`

This replaces `schema_item_property`, `schema_trait_property`, `schema_link_property`, and
`schema_property_property`. `schema_property` keeps its own columns (id, name, description, type,
cardinality, usage, controlled list) and gains parent columns; names still aren't globally unique.
The effective view still merges through the supertype chain, as today.

**Naming.** Property, group, and dynamic-root names must be unique among their siblings. Because
siblings span separate tables, this is enforced in the application (the schema mutation appliers),
as trait-property uniqueness is today — not by a DB constraint. This includes the supertype chain,
and the check has to run in both directions: adding a property, group, or root to a supertype must
be validated against every descendant's names, and implementing a trait must be validated against
the item type's effective top-level names (trait names are reserved there).

**Deletion.** A static group that contains anything cannot be deleted (no cascade).

**Mutation shape.** The envelope is unchanged from today: `properties` is a nested map, and a
group is just the container key whose value is a map of its children. Trait contributions nest
under the trait name:

    { "type": "CREATE", "itemTypeName": "Product",
      "properties": { "title": "Widget", "dimensions": { "width": 10, "height": 4 } } }
    { "type": "CREATE", "itemTypeName": "Photo",
      "properties": { "File": { "name": "IMG_0060.JPG", "content": "<binary-id>" } } }

Updates keep the recursive diff semantics (`{"dimensions": {"width": 12}}` changes only `width`).
A group is pure addressing: it has no value, so `null` on a group key
(`{"dimensions": null}`) is a **validation error** — set the individual properties to `null`
instead. This replaces today's `null`-on-object cascade (`nullAllLeaves`), which is removed; no
bulk-clear primitive exists, and one can be added later if a real need appears.

**Traits are namespaced in projections.** A trait's properties, groups, and links project (and are
written, filtered, sorted, and faceted) under the trait's name: `File.name`, `File.content`. This
makes the cross-trait name collision that exists today impossible by construction. (Today
`SchemaViewBuilder.effectivePropertiesAdmin` flat-`Stream.concat`s an item's own properties with
every trait's, and the only uniqueness check on trait properties — `PropertyMutationApplier`'s
`CREATE_TRAIT_PROPERTY` branch — is scoped to the single trait being edited; downstream,
`Collectors.toMap(AdminPropertyDefinitionView::name, ...)` would throw on a duplicate key.) Trait
names become reserved among an item type's own top-level names. Cost accepted: moving a property
into or out of a trait becomes a breaking API change. The DAM's hand-built `file` group on the File
trait becomes redundant and its consumers (`filePropertyPaths`, import mutation building) change.
Trait namespacing applies uniformly (decided 2026-09-19): projections, mutation payloads,
filters, sorts, and facets all address a trait's contributions as `TraitName.…`.

**Blast radius above the database:** the object variant of `AdminPropertyDefinitionView`,
`namesForIds`'s path builder, `SchemaViewBuilder`/`SchemaRepository`, the admin schema editor, and
the DAM frontend all assume OBJECT is a property type today.

---

## 6. Dynamic schema (decided, not built)

Static and dynamic schema are kept deliberately separate — separate tables, separate grant join
tables. They only meet where ids are used: the ledger and register (flat id-keyed), grant
resolution, and projection/query resolution (§ end of this section).

**Node kinds, all separate from the static tables.**
- **Dynamic root** — must be statically declared by an admin (this is the governance line). Parent:
  an item type, a trait, or a static property group. Not supported on link types. Contains dynamic
  groups and dynamic properties. Example: `intrinsicMetadata`, not `exif`.
- **Dynamic property group** — cannot be declared, only discovered. Parent: a root or another
  dynamic group. Contains dynamic groups and dynamic properties. Example: `exif`, `iptc`, `xmp`
  under `intrinsicMetadata`.
- **Dynamic property** — a discovered leaf. Parent: a root or a dynamic group. A scalar or a list
  of scalars. Anything structured is modeled with dynamic groups and properties rather than
  structured list elements — XMP bags translate to dynamic groups/properties (the details for bags
  of structures are still to be worked out).

Every node has exactly one parent (same single-parent columns-with-`CHECK` approach as §5, with the
parent being a root or dynamic group), and names are unique among siblings.

**Names.** Each dynamic group/property carries an *original name* — the external key,
normalized (`.` → `_`, since paths are dot-addressed throughout), immutable, and the ingest
matching key — and an optional *display name*. The display name governs the key in API responses;
the original name is opaque to clients. (`a.b` and `a_b` normalize to the same path; treat them as
the same node.)

**Identity.** Ids are minted once, at first observation, and are permanent — property-id stability
matters as much here as for static properties. The registry lives in the schema (like static
properties) and is not itself ledgered; the ledger simply references the ids, so replay works under
exactly the same durability assumption static properties already have.

**Minting is serialized by Postgres, not a cluster lock.** A unique constraint on `(parent,
original_name)`, with the row committed in its own short transaction (so a rolled-back mutation
can't leave a node cache holding an id that doesn't exist); alternatively a transaction-scoped
`pg_advisory_xact_lock`. A Hazelcast lock was rejected: a plain one can be held by a dead node, and
a strictly safe one needs the CP subsystem, which standalone/small deployments lack. The lock/insert
only happens on a first observation; each node keeps a cache of known paths, and other nodes
read-through on a miss — there is no cluster-wide broadcast.

**`SchemaManager` never loads dynamic tables.** It caches the entire admin schema in one
`AtomicReference` and every schema change does a full rebuild plus a Hazelcast broadcast to all
nodes; treating first-seen EXIF tags as schema changes would fire that on the write path and bloat
the cached schema and the schema editor. Keeping dynamic nodes in their own tables (loaded lazily,
per root) avoids it entirely.

**Per-node registry attributes** beyond name/display name: observed type(s), an optional admin-set
type hint (the only source of sort-cast information a dynamic node has), a description, and
hide/ignore flags — *hide* stores the value but doesn't show or facet it, *ignore* drops it at write
time so it is never stored (a real privacy lever: an org may never want GPS stored). Facetable
defaults to off; static-style `REQUIRED`/`DEPRECATED` usage doesn't apply. Provisional defaults for
type conflicts, to be refined in practice: a type hint wins if set, otherwise the first-observed
type; later mismatched values are still stored (JSONB doesn't care) and sort falls back to text; a
scalar-vs-group conflict at one path keeps the first shape and skips the conflicting value with a
logged warning rather than failing the whole mutation.

**Display names are API keys.** Consequence: display names must satisfy the same grammar as static
names (paths are dot-addressed in sort/filter/facet requests), and sibling display names must be
unique, including against unlabeled siblings' raw names, or keys merge silently. Renaming a display
name changes the response shape clients see — accepted.

**Writability (decided 2026-09-19): dynamic properties are not user-mutable.** Values are facts
about the file's bytes, and a human edit would be overwritten by the next re-extraction —
corrections belong in a static property. Only an ingest path writes them, through a dedicated
entry point rather than the ordinary mutation validator (which rejects unknown names). Consequence
for the ingest design (later): re-extraction needs a "replace this root's contents" operation, or
stale keys survive.

**Deletion.** Deleting a dynamic root takes a `force` flag, default false: a non-empty root is
rejected unless `force=true`, which cascades to all discovered nodes and their grant rows. Register
values under the deleted ids are silently dropped on read, as with any deleted property.

**Faceting needs no discovery mechanism.** Each dynamic property carries a `facetable` flag
(default off), which admins will eventually be able to turn on; the set of facetable fields is
simply the facetable rows across the static and dynamic tables, read through the combined
resolver. The dynamic registry is itself the list — nothing has to scan data.

**Where static and dynamic meet.**
1. Ledger and register: flat property-id keys; the per-type `properties` JSONB holds static and
   dynamic ids side by side. Ids never collide (UUIDv7).
2. Grant resolution: `RequestPermissionContext`/`AuthorizationCacheManager` hold
   `Map<markerId, Set<propertyId>>`; both grant join tables (§7) are unioned into that same map at
   cache-build time, so `filterPropertiesByReadGrant` is untouched.
3. Projection and query resolution: `namesForIds` and the facet/filter/sort resolvers need the
   combined id-to-path map (static paths plus lazily-loaded dynamic ones). Path resolution reaching
   a dynamic root hands off to dynamic lookup — a boundary, not a parallel regime.

---

## 7. Permission model: leaves only

The atomic permission primitive is **marker → leaf property → can_read / can_write**. No third
flag — `can_download` existed briefly (`V1_0_1_8__marker_scope_and_grants.sql`) but was dropped in
`V1_0_2_0__drop_property_download_grant.sql`: *"read is now equivalent to download — if a principal
can read the property they can fetch its bytes; there is no second gate,"* and the column had never
actually been enforced in the projection path even before removal.

Two grant join tables, deliberately separate: `marker_grant_property` (to static properties,
`can_read`/`can_write`) and a new one to dynamic properties (`can_read` only, since dynamic
properties are not user-writable). Each has a plain FK to its own property table; admins see the two
visually distinguished. Property groups and dynamic roots/groups are structurally ungrantable — no
table can reference them.

**Auto-grant for dynamic properties.** A freshly-discovered dynamic property has zero grants the
instant it exists — nobody was present to grant it. Groups hold no grant to inherit from, so the
result must be *materialized* grant rows written at discovery time, not resolved by an ancestor walk
at read time. Decision: the simplest thing first — a flat, admin-configured list of `(marker,
canRead)` carried on the dynamic root, copied into real dynamic-grant rows when a node is
discovered. The outcome is ordinary grant rows visible in the same Access Admin UI, nothing new to
learn to read it. Considered and deferred: a DMN-driven "grant decision," generalizing
`MarkerDecisionSupport`. Checked directly — its underlying `executeRows`/`dmnDecisionService`
execution is fully generic; only `evaluateDecisionToMarkerIds`'s output parsing (a `markerName`
cell) is marker-specific — so a grant-rule sibling would reuse the engine with different output
columns. Not built now because it costs admin-facing legibility (more UI hops, a decision table is
harder to read than a flat list) for conditional-rule capability not yet needed. The flat list is the
literal degenerate case of a decision table, so moving to DMN later replaces only the authoring
mechanism, not the materialize-at-discovery architecture.

**Scope, permanently:** auto-grant applies to dynamic properties only. Static properties already
have a deliberate human moment (an admin defines and grants them on purpose); dynamic ones don't,
by construction — that asymmetry is the gap being filled, not a general "automate grants" feature.

---

## Decided since the last checkpoint (2026-09-19)

- Write enforcement on mutations is deferred until after the schema rework (owner's to-do).
- Sibling-name uniqueness, including the supertype chain, is application-enforced (§5).
- `FlywayMigrationUpgradeIntegrationTest` is retired; the Flyway dependency stays (§5).
- Dynamic properties: scalars and lists of scalars; XMP bags map to dynamic groups/properties (§6).
- Dynamic properties are not user-mutable (§6).
- Admins will mark dynamic properties facetable, per property, later; no facet discovery (§6).
- Dynamic registry cleanup is deferred.

## Open questions

1. **Display-name grammar.** Query-side field names must match `SAFE_FIELD_NAME`
   (`^[a-zA-Z]\w*+(?:\.[a-zA-Z]\w*+)*+$`, `RegisterPartitionManager.java:212`) — a letter then
   word characters, dot-separated — so a display name like "EXIF Metadata" or "Camera Model" cannot
   currently be addressed in a sort, filter, or facet request. Options: restrict display names to
   the grammar (with a separate free-form UI label), add quoted segments to the path syntax, or an
   explicit array form for paths. May affect static properties too.
2. **Payload size.** Projections return every readable property today (no property selection on
   `CollectionProjectionSpec`); a photo with hundreds of dynamic leaves would bloat every list
   response. Proposal: dynamic roots are omitted from projections unless the request names them.
3. **Name-addressed consumers.** Marker-rule DMN tables receive `propertiesByName`; state-machine
   conditions and BPMN scripts also address properties by name. Principle agreed: these must work
   against property *ids* through an explicit input/output mapping, not names. Mechanism not yet
   designed.
4. **The advisory `edit` tree** (`ProjectedItemPermissions`, `scalars`/`objects` keys) needs to
   express groups and trait namespaces. Proposal: keep the nested shape (an earlier design comment
   deliberately rejected a flat dotted-path list) and rename `objects` to `groups`.
5. **Bags of structures in dynamic data** (§6) — exact modeling still to be worked out.
6. **Type-conflict and normalization defaults (§6) are provisional** — adopted as written, to be
   refined in practice.
7. **Write enforcement on mutations** (deferred, see above): `MutationRequestProcessor`'s header
   says "Permission checks are a separate, not-yet-built component (Section 12)"; the principal is
   attribution-only, and write grants only feed the advisory `edit` tree. When built, it must run
   after name/group resolution so every leaf is checked individually, and a failing leaf should
   reject the whole mutation.
