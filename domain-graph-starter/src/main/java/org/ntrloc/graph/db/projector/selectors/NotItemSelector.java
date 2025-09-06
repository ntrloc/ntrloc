package org.ntrloc.graph.db.projector.selectors;

public class NotItemSelector implements ItemSelector {

    private ItemSelector selector;

    public ItemSelector getSelector() {
        return selector;
    }
    public void setSelector(ItemSelector selector) {
        this.selector = selector;
    }

}
