package org.ntrloc.graph.db.language.mutation;

import java.util.List;

public class MutationRequest {

    private List<EntityMutation> entityMutations;

    public MutationRequest(List<EntityMutation> entityMutations) {
        this.entityMutations = entityMutations;
    }

    public List<EntityMutation> getEntityMutations() {
        return entityMutations == null ? List.of() : entityMutations;
    }

    public void setEntityMutations(List<EntityMutation> entityMutations) {
        this.entityMutations = entityMutations;
    }

}
