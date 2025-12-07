package org.ntrloc.graph.db.language.mutation;

import org.ntrloc.graph.db.language.Property;
import org.ntrloc.graph.db.language.selectors.Selector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class LinkUpdateMutation extends LinkMutation implements LinkMutationWithLinkType, LinkMutationWithProperties {

    private Selector selector;
    private Map<String, Property> properties = new HashMap<>();
    private String linkType;

    public LinkUpdateMutation selector(Selector selector) {
        this.selector = selector;
        return this;
    }

    public void setSelector(Selector selector) {
        this.selector = selector;
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

    public Selector getSelector() {
        return selector;
    }

    public void setProperties(List<Property> properties) {
        this.properties = properties.stream().collect(Collectors.toMap(Property::getName, p -> p));
    }

    public List<Property> getProperties() {
        return new ArrayList<>(properties.values());
    }

}
