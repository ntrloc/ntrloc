package org.ntrloc.graph.db.projector.selectors;

import java.util.List;

public class AndItemSelector implements ItemSelector {

    private List<ItemSelector> selectors;

    public List<ItemSelector> getSelectors() {
        return selectors;
    }

    public void setSelectors(List<ItemSelector> selectors) {
        this.selectors = selectors;
    }

}
