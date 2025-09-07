package org.ntrloc.graph.db.language.selectors;

/**
 * Matches links that have an item that matches a given selector.
 */
public class LinkItemSelector {

    String itemName;
    ItemSelector itemSelector;

    public String getItemName() {
        return itemName;
    }

    public void setItemName(String itemName) {
        this.itemName = itemName;
    }

    public ItemSelector getItemSelector() {
        return itemSelector;
    }

    public void setItemSelector(ItemSelector itemSelector) {
        this.itemSelector = itemSelector;
    }

}
