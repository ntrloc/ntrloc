package org.ntrloc.graph.graphql;

import org.ntrloc.graph.db.schema.EntityDefinition;
import org.ntrloc.graph.db.schema.RelationshipDefinition;

import java.util.Set;

public interface GraphQLSchemaMapper {

    void mapSchemaElements(Set<EntityDefinition> entityDefinitions, Set<RelationshipDefinition> relationshipDefinitions);

}
