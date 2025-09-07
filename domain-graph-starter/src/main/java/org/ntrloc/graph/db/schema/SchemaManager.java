package org.ntrloc.graph.db.schema;

import java.util.Optional;
import java.util.Set;

public interface SchemaManager {

    Set<ItemDefinition> retrieveEntityDefinitions();
    void createEntityDefinition(ItemDefinition definition);
    void updateEntityDefinition(ItemDefinition definition);
    Optional<ItemDefinition> retrieveEntityDefinition(String name);
    void deleteEntityDefinition(ItemDefinition definition);

    Set<LinkDefinition> retrieveRelationshipDefinitions();
    void createRelationshipDefinition(LinkDefinition definition);
    void updateRelationshipDefinition(LinkDefinition definition);
    Optional<LinkDefinition> retrieveRelationshipDefinition(String name);
    void deleteRelationshipDefinition(LinkDefinition definition);

    void addSchemaChangeReaction(SchemaChangeReaction reaction);

}
