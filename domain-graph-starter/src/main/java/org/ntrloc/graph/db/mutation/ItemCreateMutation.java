package org.ntrloc.graph.db.mutation;

import org.springframework.lang.Nullable;

import java.util.Map;

// refId is optional -- a caller-assigned tag letting a link mutation in the same request
// reference this not-yet-persisted item before it has a real id (Section 7). itemTypeName, not
// itemTypeId -- clients never see internal schema ids.
public record ItemCreateMutation(@Nullable String refId, String itemTypeName, @Nullable Map<String, Object> properties) implements ItemMutation {
}
