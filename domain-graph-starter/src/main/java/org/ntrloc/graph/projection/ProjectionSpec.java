package org.ntrloc.graph.projection;

import org.springframework.lang.Nullable;

import java.util.List;
import java.util.Map;

public sealed interface ProjectionSpec permits ItemProjectionSpec, CollectionProjectionSpec {

    String itemTypeName();

    @Nullable Map<String, LinkProjectionSpec> links();

    // Dot-notation paths matching the same names ItemDefinitionView.sortableFields already exposes
    // (e.g. "File.name") -- null returns every property (today's behavior, unchanged); non-null,
    // even empty, returns only these and nothing else -- same "null means unfiltered, non-null
    // (including empty) means exactly this" contract links() above already has. Applies only to the
    // top-level projected item: a linked item reached via links() still returns all of its own
    // properties regardless of this field -- LinkProjectionSpec has no properties override of its
    // own (see its own comment on why that's still out of scope).
    @Nullable List<String> properties();

    // Both default to false (null treated the same as false at the point of use -- see
    // RegisterPartitionManager) rather than true: most projections are read-only lookups with no
    // intent to edit or drive a workflow, and both permissions and state resolution do real work
    // (a schema-tree walk per item for edit; a marker-grant lookup plus per-machine resolution for
    // states) that a caller who only wants property values shouldn't have to pay for. Applies to
    // the whole response tree uniformly -- the outer item and every linked item at every nesting
    // depth -- not configurable per link level; a caller wanting either turns both on for the
    // whole request.
    @Nullable Boolean includePermissions();

    @Nullable Boolean includeStates();
}
