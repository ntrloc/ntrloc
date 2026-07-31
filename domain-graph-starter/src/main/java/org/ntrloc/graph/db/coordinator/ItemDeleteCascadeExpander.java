package org.ntrloc.graph.db.coordinator;

import org.ntrloc.graph.db.partition.ledger.ItemDeleteEntry;
import org.ntrloc.graph.db.partition.ledger.ItemUpdateEntry;
import org.ntrloc.graph.db.partition.ledger.LedgerEntry;
import org.ntrloc.graph.db.partition.ledger.LinkDeleteEntry;
import org.ntrloc.graph.db.partition.register.RegisterPartitionManager;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// Expands each ItemDeleteEntry into the full cascade (Section 9 of the ledger design): the
// item's own DELETE, a LinkDeleteEntry per link it's currently party to, and an empty-diff
// ItemUpdateEntry for each surviving connected item -- unless that item is also being deleted
// in this same batch, in which case it needs no ripple entry (its own DELETE already
// supersedes everything about it, links included).
@Component
public class ItemDeleteCascadeExpander {

    private final RegisterPartitionManager registerPartitionManager;

    public ItemDeleteCascadeExpander(RegisterPartitionManager registerPartitionManager) {
        this.registerPartitionManager = registerPartitionManager;
    }

    public List<LedgerEntry> expand(List<LedgerEntry> entries) {
        Set<UUID> deletedItemIds = new HashSet<>();
        for (LedgerEntry entry : entries) {
            if (entry instanceof ItemDeleteEntry e) deletedItemIds.add(e.itemId());
        }

        List<LedgerEntry> expanded = new ArrayList<>(entries);
        Set<UUID> linksAlreadyExpanded = new HashSet<>();

        for (LedgerEntry entry : entries) {
            if (!(entry instanceof ItemDeleteEntry itemDelete)) continue;

            for (var linked : registerPartitionManager.findLinksForItem(itemDelete.itemId())) {
                if (!linksAlreadyExpanded.add(linked.linkId())) continue; // already expanded from the other side

                expanded.add(new LinkDeleteEntry(linked.linkId()));
                if (!deletedItemIds.contains(linked.connectedItemId())) {
                    expanded.add(new ItemUpdateEntry(linked.connectedItemId(), Map.of()));
                }
            }
        }

        return expanded;
    }
}
