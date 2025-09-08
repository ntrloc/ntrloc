package org.ntrloc.graph.db.traversal.mutator;

import org.ntrloc.graph.db.ItemStatus;

public class MutationResult {

    private ItemStatus priorStatus;
    private ItemStatus newStatus;
    private String itemId;

    public MutationResult(ItemStatus priorStatus, ItemStatus newStatus, String itemId) {
        this.priorStatus = priorStatus;
        this.newStatus = newStatus;
        this.itemId = itemId;
    }

    public ItemStatus getPriorStatus() {
        return priorStatus;
    }
    public ItemStatus getNewStatus() {
        return newStatus;
    }
    public String getItemId() {
        return itemId;
    }

}
