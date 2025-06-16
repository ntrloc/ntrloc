package org.nterloc.graph.db.mutation;

public class EntityReference {

    enum ReferenceType {
        MUTATION,
        GRAPH
    }

    public ReferenceType type;
    public String id;

    public static EntityReference mutationReference(String id) {
        EntityReference ref = new EntityReference();
        ref.type = ReferenceType.MUTATION;
        ref.id = id;
        return ref;
    }

    public static EntityReference graphReference(String id) {
        EntityReference ref = new EntityReference();
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
