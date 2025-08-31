package org.ntrloc.graph.graphql.mapping;

import graphql.language.Field;
import graphql.language.InputObjectTypeDefinition;
import graphql.language.ObjectTypeDefinition;
import graphql.language.ObjectTypeExtensionDefinition;
import org.ntrloc.graph.db.language.mutation.EntityMutation;
import org.ntrloc.graph.db.schema.EntityDefinition;
import org.ntrloc.graph.db.schema.RelationshipDefinition;

import java.util.List;
import java.util.Map;
import java.util.Set;

public interface SchemaMapper {

    void mapSchema(Set<EntityDefinition> entityDefinitions, Set<RelationshipDefinition> relationshipDefinitions);

    List<InputObjectTypeDefinition> getInputTypes();

    List<ObjectTypeDefinition> getOutputTypes();

    List<ObjectTypeExtensionDefinition> getExtensionTypes();

    Map<String, List<EntityMutation>> parseEntityMutations(Field mutationField);

}
