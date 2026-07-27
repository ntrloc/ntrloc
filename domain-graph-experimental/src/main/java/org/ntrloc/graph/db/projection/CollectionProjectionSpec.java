package org.ntrloc.graph.db.projection;

import org.springframework.lang.Nullable;

import java.util.List;

public record CollectionProjectionSpec(
        String itemTypeName,
        @Nullable String sortField,
        @Nullable String sortDirection,
        @Nullable Predicate filter,
        @Nullable List<String> facets,
        @Nullable List<FacetFilter> facetFilters,
        // Both optional -- RegisterPartitionManager applies a default page size when limit is null
        // (and clamps it to a hard maximum regardless) rather than returning every matching row
        // unbounded, and treats a null/negative offset as 0. Left as plain nullable Integers here,
        // not defaulted in the record itself, matching how facets/facetFilters are already
        // null-checked at the point of use rather than forced to a default up front.
        @Nullable Integer offset,
        @Nullable Integer limit
) implements ProjectionSpec {

    public CollectionProjectionSpec(String itemTypeName, String sortField, String sortDirection, Predicate filter) {
        this(itemTypeName, sortField, sortDirection, filter, null, null, null, null);
    }

    public CollectionProjectionSpec(String itemTypeName, String sortField, String sortDirection) {
        this(itemTypeName, sortField, sortDirection, null, null, null, null, null);
    }
}
