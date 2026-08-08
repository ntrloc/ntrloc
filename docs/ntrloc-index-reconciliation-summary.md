# ntrloc Index Reconciliation Summary
## Topic: Usage-Driven Index Strategy for Per-Type Property Tables

Status: **Deferred.** Raised during the item-type-inheritance design discussion (2026-08-06) because inheritance and trait cross-type queries make the same underlying question sharper, but this is not a prerequisite for inheritance itself and is being picked up as a follow-on. Nothing here is implemented.

---

## The problem

Every item type gets its own physical table (`RegisterPartitionManager.createItemTypeTable`), currently with exactly two indexes: a blanket `GIN(properties)` and `GIN(states)`, created once at table-creation time and never revisited. This is schema-agnostic and always correct — a GIN index on a whole JSONB column covers any key without needing to know property names in advance — but it's weak for range comparisons and can't serve `ORDER BY` efficiently. That matters more once cross-type queries (across traits and/or supertype descendants) need sorted, paginated results merged from several tables — that merge only stays cheap if every branch can produce pre-sorted output via a real per-property index.

Real-world type shapes make the naive answers to "so index every property" or "index none of them" both wrong:
- **Index nothing** — sort/range/selective-filter performance stays bad indefinitely. Rejected.
- **Index everything** — a deep subtype with a large combined attribute set could easily accumulate over a thousand properties once traits and a supertype chain are both folded in. Blanket per-property expression indexes at that width means real write amplification (every index is maintained on every `INSERT`/`UPDATE`), storage bloat, and query-planner overhead from hundreds or thousands of largely-unused indexes on one table. Rejected.

## Index fundamentals (recap, grounding the decision below)

- An index trades write cost + storage for read speed on a specific access pattern. Btree serves equality/range/sort; GIN serves containment/existence/multi-valued predicates.
- The query planner only uses an index when it's estimated cheaper than the alternative — driven by **selectivity** (fraction of rows a predicate matches, from `ANALYZE` statistics), whether the predicate **shape matches** the index (leading-column/expression match), whether it can **avoid a sort**, and whether stats are fresh.
- **An index that exists but that no real query plan ever picks is pure cost, zero benefit.** This is the crux of the whole discussion.
- Anti-patterns: indexing low-selectivity values (not necessarily low-cardinality — see below), indexing columns never used in `WHERE`/`ORDER BY`/`JOIN`, over-indexing write-heavy tables, redundant/overlapping indexes, indexing the wrong expression shape for the actual query pattern (e.g. plain btree when real usage is substring search, which needs trigram/full-text instead).

**Key nuance surfaced by a concrete example** (an enum-like controlled-list property vs. an unconstrained free-text property): cardinality is not the same as selectivity. A controlled-list property with reasonably distributed values can be *more* index-friendly than naive "don't index enums" intuition suggests (each value may still be quite selective). An unconstrained string's high cardinality only helps if it's actually queried by equality/prefix — if realistic usage is substring/contains search, a plain btree does nothing and a different index type (trigram GIN) would be needed instead. **Neither property's index-worthiness can be predicted from its type alone — it depends on real, observed query usage.**

## The decision: usage-driven, not admin-declared

Rather than a static `queryable` flag an admin sets at schema-definition time (which forces a prediction neither the admin nor the system can reliably make — demonstrated directly by the enum/string example), index existence should be **derived from observed real usage**.

This is more tractable for ntrloc specifically than for a generic database: search never accepts raw SQL, it goes through `CollectionProjectionSpec` (item type + sort + filter + facets) — a structured object the application constructs. That means ntrloc already knows, with certainty and no log-mining, exactly which property was just used as a filter/sort/facet target, at the moment it happens.

### Proposed separation of responsibilities

Three distinct pieces, deliberately not one class:

1. **Usage recording** — capture that property P was used as filter/sort/facet, when. Pure data collection.
2. **Index strategy** — given a property's recorded usage history (and any explicit admin override), decide whether it currently *wants* an index. Pure policy, no DDL, no DB connection needed to test. Proposed as a literal strategy-pattern seam specifically because this is the piece most likely to need tuning (thresholds, recency weighting, whether facet usage counts more than filter usage) independent of the DDL mechanics.
3. **Reconciler** ("terraform apply") — given the strategy's verdict (desired state) and what's actually in `pg_indexes` (actual state) for a type's table, diff and execute `CREATE`/`DROP INDEX`. Pure mechanics.

An admin override (pin always-index / never-index) fits as an input the strategy considers, or as a composed override-aware strategy wrapping the usage-based one — not a branch hardcoded into the reconciler.

### Established principles

- **Bias toward eager creation, conservative-to-never removal.** Wrongly creating an index on real usage is cheap to be wrong about (a mildly underused index). Wrongly dropping one is expensive to be wrong about (the next time it's actually needed, the user eats a cold-start hit right when it matters).
- **Reconciliation can run asynchronously, decoupled from the write path, with zero correctness risk.** Because the blanket `GIN(properties)` always guarantees correct results regardless of whether an expression index exists yet, a missing/stale expression index is purely a performance gap, never a correctness one. `CREATE INDEX`/`DROP INDEX` are real DDL against potentially large tables and don't need to block the mutation transaction.
- **Index shape should be derived mechanically from `(PropertyType, PropertyCardinality)`, not chosen by the strategy.** `SINGLE`-cardinality `STRING/INT/LONG/DATE/DATETIME` get a btree expression index with an appropriate cast; `BOOLEAN` (low value even if used) and `OBJECT`/`BINARY` types, plus any `LIST`/`SET` cardinality property, rely on the existing blanket GIN rather than getting a dedicated expression index.
- Indexes the reconciler manages need **deterministic, predictable names** (e.g. `idx_<table>_<propertyId>`) rather than Postgres's auto-generated ones, so the diff against `pg_indexes` can match by name instead of parsing index definitions.

## Still open (not yet decided)

- Exact strategy inputs: raw usage counts vs. recency-weighted counts; does facet usage weigh differently than filter/sort usage (faceting is inherently a heavier, repeated-query pattern)?
- Threshold policy: does a single query trigger index creation, or does it need to happen some number of times / within some window, so one-off ad hoc admin digging doesn't permanently provision an index nobody needs?
- The `SchemaChangeEvent` surface needs to widen before any of this can trigger at all — today it only fires on whole item-type/link-type create/delete. Trait-implemented-on-type, property-added-to-an-already-implemented-trait, and (once inheritance ships) supertype-changed/abstract-toggled don't publish any event today.
- Blast radius resolution: a change to a widely-implemented trait or a high-in-the-hierarchy supertype needs reconciliation across every currently-affected type's table, not just the one directly edited — the same "resolve the dependent type set" operation the cross-type query engine needs, likely shared machinery rather than a separate resolver.

## Why this over the alternatives, restated

Letting admins declare `queryable` per property is workable but forces a decision most schema authors aren't equipped to make well (the enum/string example demonstrated the "obvious" answer is often wrong), and cuts against wanting the system to "just work." Indexing everything or nothing are both cheap to implement and both wrong once real subtype hierarchies push property counts high. Usage-driven indexing is the one option where the deciding signal (real query behavior) is something ntrloc already has for free, precisely because search queries are structured rather than raw SQL.
