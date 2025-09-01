package org.ntrloc.graph.graphql.mapping;

import graphql.language.Argument;
import graphql.language.ArrayValue;
import graphql.language.Field;
import graphql.language.FieldDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.ListType;
import graphql.language.NonNullType;
import graphql.language.ObjectTypeDefinition;
import graphql.language.ObjectValue;
import graphql.language.TypeName;
import org.ntrloc.graph.db.language.mutation.EntityMutation;
import org.ntrloc.graph.graphql.mapping.input.EntityInputObjectTypeMapping;
import org.ntrloc.graph.graphql.mapping.output.EntityObjectTypeMapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MutationExecutionObjectTypeMapping {

    private static final Logger LOG = LoggerFactory.getLogger(MutationExecutionObjectTypeMapping.class);

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

    public Map<String, List<EntityMutation>> parseEntityMutations(Field executionField) {
        Map<String, List<EntityMutation>> mutations = new HashMap<>();

        List<Field> selections = executionField.getSelectionSet().getSelections().stream().map(s -> (Field) s).toList();
        for (Field entitySelection : selections) {
            String entityType = entitySelection.getName();
            if (entityInputTypes.containsKey(entityType)) {

                EntityInputObjectTypeMapping inputMapping = entityInputTypes.get(entityType);

                // these will be the fields to return
                List<Field> postMutationSelectionSet = entitySelection.getSelectionSet().getSelections().stream().map(s -> (Field) s).toList();
                LOG.info("Selection fields {}", postMutationSelectionSet);

                List<Argument> arguments = entitySelection.getArguments();
                if (arguments.size() != 1) {
                    throw new IllegalArgumentException("Mutation for entity " + entityType + " must have exactly one argument");
                }
                Argument argument = arguments.get(0);
                if (!argument.getName().equals(INPUTS_FIELD_NAME)) {
                    throw new IllegalArgumentException("Mutation for entity " + entityType + " must have an argument named " + INPUTS_FIELD_NAME);
                }

                List<ObjectValue> mutationObjects = ((ArrayValue) argument.getValue()).getValues().stream().map(v -> (ObjectValue) v).toList();
                LOG.info("Mutation objects {}", mutationObjects);

                List<EntityMutation> entityMutations = inputMapping.parseEntityMutations(mutationObjects);
                LOG.info("Parsed mutations {}", entityMutations);

                mutations.put(entityType, entityMutations);
            } else {
                throw new IllegalArgumentException("Cannot execute mutations for unknown entity " + entityType);
            }
        }

        return mutations;
    }

}
