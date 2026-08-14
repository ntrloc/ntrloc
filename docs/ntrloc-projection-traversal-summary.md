# ntrloc Design Summary
## Topic: Projection Traversal, Path Collapse, Filtering, and Aggregation

This is a checkpoint of an in-progress design conversation, not a finished spec. It covers a
different axis than `ntrloc-projection-summary-2.md` (which is about *view shape* — groups, tabs,
named/persisted projections for UI rendering). This document is about *query mechanics* — how a
projection reaches linked items across multiple hops, what it materializes along the way, how that
composes with aggregation, and how filtering applies both to an item's own attributes and to
attributes of items it's related to. The two documents will eventually need to be reconciled (see
Section 11), but were developed independently so far. Revise/refine in place as the thinking
develops further.

Builds on the dot-notation work already shipped (commit `295cbce`, `RegisterPartitionManager.
resolvePropertyId`) — a client-supplied property path like `dimensions.widthCm` walks a chain of
`OBJECT`-typed properties to reach a nested leaf. Everything below reuses that same path-walking
idea, applied to *link* names instead of *property* names.

An earlier sketch of one piece of this (the plain nested-tree link expansion, before `via` and
aggregation existed) was published as an artifact, ["Work Expansion Tree"](https://claude.ai/code/artifact/c84f5834-9695-4d72-9aae-2670233f8b27).
It's superseded by Section 4 below — kept only for the visual of the two-paths-converge problem
that motivated everything that follows.

---

## 1. Motivating Example

Domain: `Work`, `Expression`, `Product`, `Contributor` item types (a POC modeled loosely on FRBR).
Links: a `Work` links to multiple `Product`s directly, and to multiple `Expression`s; an
`Expression` links to multiple `Product`s; a `Product` links to multiple `Contributor`s.

The real motivating question: *"Which Contributors are connected to a Work, via Products linked
directly to it or via Products linked through its Expressions?"* The asker doesn't care about
Expressions or Products as retrieved items — only the Contributors at the end. Two paths
(`Work→Product→Contributor` and `Work→Expression→Product→Contributor`) converge on the same target
set, and the same Contributor (or Product) can legitimately be reachable both ways.

This surfaced a real discomfort with calling the existing/planned mechanism a "projection" at all:
a projection, classically (relational algebra π), narrows a single relation's attributes and
preserves its shape. What's being asked for here is traversal (⋈) plus deduplication across
converging paths — a different operation, loosely conflated with attribute selection because both
were being designed into one nested request spec.

---

## 2. Three Conflated Capabilities

Pulling apart what "projection" was being asked to do:

1. **Attribute selection** — which properties of *this* item to return. The one thing that's
   actually a projection (π) in the classical sense.
2. **Traversal** (⋈) — reaching related items via one or more hops, and either materializing them
   (with their own attribute selection and further traversal) or reducing them to a scalar.
3. **Aggregation** (γ) — reduce a collection to a scalar (count, eventually sum/avg/min/max)
   instead of materializing it. Not hypothetical — `ProjectionResult.totalCount` already does this
   narrowly, for the top-level result set only; what's new is generalizing it to nested/traversed
   collections, and, via `RelatedCountPredicate` (Section 8), to filtering as well.

*Originally identified as **four** capabilities, treating "neighborhood shaping" (a single hop,
tree-embedded) and "reachability/path collapse" (`via`, multiple paths, deduplicated) as separate.
Corrected once it became clear `links` isn't a distinct concept from `via` at all (Section 4) —
they're the same primitive at different generality, not two capabilities.*

---

## 3. Prior Art Considered

Surveyed before designing anything new, specifically to avoid inventing a bespoke mechanism where
an established one already fits:

- **Cypher / GQL** (ISO/IEC 39075:2024, built substantially on openCypher) — `MATCH` describes a
  graph pattern as named nodes and typed edges; `RETURN`/aggregate functions decide what actually
  comes back. The query planner compiles this into physical operators (`Expand`, `Filter`,
  `Projection`, `Aggregation`, `Distinct`); property access is lazy — `Expand` only touches
  node/relationship ids during traversal.
- **MongoDB aggregation pipeline** — a linear sequence of composable stages (`$graphLookup` for
  recursive/bounded-depth traversal, `$unwind`, `$group`/`$count`, `$project`, `$match`). Notable
  as a *procedural* alternative to Cypher's declarative pattern: you chain exactly the stages you
  need, in order.
- **Gremlin / Apache TinkerPop** — worth naming specifically because it's this system's own
  ancestor (ntrloc ran on JanusGraph, Gremlin underneath, before the register/ledger redesign).
  Its traversal-step vocabulary (`out()`/`in()`, `union(...)` for alternate paths, `dedup()`,
  `count()`/`group()`, `project()`/`valueMap()`, `.where(traversal)` for related-item conditions)
  maps almost one-to-one onto the capabilities in Section 2 and Section 8, under different names.
- **Relational algebra** (π/σ/⋈/γ) — the classical roots "projection" is borrowed from; useful
  mainly for naming things honestly (see Section 2), and for naming the `EXISTS`/semi-join pattern
  behind `RelatedCountPredicate` (Section 8).
- **GraphQL** — a useful negative example. Its selection-set model matches capability #2
  (traversal, single-hop case) well, but has no native path collapse or aggregation — real
  GraphQL-over-SQL layers (Hasura, PostGraphile) bolt these on as separate `_aggregate` fields or
  Relay-style connection types, which is itself evidence these are genuinely separate concerns,
  not incidental extensions of shaping.

**Key structural finding**: every one of the graph-native options above models the query as either
a pattern of *named, reusable* nodes (Cypher) or an ordered *sequence of operators* (Mongo,
Gremlin) — not a nested tree. That's not incidental. `Work→Contributor` is a diamond, not a tree:
two paths reconverge on the same node. A JSON tree, by construction, cannot represent two branches
landing back on the same node without something bolted on. A named-node pattern doesn't need a
bolt-on: reusing a variable name across two edges just *is* the same node, and downstream
dedup/collapse falls out for free.

---

## 4. Resolved Direction: Tree Appearance, Graph-Aware Traversal

Decision: don't adopt a flat named-node pattern block, and don't let the response become
row-shaped (a table of variable bindings, the way Cypher/SQL actually return results). Keep the
request and response as a plain nested tree — closer to what a caller (including an LLM calling
this via MCP) naturally reaches for — and reconcile the graph-shaped reality underneath via one
addition to each tree node: **`via`**.

### The `links` node shape

Each entry in a node's `links` map is keyed by the **output field name**, and carries:

- `via` (optional): a list (or bare string — see Section 5) of dot-separated link-name chains —
  the exact same path-walking mechanism already built for nested `OBJECT` properties, just
  resolving link/perspective names instead of property names. **Omitted `via` defaults to "the key
  name is itself the literal link name"** — today's plain single-hop behavior, unchanged.
- `properties` (optional): which properties to return on the materialized item(s) at this node.
- `links` (optional): further nested node specs, recursively.
- `aggregate` (optional, mutually exclusive with `properties`/`links`): reduce the collection to a
  scalar instead of materializing it (Section 6).
- `filter` (optional): a predicate narrowing what's kept once arrived, before anything else
  applies (Section 7).

When `via` names more than one path (or a path with more than one hop), the resulting item set is
the **union of everything reached, deduplicated by `itemId`**, before `filter`/`properties`/
`links`/`aggregate` are applied to it.

### Worked example

> Work → all Products reached directly or through a linked Expression. For each Product: title
> and ISBN properties, plus its Contributors. Field name: `products`.

```json
{
  "itemTypeName": "Work",
  "properties": ["*"],
  "links": {
    "products": {
      "via": ["products", "expressions.products"],
      "properties": ["title", "ISBN_13"],
      "links": {
        "contributors": { "properties": ["*"] }
      }
    }
  }
}
```

producing:

```json
[
  {
    "properties": { "workProperty1": "blah", "workProperty2": "blah" },
    "products": [
      {
        "properties": { "title": "some title", "isbn": "some isbn" },
        "contributors": [ { "properties": { "name": "ted" } } ]
      }
    ]
  }
]
```

### What `via` actually buys

`via` decouples three things that a single `links` key used to fuse together: **how you traverse
there** (a chain of hops, possibly with alternate routes), **what you retrieve once there**
(filter/properties/links/aggregate), and **what you call it in the response**. Before `via`, every
hop had to be its own mandatory, visible tree level — reaching `Product` via `Expression` meant
`Expression` appeared as its own array in the output even if you didn't want it, because the
traversal path and the response shape were forced to be identical. `via` lets a hop be pure
arrival, invisible in the response, used only to get somewhere.

This is also the answer to "does the response's appearance have to match the graph's actual
shape?" — no. The traversal underneath can be an arbitrary DAG (any number of paths, any hop
count, converging wherever they converge); the response stays a plain tree, because every
`via`-bearing node still resolves to exactly one key with one deduplicated array.

**Merge scope is exactly what's listed, never inferred by type.** Two paths only merge if they're
named together in the same `via` list. If two paths happen to land on the same item type but
shouldn't be merged, give them different keys instead.

**This absorbs the earlier, narrower `collect` idea.** A separate top-level "collect a deduped
target set" directive was proposed mid-conversation (single named target type, list of paths,
properties-or-aggregate, no further recursion) — but a `via`-bearing `links` node is strictly more
general (it can still recurse into further `links`), so `collect` as a standalone concept is not
needed. Any node, anywhere in the tree, can carry `via`.

**Not the same as full pattern matching, and that gap appears to be closed by recursion, not
left open.** The earlier concern ("what if I need the *intermediate* node itself materialized,
not just used as plumbing?") turned out to already be covered — `via` on the `products` node above
both deduplicates Products across two paths *and* materializes them with their own `properties`/
`links`. Full named-node pattern matching (letting one *computed, deduped substructure* be
referenced from two different, unrelated positions in the response tree) is not something `via`
provides, but no real need for that specific case has come up.

### `links` is not a separate primitive from `via`

A `links` map entry with no explicit `via` was never a different mechanism from one that has
`via` — it's shorthand for `via: [key]`. And because `via` entries are themselves dot-path chains,
that shorthand already handles multi-hop keys too, with no special-casing: a bare key
`"expressions.products"` resolves as a two-hop chain exactly the same way `via:
["expressions.products"]` would. So `links`, structurally, is just *the map that holds named
traversals* — there is exactly one traversal primitive (`via`), not two sitting side by side. This
is what collapsed Section 2 from four capabilities to three: "neighborhood shaping" was never
anything other than the degenerate, single-hop, key-matches-the-link-name case of `via`.

---

## 5. Selection Conventions: `properties`, `facets`, `links`

Went through two revisions before settling:

- **First pass** (rejected): mirror the existing `facets` field's convention (`null` → none, empty
  → auto-populate everything, populated → these specific ones). Rejected as counterintuitive —
  "if I give the system an empty list, I expect nothing back," not everything.
- **Second pass** (superseded): `properties`: `null`/absent → all, `[]` → none, populated → these.
  `links`: `null`/`[]`/`{}` all → none, with no wildcard for "all" at all — a caller wanting every
  link had to enumerate every perspective name. Flagged at the time as a real, unresolved
  asymmetry: `properties` got a free "all," `links` didn't.
- **Settled**: "everything" is never a silent default for *any* field — it always has to be asked
  for explicitly, with one dedicated wildcard, `"*"`. Applies uniformly to `properties`, `facets`,
  and `links`, closing the asymmetry above rather than living with it:
  - For list-shaped fields (`properties`, `facets`): `null`/absent and `[]` both mean none (no
    distinction between the two); `["*"]` means all; a populated list of real names means exactly
    those.
  - For `links` (a `Map<String, Node>`, not a list, so the same list-shaped convention doesn't
    apply literally): the wildcard is expressed as a **map key**, `"*"` — `{"*": {}}` means every
    link this item type has, each expanded with that shared spec. Chosen over the alternative
    (letting `links` be either its normal map shape *or* the literal array `["*"]`, a polymorphic
    field) specifically to keep `links` a single, uniform JSON shape rather than requiring callers
    to handle two different shapes for one field.
  - **Named keys alongside `"*"` are overrides, not additions.** `{"*": {}, "contributors":
    {"properties": ["*"]}}` means "every link, except `contributors` gets this specific spec
    instead." A named key's spec fully **replaces** the wildcard's spec for that one name; the
    wildcard only fills in names not explicitly listed. (Merging a named override with the
    wildcard's spec, rather than replacing it, was considered and rejected as unnecessary
    complexity with no clear benefit.)
- **Ergonomic shortcut, orthogonal to the above and costing nothing in expressiveness**: any field
  that's naturally a list of strings (`via`, `properties`, `facets`) also accepts a bare single
  string, auto-coerced to a one-element list — `"via": "contributors"` instead of `"via":
  ["contributors"]`. A bare `"*"` coerces the same way, so it still means everything.
- **Backward compatibility is not a constraint anywhere in this design** — confirmed explicitly:
  none of this runs in production yet, so today's actual runtime default behavior (all properties,
  every link expanded one level, unconditionally) is free to change with no migration story
  needed.

---

## 6. Aggregate Functions (Retrieval Side)

`aggregate` is an alternative to `properties`/`links` on any node — reduce instead of materialize.
(This is the *retrieval-side* aggregate, producing a value in the response. See Section 8 for the
*predicate-side* version, `RelatedCountPredicate`, which uses the same underlying idea inside a
`filter`.)

```json
{
  "itemTypeName": "Work",
  "properties": ["*"],
  "links": {
    "products": {
      "via": ["products", "expressions.products"],
      "properties": ["title", "ISBN_13"],
      "links": {
        "contributorCount": {
          "via": ["contributors"],
          "aggregate": "count"
        }
      }
    }
  }
}
```

producing `contributorCount` as a scalar sibling of `properties` on each Product, rather than a
`contributors` array.

- `via` is required whenever the key doesn't match the real link name — same rule as materializing
  nodes, no special case for aggregates. (`"contributorCount"` isn't a link name at all, so `via`
  always has to say what's actually being counted.)
- **Cheaper than materializing, not just differently shaped.** An aggregate node never needs the
  target type's property table — `COUNT` only needs the edge-table join, grouped by the parent's
  id, batched across the result set. Strictly less work than even a stub fetch (`properties: []`
  under the Section 5 convention), since a stub still touches the target's own row.
- **Dedup has to happen before the reduction, not after.** If an aggregate node's `via` ever names
  multiple converging paths, the correct count is `COUNT(DISTINCT id)` over the merged set — never
  the sum of per-path counts, which would double-count anything reachable more than one way. Same
  dedup discipline `via` already guarantees for materialized lists has to hold for aggregates too,
  or the two modes would disagree on the same underlying data. Doesn't bite in the example above
  (`contributors` off `Product` is a single, non-converging path) but is a real trap once an
  aggregate sits on a multi-path `via`.
- Extending beyond `count` to `sum`/`avg`/`min`/`max` was sketched, not designed: those would need
  one more field naming which property to reduce over (`"aggregate": "sum", "of":
  "someNumericProperty"`), reusing the same dot-path property resolver rather than new resolution
  logic. Not otherwise discussed.

---

## 7. Filter, Generalized

Filter started as root-only (`CollectionProjectionSpec.filter`). Once traversal was reframed as
"arrive, then act" (Section 4), filter turned out to be one of the acts available at *any* node,
not a root-only special case.

**Order of operations at any node, root or nested**: arrive (scan, or hop including `via`'s
union+dedup across multiple paths) → filter → then either materialize (`properties`/recurse into
`links`) or reduce (`aggregate`).

Two consequences of that ordering:

- **Filter applies to the merged, deduplicated set, never per-path before the union.** A Product
  reached both directly and through an Expression is one item; it's filtered once, as itself.
  `via` doesn't expose which path reached an item as data once merged, so filter can only ever be
  a predicate over the item's own properties/state — never over its arrival route.
- **Filter runs before `aggregate`**, matching `WHERE` before `GROUP BY` in SQL — "count of
  PUB-status Products' Contributors" needs the status filter applied before anything downstream
  counts against it.

No new filtering machinery was needed for this generalization — filter reuses the exact same
`Predicate` type already built for the root (`AND`/`OR`/`NOT`/`PropertyValuePredicate`/etc., with
`OBJECT`-property dot-path resolution), evaluated against whatever item type is bound at that
node — the same target-type context `properties`/`links`/`aggregate` already need to resolve
names, reused rather than duplicated.

**This also corrected an earlier pass at the `Arrival` type sketch**, which had put `filter`
inside the root's `Scan` variant specifically (`Scan(itemTypeName, filter)`). That's wrong under
the arrive-then-act framing: filter isn't part of *how* either arrival kind produces its bound
set, so it belongs on the shared node shape, not duplicated into (or exclusive to) one `Arrival`
variant. Corrected in Section 9.

**A genuinely irreducible asymmetry, deliberately not smoothed over:** `via` (a "hop") is
inherently *relative* — it only means something with respect to an already-bound set of items.
The root's arrival (a type+predicate scan) is inherently *absolute* — it produces a bound set from
nothing, no "from" required or expressible. These aren't two syntaxes for the same operation; they
differ in arity (a hop takes an input, a scan doesn't) — and this holds even in query languages
that make the two *look* syntactically uniform. Cypher's `MATCH` pattern reads the same whether
it's a node pattern or a traversal pattern, but the query planner still compiles them to different
physical operators (`NodeByLabelScan`/`NodeIndexSeek` vs. `Expand`). Collapsing scan and hop into
one field (e.g. letting `via` be silently optional at the root) was considered and rejected —
unlike the earlier tree-vs-graph appearance decision (Section 4), where hiding the underlying
complexity was a deliberate, worthwhile simplification, here hiding the distinction would let a
caller write a nonsensical "root hop with nothing to hop from" and make an invalid state look
valid.

Modeled as a sealed `Arrival` type with two variants, `Scan` and `Hop`, wrapped by one shared node
shape carrying `filter`/`properties`/`links`/`aggregate` common to both — see Section 9.

---

## 8. Filtering by Related Items: `RelatedCountPredicate`

A different, and explicitly important, kind of filter: **"filter this item by a predicate on
items it's related to"** — not a condition on the bound item's own properties, but on whether
(and how many) related items, reached via a traversal, satisfy some condition. None of the
existing `Predicate` variants can express this — `PropertyValuePredicate` only ever looks at the
bound item's own attributes.

This is a well-established pattern elsewhere — a relational `EXISTS` subquery / semi-join,
Cypher's `EXISTS { pattern WHERE ... }` subquery predicate, Gremlin's `.where(traversal)` step —
not something being invented from scratch.

**Existence is just a count threshold** — "has at least one matching Product" is `count >= 1` —
so this unifies with the retrieval-side `aggregate` mechanism (Section 6) rather than becoming a
separate concept: traverse via `via`, optionally filter what's being counted (`where`, itself a
full `Predicate` — recursive), count what's left, compare with the same `Operator` enum
`PropertyValuePredicate` already uses.

```java
record RelatedCountPredicate(List<String> via, Predicate where, Operator operator, Integer value) implements Predicate {}
```

"Works with at least one Product currently in PUB status":

```json
{
  "type": "RELATED_COUNT",
  "via": ["products", "expressions.products"],
  "where": { "type": "PROPERTY_VALUE", "propertyName": "Product_Status", "operator": "EQUALS", "value": "PUB" },
  "operator": "GREATER_THAN_OR_EQUAL",
  "value": 1
}
```

"Products with no Contributors at all" (`where: null` = no further condition, just presence):

```json
{ "type": "RELATED_COUNT", "via": ["contributors"], "where": null, "operator": "EQUALS", "value": 0 }
```

**Recurses for free, because `where` is typed as the full `Predicate`, not narrowed to a property
predicate** — `where` can itself be another `RelatedCountPredicate`. This is what makes multi-hop
existence chains *and* conditions on intermediate hops both expressible with one primitive:

- **"Works with an Expression linked to a Product linked to a Contributor named J. K. Rowling"** —
  a pure chain of existence, no condition on any intermediate hop (Expression, Product). Flattens
  into one `via` chain with the condition at the end:
  ```json
  {
    "type": "RELATED_COUNT",
    "via": "expressions.products.contributors",
    "where": { "type": "PROPERTY_VALUE", "propertyName": "firstNameLastName", "operator": "EQUALS", "value": "J. K. Rowling" }
  }
  ```
  — equivalent to writing it fully nested, hop by hop. Flattening loses nothing here specifically
  *because* no intermediate hop needs its own condition.

- **"Works related to Expressions that have at least 3 linked Products"** — the condition is on
  the *intermediate* hop (the Expression itself), which cannot flatten, and has to nest at exactly
  that point:
  ```json
  {
    "type": "RELATED_COUNT", "via": "expressions",
    "where": { "type": "RELATED_COUNT", "via": "products", "operator": "GREATER_THAN_OR_EQUAL", "value": 3 }
  }
  ```

**General rule**: flatten a run of hops into one `via` chain wherever none of them need their own
condition; the moment one does, nest `RelatedCountPredicate` at exactly that hop — runs before and
after it can still flatten independently.

**A proposed further collapse was considered and rejected**, worth recording because the reasoning
is non-obvious. Flattening "Works related to Expressions with ≥3 Products" into a single
`{"type":"RELATED_COUNT","via":"expressions.products","operator":"GTE","value":3}` was suggested.
It is *not* equivalent — it answers a different question. Counter-example: a Work with two
Expressions, each having 2 Products (4 total). The flattened form counts 4 Products reached,
pooled across every Expression, and says yes (≥3); the correct per-Expression semantics say no,
since neither Expression individually has 3. Flattening a *sequential* chain pools/sums across
every branch of the intermediate hop, which is exactly the information a per-hop threshold needs —
structurally the difference between a flat `COUNT` and a `GROUP BY ... HAVING COUNT(*) >= 3`
followed by an existence check over the groups. The flattened form remains valid, just as the
answer to a genuinely different question: "at least 3 Products total, pooled across all
Expressions," not "at least one Expression with 3 Products." (`via` merging multiple *alternate
paths to the same destination*, as in Section 4, is a different, legitimate kind of pooling —
that's about redundant routes to one target, not about summing across the branches of a
sequential intermediate hop.)

**Two ergonomic shortcuts, orthogonal to correctness — reduce ceremony for the common case without
removing any expressiveness:**

- `where`/`operator`/`value` are all optional, defaulting to "at least one, no further condition"
  (plain existence) when omitted — `{ "type": "RELATED_COUNT", "via": "contributors" }` means "has
  at least one Contributor." Non-existence composes on top of the same terse form: `{ "type":
  "NOT", "predicate": { "type": "RELATED_COUNT", "via": "contributors" } }`. Explicit thresholds
  (e.g. "at least 3") still require spelling out `operator`/`value` — nothing about the general
  form changes, the common shape just stops paying for the rare one.
- The bare-string `via` shortcut from Section 5 applies here too, as shown throughout the examples
  above.

**Applies everywhere `Predicate` already does, automatically** — root `filter`, or any nested
node's own `filter` (Section 7) — since it's just one more variant of the same sealed interface,
composable with `AND`/`OR`/`NOT` like any other predicate.

**SQL fit**: this is a comfortable target for the underlying relational engine —
`EXISTS`/semi-join and `GROUP BY ... HAVING COUNT(...)` are well-optimized territory, unlike the
breadth-first traversal-batching concerns elsewhere in this document.

---

## 9. Illustrative Shape (Not Finalized)

Following the same disclaimer as `ntrloc-projection-summary-2.md`: type/field names below are
illustrative only.

```java
sealed interface Arrival permits Scan, Hop {}
record Scan(String itemTypeName) implements Arrival {}
record Hop(List<String> via) implements Arrival {}   // via: dot-path chains; bare string coerces to a 1-element list

record Node(
    Arrival arrival,
    Predicate filter,                     // applies to whatever this node arrived at, before properties/links/aggregate
    List<String> properties,              // null/[] = none, ["*"] = all, populated = these (dot-paths)
    Map<String, Node> links,              // keyed by output field name; "*" key = every link, named keys override it
    String aggregate,                     // e.g. "count" -- mutually exclusive with properties/links
    String of                             // target property for aggregate functions that need one
) {}

sealed interface Predicate permits AndPredicate, OrPredicate, NotPredicate,
    PropertyExistencePredicate, PropertyValuePredicate, StateValuePredicate, RelatedCountPredicate {}

record RelatedCountPredicate(
    List<String> via,
    Predicate where,      // nullable -- omitted means "no further condition, just presence"
    Operator operator,    // nullable -- omitted defaults to GREATER_THAN_OR_EQUAL
    Integer value         // nullable -- omitted defaults to 1
) implements Predicate {}
```

The root request is, in effect, the one `Node` whose `Arrival` is always `Scan` —
`CollectionProjectionSpec` is that root node plus pagination (`offset`/`limit`) and faceting,
which don't apply to nested nodes. `AndPredicate`/`OrPredicate`/`NotPredicate`/
`PropertyExistencePredicate`/`PropertyValuePredicate`/`StateValuePredicate` are the existing,
already-implemented `Predicate` variants; `RelatedCountPredicate` is the one new addition.

---

## 10. Resolved So Far

- "Projection" was conflating what turned out to be three distinct capabilities (originally
  miscounted as four — Section 2): attribute selection, traversal (one primitive, not two), and
  aggregation.
- The underlying query is graph-shaped (a DAG, with real convergence); the request/response
  surface doesn't have to be — a plain nested tree is the more natural shape for callers. `via` is
  the seam between the two.
- `links` is not a separate primitive from `via` — every `links` entry arrives via a `via`
  (explicit or defaulted from its own key), so there is exactly one traversal mechanism, not two.
- `via`: a list (or bare string) of dot-separated link-chains, unioning and deduplicating by
  `itemId` across all named paths before anything else applies. Supersedes the earlier, narrower
  `collect` proposal, and gives free response-key aliasing as a side effect.
- `aggregate` sits in the same slot as `properties`/`links` on a node — `count` is designed;
  cheaper than materialization since it skips the target's property table entirely; must dedupe
  before reducing when `via` is multi-path.
- **Selection conventions, fully settled**: `null`/`[]` always mean none, `["*"]` (or `"*"` as a
  map key for `links`) always means everything, a populated list/map means exactly those —
  uniformly across `properties`, `facets`, and `links`, with no field-specific exception. Named
  `links` keys alongside `"*"` override (replace, not merge) the wildcard's spec for that name.
- Bare-string shorthand for list-of-string fields (`via`, `properties`, `facets`) — auto-coerces
  to a one-element list, no expressiveness lost.
- Nothing in this system runs in production — backward compatibility is confirmed to be a
  non-constraint everywhere in this design, not just a low priority.
- **Filter generalizes to any node**, not just the root: arrive → filter → materialize-or-aggregate.
  Reuses the existing `Predicate` type with zero new machinery, resolved against whichever item
  type is bound at that node.
- **Root arrival (scan) and nested arrival (hop) are deliberately not unified** — different arity
  (0-ary vs. 1-ary), confirmed by how even Cypher compiles visually-uniform `MATCH` patterns into
  different physical operators underneath. Modeled as a sealed `Arrival` (`Scan`/`Hop`), with
  `filter`/`properties`/`links`/`aggregate` living on a shared node shape common to both.
- **`RelatedCountPredicate`**: a new `Predicate` variant for "filter by a condition on related
  items" (EXISTS/semi-join), unifying existence, non-existence, and count-thresholds into one
  mechanism. Recurses for free (its own `where` is a full `Predicate`), enabling both flattened
  existence-chains and nesting at whichever intermediate hop needs its own condition. A flattened
  single-count shorthand for a per-intermediate-hop threshold was proposed and correctly
  rejected — it answers a different (also valid) pooled-total question, not the intended one.
- Existence shorthand: `where`/`operator`/`value` on `RelatedCountPredicate` default to "at least
  one, no condition" when omitted.

## 11. Explicitly Open / Deferred

- `sum`/`avg`/`min`/`max` beyond `count`, for both retrieval-side `aggregate` (Section 6) and
  predicate-side `RelatedCountPredicate` (Section 8) — sketched shape only (`"of":
  "propertyName"`), no design discussion of behavior (null handling, type coercion,
  cardinality-many properties).
- **Sort and limit were named alongside filter as things that should generalize to any node, but
  only filter was actually worked through.** Today they remain root-only
  (`CollectionProjectionSpec.sortField`/`sortDirection`/`offset`/`limit`); a `Work` with many
  linked Products still has no way to sort or page that nested collection. The same arrive-then-act
  reasoning that generalized filter presumably applies here too, but hasn't been walked through.
- How this reconciles with `ntrloc-projection-summary-2.md`'s `DirectProjectionShape.links` field
  — that document's `links` field presumably needs to become (or wrap) exactly the `Node` type
  sketched in Section 9, but the two documents were developed independently and haven't been read
  against each other.
- Whether a computed/deduplicated substructure ever needs to be referenced from more than one
  position in the response tree (true pattern-matching generality, not just per-node `via`) — no
  concrete need identified yet, explicitly not designed.
- Exact query-compilation strategy — the intent (favor pushing selection into SQL over
  fetch-then-filter, for real I/O savings on large property blobs) is stated as a preference, not
  worked out: batching strategy per depth level, how `via`'s per-node union+dedup compiles
  (`UNION` + `DISTINCT` across chained joins was assumed but not specified), how
  `RelatedCountPredicate` compiles (`EXISTS`/semi-join or `GROUP BY`/`HAVING` — not decided which),
  and how deeply nested `via`/`aggregate`/`RelatedCountPredicate` combinations avoid re-fetching
  the same intermediate id sets.
- `RelatedCountPredicate`'s exact type tag/name (`RELATED_COUNT` used illustratively throughout)
  not finalized.
- `CollectionProjectionSpec`'s precise relationship to the root `Node` — described as "the root
  node plus pagination/faceting" but not reconciled against the record's actual existing fields
  (`traitName`, `facets`, `facetFilters`, `stateMachineFacets`), which have no obvious equivalent
  elsewhere in the `Node`/`Arrival` shape yet.

---

*Document generated from ntrloc design session — August 2026*
