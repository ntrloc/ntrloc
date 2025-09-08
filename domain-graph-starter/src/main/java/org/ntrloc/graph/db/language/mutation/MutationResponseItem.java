package org.ntrloc.graph.db.language.mutation;

public class MutationResponseItem {

    public enum MutationType {
        CREATE,
        UPDATE,
        DELETE
    }

    private MutationType mutationType;
    private String itemType;
    private String id;

    public MutationResponseItem() {
        // no-op
    }

    public MutationResponseItem(MutationType mutationType, String itemType, String id) {
        this.mutationType = mutationType;
        this.itemType = itemType;
        this.id = id;
    }

    public MutationType getMutationType() {
        return mutationType;
    }

    public void setMutationType(MutationType mutationType) {
        this.mutationType = mutationType;
    }

    public String getItemType() {
        return itemType;
    }

    public void setItemType(String type) {
        this.itemType = type;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }
}
