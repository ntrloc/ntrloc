package org.ntrloc.graph.db.language.mutation;

public class EntityDeleteMutation extends EntityMutation {

    private String id;

    public EntityDeleteMutation id(String id) {
        this.id = id;
        return this;
    }

    public String getId() {
        return id;
    }

}
