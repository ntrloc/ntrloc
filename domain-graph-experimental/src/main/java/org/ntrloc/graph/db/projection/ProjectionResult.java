package org.ntrloc.graph.db.projection;

import java.util.List;
import java.util.Map;

public record ProjectionResult(
        List<ProjectedItem> items,
        long totalCount,
        long facetedCount,
        Map<String, List<FacetBucket>> facets
) {
    public ProjectionResult(List<ProjectedItem> items, long totalCount) {
        this(items, totalCount, totalCount, null);
    }
}
