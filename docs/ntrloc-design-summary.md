# ntrloc Design Summary
## Topics: Per-Type Tables, Link Model, Security, Regulatory Frameworks, Staging

---

## 1. Per-Type Tables

### Decision
Items are stored in per-type tables rather than a single unified `items` table. Read performance is the top priority and per-type tables serve it best.

### Structure

```sql
-- Shared identity anchor
CREATE TABLE item_registry (
    id               UUID PRIMARY KEY,
    type             TEXT NOT NULL,
    visibility_state TEXT NOT NULL DEFAULT 'NORMAL',
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Per-type property tables
CREATE TABLE items_product (
    id         UUID PRIMARY KEY REFERENCES item_registry(id),
    properties JSONB
);

CREATE TABLE items_cover (
    id         UUID PRIMARY KEY REFERENCES item_registry(id),
    properties JSONB
);
```

### Key Points

- `item_registry` is the cross-type identity anchor. Links reference `item_registry.id`, not type-specific tables.
- Properties are stored as JSONB in per-type tables. Trait properties are stored redundantly in each participating type's table — normalized in schema metadata only, denormalized in storage.
- Property keys in JSONB are immutable property IDs (e.g. `prop1`), never labels. Labels are resolved at read time from schema metadata, ideally cached in application memory.
- ntrloc manages all DDL — table creation, index creation, view creation — automatically when types and properties are registered. This is never a manual operation.
- A `visible_items_{type}` view per type handles visibility state filtering and snapshot property substitution for `UNCOMMITTED_UPDATE` items.
- Expression indexes are created per property per type when a domain extension registers a queryable property. GIN index on the properties column provides a catch-all for ad-hoc queries.

### Cross-Type Queries

Cross-type queries are always trait-constrained — only item types implementing a given trait participate. At the database level this becomes a UNION ALL across the relevant type tables. Partial aggregation push-down keeps faceting efficient:

```sql
SELECT format, SUM(count) AS count
FROM (
    SELECT properties->>'prop3' AS format, COUNT(*) AS count
    FROM items_cover WHERE ...
    GROUP BY format

    UNION ALL

    SELECT properties->>'prop3', COUNT(*)
    FROM items_alternate_image WHERE ...
    GROUP BY format
) partial
GROUP BY format;
```

### Schema Versioning

Schema versioning is a punctuation mark, not continuous overhead. Most schema changes (adding properties, renaming labels, adding/removing traits from types) do not require a version bump. Only explicit data migrations — property consolidation, trait extraction — advance the schema version. During migration, items carry a schema version tag; reads interpret each item against its declared version until migration completes, at which point the old version is retired.

---

## 2. Traits

### Definition
A trait is a named, reusable group of property definitions. Item and link types declare which traits they implement. A type's full property set is the union of all its traits' properties plus any type-local properties.

### Schema Structure

```sql
CREATE TABLE traits (
    id          TEXT PRIMARY KEY,
    label       TEXT NOT NULL,
    description TEXT
);

CREATE TABLE trait_properties (
    trait_id    TEXT REFERENCES traits(id),
    property_id TEXT REFERENCES property_definitions(id),
    PRIMARY KEY (trait_id, property_id)
);

CREATE TABLE item_type_traits (
    item_type   TEXT,
    trait_id    TEXT REFERENCES traits(id),
    PRIMARY KEY (item_type, trait_id)
);
```

### Key Points

- Property IDs are globally unique and immutable. Label collisions between traits are possible but resolved at the read/write layer (see below).
- Trait properties are stored redundantly in item JSONB — no separate trait value table. The trait definition is schema metadata; the JSONB is the materialized storage.
- Adding a trait to an item type is non-destructive. Existing items return null for new trait properties until explicitly populated. Writes immediately enforce the new schema.
- Removing a trait from an item type is handled by ntrloc's read tolerance policy — orphaned property values in JSONB are silently ignored on read. Writes reject deprecated properties immediately.
- Links can also have traits, primarily to avoid redundant property definitions across link types.

### Label Collision Resolution

When two traits implemented by the same item type define properties with the same label, ntrloc qualifies all colliding properties with their trait label at read and write time:

- Trait "employee" defines property `prop1` labeled "name"
- Trait "user" defines property `prop2` labeled "name"
- Item type "systemAdmin" implements both traits
- At read/write time: properties surface as "employeeName" and "userName"
- If one label is later renamed, eliminating the collision, both revert to unqualified names

### Schema Evolution Policy

- **Writes** enforce compliance with the current schema. Properties not defined in the current schema are rejected.
- **Reads** interpret stored data against the current schema and silently disregard properties/links not currently defined.
- **Property types are immutable** — a property's ID, data type, and fundamental semantics never change. Labels, trait assignments, and type memberships can change.
- **Item types are mutable** — traits can be added or removed over time.

---

## 3. Link Model

### Directionality
Links are **undirected**. There is no source or target — only two participant slots (A and B) with no implied directionality between them. A link between two items either exists or it doesn't; neither item "owns" the relationship more than the other.

### Participant Labels
Each participant slot carries an optional label — what that participant calls the link. If a slot has no label, the link is invisible from that participant's perspective. This provides lightweight directional visibility without encoding direction into the link itself.

Example:
```
Link type: lt_001
Participant A: Photographer    label: "created"
Participant B: Photo           label: "creator"
```

A Photographer sees its "created" links. A Photo sees its "creator" links. A third item type linked to a Photo with no label defined simply doesn't see the relationship.

### Typed Links
Links are explicitly typed — each link type defines an exact relationship between two specific item types. This keeps link semantics precise and auditable.

Multiple link types can share the same label from a given participant's perspective. At read time, ntrloc groups links by label. Changing a label updates grouping across all link types sharing that label simultaneously.

Example:
- `lt_001`: Photographer → Photo, label "created" / "creator"
- `lt_002`: Photographer → Folder, label "created" / "contained by"
- `lt_003`: Photographer → Project, label "created" / "includes"

A Photographer's "created" links transparently includes Photos, Folders, and Projects.

### Link Properties
Links support properties under the same model as items — property IDs stored in a link properties table or JSONB, resolved to labels at read time.

### Traversal
Traversals up to approximately 5 hops are handled efficiently via PostgreSQL recursive CTEs. This covers the practical depth of real-world ntrloc queries. Deeper traversal is theoretically supported but not an identified concrete requirement.

---

## 4. Security Model

### Core Concepts

**Policy Markers** — Simple identifier tags applied to items and links. Markers carry no logic themselves; they are the attachment point for permission grants.

**Permission Grants** — A user or group is granted specific operation primitives on a marker. The grant defines what that principal can do with items/links carrying that marker.

**Marker Assignment Rules** — Declarative rules that automatically assign or remove markers from items and links based on item/link properties, type, trait, or properties of related items via link traversal.

**Temporary Security Overrides** — Time-bounded, item-scoped ephemeral policies granting specific permissions to specific users/groups, created by privileged users.

### Operation Primitives

| Primitive | Applies to |
|---|---|
| `item:create` | Item type |
| `item:read` | Item instance |
| `item:delete` | Item instance |
| `property:read` | Property on item |
| `property:write` | Property on item (implies read) |
| `link:create` | Link type |
| `link:read` | Link instance |
| `link:delete` | Link instance |
| `link_property:read` | Property on link |
| `link_property:write` | Property on link (implies read) |
| `binary:download` | Binary record |
| `security:override` | Permission to create temporary overrides |
| `marker:apply` | Apply a specific marker to an item/link |
| `marker:remove` | Remove a specific marker from an item/link |

### Permission Principles

1. **Default deny** — a principal has no permissions on any item unless explicitly granted
2. **Union across markers** — effective permissions are the union of all grants across all markers applied to an item
3. **Write implies read** — `property:write` cannot be granted without `property:read`; there are no write-only properties
4. **All-or-nothing proposals** — if any property in a mutation fails a permission check, the entire proposal is rejected
5. **Superusers bypass all policy** — system admins are not constrained by security policies; all actions are still fully audited

### Marker Assignment Rules

Rules fire as part of the commit pipeline. They are evaluated when:
- An item or link is created or modified
- A property changes on a related item (via declared traversal path)
- A property changes on a related link

Rules declare explicit traversal paths — the engine never does open-ended graph exploration. Paths are bounded and known at rule definition time:

```
Rule: CoverImageEmbargoRule
Governed type:  CoverImage
Trigger paths:
  - CoverImage itself created/modified
  - CoverImage <-[cover]- Product (trigger: Product.embargoed changes)
  - CoverImage <-[cover]- Product <-[contains]- Campaign (trigger: Campaign.restricted changes)
Condition:
  linked Product.embargoed = true
  OR linked Campaign (via Product).restricted = true
Action:
  IF true:  apply marker 'embargoed'
  IF false: remove marker 'embargoed'
Precedence: 10
```

Rule re-evaluation is part of the atomic commit — marker changes are never asynchronously lagged behind property changes.

### Ad-Hoc Markers

Ad-hoc markers are applied manually by privileged users (those with `marker:apply` permission). They are:
- **Persistent** — not overridden or removed by the rule engine regardless of rule precedence
- **Time-bounded** — a TTL is mandatory; there is no such thing as a permanent ad-hoc marker
- **Audited** — creation and expiry are both write layer events

### Temporary Security Overrides

A privileged user with `security:override` permission on an item can create a temporary override:
- Scoped to a specific item
- Grants specific operation primitives to specific users/groups
- Mandatory expiry — no permanent overrides
- Reason field mandatory — documents justification
- Full audit trail — creation, modification, and expiry are write layer events
- Expired overrides cease to exist; they do not linger in an inactive state

### Create Permission Evaluation

Create permission is evaluated in two gates before any data is persisted:

1. **Type-level gate** — does this principal have `item:create` for this item type, via any marker governing that type?
2. **Hypothetical instance gate** — given the intended properties and links of the new item, what markers would be assigned? Does the principal have `item:create` on any of those markers? Do create-specific rules (which may traverse linked items) permit this creation?

If the creating principal would not have `item:read` on the newly-created item under the markers that would be assigned, the create is rejected with a meaningful error rather than creating an item the creator cannot see.

### Policy Changes and the Audit Trail

Policy marker changes — both rule-assigned and ad-hoc — are first-class events in the write layer changelog. They participate in the same versioning model as property changes. An item's full history includes not just when its properties changed but when its security posture changed and why.

### Projected Schema

The schema endpoint returns a caller-scoped projection — the intersection of what exists and what the caller is permitted to interact with:
- Item types not visible to the caller are omitted entirely
- Properties are included only if the caller has `property:read` or `property:write`
- Each property indicates whether the caller can read it, write it, or both
- Link types are included only if both participant types are visible
- Traits are included only if at least one of their properties is visible in an accessible type context

This makes the projected schema a complete, accurate contract for what the caller can do. No trial and error, no runtime surprises.

### Permission Transparency Tools

ntrloc provides first-class administrative tools for understanding and previewing the security model:

**Current state explanation** — for any item: who can do what, and why, with full explanation of which markers grant which permissions.

**Rule simulation** — for any item: what markers would be assigned if this item's properties were X? Preview security implications of hypothetical property values without making any changes.

**Change impact analysis** — for any proposed security model change: how many items are affected, which users gain or lose access, warnings for unexpected implications (e.g. contractors gaining access to embargoed items).

---

## 5. Regulatory Frameworks

### Design Philosophy
Compliance is a first-class architectural concern in ntrloc, not an afterthought. Regulatory framework support is general and extensible — named presets for known regulations built on a common data classification and policy framework.

### Data Classification

Properties and item types carry classification tags:

```
COPPA_SENSITIVE      -- data relating to minors
GDPR_PERSONAL        -- personally identifiable information
GDPR_SENSITIVE       -- special category data (health, religion, ethnicity, etc.)
HIPAA_PHI            -- protected health information
CCPA_PERSONAL        -- California consumer personal information
PROPRIETARY          -- internal business sensitive
PUBLIC               -- explicitly safe for unrestricted exposure
```

Classifications are additive — a property can carry multiple tags. Classification is part of the schema definition, not a separate metadata layer.

### Framework Definitions

Each framework declares:
- Which classification tags it governs
- Cross-domain exposure constraints on classified data
- Retention limits
- Deletion semantics (purge, anonymization, redaction, suppression)
- Consent or legal basis requirements
- Subject rights (portability, rectification, erasure)

### Domain-Level Activation

A domain admin activates frameworks for their domain:
```
Domain: children-publishing
Active frameworks: COPPA, GDPR
```

Activation automatically enforces all framework constraints on classified properties. Hard walls on cross-domain exposure of classified data cannot be overridden by individual permission grants — not even by superusers in other domains.

### Consent Records

Consent and legal basis records are first-class write layer entities — structured, versioned, auditable facts about authorization to hold and process data. Linked to the items and properties they cover. Consent expiry or revocation triggers automatic re-evaluation of access and flags items whose legal basis for retention has expired.

### Deletion Semantics

| Mode | Description | Typical frameworks |
|---|---|---|
| Purge | Complete removal including changelog | COPPA, GDPR |
| Anonymization | Identifying values replaced with non-identifying equivalents | GDPR |
| Redaction | Values replaced with deletion marker | Various |
| Suppression | Retained internally, excluded from all external exposure | Financial retention regulations |

Every deletion produces a signed **deletion certificate** — verifiable proof of compliance without containing the deleted data.

### Supergraph and Regulatory Compliance

Cross-domain data access via the supergraph respects all domain-level framework constraints:
- COPPA-sensitive properties are never included in cross-domain query results, regardless of permission grants
- The caller's principal is propagated to each domain, which validates it and applies its own security model independently
- The supergraph never receives data the caller isn't permitted to see — enforcement happens at the domain, not the federation layer
- **Link confidentiality** — if a linked item in another domain is not visible to the caller, the link itself is not surfaced; not even as "you don't have permission to see this"

---

## 6. Staging and Changesets

### Core Philosophy
**ntrloc never makes changes visible until they are ready. The switch is always atomic.**

This principle applies at every level of the system:

| Level | Staged form | Live form | Switch |
|---|---|---|---|
| Item/link write | UNCOMMITTED state in write layer | NORMAL state in read layer | 2PC commit |
| Bulk data change | Staged overlay | Live data | Changeset commit |
| Schema change | Changeset definition | Active schema | Changeset commit |
| Security change | Changeset definition | Active policies | Changeset commit |
| Migration | Staged read entries | Live read entries | Schema version flip |

### What a Changeset Is

A **changeset** is a named, persistent, admin-managed staging context. It is opt-in and intended for changes whose blast radius exceeds what an admin can mentally verify in real time. Simple changes (new property, permission change, label rename) do not require a changeset.

A changeset can contain any combination of:
- Schema changes (new/modified item types, property types, traits, enum values)
- Security changes (new/modified markers, rules, permission grants)
- Data transformations (bulk property changes, marker reassignments)

All constituent changes are committed atomically as a single unit. Partial commits are not possible.

### The Staged Overlay

The staged view is a **sparse overlay** over live data — not a full copy:
- Only items affected by the changeset's transformations exist in the staged overlay
- Unaffected items are read directly from live when queried in the staged context
- Storage cost is proportional to the scope of change, not the size of the dataset

### Transformations as Continuous Processes

Data transformations within a changeset are not one-time batch operations — they are **running transformations** evaluated continuously against the live write stream:

- Newly created live items matching transformation criteria are automatically included in the staged overlay
- Live items that change to match criteria are picked up automatically
- Live items that change to no longer match criteria are removed from the overlay
- The staged view is always current — it reflects today's live data interpreted through the proposed changes

This means the commit operation is a true switch flip. The staged overlay is already current at commit time; no re-migration is needed.

### Lifecycle

```
Phase 1: DEFINE
  Admin defines schema changes, security changes, transformations
  ntrloc calculates scope and impact
  Pre-commit report presented to admin

Phase 2: BUILD
  Cluster works in parallel to produce staged overlay
  All transformations begin running as continuous processes
  Sanity checks run continuously

Phase 3: STAGED
  Staged overlay is queryable
  Admin and designated testers review results
  Diff available: before/after per item, marker changes, warnings
  New live writes continue propagating into staged overlay
  Admin can adjust transformations and rebuild if needed
  Can remain in this phase indefinitely

Phase 4: COMMIT (or DISCARD)
  Single atomic switch — staged schema/policies/data become live
  Old live state is retired
  Write layer records changeset as a named versioned event
  All affected items reference changeset in their history

Phase 5: CLEANUP (async, background)
  Old staged overlay physically removed
  Deprecated property values stripped if desired
```

### Governance

Changesets support approval workflows:
- Required approvers defined per changeset
- Commit blocked until all approvals received
- Approval history recorded in write layer
- Mandatory reason field documents justification for the change

### The Two-Version Constraint

At any given time, ntrloc supports at most **two versions**: live and staged. There is no support for multiple concurrent staged versions. If an admin wants to stage a different change, the current staged changeset must be committed or discarded first.

This constraint is intentional — it eliminates the complexity of concurrent changeset dependency ordering, conflict resolution, and rebasing, while still providing the core value of safe, reviewable, atomic bulk changes.

### What Admins Can Do During Staging

- Browse the staged view — query items as they would appear post-commit
- Spot-check specific items — before/after comparison
- Review marker changes — which items gain or lose security markers
- Run permission simulations — what would user X be able to do post-commit?
- Adjust transformation criteria and observe the updated scope
- Invite other admins or testers to review
- Commit when satisfied, or discard with zero consequences to live data

---

## 7. Binary Properties

### Model
Binary data is stored in a content-addressable binary store, keyed by the combination of MD5 hash, SHA-256 hash, and byte length. Identical binary content is stored exactly once regardless of how many items reference it.

### Deduplication
On upload:
1. Binary is streamed to temporary storage
2. MD5 and SHA-256 digests are computed during streaming
3. The combination of (MD5, SHA-256, length) is checked against existing binary records
4. If a match exists, the temporary file is discarded and the existing record is referenced
5. If no match exists, the temporary file is moved to permanent storage and a new binary record is created

### Storage Reference
Binary properties on items do not store binary data or URLs in JSONB. They are stored in a dedicated reference table:

```sql
CREATE TABLE item_binary_properties (
    item_id           UUID REFERENCES item_registry(id),
    property_id       TEXT NOT NULL,
    binary_record_id  UUID REFERENCES binary_records(id),
    PRIMARY KEY (item_id, property_id)
);
```

On read, ntrloc fetches binary references separately and merges them into the item response. The caller sees a seamless property with the binary metadata and a download URL.

### Download
ntrloc acts as the pass-through for all binary downloads. Callers never receive direct storage paths. This provides:
- Security enforcement on every download
- Storage backend transparency
- Stable URLs regardless of underlying storage changes
- Complete download audit trail

---

*Document generated from ntrloc design session — June 2026*
