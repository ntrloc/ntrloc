# ntrloc Design Summary: Faceting Refinements
## Topics: Multi-Value Field Faceting, Date/Datetime Range Faceting

This is a checkpoint of an in-progress design conversation, not a finished spec. Revise/refine in
place as the thinking develops further. Two follow-up topics were flagged for pursuit; only the
second has been explored in any depth so far.

---

## 1. Context

Verified (using `docs/facets/facet-1.png` through `facet-4.png` — screenshots of ntrloc's actual
faceting implementation in use via pmdm-ui, not a reference/legacy system) that the existing
terms-faceting behavior is correct and working as designed:

- Facets AND together across different fields.
- Multiple selected values within one facet OR together.
- Each facet's displayed bucket counts are computed **excluding that facet's own active filter**
  but **including every other currently-active facet's filter** — "disjunctive"/"sibling"
  faceting, already implemented in `RegisterPartitionManager.project()`:
  ```java
  // Facet GROUP BY queries — disjunctive: each facet excludes its own active filter
  for (String field : requestedFacets) {
      SqlFragment otherFilters = combineFragments(
              facetFragmentsByField.entrySet().stream()
                      .filter(e -> !e.getKey().equals(field))
                      .map(Map.Entry::getValue)
                      .toList());
      facets.put(field, runTermsFacetQuery(tableName, itemTypeId, filterFragment, otherFilters, field));
  }
  ```
  This is what lets a user reopen an already-filtered facet and still see sibling values (with
  correct counts) they haven't selected, rather than the facet collapsing to only its own current
  selection.

Two enhancement topics were then raised:

1. Faceting on multi-value (`LIST`/`SET` cardinality) fields — not yet explored beyond naming it.
2. Faceting on date/datetime and numeric fields — explored in depth below. Scope narrowed early
   to **dates/datetimes specifically**; numeric range faceting is a separate, not-yet-started
   topic.

---

## 2. Multi-Value Field Faceting

Flagged as a topic to pursue. Currently excluded entirely: `isTermsFacetable()` in
`RegisterPartitionManager` requires `PropertyCardinality.SINGLE` —

```java
private boolean isTermsFacetable(PropertyType type, PropertyCardinality cardinality, UUID controlledListId) {
    if (cardinality != PropertyCardinality.SINGLE) return false;
    ...
}
```

No design discussion has happened yet beyond confirming this exclusion exists and is the current
blocker.

---

## 3. Date/Datetime Range Faceting

### Current state

- `DateRangeFacetFilter(String field, LocalDate from, LocalDate to)` and
  `RangeFacetFilter(String field, BigDecimal from, BigDecimal to)` already exist as declared
  `FacetFilter` sealed-interface variants, but `buildFacetFilterFragment` explicitly throws
  `UnsupportedOperationException` for both — the types exist, nothing behind them works.
- `isTermsFacetable()` excludes `DATE`/`DATETIME` properties from auto-detected facets entirely
  (only `STRING` with a controlled list, or `BOOLEAN`, currently qualify).
- The original design doc (`ntrloc-security-projections-summary.md`) has a `bucketSize` concept
  for *numeric* bucketed facets, but nothing analogous defined for dates.

### Three distinct mechanisms identified (not one)

Initially approached as "one date-range faceting feature." It isn't — three genuinely different
mechanisms are needed, each with different resolution semantics and different mechanical
complications:

**Specific (absolute) range** — a single fixed `[from, to]` interval on the timeline. Stable,
non-repeating, no time-of-query resolution needed. The simplest of the three; closest in spirit
to numeric bucketing (an externally-supplied partition over a continuous domain, not a value
enumerated from the data).

**Relative range** — a single interval, but computed against "now" at query time (e.g. "Last 30
Days"). Still one contiguous window, just a moving one. Raises a genuinely open question with no
agreed default yet: when a relative-range facet is reopened for sibling/disjunctive recount (per
Section 1's mechanism), does "now" re-resolve to the moment of reopening (so the window can shift
if enough time passes between the original request and the reopen), or does it stay pinned to
whatever "now" was at the start of the originating request/session? **Not decided.**

**Recurring / cyclical (seasonal) pattern** — matches the *month-day* portion of a date while
ignoring the year entirely. Motivating example: a "Season" facet on a product's published date
("Spring" = Mar 1–May 31, irrespective of year), or "show me all Fall marketing campaigns
irrespective of year." Mechanically distinct from the other two: the matching set isn't one
interval, it's every year's occurrence of that window scattered disjointly across the whole
timeline. Introduces a real complication neither of the other two has: **year-boundary
wraparound** — "Winter" (Dec 1–Feb 28/29) crosses the year boundary and can't be expressed as a
simple `BETWEEN`; it needs something like `month_day >= Dec-1 OR month_day <= Feb-28`. Any
recurring-pattern implementation needs to handle both the simple (within-year) and wrapping case.
Also domain-specific: season/period boundaries aren't universal constants (meteorological vs.
astronomical vs. a marketing team's own fiscal calendar) — different domains will define them
differently.

### Cross-cutting theme: ad-hoc vs. named/reusable definitions (third occurrence)

The same duality worked out at length in `ntrloc-projection-summary-2.md` (ad-hoc shapes vs.
persisted/named shapes) has resurfaced here independently, for the third time this design cycle.
Specific and relative ranges could plausibly stay purely ad-hoc (specified fully in each request),
but recurring/seasonal patterns feel like they *want* to be named and curated by default — a
marketing team would define "Fall" once (per their own calendar convention) and reuse that same
named pattern across many queries and facets, rather than restate the month-day boundaries by
hand every time. Worth treating as a real signal that this is a recurring architectural pattern in
ntrloc generally, not a coincidence specific to either projections or faceting.

---

## 4. Resolved So Far

- The existing terms/disjunctive faceting implementation is confirmed correct, including its
  sibling-counting behavior — verified against real screenshots, not just code reading.
- Numeric range faceting is explicitly out of scope for the current thread; only dates/datetimes
  are being worked through right now.
- Date-range faceting is not one mechanism — at least three distinct kinds are needed: specific,
  relative, and recurring/seasonal.
- Recurring/seasonal patterns require year-boundary wraparound handling as a first-class case, not
  an edge case to bolt on later.
- The ad-hoc-vs-named duality applies here too, most plausibly for recurring/seasonal patterns
  specifically.

## 5. Explicitly Open / Deferred

- Whether "specific"/absolute date-range faceting is best understood as the same kind of thing as
  a terms facet (a dropdown of options with live counts that narrows the result set, just with
  externally-supplied rather than data-enumerated partitions) or as something that deserves a
  different name/mental model entirely — raised, argued one direction, not settled with the user.
- Relative ranges: whether "now" re-resolves on every sibling recount or stays pinned per-request
  — fully open, no default chosen.
- Whether specific/relative/recurring is the *complete* taxonomy of date-range kinds, or whether
  more are lurking — asked, not yet answered.
- How recurring/seasonal pattern definitions get curated and scoped (per item type? per property?
  admin-managed the way markers/controlled lists are?) — raised, not designed.
- Multi-value field faceting (Section 2) — essentially untouched; only the current blocking
  exclusion has been identified.
- No decision yet on sequencing: whether to scope down to building "specific" ranges first (the
  simplest, no "now"-resolution, no wraparound) and explicitly defer relative/recurring, or take
  some other path. This document is a checkpoint *before* that sequencing decision, not after it.

---

*Document generated from ntrloc design session — July 2026*
