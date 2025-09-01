package org.ntrloc.graph.graphql.mapping;

import graphql.language.FieldDefinition;
import graphql.language.NonNullType;
import graphql.language.ObjectTypeDefinition;
import graphql.language.TypeName;
import org.ntrloc.graph.graphql.mapping.input.EntityInputObjectTypeMapping;
import org.ntrloc.graph.graphql.mapping.output.EntityObjectTypeMapping;
import org.ntrloc.graph.graphql.mapping.output.ObjectTypeProducer;

import java.util.List;
import java.util.Map;

public class MutationObjectTypeMapping implements ObjectTypeProducer {

    private static final String EXECUTE_FIELD_NAME = "execute";

    private MutationExecutionObjectTypeMapping mutationExecutionObjectTypeMapping;

    public MutationObjectTypeMapping(Map<String, EntityInputObjectTypeMapping> entityInputTypes, Map<String, EntityObjectTypeMapping> entityOutputTypes) {
        this.mutationExecutionObjectTypeMapping = new MutationExecutionObjectTypeMapping(entityInputTypes, entityOutputTypes);
    }

    @Override
    public List<ObjectTypeDefinition> getObjectTypeDefinitions() {
        ObjectTypeDefinition executeDefinition = mutationExecutionObjectTypeMapping.getObjectTypeDefinition();

        ObjectTypeDefinition mutationDefinition = ObjectTypeDefinition.newObjectTypeDefinition()
                .name("Mutation")
                .fieldDefinition(new FieldDefinition(EXECUTE_FIELD_NAME, new NonNullType(new TypeName(executeDefinition.getName()))))
                .build();

        return List.of(executeDefinition, mutationDefinition);
    }

}
