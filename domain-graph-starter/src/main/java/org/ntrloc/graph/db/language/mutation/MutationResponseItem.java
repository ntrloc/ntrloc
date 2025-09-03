package org.ntrloc.graph.db.language.mutation;

public class MutationResponseItem {

    public enum MutationType {
        CREATE,
        UPDATE,
        DELETE
    }

    private MutationType mutationType;
    private String entityType;
    private String id;

    public MutationResponseItem() {
        // no-op
    }

    public MutationResponseItem(MutationType mutationType, String entityType, String id) {
        this.mutationType = mutationType;
        this.entityType = entityType;
        this.id = id;
    }

    public MutationType getMutationType() {
        return mutationType;
    }

    public void setMutationType(MutationType mutationType) {
        this.mutationType = mutationType;
    }

    public String getEntityType() {
        return entityType;
    }

    public void setEntityType(String type) {
        this.entityType = type;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }
}
