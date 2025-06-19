package org.ntrloc.graph.graphql;

import graphql.schema.idl.TypeDefinitionRegistry;
import org.ntrloc.graph.db.schema.EntityDefinition;
import org.ntrloc.graph.db.schema.RelationshipDefinition;

import java.util.Set;

public interface GraphQLSchemaGenerator {

    TypeDefinitionRegistry generateTypeDefinitions(Set<EntityDefinition> entityDefinitions, Set<RelationshipDefinition> relationshipDefinitions);

}
