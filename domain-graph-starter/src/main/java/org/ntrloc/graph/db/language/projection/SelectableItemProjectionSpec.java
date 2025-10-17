package org.ntrloc.graph.db.language.projection;

import org.ntrloc.graph.db.language.selectors.ItemSelector;

import java.util.HashMap;
import java.util.List;
import java.util.StringJoiner;

public class SelectableItemProjectionSpec extends ItemProjectionSpec {

    private ItemSelector itemSelector;

    public SelectableItemProjectionSpec(ItemSelector itemSelector) {
        this.itemSelector = itemSelector;
    }

    public ItemSelector getItemSelector() {
        return itemSelector;
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

    public SelectableItemProjectionSpec filter(ItemSelector selector) {
        this.filter = selector;
        return this;
    }

    @Override
    public String toString() {
        return new StringJoiner(", ", SelectableItemProjectionSpec.class.getSimpleName() + "[", "]")
                .add("links=" + links)
                .add("itemSelector=" + itemSelector)
                .add("properties=" + properties)
                .toString();
    }

}
