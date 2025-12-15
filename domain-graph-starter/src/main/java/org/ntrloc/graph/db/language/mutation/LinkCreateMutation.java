package org.ntrloc.graph.db.language.mutation;

import org.ntrloc.graph.db.language.Property;
import org.ntrloc.graph.db.language.selectors.ItemSelector;
import org.ntrloc.graph.db.language.selectors.Selector;

import java.util.ArrayList;
import java.util.List;

public class LinkCreateMutation extends LinkMutation implements LinkMutationWithLinkType, LinkMutationWithProperties {

    private String linkType;
    private ItemSelector selector;
    private List<Property> properties = new ArrayList<>();

    public String getLinkType() {
        return linkType;
    }

    public void setLinkType(String linkType) {
        this.linkType = linkType;
    }

    public Selector getSelector() {
        return selector;
    }

    public void setSelector(ItemSelector selector) {
        this.selector = selector;
    }

    public void setProperties(List<Property> properties) {
        this.properties = properties;
    }

    public List<Property> getProperties() {
        return properties;
    }

    // fluent methods
    public LinkCreateMutation selector(ItemSelector selector) {
        this.selector = selector;
        return this;
    }

    public LinkCreateMutation linkType(String linkType) {
        this.linkType = linkType;
        return this;
    }

    public LinkCreateMutation properties(List<Property> properties) {
        setProperties(properties);
        return this;
    }


}
