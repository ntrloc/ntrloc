package org.ntrloc.graph.db.projector;

import org.ntrloc.graph.db.projector.selectors.ItemSelector;

import java.util.HashMap;
import java.util.List;

public class SelectableItemProjectionSpec extends ItemProjectionSpec {

    private ItemSelector itemSelector;

    public SelectableItemProjectionSpec(ItemSelector itemSelector) {
        this.itemSelector = itemSelector;
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

}
