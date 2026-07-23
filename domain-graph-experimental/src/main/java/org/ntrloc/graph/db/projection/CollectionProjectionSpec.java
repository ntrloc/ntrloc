package org.ntrloc.graph.db.projection;

import org.springframework.lang.Nullable;

import java.util.List;

public record CollectionProjectionSpec(
        String itemTypeName,
        @Nullable String sortField,
        @Nullable String sortDirection,
        @Nullable Predicate filter,
        @Nullable List<String> facets,
        @Nullable List<FacetFilter> facetFilters
) implements ProjectionSpec {

    public CollectionProjectionSpec(String itemTypeName, String sortField, String sortDirection, Predicate filter) {
        this(itemTypeName, sortField, sortDirection, filter, null, null);
    }

    public CollectionProjectionSpec(String itemTypeName, String sortField, String sortDirection) {
        this(itemTypeName, sortField, sortDirection, null, null, null);
    }
}
