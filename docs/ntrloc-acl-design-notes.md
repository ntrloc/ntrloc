# ntrloc ACL Design Notes
## Companion to ntrloc-security-projections-summary.md — findings from stress-testing the marker model

These notes were captured while scoping the first ACL implementation slice (item-type-level
markers). They record correctness principles and open questions surfaced by walking through
real scenarios (embargoed covers, campaign-based exceptions, regulatory precedent) — none of
this changes what the first slice builds, but it shapes the slices that follow.

## Performance model

Item-level visibility filtering is the expensive, large-scale step — it reduces to an indexed
semi-join (`WHERE item_id IN (SELECT item_id FROM register_item_marker WHERE marker_id =
ANY(:grantedMarkerIds))`) composing as just another predicate in the same predicate-to-SQL
machinery the projection engine already has for user filters. Property/link/link-target checks
happen afterward on a small page of results, where per-item computation is cheap regardless of
table size.

## Register stores item-level markers only

Never per-property or per-link. Property and link permissions are resolved dynamically at read
time by combining an item's markers (register) with a Grant scoped to the specific
property/perspective (a small, schema-sized table, not data-sized) — this avoids the
write-amplification/storage-blowup risk of materializing markers at property granularity.

## Marker narrowing requires swap discipline

Because effective permission is a *union* of grants across an item's markers, adding a
restrictive marker without removing the broader one it's meant to override has no effect —
whoever holds a grant on the broader marker still sees the item. Any rule meant to narrow access
must pair an add with a remove of whatever marker was providing the broader access. This is a
correctness rule for the (deferred) marker assignment rule engine, not a flaw in tagging itself.

## Link permissions split three ways

Each independently governable and each with real regulatory precedent:
- `link:read` — can you traverse this perspective at all
- `link_property:read` — can you see properties stored on the edge itself
- `link_target:read` — does this specific perspective reveal the connected item's data —
  governed by the source's markers + perspective, not the target's own markers, so it doesn't
  require fetching the target's marker set just to gate traversal

Motivated by linkability as a named disclosure risk distinct from either endpoint's own
sensitivity (EU WP29 anonymization guidance), and relationship confidentiality precedent (42 CFR
Part 2, where the mere existence of a relationship is protected independent of either party's
own data).

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
