package org.ntrloc.graph.db.projection;

import org.springframework.lang.Nullable;

import java.util.Map;

// Minimal recursive link-selection spec -- a deliberately narrow subset of the fuller
// via/properties/filter/aggregate design sketched in docs/ntrloc-projection-traversal-summary.md,
// covering only "name which links to expand, and which of THEIR links to expand in turn." No via
// (a map key is always the literal link/perspective name), no properties/filter/aggregate override
// -- a requested link still returns all of its own properties, same as today's single-hop default.
// null/absent links (the enclosing ProjectionSpec's own field) means "today's behavior, unchanged:
// every direct link, one hop." A non-null map means "only these perspectives," recursing into
// whichever entries carry their own non-empty links.
public record LinkProjectionSpec(@Nullable Map<String, LinkProjectionSpec> links) {}
