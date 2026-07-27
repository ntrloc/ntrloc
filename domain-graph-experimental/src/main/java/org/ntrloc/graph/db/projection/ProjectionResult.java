package org.ntrloc.graph.db.projection;

import org.springframework.lang.Nullable;

import java.util.List;
import java.util.Map;

public record ProjectionResult(
        List<ProjectedItem> items,
        long totalCount,
        long facetedCount,
        Map<String, List<FacetBucket>> facets,
        // Keyed by state machine name -- kept separate from facets above (same "entirely separate,
        // opt-in" rule as CollectionProjectionSpec.stateMachineFacets), not merged into the same
        // map under some namespacing convention.
        @Nullable Map<String, List<FacetBucket>> stateMachineFacets
) {
    public ProjectionResult(List<ProjectedItem> items, long totalCount) {
        this(items, totalCount, totalCount, null, null);
    }
}
