package org.ntrloc.graph.db.partition.schema.definition.view.admin;

import org.ntrloc.graph.db.partition.schema.definition.PropertyType;

import java.util.List;
import java.util.UUID;

// A reusable controlled list as a first-class schema element. Values are NOT inlined here (a list
// can be large and buildAdminSchema runs on every save); the editor lazy-loads them from
// GET /api/admin/schema/controlled-lists/{id}. usedBy is every property currently pointing at it.
public record AdminControlledListView(UUID id, String name, PropertyType valueType, int valueCount,
                                      List<UsageRef> usedBy) {
    public record UsageRef(UUID propertyId, String propertyName, String ownerLabel) {}
}
