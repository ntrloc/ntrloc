package org.ntrloc.graph.db.projector.selectors;

import java.util.List;

public class AndLinkSelector implements LinkSelector {

    private List<LinkSelector> selectors;

    public List<LinkSelector> getSelectors() {
        return selectors;
    }

    public void setSelectors(List<LinkSelector> selectors) {
        this.selectors = selectors;
    }

}
