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

**Display names are API keys.** Sibling display names must be unique, including against
unlabeled siblings' raw names, or keys merge silently. Renaming a display name changes the response
shape clients see — accepted. **Decided (2026-09-19): path strings gain quoted segments**, so a
display name like "EXIF Metadata" is addressable in sort/filter/facet requests as
`intrinsicMetadata."EXIF Metadata"."Camera Model"`. Plain segments keep today's grammar
(`SAFE_FIELD_NAME`, `RegisterPartitionManager.java:212`); quoting is only needed for segments that
don't fit it, so static names are unaffected. Only ids reach SQL after resolution, so quoting doesn't
weaken the injection guard.

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
- Path strings gain quoted segments for display names (§6).
- Projections carry two separate elements: `properties` (bounded, returned by default) and
  `dynamicProperties` (unbounded, returned only when explicitly requested).
- The advisory `edit` tree keeps its nested shape; `objects` is renamed `groups`, trait namespaces
  appear as group nodes, and dynamic properties never appear (read-only).
- Decision/process/state-machine inputs and outputs get an id-based mapping layer, insulating them
  from property/group/trait name changes. Built right after the schema rework, not during it.
- Type-conflict and normalization defaults (§6) are adopted as written, to be refined in practice.

## Open questions

1. **Shape details for `dynamicProperties`** (proposed, not yet agreed):
   - Dynamic roots are owned only by item types and traits (not static groups) and appear as
     top-level keys of `dynamicProperties`, trait-namespaced like everything else
     (`dynamicProperties.Media.intrinsicMetadata…`). Allowing a root inside a static group would
     force `dynamicProperties` to mirror the static group path as a prefix.
   - A request names what it wants by path — a root, any dynamic group, or a single property — so
     a client can take `exif` without `xmp`. Permission filtering applies as usual.
   - Sort/filter/facet field paths mirror the response shape (`dynamicProperties.…` prefix), and
     `dynamicProperties` becomes a reserved top-level name. That keeps the two namespaces fully
     independent: no cross-collision rule between dynamic roots and static names, and the resolver
     dispatches on the first segment.
2. **Bags of structures in dynamic data** (§6) — exact modeling still to be worked out.
3. **Write enforcement on mutations** (deferred, see above): `MutationRequestProcessor`'s header
   says "Permission checks are a separate, not-yet-built component (Section 12)"; the principal is
   attribution-only, and write grants only feed the advisory `edit` tree. When built, it must run
   after name/group resolution so every leaf is checked individually, and a failing leaf should
   reject the whole mutation.

## Implementation checkpoint: static schema rework (backend done)

Backend of the static schema rework is implemented in `domain-graph-starter` and the full suite is green (715 tests). Frontends (admin-ui schema editor, DAM) are not yet updated.

Answers folded in from the last round of questions:
- A projection scoped by `traitName` uses plain property names for sort/filter/facet (relative to the trait). Not yet built: it belongs with the projection work.
- `PropertyGroup` is the term for the structural node. The security `Group` is renamed `UserGroup`
  (done 2026-09-19: table, Java types/methods, REST paths, the `'GROUP'` → `'USER_GROUP'` principal
  type, and the admin-ui labels; process groups and property groups were deliberately left alone).
- All 18 migrations are folded into one baseline (`V1_0_0_1__baseline.sql`); the Flyway upgrade test is retired.

As built:
- Every property and group has exactly one parent, held as parent columns with a CHECK. Sibling names are unique across properties and groups, app-enforced, including the supertype chain in both directions and the names of implemented traits.
- A trait's contributions appear on an item type as a namespace group named for the trait (`traitNamespace = true`); its link perspectives are keyed `TraitName.perspective`.
- Mutation payloads address groups by JSON nesting. `null` on a group is a validation error; there is no bulk clear.
- A group with any contents cannot be deleted. Deleting an item/trait/link cascades through the FKs.
- The advisory `edit` tree uses `{scalars, groups}` (was `objects`).
- Link perspective calculated views carry only a link's own properties; link-owned groups reach clients through the admin schema.

---

## 8. Binaries: content vs. file, and why a binary's own facts need no grants (2026-09-22)

Prompted by revisiting an earlier, dismissed suggestion (store EXIF/IPTC in `binary_content.metadata`,
a JSONB column that already exists — `V1_0_0_1__baseline.sql` — but that nothing has ever written; the
projection path already reads it if present, so it's a wired-but-empty capability today, not a gap to
build). The conversation that followed sharpened *why* it's a better fit than it first looked, and
where its edges are.

**Where binaries came from.** Not designed as a property type from the start — began as pure
deduplication (`binary_content` keyed by `sha256`, so identical bytes are stored once). The property
type came later, once it became clear a **binary is not a file**. A binary is the intrinsic *content*
— the bytes, identified by their hash. A file is a name (or path, or whatever) used to work with that
content. The File trait already reflects the split: `File.content` is the binary, `File.name` is the
file. This split is also why item-scoped storage (`register_binary_property`) is separate from
content-scoped storage (`binary_content`, unique on `sha256`) — two items referencing the same bytes
share one content row.

**The disclosure test.** A fact belongs on `binary_content.metadata` only if it reveals nothing beyond
what's already recoverable from the bytes themselves: hashes, length, MIME type, EXIF/IPTC/XMP,
ffprobe output. Anything that adds information *not* derivable from the bytes alone — ML-generated
tags, GPS coordinates geocoded to a street address, moderation scores, OCR run through a model with
outside knowledge — is extrinsic and does not belong there; it remains a candidate for governed
(static or dynamic) properties, with their own grants, editing and history.

**Why this needs no grants of its own.** The atomic permission primitive is marker → leaf → read/write
(§7) — and a binary already *is* a leaf, the one property type whose value is itself structured
(hash, length, MIME type, and now derived metadata) without being a container of separately-addressable,
separately-grantable children. Intrinsic facts about a binary's content require no grant beyond the
binary property's own `can_read`: if a principal can read the property, they can already fetch the raw
bytes and derive every intrinsic fact from them directly — the hash, the length, the EXIF. A grant on
any individual fact would be enforcing something already unenforceable. This is *read implies all
intrinsic facts*, and it is what makes a binary's structure safe in a way an old-style OBJECT property's
structure never was: object properties were removed because their children needed independent grants
(§4); a binary's "children" don't, because none of them are extrinsic. This also means intrinsic
metadata is never user-supplied and never user-editable — it can only be produced from the bytes — so
the object-property read/write asymmetry question doesn't arise for it either.

**Redaction stays consistent.** If a lower-privileged principal should see a version stripped of GPS or
other sensitive EXIF, that's a *different binary* — a rendition, its own `binary_content` row with its
own hash and its own (stripped) metadata — never a permission-filtered view of the same row. A
principal who can read the original binary sees the original's full metadata; there's nothing to grant
or hide within one binary's facts.

**The size/sort gap this surfaced, built.** Prompted by a concrete DAM need — "find photos with content
length between 1MB and 6MB," "sort photos by content length." Sort is built (filter/facet on binary
attributes are not — still deliberately deferred, see below); a property path may now continue past a
BINARY leaf into its metadata, resolved via a scalar subquery through `register_binary_property` →
`binary_content` (safe because binary cardinality is always SINGLE), NULL for an item with no binary set,
feeding the same NULLS-LAST sort/typed-cast machinery every other scalar sort already uses.

The path grammar went through a correction once it was actually used. First built as a shortcut —
`File.content.length` — reading straight from `binary_content.metadata` under the hood but naming a
shorter path than the response itself uses (`assembleBinaryValue` returns `content.metadata.length`, not
`content.length` — see below). Flagged as inelegant: a client has to remember two different paths for the
same value, one to read it out of a response, another to sort by it, with no rule connecting them.
**Corrected:** the sort path is exactly the response path — `File.content.metadata.length`, walking the
JSON the same number of levels the path names (`bc.metadata->>'length'` for one level, a future
`bc.metadata->'hashes'->>'sha256'` for two). No shortcut form exists; `File.content.length` is now a
rejection, on purpose, so the address-what-you-see invariant every other property path already had holds
here too. This is also what lets `File.content.metadata.extracted.exif.Make` resolve through the exact
same mechanism once EXIF lands, rather than a separate one just for `length` (see "Storage shape,
decided" below for why the extracted-metadata key is named `extracted`, not `metadata` — avoiding
exactly the redundant `metadata.metadata` this note used to describe).

A second asymmetry surfaced right behind the first: an ordinary property's sort cast is chosen from its
own declared `PropertyType` (`typedSortExpression`) — why would a binary attribute's cast be hardcoded
instead of type-directed the same way? It shouldn't, and now isn't: `BINARY_METADATA_PATH_TYPES` maps
each recognized metadata path to the `PropertyType` its value should be treated as (`["length"] ->
PropertyType.LONG` today), and both cases share one cast dispatch (`castedText`), just sourced from a
different place — a real `schema_property` row for ordinary properties, a fixed map for binary attributes,
since the latter have no schema row to read a type from.

**Permission enforcement at filter/sort/facet time (scope widened).** Filters and sorts today check
only item-level read; property-level read is applied afterward, by redacting already-fetched *results*
(§7, "Two grant join tables"; `filterPropertiesByReadGrant`) — so a principal can already filter or sort
on a property whose value they're not permitted to see, a pre-existing gap not specific to binaries.
Binaries sharpen it into something worse: an equality filter on `sha256` becomes an existence oracle —
"does this exact file exist in the system?", answerable by someone who cannot read the property at all.
**Decided:** filter/sort/facet must be brought in line with property-level read grants generally, not
just for binaries — this is now a second, related item alongside write enforcement on mutations (§7,
open question 3), tracked together since both are "permission checks MutationRequestProcessor's /
RegisterPartitionManager's own comments already flag as not-yet-built." Binary attribute filters/sorts
specifically must require the *binary property's own* read grant (not just item-level read), precisely
because of the oracle risk.

**What this means for the larger dynamic-properties design.** Not a replacement for it — dynamic roots,
groups and properties are still needed, for different reasons (governed/extrinsic/curated data, not
intrinsic content facts) — but likely a significant *scope reduction*. If most of what a DAM actually
wants from "dynamic properties" turns out to be intrinsic (EXIF, IPTC, XMP, dimensions, codec,
duration), binary metadata absorbs that whole category on its own, permission-free, without touching
the schema. What's left for the dynamic-properties machinery proper is the smaller, harder-to-avoid
part: extrinsic, curated, user-editable, individually-permissioned facts.

**Storage shape, decided.** `binary_content` keeps `sha256`, `md5` and `length` as real, `NOT NULL`
columns — not folded into `metadata` — because they're not purely descriptive: the storage layer
(`BlockDeviceBinaryStorageAdapter.permanentRelativePath`) already places a file on disk by `sha256` *and*
`md5` together, so the DB's uniqueness has to match that or the two layers can disagree about what "the
same content" means. It already did: the original schema had `sha256` alone as the unique key, so a
`sha256` match with a `md5` mismatch — extremely unlikely under correct hashing, but exactly the failure
mode worth defending against — would have let the DB silently conflate two different uploads under one
id while the storage layer wrote them to two different files, orphaning the second one permanently.
**Decided:** `UNIQUE (sha256, md5, length)`, `ON CONFLICT (sha256, md5, length) DO UPDATE ...`, replacing
the single-column constraint. `length` earns its place in the key the same way `md5` does — not for
cryptographic strength (an attacker who can't forge a `sha256` collision gains nothing from also matching
a weaker hash), but because it's computed by an entirely independent code path (a filesystem stat after
the write closes, versus a running digest during the write) and so catches our own hashing bugs, which
are far likelier than an actual break.

**The storage layer has to grow to match, not just the DB.** `permanentRelativePath`/`openReader` are
still keyed on `(sha256, md5)` alone. Left that way, two rows differing only in `length` — the exact
scenario the three-column key exists to catch — would resolve to the *same* file on disk: the second
`close()` would see the first upload's path already occupied, treat itself as a duplicate, and silently
discard its own (different) bytes, while its DB row's `openReader(sha256, md5)` would then hand back the
*first* upload's content. That's worse than the original gap, not just incomplete — the DB would
correctly record two distinct binaries while storage silently serves one of them the wrong bytes.
**Decided:** `permanentRelativePath` and `openReader` both take `length` as a third key component,
alongside `sha256` and `md5`, so the two layers can't diverge in either direction again.

`mime_type` has no role in the constraint but is treated the same way as the other three for a different
reason: `sha256`, `md5`, `length` and `mimeType` are *all* duplicated — kept as real columns **and**
mirrored into `metadata` — so that every intrinsic fact about a binary is reachable through one uniform
mechanism (`metadata`, resolved the same way `File.content.metadata.extracted.exif.Make` eventually will be), while
the real columns stay authoritative for the handful of typed, functional reads that want them directly:
locating the file (`openReader(sha256, md5)`), the ETag header, `Content-Length`. This duplication is
safe specifically because these four values are write-once — computed synchronously during upload,
written in the same `INSERT` as the row itself, and `binary_content` rows are never updated after that.
There is no path by which a column and its `metadata` copy can drift, because there is no second write to
either.

That safety argument does **not** extend to the nested `metadata` key (see "Still open," below) — it
comes from the media processor, asynchronously, after the row already exists, so writing it is
unavoidably a *second* write to the column, from a different process, at a different time. **Revised
2026-09-22, later the same day:** the first pass wrote this key as `embedded`, holding a
hand-curated, per-kind grouping (`{"exif": {...}, "iptc": {...}}`) built from a hardcoded
allowlist (a `MetadataKind` proto enum). That model needed a proto-and-code change every time a new
category of file fact turned out to matter — it happened once in practice (adding JPEG's own
technical properties), and a second real test photo immediately surfaced a category (`colorspace`,
`geometry`, ...) that isn't reachable by extending the allowlist at all, because it lives in a
structurally different part of the media processor's own output. **Decided:** invert the model —
the processor now returns (almost) everything it can tell about the file, admin-configurable
exclusion (`media-processor.metadata.exclude`, on the processor's own deployment config) trims what
a given installation doesn't want, shipped with reasonable defaults. The key holding this result is
renamed `embedded` → `metadata` (yes, nested inside `binary_content.metadata` itself — considered
and rejected the idea that this needs to be disambiguated further; there's no real ambiguity once
you're looking at the actual column). The per-request `MetadataKind`/`kinds` selection is retired
entirely, wire and all — nothing ever used it non-empty, and narrowing what comes back is now an
operator decision, not a caller one; a caller wanting custom post-processing beyond the built-in
exclusion does so downstream (BPMN/delegate), not via new gRPC surface. The write is still whole-key
replace, not a merge — still correct for this pass because ImageMagick is the only producer; the
merge-not-replace concern below is about two *independent* producers, not yet a real scenario. (The
exact key name and write mechanism below changed again the same day — see the next "Revised" note.)

Final shape:
```json
{
  "length": 1234563445,
  "mimeType": "image/jpeg",
  "hashes": { "sha256": "...", "md5": "..." },
  "extracted": {
    "colorspace": "sRGB",
    "geometry": { "width": 1024, "height": 768, "x": 0, "y": 0 },
    "depth": 8,
    "compression": "JPEG",
    "quality": 94,
    "properties": {
      "exif": { "Make": "Apple", "...": "..." },
      "icc":  { "description": "Display P3", "...": "..." },
      "jpeg": { "colorspace": "2", "sampling-factor": "2x2,1x1,1x1" }
    }
  },
  "derived": {
    "location": { "latitude": 33.782833, "longitude": -84.392333 }
  }
}
```
Top-level image characteristics (`colorspace`, `geometry`, `depth`, ...) pass through close to
verbatim from the processor's own output — no separate "technical" wrapper invented for them. Only
`properties` (the flat, colon-namespaced metadata bag — `exif:Make`, `jpeg:colorspace`, etc.) is
reshaped, generically, into a nested tree keyed by namespace (split on each key's first colon; a
colon-less key like `signature` would be a bare leaf, were it not excluded by default — see
`ntrloc-media-processor`'s own `application.yml` for the full default exclusion list and reasoning).

**Revised 2026-09-22, later again: `extracted`/`derived` split.** EXIF's own GPS representation
(`GPSLatitude`/`GPSLongitude` as three comma-separated degrees/minutes/seconds rationals, plus
separate `GPSLatitudeRef`/`GPSLongitudeRef` hemisphere tags — e.g. `"33/1,4697/100,0/1"` + `"N"`)
turned out to be a concrete, motivating case for a distinction that had been implicit until now:
some values genuinely are just "what the file says," passed through unmodified — the whole
`extracted` object above — while others are *computed* from that raw data, not reported directly by
anything. GPS is exactly the latter: four separate raw tags combined into one portable,
decimal-degree coordinate. Decided: `ImageOperations.extractMetadata` now returns two sibling
top-level objects, `extracted` (unchanged from above) and `derived` (currently just `location`,
computed by `ImageOperations.deriveLocation`/`parseGpsCoordinate` — `null`/absent rather than a
partial result if any of the four GPS tags is missing or malformed). Both land as top-level keys in
`binary_content.metadata` directly (`ExtractBinaryMetadataDelegate` merges the whole response in
with a shallow `metadata = metadata || :response::jsonb`, rather than nesting it under a further key
— the response is already shaped exactly like the two keys that belong there). First tried naming
these `metadata`/`derivedMetadata`, then caught: the column they get merged into is *itself* called
`metadata`, so a key called `metadata` inside it reads back as the redundant `metadata.metadata`.
`extracted`/`derived` sidestep that entirely, and read naturally as full paths too —
`File.content.metadata.extracted.exif.Make`. `exclude` (the deployment's own config) applies only to
`extracted`; `derived` isn't part of the generic passthrough surface it governs. Decimal degrees, not
ISO 6709 — ISO 6709 is a string *display/interchange* format; storing it would mean re-parsing a
string every time anything downstream (a proximity query, a map widget) needs to actually use the
value, which is exactly the "not system-friendly" problem this exists to fix. Plain
`{latitude, longitude}` numbers map directly
onto what PostGIS/GeoJSON/every mapping API already expects.

Deliberately out of scope for this pass: actual proximity search ("photos within 10 miles of this
one"). Having a clean decimal coordinate doesn't provide that by itself — it needs its own indexed
column on `binary_content` (PostGIS `geography(Point,4326)` + `ST_DWithin`, or plain
`latitude`/`longitude` columns with a bounding-box-plus-Haversine fallback — a real infrastructure
decision, not yet made) and a genuinely new kind of filter predicate (everything the sort/filter
machinery does today is equality/range on a scalar; "within N miles" is a spatial predicate, a
different shape of thing, on top of filter/facet on binary attributes not existing yet at all — see
the "still open" items above). Its own design conversation when it's next in scope.

`length` (not `size`) is deliberate — "size" invites reading this as *file* size, and a binary is
explicitly not a file (see "Where binaries came from," above). `mimeType` (not `mime_type`) matches what
the projection already returns and what the DAM frontend already reads (`SearchResultModel.js`) — the
stored key and the response key are meant to be identical, so the response can be assembled by spreading
`metadata` directly rather than translating field names.

**Still open, not yet decided:**
- Is binary metadata a *cache* (freely re-extracted and overwritten whenever the extractor improves) or
  a *record* (preserved once written, changed only by explicit re-processing)? This turns out to gate a
  concrete, unavoidable problem, not just a someday question: because binaries dedup on identical bytes,
  two uploads of the same file can each trigger their own async extraction job against the *same*
  `binary_content` row, racing to write `metadata.extracted`/`metadata.derived`. Nothing decided so far says what should
  happen — last-write-wins, first-write-wins with the second a no-op, or a guard that only extracts when
  the key is still absent. **Owner's read:** the correct answer likely points toward a generalized
  job-tracking subsystem (has extraction already run, or started, for this content?) — deliberately not
  being tackled now. Whatever ships first for this key should be the smallest thing that doesn't
  corrupt data under the race, not a preview of that subsystem.
- Exact exposed attribute set for the `File.content.metadata.<path>` continuation — `length` is built;
  `mimeType`, `hashes.sha256`, `hashes.md5` are settled in shape (see "Storage shape," above) but not
  yet wired into `BINARY_METADATA_PATH_TYPES`. `metadata.<path>` (the extracted-metadata subtree) is
  now *deliberately* open-ended, both per format and per deployment's own exclusion config — not a gap
  to close, the actual design (see the "Revised 2026-09-22" note above).
- Filtering and faceting on binary attributes — same mechanism as sort, not yet extended to
  `translatePredicate`/`buildTermsFacetFilterFragment`, nor to the cross-type projection path
  (`orderByClauseAcrossTypes`). All still resolve an unqualified `File.content...` path the old way
  (a real schema property or nothing) and reject a binary-attribute path outright.

**Built, end to end (DAM).** `assembleBinaryValue` returns `{id, url, metadata}` for a binary value —
no top-level `sha256`/`md5`/`mimeType`/`length` siblings duplicating what's already in `metadata`; the
response and the sort/filter addressing scheme now agree on where every fact lives. The DAM's Search
view shows each result's file size and offers a three-way sort control (default / smallest first /
largest first) against `File.content.metadata.length` — the first real client of any of this.
- Link perspective calculated views carry only a link's own properties; link-owned groups reach clients through the admin schema.

---

## 9. Media processor integration: first pass (2026-09-22)

Scope for the first working slice of automatic metadata extraction, deliberately small — event-driven,
async, content-scoped, and nothing more. Recorded before building it so "what's in this pass" doesn't
drift as it's refined later.

**In scope:**
- A new event, `BinaryContentEvent.Created(UUID binaryContentId)` (mirroring the existing
  `SchemaChangeEvent`/`ApplicationEventPublisher` pattern already used by the schema mutation
  appliers), published from `BinaryPartitionManagerImpl.insert()` — and *only* on a genuine fresh
  insert, never on a dedup hit. Detected via Postgres's `RETURNING (xmax = 0) AS inserted` on the
  existing `ON CONFLICT (sha256, md5, length)` upsert, which also means two people uploading
  identical bytes at the same moment can't both trigger it — Postgres's own row lock during the
  upsert already gives the single-writer guarantee that would otherwise need a job-tracking
  subsystem (see §8's "still open" cache-vs-record question, which this doesn't resolve but does
  shrink).
- Bound to the binary, never the item type. No "Photo" (or any item type name) appears anywhere in
  this design — extraction is a property of the content, not of whichever item(s) happen to
  reference it.
- One listener translating that event into starting one hardcoded Flowable process definition,
  passing `binaryContentId` as a process variable.
- One BPMN process: start event → one service task, marked `flowable:async="true"` → end. No
  gateways/branching in the process itself.
- The service task's delegate follows the existing `HelloWorldDelegate` shape (`@Component`,
  `@ProcessAccessible`, `JavaDelegate`), calls the media processor via the already-wired
  `ImageProcessorGrpc`/`VideoProcessorGrpc` stubs (`MediaProcessorClientConfiguration`), and merges
  the whole response's top-level keys straight in (`metadata = metadata || :response::jsonb`) — not
  the finer-grained per-kind merge §8 originally described; correct for now because ImageMagick is
  the only producer (see §8's "Revised 2026-09-22" notes for the full reasoning and current shape).
- "Asynchronous" means Flowable's own async job executor (already running in this app), reached by
  marking the service task as an async continuation — not Spring's `@Async`/`@EnableAsync`, which
  this app doesn't use anywhere today and isn't being introduced just for this.
- "After the creating transaction has committed" comes for free: `insert()`'s `INSERT ... RETURNING
  id` has no `@Transactional` wrapping it anywhere in the call chain (checked), so it's Postgres's
  own implicit, already-committed autocommit statement by the time the call returns — publishing the
  event right after is already "after commit," with no `@TransactionalEventListener` needed.
- Graceful no-op, not an error, when the binary's `mimeType` isn't something the media processor
  handles, or `media-processor.url` isn't configured at all (mirrors
  `MediaProcessorClientConfiguration`'s existing `@ConditionalOnProperty` opt-in).

**Explicitly out of scope, deferred:**
- Branching by media kind (image vs. video vs. other) inside the BPMN process — for this pass, that
  dispatch is a plain conditional inside the delegate, not a gateway.
- IPTC/XMP, or anything beyond the one extraction call the delegate makes.
- Retry policy or failure handling beyond whatever Flowable's async job executor already does by
  default.
- Re-processing/idempotency story (rerun extraction after an extractor upgrade, etc.) — still the
  open cache-vs-record question from §8, not resolved by this pass.
- Any admin-ui/DAM-facing visibility into extraction status ("processing…", failures, etc.).
- Filtering/faceting/sorting on any extracted field — `BINARY_METADATA_PATH_TYPES` isn't extended by
  this pass; that's a separate, later step per extracted field, same as `mimeType`/`hashes.*` already
  are for the intrinsic facts.

**Verified live (2026-09-22):** end to end against a real running `ntrloc-media-processor`, with a
real photo carrying genuine EXIF (Make/Model/GPS/DateTimeOriginal/etc.) — uploaded through the DAM
UI, confirmed in Postgres that `binary_content.metadata->'embedded'->'exif'` held the real extracted
values, not synthetic test data.

**Found and closed a scope gap, same day:** a real-world photo carried ImageMagick properties outside
the four `MetadataKind`s this pass shipped with — `jpeg:colorspace`/`jpeg:sampling-factor` (JPEG
codec-level technical parameters), `date:create`/`date:modify`/`date:timestamp`, and `signature`.
Decision, per discussion:
- **`jpeg:*` — added.** New `METADATA_KIND_JPEG` value in `media_processor.proto`, prefix `jpeg:`,
  included in `ImageOperations.extractMetadata`'s default "everything" list alongside exif/icc/iptc/
  xmp. These are intrinsic, portable facts about the JPEG format itself — any JPEG decoder reports
  the same values — same category as EXIF/IPTC, just a different standard. No `domain-graph-starter`
  change was needed: the delegate already requests the empty/"everything" kind list and writes back
  whatever JSON comes back wholesale, so a new server-side kind flows through automatically.
- **`date:*` — deliberately excluded, not deferred.** These are ImageMagick's read of the *temp
  file's own OS timestamps* from processing (the delegate always operates against a fresh temp file),
  not anything about the photo's history — noise, not signal, in this pipeline. The real capture date
  is already captured correctly as `exif:DateTimeOriginal`.
- **`signature` — deliberately excluded, not deferred.** ImageMagick's own proprietary pixel-content
  hash — categorically different from `hashes.sha256`/`hashes.md5` (byte-level, portable) and tied to
  this one processor implementation. Decided against baking an ImageMagick-specific concept into the
  stored shape, since the processor is swappable in principle.

**Revised again, later the same day: kind curation replaced with generic passthrough +
admin-configurable exclusion.** A *second* real test photo immediately surfaced another category —
`colorspace`, `geometry`, `depth`, etc. — that isn't reachable by extending the `jpeg:`-style prefix
map at all, because it lives in a structurally different part of the media processor's own output
(top-level image attributes, not the flat properties bag `jpeg:`/`exif:`/etc. all share). Rather than
keep chasing categories one at a time, the model inverted: `ImageOperations.extractMetadata` now
captures (almost) everything the processor can tell about a file — every top-level image attribute
passed through close to verbatim, plus the properties bag reshaped generically into a nested tree
(split on each key's first colon, no hardcoded namespace list) — trimmed by an admin-configurable
exclusion list (`media-processor.metadata.exclude`, deployment config on the processor itself, not a
per-request field), shipped with reasonable defaults (processing-run noise like `userTime`/`version`/
`name`, plus the already-decided `properties.date`/`properties.signature`). The `MetadataKind` enum
and `ExtractMetadata.kinds` field are retired from the proto entirely — nothing ever used `kinds`
non-empty, and per-request narrowing is superseded by the exclusion config; a caller wanting custom
post-processing beyond that does so downstream (BPMN/delegate), not via new gRPC surface. See §8's
own "Revised 2026-09-22" note for the storage-side half of this (the `embedded` key renamed to
`metadata`) and the current final JSON shape.

---

## 10. Binary dynamic properties: a registry, and what it unlocks (2026-09-26)

Prompted by a concrete bug, not a speculative concern: the admin UI's new property-selection control
let a caller ask for `File.content.metadata.length`, and the response came back with the *entire*
binary object (id/url/full `extracted`/`derived` tree) instead of anything resembling a narrow
selection — and the dropdown offered `length` as the *only* addressable metadata field, despite the
real, live metadata blob (ImageMagick output plus EXIF) having 80-100+ distinct leaf paths on a single
photo. Both symptoms trace to the same root cause: `.metadata.<path>` addressing
(`BINARY_METADATA_PATH_TYPES`, §8) was built for sort/filter, where "narrow" is inherent (one typed
scalar for comparison) — it was never designed for property-*selection*, and got reused there anyway.
Chasing that bug back far enough arrives at exactly the unresolved question §8 already flagged:
`metadata.<path>` is deliberately open-ended, and nothing gives a client, in advance of a query, a way
to know what's actually addressable. That is the same problem §6's dynamic-property registry exists to
solve for item-level dynamic roots — this section works out why that design doesn't transfer directly,
and what does.

**Rejected: denormalizing metadata into the referencing item's own register row.** Considered because
it would let ordinary JSONB-path restriction/sort/filter (already built for every other property) just
work, with no BINARY-specific code at all. Rejected on inspection: binaries are content-addressed
specifically so identical bytes are stored once and shared by any number of items — denormalizing
metadata into every referencing item's row throws that away for metadata specifically. Worse,
`register_item` rows are immutable-per-version (delete-old-committed/insert-new-committed, never
patched in place), so an update to shared content's metadata (re-extraction, an extractor upgrade)
would mean re-materializing a new row version for every item that happens to reference it, rather than
one `binary_content` update instantly visible via the existing join. It also worsens the existing
extraction-vs-attachment race (§8, "still open," cache-vs-record): today the join always reflects
whatever's currently in `binary_content.metadata`, live; a denormalized copy would go stale the moment
extraction completes after attachment, with no propagation-on-completion mechanism built. And it
doesn't solve the actual problem anyway — knowing *where the data lives* doesn't tell a client, in
advance, which paths are valid to ask for.

**Why §6's registry shape doesn't transfer.** An item-level dynamic root is parented to one item type
(or trait, or static group) — a fixed, known scope to register discoveries against. A binary has no
such scope: it's independent of any item, and the same `binary_content` row (deduped by hash) can back
any number of (item type, property) pairs simultaneously — the same JPEG bytes could be `Photo.propA`
for one item and `Person.avatar` for another, at once. There is no single item type to hang a registry
off of.

**The registry, as built out here.** Keyed by the *binary property's own schema id* (e.g.
`File.content`'s id), not `(itemTypeId, propertyId)` — a property id already belongs to exactly one
declaring item type or trait, so the type is implied, not a separate key component. Two tables, not
one:

- `binary_dynamic_property` — a global catalog: id, dot-path (`extracted.exif.colorspace`), and an
  observed/hinted type, mirroring §6's dynamic-property type handling (first-observed wins unless an
  admin hint is set, later mismatches fall back to text). Global, not scoped per property, because the
  same path string means the same thing regardless of which property happens to reference a binary
  carrying it — and where type genuinely could vary, it varies with the *source data*, not with which
  property/item type references it. Concrete evidence, not a hypothetical: a real captured photo's
  EXIF has `"FNumber":"9/5"` — a rational-notation *string*, not a native number. Any such divergence
  would be scattered across whichever binaries happen to be attached to whichever properties, with no
  correlation to property boundaries — so type belongs on the path, not on a per-property join.
- A join table, `(binary_property_id, dynamic_property_id, reference_count)` — **not** a
  `(binary_content_id, dynamic_property_id)` mapping, which was considered and rejected for scale: a
  single real photo flattens to 80-100+ leaf paths, so a per-binary mapping would mean tens to hundreds
  of millions of rows at real DAM scale, written on every upload, with no natural pruning of its own.
  The counter table's size is bounded by (distinct properties) × (distinct paths ever observed under
  each) — orders of magnitude smaller.

**Permissions: none needed.** §8 already established that reading a binary implies reading all of its
intrinsic facts — no per-fact grant is enforceable or meaningful. That holds here unchanged: unlike
item-level dynamic properties (§7), which need their own auto-grant-at-discovery machinery because a
freshly-discovered one starts with zero grants, binary dynamic properties need no grant table and no
auto-grant step at all.

**Identity, and why it's lighter than §6's.** The catalog row still needs a real id (it's the join
table's FK target, and what sort/filter would key a type lookup off), but it doesn't carry §6's
rename-stability pressure — item-level dynamic properties mint permanent ids specifically because
grants target ids and a rename must not silently disconnect one. With no grants here at all, that
pressure doesn't exist; the id is structural, not a stability guarantee anything else depends on.

**Population: three triggers, one shared reconciliation path.**
1. *Attach* — an item's property gets set to a binary (create or update). Flatten the binary's current
   `metadata`; for each path, upsert the catalog row if new, then upsert-or-increment the
   `(binary_property_id, dynamic_property_id)` counter.
2. *Detach/repoint* — an item's property changes away from a binary (repointed, cleared, or the item
   deleted). Flatten the *departing* binary's metadata; decrement each path's counter; delete the join
   row if it hits zero. A shared binary falls out correctly for free: if two items both currently point
   the same property at the same binary, one detaching doesn't zero the count, because the other's
   reference still holds it up.
3. *Re-extraction of an already-connected binary* (config change, extractor upgrade) — not an item
   mutation at all, so it can't reuse triggers 1-2 as-is. Requires: (a) reading the *old* metadata
   before it's overwritten, in the same transaction as the write; (b) diffing old vs. new into
   `addedPaths`/`removedPaths`; (c) finding every distinct property currently referencing that binary
   (`register_binary_property` by `binary_id` — possibly more than one property or item type sharing
   the content); (d) for each such property, adjusting the counter not by ±1 but by the actual current
   reference count for that specific `(property, binary)` pair (`COUNT(*) ... WHERE property_id=X AND
   binary_id=Y`), since multiple items can share one binary under one property. Getting this last part
   wrong (assuming ±1 always) would desynchronize the counters the first time a shared binary is
   reprocessed.

Triggers 1-2 run inside the same database transaction as the register mutation itself — verified
directly, not assumed: `LedgerRegisterCoordinatorImpl.prepare()`/`.commit()`/`.abort()` are already
`@Transactional`, and `prepare()` is what calls `stageItemCreate`/`stageItemChange` (which write
`register_binary_property`), so the counter adjustments piggyback on that existing boundary with no
separate transactional fix needed. Catalog-row inserts need the same minting-concurrency discipline §6
already uses for item-level dynamic properties — a unique constraint on the path column with
`INSERT ... ON CONFLICT (path) DO NOTHING`, not a cluster lock — to avoid two concurrent
first-observations of a brand-new path racing into duplicate catalog rows.

Trigger 3's diff also requires the extraction write itself to become a real replace of `extracted`/
`derived`, not the shallow JSONB merge (`metadata = metadata || :response::jsonb`) currently in
`ExtractBinaryMetadataDelegate.writeMetadata` — a merge only sets/overwrites keys present in the new
response, it never removes one absent from it, so a narrowed exclusion config would never actually
cause fields to disappear under a merge regardless of the registry. **Decided:** move this write (plus
the new diff/reconciliation logic) out of the delegate and into `BinaryPartitionManager` — e.g. a new
`updateMetadata(UUID binaryContentId, String responseJson)` — since the diff needs transactional
read-then-write access to the current value, and actual partition/storage writes belong there, not in
a BPMN delegate. The delegate correspondingly shrinks to "call the media processor, hand the JSON to
`BinaryPartitionManager`" — a first, concrete step toward addressing that the delegate currently does
too much, though the fuller split (separating the gRPC-calling concern from everything else) stays
explicitly deferred. Housekeeping decided alongside this: `ExtractBinaryMetadataDelegate` (and
`HelloWorldDelegate`, now redundant as a reference stub) move out of `org.ntrloc.graph.db.partition.*`
into `org.ntrloc.graph.process.delegate` — `db.partition.*` is for actual partition/storage
interaction, and a process delegate isn't that. Trigger 3's own *invocation* mechanism — how/when
re-extraction of existing content actually gets kicked off — is still the deferred "cache vs. record"
question from §8; this section only fixes what the reconciliation logic must do once that trigger
exists, not the trigger itself.

**Schema representation.** The instinct to keep discovered paths out of the cached, broadcast admin
schema (mirroring §6's "`SchemaManager` never loads dynamic tables") turned out to be solving the wrong
layer of the problem. The actual cost being avoided — a full schema rebuild plus a cluster-wide
broadcast — is a property of the *current, naive* mechanism for *structural* schema changes, which
legitimately have wide blast radius through trait/supertype inheritance (`SchemaAdminViewBuilder`
re-derives effective views across the whole chain). A newly-observed dynamic path has none of that: no
inheritance implications, purely local and additive to one property. **Decided:** build a second,
lighter-weight update path alongside the existing full `reload()`/broadcast mechanism — a distinct
broadcast message carrying just the delta (which property, which path, added or removed), applied by
each node as a small, targeted copy-with-one-field-changed patch to its cached
`AtomicReference<AdminSchema>`, not a full re-derive from Postgres. With that in place, there's no
remaining reason to keep the data out of the schema itself — clients read it the same way they already
read `sortableFields`, no separate lookup API.

Concrete shape: `AdminPropertyDefinitionView` becomes a sealed interface; a new
`AdminBinaryPropertyDefinitionView` record variant (meaningful only when `type() == BINARY`) carries a
`dynamicFields` list (path + type), populated from every join row with `reference_count > 0` for that
property. This is a variant of the *same* property hierarchy, not a wholly separate kind of schema node
the way `PropertyGroup` had to become when `PropertyType.OBJECT` was retired (§4) — appropriate because
a BINARY property remains a genuine property (valued, grantable), unlike OBJECT, which had neither.
Blast radius, to account for during implementation: every existing `property.type() == PropertyType.
BINARY` call site (at least in `SchemaContentResolver`) needs to become an `instanceof`/pattern-match
to actually reach `dynamicFields`; the `withDefinedIn`-style copy helper used by trait/supertype
inheritance-tagging needs an equivalent on the binary variant, or `dynamicFields` could be silently
dropped the first time a BINARY property is copied during effective-view resolution.

**What the registry unlocks, and the resulting request grammar.** Once a path is in `dynamicFields`,
its full dotted path becomes usable both for sort/filter and for property-selection in projection
requests — but the two have different realistic shapes. Sort/filter is expected to stay narrow in
practice — a single typed comparison, e.g. "Photos where `File.content.metadata.extracted.exif.
colorspace == 'CMYK'`" — and needs the existing sort mechanism (`sortExpressionFor`/
`metadataJsonExtraction`, today hardcoded to `BINARY_METADATA_PATH_TYPES`'s tiny fixed map) to become
fully generic: walk however many JSON levels the *requested* path names, cast using whatever type the
*registry* recorded, rather than a small fixed map. Filter/facet on binary attributes was already
flagged in §8 as "not yet extended to `translatePredicate`/`buildTermsFacetFilterFragment`" — still
true, now with the same genericization requirement as sort.

Property-selection is expected to be broader, and can legitimately ask for everything under a subtree
(`File.content.metadata`) or one specific deep leaf (`File.content.metadata.extracted.exif.
FocalLength`). Requesting a specific leaf means genuine per-field response slicing — new work, since
`assembleBinaryValue`/`mergeReadableBinaryProperties` today only know "return the whole binary object or
nothing." For consistency, the existing small hardcoded set (`length`, `mimeType`, `hashes.*`) gets the
same slicing treatment once it exists — there's no principled reason a discovered field slices narrowly
while a built-in one doesn't; this supersedes the live stopgap fix that currently returns the whole
binary object for any `.metadata.*` property-selection request. **Decided:** `id`/`url` are always
included in a binary's response regardless of what's requested — they're structural facts about the
binary value, not opt-in "properties" a caller selects among; only the `metadata` subtree is subject to
slicing.

**Decided: bare prefix, not a glob.** Naming a non-leaf prefix (`File.content.metadata`, no trailing
wildcard character) means "everything under this subtree" — unambiguous the same way a bare property
*group* name already is (a group is never itself a leaf), and consistent with §8's existing
"address exactly what you see, no shortcut forms" rule rather than introducing a new syntax element.
Implemented as returning the raw stored value verbatim, unsliced — the registry is never consulted for
this case (its job is discovery, and resolving one *specific* narrow path into a JSON walk and a type
for slicing or casting), which also means "everything" always reflects exactly what's actually stored,
with no risk of drifting from the counter/removal bookkeeping's own eventual consistency. **Also
decided:** the same bare-prefix-means-whole-subtree rule extends to ordinary (non-binary) property
groups, which have an identical, pre-existing gap today — `restrictToRequestedProperties` only ever
compares against full *leaf* paths, so requesting a bare group name like `dimensions` (no leaf suffix)
currently matches nothing at all, silently returning no properties under it.

**Implementation checkpoint (2026-09-26): backend built and live-verified end to end.** Everything
this section describes above is implemented, covered by tests (`BinaryDynamicPropertyRegistrar`'s own
unit-style integration tests, `RegisterPartitionManager`'s attach/detach/delete/re-extraction
reconciliation tests, the schema-representation test proving `dynamicFields` actually reaches
`AdminBinaryPropertyDefinitionView`, and the projection tests proving bare/prefix/specific-path
selection all behave as designed), and confirmed live against a real running `domain-1` with real
EXIF-bearing photos uploaded earlier in development: creating an item against an already-processed
binary immediately (no manual cache refresh) surfaced all ~58 of that binary's discovered paths —
every top-level ImageMagick attribute, the full `properties.exif.*`/`properties.jpeg.*` tree, and
`hashes`/`length`/`mimeType` — in `projectableFields`, in the admin UI's property-selection dropdown,
and requesting one specific deep path (`File.content.metadata.extracted.properties.exif.Make`) through
that same dropdown returned exactly `{"Make":"Canon"}` nested correctly, `id`/`url` unconditionally
included, nothing else — while photos backed by different binaries lacking that field correctly came
back with an empty `metadata: {}}` rather than an error. One deployment note from getting this far:
this pass edited the baseline migration in place (per this repo's own "single baseline, no incremental
migrations" convention) rather than adding a new migration file — fine for a disposable test
container, but it desyncs Flyway's checksum against any already-migrated persistent dev database, and
must be reconciled there by hand (apply the new DDL directly, then update `flyway_schema_history`'s
recorded checksum) rather than by dropping and recreating it.

The specific pieces built:
- `binary_dynamic_property` (catalog) + `binary_property_dynamic_property` (counted join), per the
  shape above.
- `BinaryDynamicPropertyRegistrar`: `onAttach`/`onDetach` (triggers 1-2) and `onMetadataChanged`
  (trigger 3, scaled by live reference count per property).
- `RegisterPartitionManager.reconcileBinaryDynamicProperties`, wired into `stageItemCreate`,
  `stageItemChange`, and `deleteItem` (the last reads the about-to-cascade-delete binary properties
  before issuing the delete, since `register_binary_property` cascades with the item).
- `BinaryPartitionManager.updateMetadata`: reads the current row `FOR UPDATE`, diffs old vs. new
  `extracted`/`derived`, replaces (not merges) them, and calls `onMetadataChanged`.
  `ExtractBinaryMetadataDelegate` is now a thin caller of it, moved (with `BinaryMetadataExtractionTrigger`
  and their tests) out of `db.partition.process` into `org.ntrloc.graph.process.delegate`, since a BPMN
  delegate isn't partition-interaction code.
- `AdminPropertyDefinitionView` is now a sealed interface (`AdminScalarPropertyDefinitionView` /
  `AdminBinaryPropertyDefinitionView`), the latter carrying `dynamicFields` populated from the registry
  at schema-build time (`SchemaRepository.dynamicFieldsFor`) — one extra query per BINARY property
  found, never on the read/projection hot path.
- The schema-refresh gap this section originally left open turned out not to be optional: without it,
  sort/filter against a newly (or no-longer) discovered path silently used a stale cached schema —
  caught by the existing binary-content-length sort tests failing the moment genericization (below)
  shipped. Fixed with `scheduleSchemaRefreshAfterCommit`: deduplicated per transaction, deferred until
  after commit via `TransactionSynchronizationManager` (never mid-transaction, which would broadcast to
  other cluster nodes before the row they'd re-read is visible to them), and only for a *structural*
  change (a join row actually created or deleted via Postgres's `xmax = 0` / delete-row-count checks),
  not every reference-count increment/decrement. The partial-*schema-tree* patch (copy-with-one-field-
  changed instead of a full rebuild) that this section originally proposed alongside it turned out to
  be separable and was **not** built — `SchemaManager.refreshCache()`'s existing full rebuild is what
  actually runs today, just triggered at the right, newly-added moment; that's a pure schema-cache-
  rebuild cost on the write path, not something touching read/projection performance.
- `RegisterPartitionManager.resolvePropertyPath`/`sortExpressionFor`: `BINARY_METADATA_PATH_TYPES` (the
  hardcoded `{length -> LONG}` map) is deleted entirely, superseded by resolving against the leaf's own
  `dynamicFields`. Since ordinary attach/detach already discovers `length`/`mimeType`/`hashes.*` as real
  registry entries (they're always present in every binary's own metadata), the registry fully subsumes
  what the hardcoded map used to hand-maintain.
- `RegisterPartitionManager.restrictToRequestedProperties`/`assembleBinaryValue`: property-selection now
  supports all three request shapes decided above for a BINARY leaf — bare name or bare
  `<name>.metadata` (whole value, unsliced), and any registry-known deep path (sliced to just that
  value, multiple such paths merging into one combined partial tree). `propertyViewsById` (a sibling
  walk to the existing `propertyPaths`) threads the actual `AdminPropertyDefinitionView` objects through
  so inclusion-checking can consult a BINARY leaf's `dynamicFields`.
- `SchemaContentResolver.appendProjectableFields` also emits one flat entry per registered dynamic
  path (`"<name>.metadata.<path>"`) alongside the bare property name, so the existing flat,
  single-choice property-selection UI needs no frontend code change at all to offer them.

**Still open, not resolved in this pass:**
- The partial-*schema-tree* patch (avoid a full `refreshCache()` rebuild on every newly-discovered
  path) — deliberately not pursued once the correctness fix above shipped; a pure performance
  refinement on the write path, decoupled from the correctness fix itself, and not yet warranted
  without evidence the full rebuild is actually a real cost in practice.
- Trigger 3's own invocation mechanism (bulk re-extraction of existing content) — still §8's deferred
  "cache vs. record" question; `BinaryPartitionManager.updateMetadata` is ready to be called once
  something exists to call it.
- Filter/facet genericization for binary attributes — sort is done; filter/facet
  (`translatePredicate`/`buildTermsFacetFilterFragment`) still need the equivalent registry-driven
  path resolution.
- Whether `dynamicFields` presents the small, always-present intrinsic set (`length`, `mimeType`,
  `hashes.*`) and the open-ended `extracted`/`derived` set uniformly, or distinguishes them somehow —
  today they're uniform (both just entries in the same list), which live verification confirmed reads
  fine in the admin UI dropdown even at ~58 entries for one property, but a UI grouping them visually
  is a reasonable later refinement, not required.
- The fuller split of `ExtractBinaryMetadataDelegate`'s remaining responsibilities (separating the
  gRPC-calling concern from the rest) beyond the `updateMetadata` move — explicitly deferred.
- Registry cleanup/backfill for data that predates the registry tables' existence: this pass's own live
  verification hit exactly this — existing `register_binary_property` rows from before the tables
  existed have no registry entries until their property is genuinely re-attached (a new create/update
  touching that binary). No backfill job exists; not needed for a fresh deployment (baselined from
  scratch), only relevant when evolving an existing populated database, as this pass's own local dev
  environment was.
