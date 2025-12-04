package org.ntrloc.graph.db.language.mutation;

import org.ntrloc.graph.db.language.Property;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class LinkUpdateMutation extends LinkMutation implements LinkMutationWithLinkType, LinkMutationWithProperties {

    private String id;
    private Map<String, Property> properties = new HashMap<>();
    private String linkType;

    public LinkUpdateMutation id(String id) {
        this.id = id;
        return this;
    }

    public void setId(String id) {
        this.id = id;
    }

    public LinkUpdateMutation linkType(String linkType) {
        this.linkType = linkType;
        return this;
    }

    @Override
    public String getLinkType() {
        return linkType;
    }

    @Override
    public void setLinkType(String linkType) {
        this.linkType = linkType;
    }

    public String getId() {
        return id;
    }

    public void setProperties(List<Property> properties) {
        this.properties = properties.stream().collect(Collectors.toMap(Property::getName, p -> p));
    }

    public List<Property> getProperties() {
        return new ArrayList<>(properties.values());
    }

}
