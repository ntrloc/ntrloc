package org.ntrloc.graph.db.language.mutation;

import org.ntrloc.graph.db.language.selectors.Selector;

public class ItemDeleteMutation extends ItemMutation {

    private final Selector selector;

    public ItemDeleteMutation(Selector selector) {
        this.selector = selector;
    }

    public Selector getSelector() {
        return selector;
    }

}
