package org.ntrloc.graph.graphql.mapping;

import graphql.language.FieldDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.ListType;
import graphql.language.NonNullType;
import graphql.language.ObjectTypeDefinition;
import graphql.language.TypeName;
import org.ntrloc.graph.graphql.mapping.input.EntityInputObjectTypeMapping;
import org.ntrloc.graph.graphql.mapping.output.EntityObjectTypeMapping;

import java.util.List;
import java.util.Map;

public class MutationExecutionObjectTypeMapping {

    private static final String INPUTS_FIELD_NAME = "inputs";

    private Map<String, EntityInputObjectTypeMapping> entityInputTypes;
    private Map<String, EntityObjectTypeMapping> entityOutputTypes;

    MutationExecutionObjectTypeMapping(Map<String, EntityInputObjectTypeMapping> entityInputTypes, Map<String, EntityObjectTypeMapping> entityOutputTypes) {
        this.entityInputTypes = entityInputTypes;
        this.entityOutputTypes = entityOutputTypes;
    }

    ObjectTypeDefinition getObjectTypeDefinition() {
        List<FieldDefinition> entityMutationInputs = entityInputTypes.entrySet().stream().map(entry -> {

            String outputType = entityOutputTypes.get(entry.getKey()).getGraphQlTypeName();

            String entityName = entry.getKey();
            EntityInputObjectTypeMapping entityInput = entry.getValue();
            String entityGraphQlTypeName = entityInput.getGraphQlTypeName();
            InputValueDefinition entityInputArgument = InputValueDefinition.newInputValueDefinition()
                    .name(INPUTS_FIELD_NAME)
                    .type(new NonNullType(new ListType(new NonNullType(new TypeName(entityGraphQlTypeName)))))
                    .build();
            return FieldDefinition.newFieldDefinition()
                    .name(entityName)
                    .type(new ListType(new TypeName(outputType)))
                    .inputValueDefinitions(List.of(entityInputArgument))
                    .build();
        }).toList();

        return ObjectTypeDefinition.newObjectTypeDefinition()
                .name("MutationExecution")
                .fieldDefinitions(entityMutationInputs)
                .build();

    }

}
