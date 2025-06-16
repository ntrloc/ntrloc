package org.nterloc.graph.graphql;

import graphql.schema.idl.TypeDefinitionRegistry;
import org.nterloc.graph.db.schema.EntityDefinition;
import org.nterloc.graph.db.schema.RelationshipDefinition;

import java.util.Set;

public interface GraphQLSchemaGenerator {

    TypeDefinitionRegistry generateTypeDefinitions(Set<EntityDefinition> entityDefinitions, Set<RelationshipDefinition> relationshipDefinitions);

}
