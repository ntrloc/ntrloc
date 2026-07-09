# ntrloc Design Summary 2
## Topic: Projection Shapes (Groups, Views, Named Projections)

This is a checkpoint of an in-progress design conversation, not a finished spec. It extends
Section 4 ("Projections") of `ntrloc-security-projections-summary.md`, which already gestured at
saved/named projections without fully specifying them, and never addressed grouping at all.
Revise/refine in place as the thinking develops further.

---

## 1. Motivating Example

While implementing a `groupProperties` toggle (schema-level, properties-only — a group was a
fixed, admin-curated grouping baked into the item type definition, assigned to individual
properties only), a real production MDM screenshot (Stibo, the reference system behind the
`pmdm-update-poc` project — see `docs/scholastic-ui/product-details.png` in that repo) surfaced
two problems with that premise:

1. **"Cloned From"** and **"Title Grouping Work"** are grouped sections in that UI, but both
   render as tables pointing at *other items* (a Product, a Work) — i.e. they are **links**, not
   properties. A group in the real system is a mixed collection of properties *and* links, not a
   properties-only concept. This breaks the schema-level "property group" idea completely.
2. The screen also has multiple top-level tabs ("Key Info," "Components (STEP)," "Components
   (WMS)," "Components (OPUS)," "Uses/Prepacks," "ISBN & Contract Info," "Levels," "Editorial &
   Inv Restrictions," "Character Representation/Diversity," "Associated Products") — each
   apparently a **separate query/view** of the same item (confirmed: switching tabs appears to
   re-execute a different query, not just re-render already-fetched data).

**Guiding principle**: Stibo's specific implementation should not drive ntrloc's design. It's
useful only as an existence proof that "richer, multi-shaped views of the same item" is a real
need — not as a spec to copy. Notably, ntrloc's goal is *more* general than what Stibo appears to
do: a single ntrloc projection should be able to present several distinct views of the same item
in one response, rather than requiring a separate query per view.

---

## 2. Two Orthogonal Axes

Two previously-conflated ideas turned out to be independent:

- **Transform-ness** — is a given flat view *null* (pure, unmodified — today's default/baseline
  behavior) or *transformed* (property transforms, link aggregates applied)?
- **Shape** (structural) — is the request/response organized as one flat view, a bundle of
  several named views, or a reference to a persisted view?

A `views` bundle can contain flat specs that are themselves null or transformed; a named
reference resolves server-side to something that is itself one of the other shapes underneath.

---

## 3. Shape vs. Query

Filter, sort, facets, count, and cursor are **query** concerns — which items to select, in what
order, what to aggregate over the result set — not **shape** concerns (what one item's content
looks like once selected). This isn't a new idea being retrofitted; it's already implicit in the
existing code: `SingleItemProjectionSpec` carries no `filter`/`sortField`/`facets` at all — only
`CollectionProjectionSpec` does, because those fields only mean something when selecting from a
set.

Generalizing that split: a **shape** should describe *only* content and organization (properties,
links, nested views). Query-level specs (`CollectionProjectionSpec`/
`SingleItemProjectionSpec`, or their future evolution) keep their existing query fields as-is,
plus gain a new `shape` field, defaulting to the null/flat shape when omitted.

**Consequence**: a named projection is a persisted *shape*, never a persisted *query* — using a
named projection never locks in someone else's filter/sort/pagination choices, only the content
organization.

---

## 4. Shape: Four Polymorphic Forms

```java
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = DirectProjectionShape.class, name = "DIRECT"),
    @JsonSubTypes.Type(value = ViewProjectionShape.class,   name = "VIEWS"),
    @JsonSubTypes.Type(value = NamedProjectionShape.class,  name = "NAMED"),
    @JsonSubTypes.Type(value = FieldsProjectionShape.class, name = "FIELDS")
})
sealed interface ProjectionShape permits DirectProjectionShape, ViewProjectionShape, NamedProjectionShape, FieldsProjectionShape {}
```

Type/field names below are illustrative, not finalized. This follows an idiom already
established in this codebase: `Predicate` and `FacetFilter`
(`org.ntrloc.graph.db.projection`) already use the same
`@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")` + `@JsonSubTypes` pattern for
sealed-interface polymorphism — no new deserialization mechanism needed.

### `DirectProjectionShape`
The flat/single-view shape: `properties`, `links`. **No `groups` field** — grouping is not a
distinct concept from viewing (see below). Null vs. transformed is a property of this shape's
contents (whether property-transforms/link-aggregates are present), not a different shape.

### `ViewProjectionShape`
```java
record ViewProjectionShape(Map<String, ProjectionShape> views) implements ProjectionShape {}
```
```
{ "type": "VIEWS", "views": { "keyInfo": <ProjectionShape>, "components": <ProjectionShape> } }
```
Recursive: `views` maps to `ProjectionShape`, not specifically `DirectProjectionShape`. The **key
is a caller-chosen label for that slot in the response** — independent of whatever shape fills it.
If the value is `{"type":"NAMED","name":"keyInfo"}`, key and name happen to match for clarity, but
don't have to (`"info": {"type":"NAMED","name":"keyInfo"}` is equally valid — the response would
just come back keyed `"info"`).

**A "group" is not a separate concept — it's a view at finer grain.** A named section like
Stibo's "Series & Property" is a `ViewProjectionShape` entry. For a section with *only*
properties and no ordering requirement, the value can be a plain `DirectProjectionShape`:

```
{
  "type": "VIEWS",
  "views": {
    "Product": { "type": "DIRECT", "properties": ["publishedTitle", "subTitle", "language"] }
  }
}
```

**Correction from an earlier version of this document**: "Cloned From" and "Title Grouping Work"
are *not* separate top-level views — in the real screenshot they're members positioned inline
*within* "Series & Property," interleaved with plain properties. `DirectProjectionShape` can't
express that interleaving (see Section 4a, `FieldsProjectionShape`) — a section like this needs
the ordered-list shape instead, not a plain `DirectProjectionShape`.

Tabs and sections-within-a-tab are the same mechanism (`ViewProjectionShape` composing nested
shapes) at different nesting depths — not two overlapping concepts (groups *and* views). This is
why unrestricted views-within-views matters: sections-within-tabs *are* views-within-views.

**Views-within-views is explicitly allowed**, deliberately, with no depth restriction. Once a view
is understood as pure shape (no query semantics attached), nesting is just composition — the same
way smaller reusable UI regions compose into a bigger page — not the much stranger idea of nesting
queries inside queries.

### `NamedProjectionShape`
```java
record NamedProjectionShape(String name) implements ProjectionShape {}
```
```json
{ "type": "NAMED", "name": "keyInfo" }
```
A pointer, resolved server-side to whatever shape was persisted under that name (itself a
`DirectProjectionShape` or `ViewProjectionShape`).

Multi-view (`ViewProjectionShape`) is supported **ad-hoc**, not only via persisted/named
projections — confirmed as a requirement, not an optional extra deferred to later.

### `FieldsProjectionShape` (4a)

**`DirectProjectionShape` is staying exactly as it is** — `properties` and `links` as two
separate, unordered-relative-to-each-other collections. That's the right shape for plain,
order-doesn't-matter API consumption (e.g. a system-to-system caller pulling data via a PAT).

But it can't express what a real view section needs: in Stibo's "Series & Property" section,
properties and links are interleaved in a specific order — `series`, `subseries`, `property`,
..., **`clonedFrom` (a link)**, `suggested3rdPartyContributors`, ..., `setupUser`, `setupDate`,
..., **`titleGroupingWork` (a link)**, `isValidForTitleGrouping`. Two separate buckets
(`properties` then `links`, or vice versa) can never reproduce that order. A fourth shape covers
this: an **ordered list of members**, each either a property reference or a link reference:

```java
record FieldsProjectionShape(List<Field> fields) implements ProjectionShape {}

sealed interface Field permits PropertyField, LinkField, GroupField {}
record PropertyField(String name) implements Field {}
record LinkField(String name, List<String> properties, FieldsProjectionShape item) implements Field {}
record GroupField(String name, FieldsProjectionShape fields) implements Field {}
```

**No custom display labels.** A property or link's own name is the label — there is no separate
caller-chosen alias for an individual field the way there is for a `views` map key. (Confirmed:
this was considered and explicitly rejected — rely on the property/link's own label.)

**Wire shorthand**, reusing the bare-string/single-key-object convention the base design doc
already uses for `links` generally, just applied at finer grain and interleaved in one list:
- A bare string is a `PropertyField` — e.g. `"series"`.
- A single-key object is a `LinkField` — e.g. `{"clonedFrom": {"item": ["title", "isbn13", ...]}}`.
  `properties` here means properties on the **link instance itself** (e.g. a Contributor link's
  `role`); `item` means properties/fields on the **connected item** — reusing the exact
  `properties`/`item` split the base doc already established for links generally, not new
  vocabulary.
- A single-key object with an **array** value is a `GroupField` — a named sub-list of this same
  item's own fields, for further hierarchical grouping (e.g. grouping `setupUser` and `setupDate`
  under a "Set Up By" sub-heading within "Series & Property").

**Open ambiguity, deliberately pinned, not resolved**: as sketched immediately above, `LinkField`
and `GroupField` collide — `{"clonedFrom": ["title", "isbn13", ...]}` (a link, using a bare-array
shorthand for "item fields only, no link-own properties") is structurally identical to
`{"Set Up By": ["setupUser", "setupDate"]}` (a same-item sub-group). Nothing in the JSON itself
disambiguates them without a schema lookup for whether the key names a real link. A candidate fix
was raised — drop the link bare-array shorthand entirely, always requiring the object form
(`{"item": [...]}` at minimum) for links, so array-valued single-key objects unambiguously mean
`GroupField` and object-valued ones unambiguously mean `LinkField` — but this has **not** been
decided. Revisit before implementing.

**Full worked example** — Stibo's "Series & Property" section, as a `FieldsProjectionShape`
(using the pinned-ambiguity link syntax above, i.e. not yet fully resolved):
```json
[
  "series",
  "subseries",
  "property",
  "obligationsInitial",
  "obligationsRevised",
  { "clonedFrom": { "item": ["title", "isbn13", "generalFormat", "format", "type", "owner"] } },
  "suggested3rdPartyContributors",
  "suggested3rdPartySeries",
  { "Set Up By": ["setupUser", "setupDate"] },
  "commentsDisplayedInOtherSystems",
  "isbnFinalizedBy",
  "isbnFinalizedDate",
  { "titleGroupingWork": { "item": ["id", "workTitle", "primaryProductIsbn"] } },
  "isValidForTitleGrouping"
]
```

**Noted tension, not yet reconciled**: `GroupField` (nesting within a `FieldsProjectionShape`)
and `ViewProjectionShape`'s views-within-views (nesting within `ViewProjectionShape`) both solve
"a named sub-collection nested inside a bigger one." Whether these are two genuinely different
mechanisms serving different purposes (e.g. `GroupField` for same-item field layout,
`ViewProjectionShape` for anything that might itself reference a named/persisted shape) or one
should subsume the other hasn't been worked through yet.

Also still open: does a `views` map entry always have to resolve to a `FieldsProjectionShape` in
practice, or can it still legitimately be a plain `DirectProjectionShape` (fine for a
properties-only, no-links, order-doesn't-matter section), a nested `ViewProjectionShape`, or a
`NamedProjectionShape`? Nothing so far rules any of them out, but it hasn't been explicitly
reconfirmed since `FieldsProjectionShape` was introduced.

---

## 5. Named Projections = Persisted Ad-Hoc Shapes

A named/admin-defined projection is simply the persisted form of an ad-hoc shape — one canonical
representation (`ProjectionShape`), used both inline (ad-hoc, in a request) and by reference
(named, persisted, invoked later). Not two parallel mechanisms.

This is consistent with the original design doc's already-stated intent: "Saved projections store
property references by ID internally, resolving to current labels at execution time." Persisting
a shape therefore requires a label→ID resolution pass at save time and an ID→label rehydration
pass at execution time — the same pattern already used by
`AssignItemPropertyGroupMutation`, which resolves a property name to its ID rather than storing
the name directly.

---

## 6. Resolved So Far

- A "group" is not a distinct concept — it collapses entirely into `ViewProjectionShape` composing
  `DirectProjectionShape`s. There is no separate `groups` field anywhere.
- `DirectProjectionShape` has only `properties` and `links`.
- Shape (structure) and transform-ness (null vs. transformed) are independent axes.
- Filter/sort/facets/count/cursor belong to the query level, never the shape level.
- Four shapes: `DirectProjectionShape`, `ViewProjectionShape`, `NamedProjectionShape`,
  `FieldsProjectionShape` — sealed, following the existing `Predicate`/`FacetFilter`
  JSON-polymorphism idiom.
- `views` map keys are caller-chosen response labels, independent of any name referenced by the
  value.
- Views-within-views is allowed, unrestricted — this is how section-within-tab hierarchy is
  expressed, with no separate grouping mechanism needed.
- Multi-view (`ViewProjectionShape`) works ad-hoc, not only when persisted/named.
- Named projection = persisted shape only, never a persisted query.
- Stibo's UI is an existence proof, not a spec to match; ntrloc's single-projection multi-view
  capability is intentionally more general than what Stibo's tab-per-query behavior appears to do.
- `DirectProjectionShape` stays exactly as originally defined (`properties` + `links`, two
  separate collections) — it is *not* the shape used for ordered/interleaved view content.
- A fourth shape, `FieldsProjectionShape`, covers ordered/interleaved view content: an ordered
  list of `Field`s (`PropertyField`, `LinkField`, `GroupField`).
- No custom display labels for individual fields — a property/link's own name is always the
  label. (Custom labels *do* still apply to `views` map keys, which are independent of any name
  referenced by their value — that's a different thing.)
- `LinkField` splits into `properties` (on the link instance itself) and `item` (the connected
  item's own fields) — reusing the base design doc's existing `properties`/`item` naming for
  links, not new vocabulary.
- `GroupField` exists for same-item sub-grouping nested inside a `FieldsProjectionShape` (e.g.
  "Set Up By" grouping `setupUser`/`setupDate`).

## 7. Explicitly Open / Deferred

- Exact field names and record shapes for `DirectProjectionShape`/`ViewProjectionShape`/
  `NamedProjectionShape`/`FieldsProjectionShape` — everything above is illustrative only.
- Where persisted named shapes live structurally (a new schema-level entity? how are they scoped
  — per item type, cross-type?), and how they're versioned.
- Admin-controlled default shape per item type ("if no shape specified, use view Z") — tentatively
  the right home for a default, and tentatively resolves to exactly one full shape (which might
  itself be a `ViewProjectionShape` bundle, with no separate "default view within a bundle" concept),
  but not confirmed.
- Permission model for named shapes — who can create/edit/apply one — not discussed at all yet.
- Migration path from the currently-implemented `groupProperties: Boolean` flag on
  `CollectionProjectionSpec`/`SingleItemProjectionSpec` (built and shipped earlier, schema-level,
  properties-only) to this model. That flag represents the premise this document supersedes; no
  migration has been scheduled.
- **Pinned**: `LinkField` vs. `GroupField` wire-format ambiguity — a bare-array-valued single-key
  object means different things for the two, with no schema-free way to tell them apart. Candidate
  fix (drop the link bare-array shorthand, always require the object form) proposed but not
  decided. See Section 4a.
- Whether `GroupField` (nesting within `FieldsProjectionShape`) and `ViewProjectionShape`'s
  views-within-views (nesting within `ViewProjectionShape`) are two deliberately different
  mechanisms or should be reconciled into one.
- Whether a `views` map entry can still legitimately be a plain `DirectProjectionShape`,
  `ViewProjectionShape`, or `NamedProjectionShape`, or whether in practice it always ends up being
  a `FieldsProjectionShape` for anything with real content — not reconfirmed since
  `FieldsProjectionShape` was introduced.

---

*Document generated from ntrloc design session — July 2026*
