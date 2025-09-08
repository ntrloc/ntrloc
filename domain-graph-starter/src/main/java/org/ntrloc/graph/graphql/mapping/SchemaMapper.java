package org.ntrloc.graph.graphql.mapping;

import graphql.language.Field;
import graphql.language.InputObjectTypeDefinition;
import graphql.language.ObjectTypeDefinition;
import graphql.language.ObjectTypeExtensionDefinition;
import org.ntrloc.graph.db.language.mutation.ItemMutation;
import org.ntrloc.graph.db.schema.ItemDefinition;
import org.ntrloc.graph.db.schema.LinkDefinition;

import java.util.List;
import java.util.Map;
import java.util.Set;

public interface SchemaMapper {

    void mapSchema(Set<ItemDefinition> itemDefinitions, Set<LinkDefinition> linkDefinitions);

    List<InputObjectTypeDefinition> getInputTypes();

    List<ObjectTypeDefinition> getOutputTypes();

    List<ObjectTypeExtensionDefinition> getExtensionTypes();

    Map<String, List<ItemMutation>> parseEntityMutations(Field mutationField);

}
