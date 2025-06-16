package org.nterloc.graph.db.mutation;

public class RelationshipDeleteMutation extends RelationshipMutation {

    private String id;

    public RelationshipDeleteMutation id(String id) {
        this.id = id;
        return this;
    }

    public String getId() {
        return id;
    }

}
