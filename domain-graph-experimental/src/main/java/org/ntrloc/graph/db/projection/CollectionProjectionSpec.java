package org.ntrloc.graph.db.projection;

import java.util.List;

public record CollectionProjectionSpec(
        String itemTypeName,
        String sortField,
        String sortDirection,
        Predicate filter,
        List<String> facets,
        List<FacetFilter> facetFilters
) implements ProjectionSpec {

    public CollectionProjectionSpec(String itemTypeName, String sortField, String sortDirection, Predicate filter) {
        this(itemTypeName, sortField, sortDirection, filter, null, null);
    }

    public CollectionProjectionSpec(String itemTypeName, String sortField, String sortDirection) {
        this(itemTypeName, sortField, sortDirection, null, null, null);
    }
}
