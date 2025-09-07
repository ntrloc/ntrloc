package org.ntrloc.graph.db.language.selectors;

public class NotLinkSelector implements LinkSelector {

    private LinkSelector selector;

    public LinkSelector getSelector() {
        return selector;
    }

    public void setSelector(LinkSelector selector) {
        this.selector = selector;
    }

}
