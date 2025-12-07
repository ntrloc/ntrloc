package org.ntrloc.graph.db.language.mutation;

import org.ntrloc.graph.db.language.selectors.Selector;

public class LinkDeleteMutation extends LinkMutation {

    private Selector selector;
    private String linkType;

    public LinkDeleteMutation selector(Selector selector) {
        this.selector = selector;
        return this;
    }

    public Selector getSelector() {
        return selector;
    }

    public String getLinkType() {
        return linkType;
    }

    public void setLinkType(String linkType) {
        this.linkType = linkType;
    }
}
