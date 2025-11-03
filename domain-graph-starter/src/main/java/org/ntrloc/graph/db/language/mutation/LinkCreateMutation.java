package org.ntrloc.graph.db.language.mutation;

import org.ntrloc.graph.db.language.Property;
import org.ntrloc.graph.db.language.selectors.Selector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class LinkCreateMutation extends LinkMutation implements LinkMutationWithLinkType, LinkMutationWithProperties {

    private String linkType;
    private Selector selector;
    private Map<String, Property> properties = new HashMap<>();

    public String getLinkType() {
        return linkType;
    }

    public void setLinkType(String linkType) {
        this.linkType = linkType;
    }

    public Selector getSelector() {
        return selector;
    }

    public void setSelector(Selector selector) {
        this.selector = selector;
    }

    public void setProperties(Map<String, Property> properties) {
        this.properties = properties;
    }

    public void setProperties(List<Property> properties) {
        this.properties = properties.stream().collect(Collectors.toMap(Property::getName, p -> p));
    }

    public List<Property> getProperties() {
        return new ArrayList<>(properties.values());
    }

}
