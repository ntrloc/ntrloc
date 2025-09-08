package org.ntrloc.graph.db.language.mutation;

public class ItemDeleteMutation extends ItemMutation {

    private final String id;

    public ItemDeleteMutation(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }

}
