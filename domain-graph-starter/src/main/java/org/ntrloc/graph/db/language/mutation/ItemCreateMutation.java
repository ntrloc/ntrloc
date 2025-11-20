package org.ntrloc.graph.db.language.mutation;

import org.ntrloc.graph.db.language.Property;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class ItemCreateMutation extends ItemMutation implements ReferenceableItemMutation, ItemMutationWithItemType, ItemMutationWithLinks<LinkCreateMutation>, MutationWithProperties {

    private String itemType;
    private Map<String, Property> properties = new HashMap<>();
    private String refId;
    private List<LinkCreateMutation> links;

    public void setItemType(String itemType) {
        this.itemType = itemType;
    }

    public String getItemType() {
        return itemType;
    }

    public void setRefId(String refId) {
        this.refId = refId;
    }

    public String getRefId() {
        return refId;
    }

    public void setProperties(List<? extends Property> properties) {
        this.properties = properties.stream().collect(Collectors.toMap(Property::getName, p -> p));
    }

    public List<Property> getProperties() {
        return new ArrayList<>(properties.values());
    }

    public List<LinkCreateMutation> getLinks() {
        return links == null ? List.of() : links;
    }

    public void setLinks(List<LinkCreateMutation> links) {
        this.links = links;
    }



}
