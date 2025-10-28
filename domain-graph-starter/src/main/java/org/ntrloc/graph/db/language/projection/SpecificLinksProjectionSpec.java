package org.ntrloc.graph.db.language.projection;

import java.util.Map;

public class SpecificLinksProjectionSpec implements LinksProjectionSpec {

    private Map<String, LinkProjectionSpec> links;

    public SpecificLinksProjectionSpec(Map<String, LinkProjectionSpec> links) {
        this.links = links;
    }

    public Map<String, LinkProjectionSpec> getLinks() {
        return links;
    }
}
