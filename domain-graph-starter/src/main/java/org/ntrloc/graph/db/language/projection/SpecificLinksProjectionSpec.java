package org.ntrloc.graph.db.language.projection;

import java.util.Set;
import java.util.StringJoiner;

public class SpecificLinksProjectionSpec implements LinksProjectionSpec {

    private Set<LinkProjectionSpec> links;

    public SpecificLinksProjectionSpec() {
        // no-op for Jackson
    }

    public SpecificLinksProjectionSpec(Set<LinkProjectionSpec> links) {
        this.links = links;
    }

    public void setLinks(Set<LinkProjectionSpec> links) {
        this.links = links;
    }

    public Set<LinkProjectionSpec> getLinks() {
        return links;
    }

    @Override
    public String toString() {
        return new StringJoiner(", ", SpecificLinksProjectionSpec.class.getSimpleName() + "[", "]")
                .add("links=" + links)
                .toString();
    }
}
