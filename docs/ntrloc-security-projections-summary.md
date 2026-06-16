# ntrloc Design Summary
## Topics: Security Model (Detailed), Projections, Query Model

---

## 1. Authentication

### Design Philosophy
Authentication and authorization are strictly separated. The authentication layer's sole responsibility is establishing identity — resolving a caller to an `NtrlocPrincipal`. Everything authorization-related (group membership, permissions) comes from ntrloc's own model, never from the external authentication system.

### Supported Mechanisms

**LDAP** — credentials validated against LDAP directory. Group memberships from LDAP are discarded. Only the stable unique identifier (uid/DN) is extracted.

**OAuth/OIDC** — Spring Security OAuth2 login. SSO handled at the identity provider level. Only the `sub` claim is extracted; group claims are discarded.

**SAML** — Spring Security SAML2. SSO handled at the identity provider. Only the NameID is extracted; attribute assertions are discarded.

**Personal Access Tokens (PATs)** — for direct REST access, Postman, curl, system-to-system, and developer tooling. Issued by ntrloc itself. The actual token is shown once at creation and never stored — only its SHA-256 hash is kept. Tokens can be scoped, optionally expiring, and independently revocable.

**Local Users** — credentials (email + bcrypt-hashed password) managed entirely by ntrloc with no external identity provider. Intended for quick setup, testing, and development rather than production user populations. Created by admins only — no self-registration. Spring Security's `UserDetailsService` handles credential validation natively.

```sql
CREATE TABLE local_user_credentials (
    user_id       UUID REFERENCES users(id) PRIMARY KEY,
    email         TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,  -- bcrypt
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    active        BOOLEAN NOT NULL DEFAULT TRUE
);
```

Key points:
- Password hashing via bcrypt — Spring Security's `BCryptPasswordEncoder`
- No self-registration — admin-created only
- PATs work for local users the same as any other user — the most convenient testing workflow: create a local user, generate a PAT, use it in Postman
- Password reset is an admin action rather than a self-service email flow
- Simplest of all mechanisms to implement since no external system is involved

### The Convergence Point
All five mechanisms produce a resolved `NtrlocPrincipal`:

```java
public interface NtrlocPrincipal {
    String getId();
    String getDisplayName();
    Set<String> getGroups();
    Map<String, Object> getClaims();
}
```

### User Provisioning
**Just-in-time provisioning** — on first successful authentication, ntrloc automatically creates a user record with the external ID. The user starts with no group memberships (default deny). An admin assigns them to groups.

### Account Linking
Each deployment is expected to use a single authentication mechanism per user population. Multi-provider account linking (same user authenticating via LDAP one day and OIDC another) is handled by verified email as a linking key if needed, but is not a first-release concern.

### Local Development
**Keycloak** runs locally in Docker and supports OIDC, SAML, and LDAP simulation. Standard choice for development and testing of authentication flows. PAT testing requires no external identity provider — PATs are fully internal to ntrloc.

### Testing Strategy
- **Unit tests** — construct `NtrlocPrincipal` directly, test authorization logic in isolation. No external dependencies.
- **Integration tests** — Spring Security's `@WithSecurityContext` injects mock principals without real authentication. Fast and deterministic.
- **PAT tests** — Testcontainers PostgreSQL only. No Keycloak needed.
- **OIDC/SAML/LDAP flow tests** — Testcontainers Keycloak. Slower, fewer in number. Test authentication plumbing only, not authorization logic.

---

## 2. Security Model

### Core Concepts

**Policy Markers** — simple identifier tags applied to items and links. Markers carry no logic; they are the attachment point for permission grants. The term "policy" is a misnomer — markers are tags, and the user/group permission grants against them are the actual policy.

**Permission Grants** — a user or group is granted specific operation primitives on a marker. The effective permission set for a principal on an item is the union of all grants across all markers applied to that item.

**Marker Assignment Rules** — declarative rules that automatically assign or remove markers from items and links based on properties, type, trait, or properties of related items via link traversal. Rules fire as part of the atomic commit pipeline.

**Temporary Security Overrides** — time-bounded, item-scoped ephemeral policies granting specific permissions to specific users/groups, created by privileged users with `security:override` permission.

---

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

---

### Permission Principles

1. **Default deny** — a principal has no permissions on any item unless explicitly granted
2. **Union across markers** — effective permissions are the union of all grants across all markers on an item
3. **Write implies read** — `property:write` cannot be granted without `property:read`; there are no write-only properties
4. **Positive assertions only** — there are no explicit deny grants; permissions are additive
5. **All-or-nothing proposals** — if any property in a mutation fails a permission check, the entire proposal is rejected
6. **Superusers bypass all policy** — system admins are not constrained by security policies; all actions are still fully audited

---

### Property-Level Permission Granularity

Permissions are granted at the individual property level — not as blanket "read all properties" or "write all properties". A user might have `property:read` on some properties and `property:write` on others, independently controlled per marker.

---

### Marker Assignment Rules

Rules are declarative and fire as part of the commit pipeline. They are evaluated when:
- An item or link is created or modified
- A property changes on a related item (via declared traversal path)
- A property changes on a related link

Rules declare **explicit traversal paths** — the engine never does open-ended graph exploration. Paths are bounded and known at rule definition time:

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

**Cascading re-evaluation** is triggered by property changes only, never by marker changes. Markers are orthogonal to properties — a marker change on item X does not trigger rule re-evaluation on items linked to X.

Rule re-evaluation is part of the atomic commit — marker assignments are never asynchronously lagged behind property changes.

**Rule precedence** is explicit and admin-defined. No implicit precedence rules.

---

### Create Permission Rules vs. Marker Assignment Rules

These are distinct:

**Create permission rules** — evaluated before create, determine whether this caller can create this item given its intended properties and relationships. May traverse linked items (e.g. "this user can create a Cover only for Products whose owner = 'Education'").

**Marker assignment rules** — fire after create/modify, determine which markers an item carries. Only relevant if the user had permission to create the item in the first place.

**Create permission evaluation — two gates:**

1. **Type-level gate** — does this principal have `item:create` for this item type via any applicable marker?
2. **Hypothetical instance gate** — given the intended properties and links of the new item, what markers would be assigned? Does the principal have `item:create` on any of those markers? Do create-specific rules (which may traverse linked items) permit this creation?

**Creator visibility check** — if the creating principal would not have `item:read` on the newly-created item under the markers that would be assigned, the create is rejected. A user should never be able to create something they immediately can't see.

---

### Ad-Hoc Markers

Applied manually by privileged users (`marker:apply` permission):
- **Persistent** — never overridden or removed by the rule engine regardless of rule precedence
- **TTL is mandatory** — there is no such thing as a permanent ad-hoc marker
- **Audited** — creation and expiry are both write layer events
- A user can only apply markers they themselves have permissions on — you cannot grant access you don't have

---

### Temporary Security Overrides

A privileged user with `security:override` permission on an item can create a temporary override:
- Scoped to a specific item
- Grants specific operation primitives to specific users/groups
- Mandatory expiry — no permanent overrides
- Reason field mandatory
- Full audit trail — creation, modification, and expiry are write layer events
- Expired overrides cease to exist entirely

---

### Policy Markers and Audit Trail

Policy marker changes — both rule-assigned and ad-hoc — are first-class events in the write layer changelog. They participate in the same versioning model as property changes. An item's full history includes when its security posture changed and why.

---

### Projected Schema

The schema endpoint returns a caller-scoped projection:
- Item types not visible to the caller are omitted entirely
- Properties are included only if the caller has `property:read` or `property:write`
- Each property indicates read-only or read-write
- Link types are included only if both participant types are visible
- Traits are included only if at least one property is visible in an accessible type context

This makes the projected schema a complete, accurate contract for what the caller can do — no trial and error, no runtime surprises. Particularly valuable for MCP tool exposure — an AI agent sees exactly what it's permitted to do.

---

### Permission Transparency Tools

**Current state explanation** — for any item: who can do what and why, with full explanation of which markers grant which permissions.

**Rule simulation** — for any item: what markers would be assigned if properties were X? Preview security implications without making changes.

**Change impact analysis** — for any proposed security model change: how many items affected, which users gain or lose access, warnings for unexpected implications.

---

### Delegated Security Administration

**Delegated admin scope** — a system admin can delegate marker management authority to a privileged user within a defined scope:
- Scoped to specific item types, traits, or property values
- User can only apply/remove markers they themselves have permissions on
- Cannot grant access they don't have

---

### Regulatory Framework Support

Data classification tags applied to properties and item types:

```
COPPA_SENSITIVE      -- data relating to minors
GDPR_PERSONAL        -- personally identifiable information
GDPR_SENSITIVE       -- special category data
HIPAA_PHI            -- protected health information
CCPA_PERSONAL        -- California consumer personal information
PROPRIETARY          -- internal business sensitive
PUBLIC               -- explicitly safe for unrestricted exposure
```

Each regulatory framework declares which classifications it governs, cross-domain exposure constraints, retention limits, deletion semantics, and consent requirements. Domain admins activate frameworks for their domain — activation automatically enforces all constraints.

**Hard walls on cross-domain exposure** of classified data cannot be overridden by individual permission grants, not even by superusers in other domains.

**Deletion semantics:** Purge, Anonymization, Redaction, or Suppression — each framework declares which mode satisfies its requirements. Every deletion produces a signed deletion certificate.

**Consent records** are first-class write layer entities — structured, versioned, auditable facts about authorization to hold and process data.

---

### System Properties

Present on every item regardless of type. Managed exclusively by ntrloc — never writable by users. Attempting to set a system property in a mutation is a 400 (malformed request), not a 403.

| Property | Type | Description |
|---|---|---|
| `itemId` | UUID | Immutable unique identifier |
| `itemType` | Text | The item's type name |
| `createdAt` | Timestamptz | When the item was created |
| `updatedAt` | Timestamptz | When the item was last modified |
| `createdBy` | UUID | Principal who created the item |
| `updatedBy` | UUID | Principal who last modified it |
| `visibilityState` | Text | Current visibility state |

System properties are present in every type's projected schema automatically. `createdBy` and `updatedBy` may be subject to regulatory constraints (GDPR) and domain admins can suppress them if required.

---

## 3. Mutations

### Philosophy
A mutation is an atomic proposal — the client submits a complete unit of work and ntrloc either accepts the entire proposal or rejects it. No partial application, no incremental changes accumulated over time, no session state.

The client submits its changes (new properties, changed properties, cleared properties). Omitted properties are left unchanged. Explicitly nulled properties are cleared.

### Mutation Lifecycle

```
1. Authenticate caller → resolve NtrlocPrincipal
2. Evaluate create permission rules → allow or deny (for creates)
3. Validate all proposed values against current schema → reject invalid
4. Evaluate permissions for every property and link in the mutation → reject entire proposal if any single check fails
5. Preview marker assignment → check creator visibility coherence (for creates)
6. Accept into write layer
7. Commit pipeline:
   a. Persist to write layer changelog
   b. Evaluate marker assignment rules → assign markers
   c. Evaluate cascading rules on linked items/links → update markers if needed
   d. Update read layer
8. Return result to caller projected through their permissions
```

### Validation Rules
- A mutation that attempts to set a system property is rejected with 400
- A mutation where any single property fails a permission check rejects the entire proposal with 403
- A mutation that would result in the creator being unable to see the created item is rejected

---

## 4. Projections

### Philosophy
A projection is the client saying "here's what I want to get back." It is the flip-side of a mutation. Projections are declarative — the client describes the desired result shape and ntrloc generates the appropriate SQL.

Projections can be saved and reused. Saved projections store property references by ID internally, resolving to current labels at execution time — so label renames don't break saved projections.

### Permission Interaction
- **Explicit request for inaccessible property/link** → 403
- **Wildcard `"*"` request** → returns whatever the caller is permitted to see, no error
- **Request for non-existent property/link/type** → 400
- **Filter on a property the caller can't read** → 403 (a caller shouldn't be able to infer values they can't see through filter behavior)

### Basic Structure

```json
{
  "type": "product",
  "properties": ["title", "isbn", "status"],
  "filter": { "status": "active" },
  "sort": "-createdAt",
  "count": 25,
  "facets": ["status", "format"],
  "links": {
    "contributors": {
      "properties": ["role"],
      "item": {
        "properties": ["name"]
      },
      "count": 10
    },
    "cover": {
      "item": {
        "properties": ["title", "format"]
      }
    }
  }
}
```

### Properties

`"*"` — all visible properties (wildcard)

Array of property labels — specific properties:
```json
"properties": ["title", "isbn", "status"]
```

Transformation — computed value from one or more source properties:
```json
"properties": [
  {
    "transform": "concat",
    "inputs": ["firstName", "lastName"],
    "pattern": "%s %s",
    "alias": "fullName"
  },
  "status"
]
```

Transformation rules:
- Source properties are still subject to permission checks — if the caller can't read `firstName`, a transformation using it as input is a 403
- Transformed aliases must not conflict with real property labels — 400 if they do
- Source properties used only as transformation inputs are not included in the response unless also explicitly requested

### Links

Links use an object where the link name is the key and the retrieval specification is the value:

```json
"links": {
  "cover": {
    "properties": ["capturedOn"],
    "item": {
      "properties": ["title", "format"],
      "links": {
        "photographer": {
          "item": { "properties": ["name"] }
        }
      }
    },
    "linkFilter": { "role": "primary" },
    "itemFilter": { "status": "active" },
    "sort": "-createdAt",
    "count": 10,
    "facets": ["format"]
  }
}
```

**`properties`** — link instance properties (properties on the link itself, not the connected item)
**`item`** — the connected item's projection — its own properties, links, filters, sorts, counts
**`linkFilter`** — predicates applied to link instance properties
**`itemFilter`** — predicates applied to connected item properties
**`sort`** — sort order for this link collection
**`count`** — requested page size for this link collection
**`facets`** — facets computed over this link collection

Link shorthand — string array for simple retrieval of all visible properties:
```json
"links": ["cover", "contributors"]
```

Wildcard — all visible links:
```json
"links": "*"
```

### Aggregations on Link Collections

Instead of returning linked items, request a computed aggregate:

```json
"links": {
  "photoCount": { "link": "photos", "aggregate": "count" },
  "publishedPhotoCount": {
    "link": "photos",
    "aggregate": "count",
    "itemFilter": { "status": "published" }
  },
  "totalRevenue": { "link": "sales", "aggregate": "sum", "property": "amount" }
}
```

Supported aggregate functions: `count`, `sum`, `avg`, `min`, `max`

A link collection entry requests either linked items OR an aggregate, not both simultaneously. Request them as separate entries with different keys if both are needed.

### Sorting

String shorthand — `-` prefix for descending:
```json
"sort": "-createdAt"
"sort": "lastName"
"sort": ["-createdAt", "itemId"]
```

`itemId` is the natural stable tiebreaker — always available as a sort key since it's a system property.

Sorting on link properties or linked item properties is intentionally not supported — ambiguous semantics when a parent has multiple linked items.

Sorting on aggregated link values is supported:
```json
"sort": { "photos": { "$count": { "$where": { "status": "published" } } }, "$direction": "desc" }
```

### Pagination

**`count`** — requested page size (caller-controlled, subject to system limit ceiling)
**`cursor`** — forward-only keyset cursor returned in response for subsequent pages
**`totalCount`** — always returned in response at every level, unconditionally

Forward-only cursor pagination. Clients cache earlier pages if they need to navigate backwards.

### Facets

Facets compute count-distinct of values for specified properties across the full result set (before pagination). Declared at top level and within link specifications:

```json
"facets": ["format", "status"]
```

Bucketed facets for numeric properties:
```json
"facets": [
  "format",
  { "property": "price", "bucketSize": 10 }
]
```

Facets must respect the same filters as the main query — they reflect the filtered result set, not the entire table.

### Response Structure

```json
{
  "items": [
    {
      "itemId": "product-123",
      "itemType": "product",
      "createdAt": "2026-06-15T10:23:00Z",
      "updatedAt": "2026-06-15T10:23:00Z",
      "properties": { "title": "Summer Catalog", "isbn": "978-0-123456-78-9" },
      "links": {
        "contributors": {
          "items": [
            {
              "properties": { "role": "AUTHOR" },
              "item": {
                "itemId": "contributor-456",
                "properties": { "name": "J. K. Rowling" }
              }
            }
          ],
          "totalCount": 3,
          "cursor": "eyJ..."
        },
        "photoCount": 142
      }
    }
  ],
  "totalCount": 891,
  "cursor": "eyJ...",
  "facets": {
    "status": [
      { "value": "active", "count": 712 },
      { "value": "draft", "count": 179 }
    ]
  }
}
```

### Predicates (Filters)

Filters are expressed as predicate trees — recursive structures that evaluate to true/false against an item.

**Implicit AND** — object with multiple keys:
```json
"filter": { "status": "active", "format": "PDF" }
```

**Implicit equality** — bare value:
```json
"filter": { "status": "active" }
```

**Comparison operators:**
```json
{ "status": { "$eq": "active" } }
{ "status": { "$neq": "active" } }
{ "status": { "$in": ["active", "review"] } }
{ "status": { "$notIn": ["deleted"] } }
{ "price": { "$gt": 20.00 } }
{ "price": { "$gte": 20.00 } }
{ "price": { "$lt": 100.00 } }
{ "price": { "$lte": 100.00 } }
{ "title": { "$contains": "summer" } }
{ "title": { "$startsWith": "summer" } }
{ "title": { "$endsWith": "guide" } }
```

**Existence predicate:**
```json
{ "embargoDate": { "$exists": true } }
{ "embargoDate": { "$exists": false } }
```

**Range predicate:**
```json
{ "price": { "$range": { "from": 20.00, "to": 100.00 } } }
{ "createdAt": { "$range": { "from": "2026-01-01", "to": "2026-06-30" } } }
```

**Within predicate (geolocation):**
```json
{
  "location": {
    "$within": {
      "center": { "lat": 40.7128, "lon": -74.0060 },
      "radius": 10,
      "unit": "miles"
    }
  }
}
```

```json
{
  "location": {
    "$within": {
      "bounds": {
        "northeast": { "lat": 41.0, "lon": -73.5 },
        "southwest": { "lat": 40.5, "lon": -74.5 }
      }
    }
  }
}
```

**Logical predicates:**
```json
{ "$and": [ ...predicates ] }
{ "$or": [ ...predicates ] }
{ "$not": predicate }
```

**Link predicates:**
```json
{ "photos": { "$exists": true } }
{ "photos": { "$exists": false } }
{ "photos": { "$count": { "$gte": 5 } } }
{ "photos": { "$count": { "$gte": 5, "$where": { "colorMode": "black_and_white" } } } }
```

**Link filter with linkFilter and itemFilter:**
```json
{
  "contributors": {
    "linkFilter": { "role": "AUTHOR" },
    "itemFilter": { "name": "J. K. Rowling" }
  }
}
```

**Dot notation in filters** — traverse link path to apply predicate on terminal item property:
```json
{ "photos.collections.status": "published" }
```

Predicates compose to arbitrary depth — no artificial limit on nesting. Deep predicate trees generate nested SQL subqueries recursively.

Filter processing has two distinct phases:
1. **Parse and validate** — resolve labels to IDs, validate operators against property types, check permissions, validate referenced names exist. 400 or 403 returned before touching the database.
2. **SQL generation** — walk the validated predicate tree recursively, emit SQL. Each predicate type has a corresponding SQL generation strategy.

### Cross-Type Queries

When the caller wants heterogeneous results from multiple types with nothing in common, they use a `types` array with a `for` block for type-specific retrieval:

```json
{
  "types": ["cover", "product"],
  "sort": "-createdAt",
  "count": 25,
  "for": {
    "cover": { "properties": ["title", "format"] },
    "product": { "properties": ["title", "price", "status"] }
  }
}
```

Sorting and filtering at the top level operates on **system properties** only — the only properties guaranteed present across all types. Type-specific filters go inside `for`.

### Trait Queries

When the caller wants items across multiple types sharing a common trait:

```json
{
  "trait": "MediaAsset",
  "properties": ["title", "format", "status"],
  "sort": "-createdAt",
  "count": 25,
  "facets": ["format", "status"],
  "links": {
    "tags": { "item": { "properties": ["label"] }, "count": 10 }
  },
  "for": {
    "cover": {
      "properties": ["printResolution"],
      "links": {
        "products": { "item": { "properties": ["title"] }, "count": 5 }
      }
    },
    "photo": { "properties": ["capturedAt", "colorMode"] },
    "alternateImage": { "properties": ["resolution"] }
  }
}
```

- **Top-level `properties`** — trait properties returned for all participating types
- **Top-level `links`** — trait-defined links returned for all participating types
- **`for` block** — type-specific additional properties and links, additive not replacing
- **`for` omitted** — all visible types returned with only trait-level properties and links

### Multi-Query

Multiple independent projections in a single request, executed in parallel:

```json
{
  "queries": {
    "covers": {
      "type": "cover",
      "properties": ["title", "format"],
      "count": 25
    },
    "products": {
      "type": "product",
      "properties": ["title", "price"],
      "count": 25
    }
  }
}
```

Response keys mirror query keys. Permission check failure on any single query fails the entire request.

### Property Name Disambiguation

When a trait property label collides with another property label on any item type that uses the trait, the trait property is qualified with the trait name across **all** types that use that trait — not just the type where the collision exists.

The qualified label is always used, consistently, across all types — making it stable and self-documenting. The qualification is determined when the collision is first detected and becomes permanent for that trait property.

Example: Trait `Identifiable` has property "name". Item type `Person` also has a property "name". Collision detected → trait property becomes `identifiableName` on ALL types implementing `Identifiable`, not just `Person`.

If the colliding property is later deleted, the qualified label remains — stability takes precedence over brevity. Admins can explicitly rename the trait property if they want to revert to the unqualified form.

---

## 5. Traversals (Pending)

Traversals — retrieving items more than one hop away from top-level items — are acknowledged as valuable but deliberately tabled for further design thought. Key considerations carried forward:

- A 1-hop link retrieval is a special case of traversal
- Each link in a traversal can have its own `linkFilter` and `itemFilter`
- Intermediate hops in a traversal may or may not be returned in the response
- Automatic deduplication at the terminal level is required — the same item reached via multiple paths should appear once
- Traversals are conceptually distinct from direct link retrieval and may warrant distinct syntax

---

*Document generated from ntrloc design session — June 2026*
