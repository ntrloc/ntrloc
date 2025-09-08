package org.ntrloc.graph.db.language.mutation;

public class ItemReference {

    public enum ReferenceType {
        MUTATION,
        GRAPH
    }

    public ReferenceType type;
    public String id;

    public ReferenceType getType() {
        return type;
    }

    public void setType(ReferenceType type) {
        this.type = type;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

}
