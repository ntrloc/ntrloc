package org.ntrloc.graph.projection;

// What a projection response shows for a createdBy/updatedBy field (ProjectedItem, ProjectedLink)
// -- resolved from the register row's own stored UUID (security_user.id) at projection-assembly
// time, batched across the whole page (RegisterPartitionManager.resolveUserInfo), never one
// lookup per row. username is security_user.external_id -- the same "username" terminology
// AccountController.MeResponse already uses for the same column.
public record UserInfo(String username, String email) {}
