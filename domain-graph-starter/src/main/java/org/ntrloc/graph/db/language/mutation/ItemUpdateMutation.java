package org.ntrloc.graph.db.language.mutation;

import org.ntrloc.graph.db.language.Property;
import org.ntrloc.graph.db.language.selectors.IdSelector;
import org.ntrloc.graph.db.language.selectors.ItemSelector;
import org.ntrloc.graph.db.language.selectors.Selector;

import java.util.ArrayList;
import java.util.List;

public class ItemUpdateMutation extends ItemMutation implements ReferenceableItemMutation, ItemMutationWithItemType, MutationWithProperties, ItemMutationWithLinks<LinkMutation> {

    private String itemType;
    private ItemSelector selector;
    private List<Property> properties = new ArrayList<>();
    private String refId;
    private List<LinkMutation> links;

    @Override
    public String getItemType() {
        return itemType;
    }

    @Override
    public void setItemType(String itemType) {
        this.itemType = itemType;
    }

    public Selector getSelector() {
        return selector;
    }

    public void setSelector(ItemSelector selector) {
        this.selector = selector;
    }

    @Override
    public String getRefId() {
        return refId;
    }

    public void setRefId(String refId) {
        this.refId = refId;
    }

    public List<Property> getProperties() {
        return properties;
    }

    public void setProperties(List<Property> properties) {
        this.properties = properties;
    }

    public List<LinkMutation> getLinks() {
        return links;
    }

    public void setLinks(List<LinkMutation> links) {
        this.links = links;
    }

    // fluent methods

    public ItemUpdateMutation itemType(String itemType) {
        setItemType(itemType);
        return this;
    }

    public ItemUpdateMutation properties(List<Property> properties) {
        setProperties(properties);
        return this;
    }

    public ItemUpdateMutation id(String id) {
        this.selector = new IdSelector(id, IdSelector.Scope.GLOBAL);
        return this;
    }

    public ItemUpdateMutation refId(String refId) {
        setRefId(refId);
        return this;
    }

    public ItemUpdateMutation links(List<LinkMutation> links) {
        setLinks(links);
        return this;
    }
}
