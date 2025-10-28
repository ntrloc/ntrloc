package org.ntrloc.graph.db.language.projection;

import org.ntrloc.graph.db.language.selectors.ItemSelector;

import java.util.List;

public class ItemProjectionSpec {

    protected ItemSelector filter;
    protected List<String> properties;

    /**
     * Maps a link projection spec to the name with which it should be returned.
     */
    protected LinksProjectionSpec links;

    public ItemProjectionSpec() {
        // no-op
    }

    public ItemProjectionSpec properties(List<String> properties) {
        this.properties = properties;
        return this;
    }

    public ItemSelector getFilter() {
        return filter;
    }

    public void setFilter(ItemSelector filter) {
        this.filter = filter;
    }

    public List<String> getProperties() {
        return properties;
    }

    public void setProperties(List<String> properties) {
        this.properties = properties;
    }

    public ItemProjectionSpec links(LinksProjectionSpec links) {
        this.links = links;
        return this;
    }

    public LinksProjectionSpec getLinks() {
        return links;
    }

    /**
     * Sets the links to include in the item projection.
     */
    public void setLinks(LinksProjectionSpec links) {
        this.links = links;
    }

}
