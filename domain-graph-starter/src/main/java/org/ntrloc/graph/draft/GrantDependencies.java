package org.ntrloc.graph.draft;

import java.util.Map;
import java.util.UUID;

/**
 * What a draft grant override's effect depends on in live, as it was when the override was last written
 * or reconciled (docs/ntrloc-system-model-notes.md §13.3), read from the live changelog: for each user or
 * group it depends on, how many relevant changes the log held since the draft started (`since`, the
 * draft's starting position). A count that has grown means live changed that user or group underneath the
 * override; counting rather than comparing positions means a change committed late, with an earlier
 * position, still shows.
 * <ul>
 *   <li>{@code principal}: the user or group the override is for;</li>
 *   <li>{@code path}: the groups its effect also runs through (a group's ancestors; a user's groups and
 *   theirs) -- only their being moved or deleted counts;</li>
 *   <li>{@code below}: a group principal's subgroups, whose members also get the grant;</li>
 *   <li>{@code inherited}: whether live gave the same permission through the path.</li>
 * </ul>
 */
public record GrantDependencies(long since, Map<UUID, Integer> principal, Map<UUID, Integer> path, Map<UUID, Integer> below,
                                boolean inherited) {}
