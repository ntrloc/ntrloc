package org.ntrloc.graph.db.language.projection;

import org.ntrloc.graph.db.language.selectors.ItemSelector;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ItemProjectionSpec {

    protected ItemSelector itemSelector;
    protected List<String> properties;

    /**
     * Maps a link projection spec to the name with which it should be returned.
     */
    protected Map<String, LinkProjectionSpec> links;

    public ItemProjectionSpec() {
        // no-op
    }

    public ItemProjectionSpec properties(List<String> properties) {
        this.properties = properties;
        return this;
    }

    public List<String> getProperties() {
        return properties;
    }

    public void setProperties(List<String> properties) {
        this.properties = properties;
    }

    public ItemProjectionSpec links(Map<String, LinkProjectionSpec> links) {
        this.links = links;
        return this;
    }

    public Map<String, LinkProjectionSpec> getLinks() {
        return links;
    }

    /**
     * Sets the links to include in the item projection.
     * @param links maps a link projection to the name by which the projection should be returned
     */
    public void setLinks(Map<String, LinkProjectionSpec> links) {
        this.links = links;
    }

    public ItemProjectionSpec link(String projectedFieldName, LinkProjectionSpec linkProjectionSpec) {
        if (links == null) {
            links = new HashMap<>();
        }
        links.put(projectedFieldName, linkProjectionSpec);
        return this;
    }

}
