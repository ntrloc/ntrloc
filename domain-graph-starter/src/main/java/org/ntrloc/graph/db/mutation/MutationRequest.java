package org.ntrloc.graph.db.mutation;

import java.util.Collections;
import java.util.Set;

public class MutationRequest {

    private Set<EntityMutation> entityMutations;

    private Set<RelationshipMutation> relationshipMutations;

    public MutationRequest(Set<EntityMutation> entityMutations, Set<RelationshipMutation> relationshipMutations) {
        this.entityMutations = entityMutations;
        this.relationshipMutations = relationshipMutations;
    }

    public Set<EntityMutation> getEntityMutations() {
        return entityMutations == null ? Collections.emptySet() : entityMutations;
    }

    public void setEntityMutations(Set<EntityMutation> entityMutations) {
        this.entityMutations = entityMutations;
    }

    public Set<RelationshipMutation> getRelationshipMutations() {
        return relationshipMutations == null ? Collections.emptySet() : relationshipMutations;
    }

    public void setRelationshipMutations(Set<RelationshipMutation> relationshipMutations) {
        this.relationshipMutations = relationshipMutations;
    }

}
