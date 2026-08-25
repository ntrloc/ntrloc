# ntrloc ACL Design Notes
## Companion to ntrloc-security-projections-summary.md — findings from stress-testing the marker model

These notes were captured while scoping the first ACL implementation slice (item-type-level
markers). They record correctness principles and open questions surfaced by walking through
real scenarios (embargoed covers, campaign-based exceptions, regulatory precedent) — none of
this changes what the first slice builds, but it shapes the slices that follow.

**2026-08-24 pivot:** the "first slice" described below routed type-level read visibility through
`authorization_item_type_marker` (a marker assigned to a type, with `item:read` grants against
that marker) — i.e. the *same* marker/grant mechanism instance-level permissions use. That's been
superseded: `item-type:read` and `item-type:create` are now direct `(principal, item_type,
permission)` grants with no marker in between (see "Type Visibility" in
`ntrloc-security-projections-summary.md`). `authorization_item_type_marker` was dropped in favor of
`authorization_item_type_grant` (migration `V1_0_1_6__item_type_direct_grants.sql`).

**2026-08-24, later the same day — instance-level markers built.** Everything below that was
written as a design proposal is now real, implemented code, in four slices:

- **Schema + write path** — `register_item_marker`/`register_link_marker` join tables,
  `authorization_grant.property_id`, migration `V1_0_1_7__instance_level_markers.sql`.
  `MarkerAssignmentService` is the minimal marker-assignment write path (ledgered via four new
  sealed `LedgerEntry` types — `ItemMarkerAddEntry`/`RemoveEntry`, `LinkMarkerAddEntry`/
  `RemoveEntry` — and posted to the register by `LedgerRegisterCoordinatorImpl`), deliberately
  decoupled from *how* a marker gets assigned (no TTL/ad-hoc feature, no rule engine yet — those
  are still open, see "Marker narrowing requires swap discipline" below).
- **Existence filtering (mode 1)** — `RegisterPartitionManager.buildItemReadPermissionFragment`/
  `buildLinkVisibilityPermissionFragment`, wired into `project`/`projectAcrossTypes`/
  `fetchLinksByItem` via `RequestPermissionContext` (new type,
  `org.ntrloc.graph.db.partition.authorization`).
- **Field/capability filtering (mode 2)** — `RegisterPartitionManager.filterPropertiesByReadGrant`/
  `buildPermissions`, wired into `assembleProjectedItems`/`fetchLinksByItem`.
- **Caching** — `AuthorizationCacheManager`.

See each section below for what changed between the original sketch and what actually got built —
mostly matches, a few concrete corrections are called out inline.

## Superuser bypass lives on the identity, not the authentication mechanism

`security_user.is_superuser` is the single source of truth for bypassing marker authorization
entirely (per the original design doc's "superusers bypass all policy" principle), checked in
`PermissionService.canReadItemType` and `SchemaManager.getSchema`. It deliberately does not live
on `security_local_credentials.role` — a superuser should bypass policy the same way regardless
of whether they authenticated locally, via LDAP, OAuth, or the stand-in header, since "admin" is
a property of the identity, not of how that identity proved itself in a given request. The local
credentials `role` column stays as descriptive/display data only; `is_superuser` is what's
actually checked.

Bypass is applied at each enforcement call site (`canReadItemType`, `getSchema`) rather than
inside `effectiveMarkers` — that keeps the raw grant-resolution primitive a pure function of
grants, with superuser semantics as a policy decision made by callers, not baked into the query
that computes "what markers does this principal hold."

## Principal resolution: real session takes priority over the stand-in

`PrincipalResolver` checks the real authenticated Spring Security session first, falling back
to the header/query-param stand-in only when no real session exists. This ordering is what
keeps the stand-in safe rather than a spoofing vector: `SecurityConfig`'s filter chain already
requires authentication for every request once `ntrloc.security.enabled=true`, so a real
`Authentication` is guaranteed present by the time `PrincipalResolver` runs in that mode — the
stand-in header can only ever be reached when security is disabled (permissive/test mode), and
a real session can never be overridden by a forged header (verified: presenting a valid session
cookie alongside a spoofed `X-Ntrloc-User` header for a different identity resolves to the real
session's identity, not the header's).

Implementation note: the `Authentication` must be obtained as a resolved controller method
parameter (a bare `Authentication` parameter, or `@AuthenticationPrincipal`), not by blocking on
`ReactiveSecurityContextHolder.getContext()` inside `PrincipalResolver` itself — Reactor's
non-blocking-thread guard rejects `.block()`/`.blockOptional()` calls made from within the
Security filter chain's reactive context (unlike the plain blocking JDBC calls elsewhere in this
module, which aren't Reactor operators and so aren't subject to that guard).

## Performance model

**Two computational modes, chosen per operation, never mixed:**

1. **Existence-affecting → SQL semi-join.** `item:read`, `link:read`. These change whether a row
   is in the result set at all, so they must be in the WHERE clause — pagination and `totalCount`
   have to reflect the filtered set, which post-fetch filtering can't guarantee.
2. **Field/capability-affecting → in-memory, post-fetch.** `item:delete`, `link:delete`,
   `property:read`/`write`, `link_property:read`/`write`. None of these change row counts (they
   decide what a row *shows*, or what a client is *allowed to do* with an already-visible row, not
   whether it appears), so none of them belong in a WHERE clause. See "Request-scoped permission
   context" below for how these resolve without any per-item or per-property query.

**Built.** Item-level visibility filtering is the expensive, large-scale case in mode 1 — it
reduces to an indexed semi-join composing as just another predicate in the same predicate-to-SQL
machinery the projection engine already has for user filters (`buildItemReadPermissionFragment`,
combined into `filterFragment` right where it's built in `project`/`projectAcrossTypes`). This is
the same predicate a link's *target* item is checked with (see "Link existence filtering" below) —
joined into the same query that retrieves the link, using the same request-scoped
`grantedItemReadMarkerIds`, rather than a second fetch.

One correction from the original sketch: the actual predicate is `WHERE ri.id IN (SELECT
register_item_id FROM register_item_marker WHERE marker_id IN (:grantedItemReadMarkerIds))` —
`IN (:collection)`, not `= ANY(:collection)`. JdbcClient binds a `Collection` parameter by
expanding it into a comma-separated `IN` list, not as a native Postgres array value, so `= ANY(:x)`
against a plain `Set<UUID>` param would either fail to bind correctly or require a real
`java.sql.Array`; `IN (:x)` is the form already proven throughout this codebase (e.g.
`AuthorizationRepository`'s existing `principal_id IN (:groupIds)`). Also load-bearing: an empty
`grantedItemReadMarkerIds` set must never reach `IN (:x)` as an empty list (invalid SQL) — it
short-circuits to a literal `AND FALSE` instead, correctly meaning "nothing visible" with no query
at all.

## Register stores item-level markers only

Never per-property or per-link. Property and link permissions are resolved dynamically at read
time by combining an item's markers (register) with a Grant scoped to the specific
property/perspective (a small, schema-sized table, not data-sized) — this avoids the
write-amplification/storage-blowup risk of materializing markers at property granularity.

## Grants need a property-scope column

**Built** (migration `V1_0_1_7__instance_level_markers.sql`). One implementation detail beyond
the original proposal: the existing `UNIQUE (marker_id, principal_type, principal_id, operation)`
constraint had to become `UNIQUE NULLS NOT DISTINCT (marker_id, principal_type, principal_id,
operation, property_id)` — plain `UNIQUE` treats every NULL `property_id` as distinct from every
other, which would have silently allowed duplicate `item:read` grants for the same marker/
principal. Also added a `CHECK` tying `property_id IS NOT NULL` to exactly the four property-scoped
operations, so the invariant is enforced at the DB level, not just by convention.

**2026-08-24, found while designing the read path.** `authorization_grant` as it stands
(`marker_id`, `principal_type`, `principal_id`, `operation`) has no way to scope a `property:read`/
`property:write` grant to a *specific* property — as shaped, granting `property:read` on a marker
would mean "read every property on any item carrying this marker," contradicting the documented
"granted at the individual property level... independently controlled per marker." Fix: add
`property_id UUID NULL REFERENCES schema_property(id)` — NULL for `item:read`/`item:delete`/
`link:read`/`link:delete` (unscoped, item/link-level operations), required for `property:read`/
`property:write`/`link_property:read`/`link_property:write`.

One column serves both item and link properties: `schema_link_property.property_id` and
`schema_item_property.property_id` both reference the same `schema_property.id` — properties are
globally unique and just associated to item or link types via separate join tables (see
`ntrloc-design-summary.md` §2 Traits). `operation` already disambiguates item-context vs
link-context, so no second scope column is needed for links.

## Request-scoped permission context — computed once, reused everywhere

**Built** as `RequestPermissionContext` (`org.ntrloc.graph.db.partition.authorization`), assembled
once per request by `PermissionService.buildContext`:

```
superuser                     : boolean                             -- short-circuits everything else
readableItemTypeIds           : Set<itemTypeId>                     -- type-level, direct-grant (see Type Visibility)
grantedItemReadMarkerIds      : Set<markerId>                       -- mode 1, reused by every semi-join
grantedLinkReadMarkerIds      : Set<markerId>                       -- mode 1, reused by every semi-join
itemDeleteGrantedMarkerIds    : Set<markerId>                       -- mode 2, capability flag
linkDeleteGrantedMarkerIds    : Set<markerId>                       -- mode 2, capability flag
propertyReadGrantsByMarker    : Map<markerId, Set<propertyId>>      -- mode 2, property:read (item's own properties)
propertyWriteGrantsByMarker   : Map<markerId, Set<propertyId>>      -- mode 2, property:write
linkPropertyReadGrantsByMarker  : Map<markerId, Set<propertyId>>    -- mode 2, link_property:read (the link edge's own properties)
linkPropertyWriteGrantsByMarker : Map<markerId, Set<propertyId>>    -- mode 2, link_property:write
```

One correction from the original sketch: property read/write grants are **four** maps, not two —
kept separate for item-context (`property:*`) vs link-context (`link_property:*`) rather than one
shared pair, because a single request can need both at once (a linked item's own properties are
governed by `property:read`/`write`, the link edge's own properties by `link_property:read`/
`write` — conflating them would let a `link_property:write` grant leak into a linked item's `edit`
list or vice versa; see `PropertyAndCapabilityFilteringIntegrationTest`'s
`linkPropertyWriteGrant_doesNotLeakIntoLinkedItemsEditList`).

Ten fields, each backed by a lookup against the cache (see "Caching grant definitions" below) —
zero live queries per request now that the cache exists.

For each page of rows fetched (top level or any `fetchLinksByItem` call, any recursion depth): one
batched lookup (`RegisterPartitionManager.getMarkerIdsForRegisterItems`/
`getMarkerIdsForRegisterLinks`), grouped into `Map<registerItemId, Set<markerId>>`. Everything
downstream is in-memory set arithmetic against the context above, no further queries:

- `filterPropertiesByReadGrant` — filters the still-ID-keyed JSONB properties map (before
  `namesForIds` resolves names) down to the union of `propertyReadGrantsByMarker`/
  `linkPropertyReadGrantsByMarker` across the row's markers.
- `buildPermissions` — the `edit` name list (from the write-grant map, resolved back to top-level
  property names) and the `delete` flag (marker set intersects the delete-grant set), populating
  `ProjectedItemPermissions` per item and per link (`ProjectedLink` gained its own `permissions`
  field, reusing the same type).

Cost per page: one extra indexed query, output bounded by `page_size × avg_markers_per_item` —
same order of magnitude as the mode-1 semi-join, and it composes at every nesting level without
recomputing the request-scoped context.

Known, deliberately out-of-scope gap: binary properties bypass all of this (unchanged pre-existing
behavior, not a regression) — the design calls for a separate `binary:download` primitive that
`RequestPermissionContext` doesn't cover yet.

## Link existence filtering: two semi-joins in `fetchLinksByItem`

**Built** — `RegisterPartitionManager.buildLinkVisibilityPermissionFragment`, spliced into
`fetchLinksByItem`'s `WHERE` clause. `fetchLinksByItem` had zero permission filtering before this
and doesn't share code with `project`/`projectAcrossTypes`'s predicate machinery — it's a separate
query path (joins `register_item_link_perspective` → `register_link` → the target's
`register_item`), so link visibility (see "Link Read Visibility" in
`ntrloc-security-projections-summary.md`) had to be added there directly, once, covering every
recursion level for free (`fetchNestedLinksForRequestedPerspectives` recurses through the same
method, threading the same `RequestPermissionContext`). Of the five ANDed conditions in that model,
two need a semi-join here (the other three are already established by the time a row reaches this
query — the source item's own type/instance readability got checked to produce the page this
method was called on):

```sql
... JOIN register_item ri_other ON ri_other.id = rilp_other.register_item_id ...
WHERE ...
  AND ri_other.item_type_id IN (:readableItemTypeIds)                        -- target item-type:read
  AND ri_other.id IN (SELECT register_item_id FROM register_item_marker
                       WHERE marker_id IN (:grantedItemReadMarkerIds))       -- target item:read
  AND rl.id IN (SELECT register_link_id FROM register_link_marker
                WHERE marker_id IN (:grantedLinkReadMarkerIds))              -- link:read itself
```

(`IN`, not `ANY` — see the correction in "Performance model" above; the same empty-set-to-`FALSE`
guard applies to all three conditions here too.)

Both semi-joins reuse the exact same `grantedItemReadMarkerIds`/`grantedLinkReadMarkerIds` sets
computed once for the whole request (see "Request-scoped permission context" above) — never
recomputed per link, per item, or per nesting level. The `readableItemTypeIds` check is a cheap,
already-in-memory set (see "Type Visibility" in the security-projections doc); it's applied as a
row filter rather than pruned before the query runs, which is a fine simplification for now.

Verified end to end by `InstanceReadFilteringIntegrationTest`: target without `item:read` →
excluded even with `link:read` granted; link without `link:read` → excluded even with both
endpoints visible; all three conditions satisfied → included; superuser bypasses all three.

## Caching grant definitions, not item-marker assignments

**Built** as `AuthorizationCacheManager`. Caches `authorization_grant` *and*
`authorization_item_type_grant` — schema-sized, admin-curated, rarely-changing config data — never
the data-sized `register_item_marker`/`register_link_marker` tables (those stay real per-page
queries in `RegisterPartitionManager`/`AuthorizationRepository`). `authorization_marker` itself
doesn't need caching at all — nothing looks a marker up by name/description at request time, only
by id via the FKs already embedded in `register_item_marker`/`authorization_grant` rows, so there's
no read path that would benefit. Mirrors `SchemaManager`'s pattern exactly: an `AtomicReference`
holding the derived structure, a Hazelcast `ITopic` (`authorizationChangedTopic`) with
`addMessageListener` (including the same self-publishing-member filter, so the node that made the
change doesn't redundantly rebuild twice) so every cluster node rebuilds on change, and a
`refreshCache()` that rebuilds locally and publishes.

**The trigger mechanism** ended up more specific than "whenever a grant is added/removed" implied:
`AuthorizationRepository` takes a `@Lazy`-injected `AuthorizationCacheManager` and calls
`refreshCache()` at the end of every grant-mutating write method
(`grantItemType`/`grantItemTypeIfAbsent`/`deleteItemTypeGrant`/`grantMarker`/
`grantMarkerIfAbsent`/`deleteMarkerGrant`). `@Lazy` breaks what would otherwise be a circular bean
dependency (`AuthorizationCacheManager` needs the repository's bulk-read methods to rebuild itself)
via Spring's standard deferred-resolution-proxy technique. The refresh is synchronous, so a write
immediately followed by a read — the pattern essentially every test in this session's marker/
permission suite relies on — stays correct with no test changes required when caching was added.

Cache at the **grant-source level** — `Map<(principalType, principalId, operation), Set<markerId>>`
(and the property-scoped variant, and the item-type-grant variant) — not at the
resolved-effective-principal level. A per-user cache would need invalidating for every member
whenever a shared group's grant changes, and would need to know about group-membership changes too.
Caching the raw grant rows instead means resolving a principal's effective set at request time is
just: `index.get(("USER", userId, op))` unioned with each of `groupIds.map(g -> index.get(("GROUP",
g, op)))` — a few hashmap lookups, no I/O — and group-membership changes need zero cache
invalidation at all, since membership is only consulted at union-time. Verified directly by
`AuthorizationCacheManagerIntegrationTest.groupMembershipChangeAlone_needsNoCacheRebuild_membershipResolvedAtReadTime`.

**Caveat, still open:** this assumes grants stay schema-sized, as the design already asserts. If
direct-to-user grants (vs. group grants) ever become numerous at scale, the fallback is caching
group-scoped grants fully and resolving direct user grants on demand — not needed now.

## Marker storage: join tables, not a multi-value field

**Built** (migration `V1_0_1_7__instance_level_markers.sql`, `RegisterPartitionManager.
postItemMarkerAdd`/`postItemMarkerRemove`/`postLinkMarkerAdd`/`postLinkMarkerRemove`).
`register_item_marker(register_item_id, marker_id, ...)` and
`register_link_marker(register_link_id, marker_id, ...)` — narrow join tables, not an array/JSONB
column on `register_item`/`register_link`, and not embedded in a per-type table's `properties`
JSONB. Considered and rejected:

- **A field on `register_item`/`register_link` itself.** These rows aren't updated in place —
  `RegisterPartitionManager.commitItem()` stages a whole new row (new surrogate id) for every
  property mutation and deletes the old one. A marker column living on that row would need to be
  explicitly copied into every new staged row or it silently vanishes on the next commit —
  coupling ordinary property mutations to marker bookkeeping, which contradicts markers being
  orthogonal to properties (see "Marker narrowing requires swap discipline" above). A join table
  needs no such logic: it gets the same one-line FK repoint `register_item_link_perspective`
  already uses on commit (`UPDATE register_item_marker SET register_item_id = :newId WHERE
  register_item_id = :oldId`), for free, because it's the identical structural problem link
  perspectives already solved.
- **Embedding marker ids inside the item's own `properties` JSONB.** Avoids the repoint problem
  (properties are already carried forward on every mutation by construction) but mixes security
  metadata into user-writable domain data: needs airtight guarding in every property read/write/
  permission-check path so a marker key is never treated as a real property, is exposed to the
  "orphaned JSONB keys are silently dropped" read-tolerance policy unless specifically excluded,
  duplicates the concern across every per-type table, and is the worst of the three for write
  amplification since it rewrites the actual properties blob on every marker change.

**Filtering must be a semi-join, not a join.** `WHERE item_id IN (SELECT item_id FROM
register_item_marker WHERE marker_id = ANY(:grantedMarkerIds))` (or the `EXISTS`-correlated
equivalent) returns each item at most once regardless of how many markers it carries — Postgres
plans `IN`/`EXISTS` subqueries as a semi-join, a distinct join type from an ordinary `JOIN ... ON`,
which is what would actually produce one row per matching marker. Enumerating an item's full
marker set (admin transparency tooling, per-property grant resolution) is a separate, deliberately
multi-row query that runs on an already-small page, not something joined into the primary
filtered result set.

**Sizing.** At millions of items with a generous per-item marker count (e.g. 5M × 20 = 100M rows),
the join table lands around 15–20 GB including two btree indexes (`(register_item_id, marker_id)`
as PK, `(marker_id, register_item_id)` for the semi-join direction) — ordinary production Postgres
territory, not an outlier. Semi-join cost is governed by the size of `grantedMarkerIds` (small,
principal-scoped) and index depth (O(log n)), not by the join table's total row count. Marker
churn's MVCC bloat stays isolated to this narrow table instead of infecting the (larger, more
valuable-to-keep-clean) per-type property tables — an advantage of the join table at scale, not
just a neutral tradeoff. Partitioning `register_item_marker` by `register_item_id` is available as
a later lever if row counts ever reach the billions; not needed now.

**Ledger integration needed no new table** — confirmed. `ledger_entry` is a generic, polymorphic
changelog (`target_type`, `target_id`, `entry_type`, `payload JSONB`), but `LedgerEntry` itself
(the Java side) is a *sealed interface*, one record per entry kind, matching `ItemCreateEntry`/
`LinkCreateEntry`'s existing Item/Link-paired pattern — so this became **four** entry types, not
two generic ones distinguished by `target_type`: `ItemMarkerAddEntry`, `ItemMarkerRemoveEntry`,
`LinkMarkerAddEntry`, `LinkMarkerRemoveEntry`, each just `(targetId, markerId)`. `payload` doesn't
yet carry `reason`/`ruleId` — those are ad-hoc-marker-TTL and rule-engine concepts, both still
unbuilt (see "Marker narrowing requires swap discipline" below); `MarkerAssignmentService`'s
current write path is deliberately minimal. Posting into the register is a direct insert/delete in
`LedgerRegisterCoordinatorImpl.commit()`'s dispatch loop, keyed by the same `(itemId/linkId,
markerId)` pair the ledger entry already carries — no staging needed at all (unlike a create/
update), the same "nothing to stage, apply directly at commit" shape a delete already has.

## Marker narrowing requires swap discipline

Because effective permission is a *union* of grants across an item's markers, adding a
restrictive marker without removing the broader one it's meant to override has no effect —
whoever holds a grant on the broader marker still sees the item. Any rule meant to narrow access
must pair an add with a remove of whatever marker was providing the broader access. This is a
correctness rule for the (still deferred) marker assignment rule engine, not a flaw in tagging
itself — the union-across-markers mechanism this rule depends on is real and tested now (e.g.
`collectionProjection_mixedVisibility_...` in `InstanceReadFilteringIntegrationTest`), only the
rule engine that would *automatically* add/remove markers on property changes is still unbuilt;
markers are assigned manually via `MarkerAssignmentService` today.

## Link permissions split two ways

**Built and enforced** — `link:read` via the semi-join in "Link existence filtering" above,
`link_property:read`/`write` via the mode-2 machinery in "Request-scoped permission context".

Each independently governable and each with real regulatory precedent:
- `link:read` — can you traverse this perspective at all
- `link_property:read` — can you see properties stored on the edge itself

Motivated by linkability as a named disclosure risk distinct from either endpoint's own
sensitivity (EU WP29 anonymization guidance), and relationship confidentiality precedent (42 CFR
Part 2, where the mere existence of a relationship is protected independent of either party's
own data). Both still hold under the resolution below — `link:read` can be granted more tightly
than either endpoint's own visibility, so a Part-2-style relationship can still be locked down
beyond what item-level access alone would allow.

**2026-08-24: resolved.** An earlier draft of this split had a third primitive,
`link_target:read` — does this specific perspective reveal the connected item's data, governed by
the *link's own* markers + perspective rather than the target's own markers, specifically to avoid
fetching the target's marker set just to gate traversal. Dropped. Reading a link now requires
`item:read` on the target item exactly as it does on any directly-queried item (see "Link Read
Visibility" in `ntrloc-security-projections-summary.md`), resolved as a joined semi-join rather
than a second fetch — see "Performance model" above — so the performance concern that motivated
`link_target:read` no longer applies once the check is expressed as a join instead of a fetch.

Two reasons beyond performance to prefer this:
- It generalizes a principle the design already committed to for the cross-domain case —
  `ntrloc-design-summary.md` §5: "if a linked item in another domain is not visible to the caller,
  the link itself is not surfaced" — rather than introducing a second, narrower stance for the
  same-domain case.
- **Understandability.** `link_target:read` would have let a link's own markers make an item's
  data visible to a principal who has no `item:read` grant on that item at all, and would never
  see it any other way. A domain admin configuring markers on the link wouldn't necessarily
  connect that grant back to the item's own read policy — a second, easy-to-miss channel for
  exposing data, and a standing source of "why can this user see that?" surprises that the
  "Current state explanation" tooling would then have to reconstruct across two unrelated grant
  paths instead of one. A single channel (`item:read`, intrinsic-marker-based today, later also
  extrinsic-rule-derived) keeps the model something an admin can actually reason about.

The cost: pure relationship-conferred visibility (e.g. a caseworker seeing clients linked via
`assignedTo` without holding a blanket `item:read` grant on Client) isn't representable until
extrinsic rules exist (see "Intrinsic vs. extrinsic permissions" below) to grant `item:read` via
that relationship. That pattern was never actually implemented by `link_target:read` either — it's
a gap either way, just now attributed to the right place (extrinsic rules, not yet built) instead
of accidentally covered by a mechanism whose actual purpose was something else.

## Traversal/query permission checks are monotonic

AND down the path, prune on first failure. This is tractable specifically because a
query/projection request is always a finite, client-authored tree (JSON nesting), regardless of
how tangled the underlying data graph is — permission is evaluated per response-position (a path
through the query), not per-item globally, so the same underlying item can legitimately have
different visibility at different positions in one response. Analogous to Unix directory
execute-bits (need +x on every parent directory *and* read on the file) — reframed around
query-tree shape rather than data hierarchy.

## Intrinsic vs. extrinsic permissions are complementary, not competing

Intrinsic (marker-based) permissions are resource-state-derived, principal-independent, cheap,
and additive-only. Extrinsic (relational/contextual) permissions depend on a specific
relationship between the requesting principal and the item (e.g., "visible because you're
assigned to the campaign this belongs to") — evaluated per-request at the small-N stage, and
uniquely support explicit **deny**, which overrides grant (consistent with default-deny).

This split exists because real regulatory frameworks are partly extrinsic by nature — GDPR
purpose limitation, HIPAA's treatment-relationship requirement, and healthcare "VIP"/self-access
restrictions, financial/legal ethical walls, and segregation-of-duties rules are all real-world
cases where a relationship *narrows* access a role would otherwise grant, not just widens it.

A rough future shape for recording extrinsic rules: a small admin-curated definitions table
(name, effect GRANT|DENY, primitive, governed type, a bounded path expression anchored at
`PRINCIPAL`, precedence), evaluated at request time as a per-(principal, item) existence check —
reusing the same bounded-traversal and dot-notation predicate style the projection engine
already has for filters, rather than a new path language. Example shape:

    ExtrinsicRule {
      name: "campaign-marketer-preview-access"
      effect: GRANT | DENY
      primitive: item:read
      governedType: CoverImage
      path: PRINCIPAL -[assignedTo]-> Campaign <-[contains]- Product -[cover]-> ITEM
      precedence: 20
    }

Open question not yet resolved: whether extrinsic GRANT and DENY rules of equal precedence
should have defined tie-breaking beyond "deny wins" — not yet a concrete scenario to test this
against.
