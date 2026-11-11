package org.ntrloc.graph.draft;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What a draft grant override's effect depends on in live, as it was when the override was last written
 * or reconciled (docs/ntrloc-system-model-notes.md §13.3):
 * <ul>
 *   <li>{@code principal}: the user's or group's revision, which moves when it changes (for a group: its
 *   members, parent, subgroups or name; for a user: their groups, details or admin flag);</li>
 *   <li>{@code path}: the groups the effect also runs through -- a group's ancestors, nearest first, or a
 *   user's groups and theirs, sorted. Compared as a list: what matters is which groups they are, not what
 *   else happens to them;</li>
 *   <li>{@code inherited}: whether live gave the same permission through those groups.</li>
 * </ul>
 */
public record GrantDependencies(Map<UUID, Long> principal, List<UUID> path, boolean inherited) {}
