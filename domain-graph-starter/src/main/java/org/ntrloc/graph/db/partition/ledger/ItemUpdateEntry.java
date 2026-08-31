package org.ntrloc.graph.db.partition.ledger;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

// properties is a diff keyed by property id, not name (see ItemCreateEntry): an absent key
// leaves that property unchanged, a null value clears it. stateChanges is the same kind of
// sparse delta, one entry per state machine actually transitioning (an absent machine id is left
// alone). markersAdded/markersRemoved are set deltas, not a wholesale replacement -- unlike state
// (one value per machine, so "replace" and "diff" are the same operation), an item can carry
// several independent markers, and two different writers touching different markers in the same
// transaction must not be able to clobber each other by each submitting "the new complete set."
//
// stateMachinesEnded is the counterpart to stateChanges for the END pseudostate: the listed
// machine ids are removed from the item's _state entirely (the item stops participating), rather
// than moved to a new current state. No tombstone -- re-entry via START is valid, so "ended" and
// "never started" are the same register shape.
//
// One entry, four optional facets -- not four entry types -- because a coincident change (say,
// a state transition that also adjusts a property) shows up as one ledger row with multiple
// populated facets, which is a much stronger and more legible signal of relatedness for an admin
// reading the ledger than two separate rows correlated only by a shared transaction_id.
public record ItemUpdateEntry(UUID itemId, Map<UUID, Object> properties, Map<UUID, UUID> stateChanges,
                               Set<UUID> stateMachinesEnded,
                               Set<MarkerAttribution> markersAdded, Set<MarkerAttribution> markersRemoved) implements LedgerEntry {

    // Same null-normalizing compact constructor as ItemCreateEntry, same reason: a stored ledger
    // payload missing one of these facets (predates markers being written at all, e.g.) would
    // otherwise deserialize it as null instead of empty.
    public ItemUpdateEntry {
        if (properties == null) properties = Map.of();
        if (stateChanges == null) stateChanges = Map.of();
        if (stateMachinesEnded == null) stateMachinesEnded = Set.of();
        if (markersAdded == null) markersAdded = Set.of();
        if (markersRemoved == null) markersRemoved = Set.of();
    }
}
