package org.ntrloc.graph.projection;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.springframework.lang.Nullable;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

// permissions governs the link edge itself (link_property:write -> edit, link:delete -> delete)
// -- reuses ProjectedItemPermissions rather than a near-identical twin type since the shape (an
// edit tree plus a delete flag) is genuinely the same concept, just resolved from
// link_property:write/link:delete grants instead of property:write/item:delete ones. Null both
// when the caller's ProjectionSpec didn't ask for permissions at all (the common case -- see
// ProjectionSpec.includePermissions) and, within a leaf edit tree, wherever nothing is writable.
//
// createdDate/createdBy/updatedDate/updatedBy describe this LINK itself (same conventions as
// ProjectedItem's own fields, see its comment, including updatedDate/updatedByUserId/updatedBy
// being null until the link is actually updated) -- not the linked item's own provenance, which
// lives on item's own createdDate/createdBy/updatedDate/updatedBy instead. createdBy/updatedBy
// are UserInfo, resolved the same way and at the same point (EntityManagerImpl) as ProjectedItem's
// own fields -- see that record's comment for why createdByUserId/updatedByUserId exist and are
// @JsonIgnore.
public record ProjectedLink(UUID linkId, Map<String, Object> properties, ProjectedItem item, @Nullable ProjectedItemPermissions permissions,
                             OffsetDateTime createdDate, @JsonIgnore UUID createdByUserId, @Nullable UserInfo createdBy,
                             @Nullable OffsetDateTime updatedDate, @JsonIgnore @Nullable UUID updatedByUserId, @Nullable UserInfo updatedBy) {}
