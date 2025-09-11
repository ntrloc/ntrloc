package org.ntrloc.graph.db.language.projection;

import org.ntrloc.graph.db.language.selectors.ItemSelector;

import java.util.HashMap;
import java.util.List;

public class SelectableItemProjectionSpec extends ItemProjectionSpec {

    private String itemType;

    public SelectableItemProjectionSpec(String itemType) {
        this.itemType = itemType;
    }

    public String getItemType() {
        return itemType;
    }

    public void setItemType(String itemType) {
        this.itemType = itemType;
    }

    public ItemSelector getItemSelector() {
        return itemSelector;
    }

    public void setItemSelector(ItemSelector itemSelector) {
        this.itemSelector = itemSelector;
    }

    public SelectableItemProjectionSpec properties(List<String> properties) {
        this.properties = properties;
        return this;
    }

    public SelectableItemProjectionSpec link(String linkName, LinkProjectionSpec linkProjectionSpec) {
        if (links == null) {
            links = new HashMap<>();
        }
        links.put(linkName, linkProjectionSpec);
        return this;
    }

    public SelectableItemProjectionSpec select(ItemSelector selector) {
        this.itemSelector = selector;
        return this;
    }

}
