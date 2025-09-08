package org.ntrloc.graph.db.language.mutation;

public class ItemReference {

    enum ReferenceType {
        MUTATION,
        GRAPH
    }

    public ReferenceType type;
    public String id;

    public static ItemReference mutationReference(String id) {
        ItemReference ref = new ItemReference();
        ref.type = ReferenceType.MUTATION;
        ref.id = id;
        return ref;
    }

    public static ItemReference graphReference(String id) {
        ItemReference ref = new ItemReference();
        ref.type = ReferenceType.GRAPH;
        ref.id = id;
        return ref;
    }

    public ReferenceType getType() {
        return type;
    }

    public String getId() {
        return id;
    }

}
