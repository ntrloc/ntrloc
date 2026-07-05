# Projection Filter, Sort, and Limit — Design Notes

## Guiding Principle

Filter, sort, and limit apply wherever a collection is being retrieved — at the root level and within any nested link collection. The same constructs work at every level of the projection tree.

---

## Filter

### ItemFilter

Evaluated against each candidate item. The subject is always the item being evaluated, but predicate leaves can be grounded in four places:

1. **The item's own properties** — `active = true`, `lastName contains "smith"`
2. **A link's properties** — `license = "CC BY"` on a specific link type
3. **A linked item's properties** — the item on the other side of a named link
4. **An aggregate over a link collection** — `count("created" links) >= 5`, optionally with a sub-filter on the collection

### LinkFilter

Evaluated against each candidate link from a given item's perspective. Predicate leaves can be grounded in two places:

1. **The link's own properties** — `role = "Author"`, `createDate > "2020-01-01"`
2. **The target item's properties** — the item on the *other* side from the item being projected

### Composition

Both ItemFilter and LinkFilter support the same logical operators (`$and`, `$or`, `$not`) and the same comparison operators at the leaf level (`$eq`, `$neq`, `$in`, `$notIn`, `$gt`, `$gte`, `$lt`, `$lte`, `$contains`, `$startsWith`, `$endsWith`, `$exists`, `$range`). The distinction is in what the predicate can reach, not in how predicates compose.

---

## Sort

### ItemSort

Sort key can be grounded in the same four places as ItemFilter:

1. **The item's own properties**
2. **A link's properties**
3. **A linked item's properties**
4. **An aggregate over a link collection**

### LinkSort

Sort key can be grounded in the same two places as LinkFilter:

1. **The link's own properties**
2. **The target item's properties**

### Multi-Valued Sort Keys

When a sort key resolves to multiple values per item (e.g. a multi-valued property, or a linked item property where multiple links of that type exist), the system applies a default resolution strategy rather than rejecting the sort:

- **Ascending** — uses the **minimum** value across the set
- **Descending** — uses the **maximum** value across the set

Example: Photo A has tags `["cat", "dog", "whale"]`, Photo B has tags `["fish", "frog"]`.
- Sort tags ascending → `[Photo A, Photo B]` (min: "cat" < "fish")
- Sort tags descending → `[Photo A, Photo B]` (max: "whale" > "frog")

Users can override the default by explicitly specifying an aggregation function on the sort key when finer control is needed.

### Null Handling

Items with no value for the sort key sort **last**, regardless of sort direction. This applies at every level.

### Direction and Multi-Key

Sort supports ascending/descending direction per key, and multi-key ordering (primary sort, tiebreaker, etc.).

---

## Limit

Limit caps the number of items returned in a collection — at the root level and within each link collection independently.

- A **system default limit** applies when no limit is specified, to prevent unbounded queries.
- A **maximum limit ceiling** is enforced regardless of what the caller requests.
- **Per-type limits** at the schema level and **global default limits** are acknowledged as useful configuration concerns — tabled for later design.

---

## Pipeline Ordering

Filter, sort, and limit are pipeline stages — each stage operates on the output of the previous one. The caller controls the order, and different orderings produce meaningfully different results.

**Example — sort → limit → filter:**
"Sort all products by name, take the first 20, then filter to those with covers." Produces a different result set than filtering first and then sorting and limiting.

**Example — filter → sort → limit (conventional):**
"Find all products with covers, sort by name, take the first 20." The most common case and the conventional SQL ordering.

### Pipeline Expression

The pipeline is expressed as an ordered array of discriminated stage objects. Each stage type (`filter`, `sort`, `limit`) may appear at most once — duplicates are rejected at parse time.

```json
"pipeline": [
  { "sort": { ... } },
  { "limit": 20 },
  { "filter": { ... } }
]
```

Most projections will use the conventional order (filter → sort → limit), which reads naturally as an array. The flexibility to reorder is available when needed without adding structural complexity for the common case.

### Default Sort

A default sort of `itemId` ascending applies whenever no explicit sort is specified. This ensures:

- **Limit is always well-defined** — "limit 10" without an explicit sort still has a deterministic, stable sequence to work against
- **Pagination is stable** — cursor position is meaningful across pages only when the underlying sequence is deterministic; `itemId` as the default tiebreaker guarantees this

An explicit sort always takes precedence over the default, but `itemId` remains the implicit final tiebreaker even when an explicit sort is provided.

---

## Type Homogeneity and Perspective Names

### Perspective Name Uniqueness

Within a given item type, perspective names must be unique. An item type cannot have two perspectives with the same name pointing to different targets. This is enforced at schema definition time, not at query time.

**Why:** Filter and sort demand a known, consistent property space to operate against. A perspective name that maps to multiple target types produces a heterogeneous collection with an undefined property space — predicates and sort keys have no reliable ground to stand on. Surfacing this ambiguity at schema definition time prevents silent, surprising behavior downstream.

### What a Perspective Points To

A named perspective points to exactly one target — either a **concrete item type** or a **trait**. In both cases the property space is unambiguous:

- **Concrete item type** — the set of properties is exactly the type's defined properties
- **Trait** — the set of properties is exactly what the trait guarantees across all implementing types

### Traits as the Correct Mechanism for Heterogeneous Collections

When a user genuinely wants to treat multiple concrete types as a unified collection through a single named perspective, the correct modeling choice is a trait — not a shared perspective name. The trait is the explicit declaration that those types share a common property space, making filter and sort well-defined across the entire collection.

**Schema stays precise. Projection stays predictable. Users are never surprised.**

---

*Working notes — subject to revision*
