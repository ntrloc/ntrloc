package org.ntrloc.graph.projection;

// What a projection response shows for a createdBy/updatedBy field (ProjectedItem, ProjectedLink)
// -- resolved from the register row's own stored UUID (security_user.id) at projection-assembly
// time, batched across the whole page (RegisterPartitionManager.resolveUserInfo), never one
// lookup per row.
public record UserInfo(String email) {}
