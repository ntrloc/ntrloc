package org.ntrloc.graph.db.projector.selectors;

/**
 * Matches items that have a link that matches a set of criteria.
 */
public class ItemLinkSelector implements ItemSelector {

    String linkName;
    LinkSelector linkSelector;

    public String getLinkName() {
        return linkName;
    }

    public void setLinkName(String linkName) {
        this.linkName = linkName;
    }

    public LinkSelector getLinkSelector() {
        return linkSelector;
    }

    public void setLinkSelector(LinkSelector linkSelector) {
        this.linkSelector = linkSelector;
    }

}
