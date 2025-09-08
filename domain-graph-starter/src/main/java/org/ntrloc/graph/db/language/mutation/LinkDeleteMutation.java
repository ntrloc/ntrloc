package org.ntrloc.graph.db.language.mutation;

public class LinkDeleteMutation extends LinkMutation {

    private String id;

    public LinkDeleteMutation id(String id) {
        this.id = id;
        return this;
    }

    public String getId() {
        return id;
    }

}
