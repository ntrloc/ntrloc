package org.ntrloc.graph.db.language.mutation;

import org.ntrloc.graph.db.language.Property;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class LinkCreateMutation extends LinkMutation {

    private String linkType;
    private ItemReference linkedItemReference;
    private Map<String, Property> properties = new HashMap<>();

    public String getLinkType() {
        return linkType;
    }

    public void setLinkType(String linkType) {
        this.linkType = linkType;
    }

    public ItemReference getLinkedItemReference() {
        return linkedItemReference;
    }

    public void setLinkedItemReference(ItemReference linkedItemReference) {
        this.linkedItemReference = linkedItemReference;
    }

    public void setProperties(List<? extends Property> properties) {
        this.properties = properties.stream().collect(Collectors.toMap(Property::getName, p -> p));
    }

    public Set<Property> getProperties() {
        return new HashSet<>(properties.values());
    }

}
