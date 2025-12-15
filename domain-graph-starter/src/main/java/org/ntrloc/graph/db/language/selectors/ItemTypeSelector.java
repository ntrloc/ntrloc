package org.ntrloc.graph.db.language.selectors;

import java.util.StringJoiner;

public class ItemTypeSelector implements ItemSelector {

    private String itemType;

    public ItemTypeSelector() {
        // no-op for Jackson
    }

    public ItemTypeSelector(String itemType) {
        this.itemType = itemType;
    }

    public static ItemTypeSelector on(String itemType) {
        return new ItemTypeSelector(itemType);
    }

    public void setItemType(String itemType) {
        this.itemType = itemType;
    }

    public String getItemType() {
        return itemType;
    }

    @Override
    public String toString() {
        return new StringJoiner(", ", ItemTypeSelector.class.getSimpleName() + "[", "]")
                .add("label='" + itemType + "'")
                .toString();
    }
}
