package org.ntrloc.graph.db.language.projection;

import java.util.Set;

public class SpecificLinksProjectionSpec implements LinksProjectionSpec {

    private Set<LinkProjectionSpec> links;

    public SpecificLinksProjectionSpec(Set<LinkProjectionSpec> links) {
        this.links = links;
    }

    public Set<LinkProjectionSpec> getLinks() {
        return links;
    }
}
