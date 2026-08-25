package org.ntrloc.graph.db.projection;

import org.springframework.lang.Nullable;

import java.util.Map;
import java.util.UUID;

// permissions governs the link edge itself (link_property:write -> edit, link:delete -> delete)
// -- reuses ProjectedItemPermissions rather than a near-identical twin type since the shape
// (a writable-field-name list plus a delete flag) is genuinely the same concept, just resolved
// from link_property:write/link:delete grants instead of property:write/item:delete ones. Null
// only where permissions were never computed at all (should not happen once callers are updated;
// see RegisterPartitionManager.assembleProjectedItems/fetchLinksByItem).
public record ProjectedLink(UUID linkId, Map<String, Object> properties, ProjectedItem item, @Nullable ProjectedItemPermissions permissions) {}
