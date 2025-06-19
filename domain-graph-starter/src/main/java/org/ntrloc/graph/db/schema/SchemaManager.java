package org.ntrloc.graph.db.schema;

import java.util.Optional;
import java.util.Set;

public interface SchemaManager {

    Set<EntityDefinition> retrieveEntityDefinitions();
    void createEntityDefinition(EntityDefinition definition);
    void updateEntityDefinition(EntityDefinition definition);
    Optional<EntityDefinition> retrieveEntityDefinition(String name);
    void deleteEntityDefinition(EntityDefinition definition);

    Set<RelationshipDefinition> retrieveRelationshipDefinitions();
    void createRelationshipDefinition(RelationshipDefinition definition);
    void updateRelationshipDefinition(RelationshipDefinition definition);
    Optional<RelationshipDefinition> retrieveRelationshipDefinition(String name);
    void deleteRelationshipDefinition(RelationshipDefinition definition);

    void addSchemaChangeReaction(SchemaChangeReaction reaction);

}
