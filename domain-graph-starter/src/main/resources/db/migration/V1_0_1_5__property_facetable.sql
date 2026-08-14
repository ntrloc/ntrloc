-- Facetability is now an explicit admin declaration, not inferred purely from type/cardinality/
-- controlled-list (see RegisterPartitionManager.isTermsFacetable's own history). A data model
-- carrying 50+ controlled-list-backed fields would otherwise have every one of them auto-treated
-- as facetable -- and therefore computed by default whenever a caller passes an empty facets
-- list -- which is exactly the expensive-by-default trap already avoided elsewhere (see
-- stateMachineFacets' own no-auto-populate design). The structural rule (SINGLE cardinality, and
-- either STRING with a controlled list or BOOLEAN) still gates *eligibility*; this column is the
-- separate, admin-controlled opt-in layered on top of it -- a property can be eligible and still
-- not facetable until an admin says so.
ALTER TABLE schema_property ADD COLUMN facetable BOOLEAN NOT NULL DEFAULT FALSE;
