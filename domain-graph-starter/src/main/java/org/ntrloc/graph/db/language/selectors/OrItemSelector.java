package org.ntrloc.graph.db.language.selectors;

import java.util.List;

public class OrItemSelector implements ItemSelector {

    private List<ItemSelector> selectors;

    public List<ItemSelector> getSelectors() {
        return selectors;
    }

    public void setSelectors(List<ItemSelector> selectors) {
        this.selectors = selectors;
    }

}
