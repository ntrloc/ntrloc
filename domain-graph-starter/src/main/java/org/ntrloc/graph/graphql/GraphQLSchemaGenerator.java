package org.ntrloc.graph.graphql;

import org.ntrloc.graph.db.schema.EntityDefinition;
import org.ntrloc.graph.db.schema.RelationshipDefinition;
import org.ntrloc.graph.graphql.impl.GraphqlDefinitions;

import java.util.Set;

public interface GraphQLSchemaGenerator {

    GraphqlDefinitions generateTypeDefinitions(Set<EntityDefinition> entityDefinitions, Set<RelationshipDefinition> relationshipDefinitions);

}
