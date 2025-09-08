package org.ntrloc.graph.graphql.mapping;

import graphql.language.Argument;
import graphql.language.ArrayValue;
import graphql.language.Field;
import graphql.language.FieldDefinition;
import graphql.language.InputObjectTypeDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.ListType;
import graphql.language.NonNullType;
import graphql.language.ObjectTypeDefinition;
import graphql.language.TypeName;
import graphql.language.Value;
import org.ntrloc.graph.db.language.mutation.ItemMutation;
import org.ntrloc.graph.db.schema.ItemDefinition;
import org.ntrloc.graph.db.schema.LinkDefinition;
import org.ntrloc.graph.graphql.mapping.mutation.InputObjectTypeProducer;
import org.ntrloc.graph.graphql.mapping.mutation.MutationChoiceInputObjectTypeMapping;
import org.ntrloc.graph.graphql.mapping.query.ObjectTypeProducer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class MutationObjectTypeMapping implements ObjectTypeProducer, InputObjectTypeProducer {

    private static final String EXECUTE_FIELD_NAME = "execute";
    private static final String INPUT_ARGUMENT_NAME = "inputs";

    private MutationChoiceInputObjectTypeMapping mutationChoiceInputObjectTypeMapping;
    private MutationResultObjectTypeMapping mutationResultObjectTypeMapping = new MutationResultObjectTypeMapping();

    public MutationObjectTypeMapping(Set<ItemDefinition> itemDefinitions, Set<LinkDefinition> linkDefinitions) {
        this.mutationChoiceInputObjectTypeMapping = new MutationChoiceInputObjectTypeMapping(itemDefinitions, linkDefinitions);
    }

    @Override
    public List<ObjectTypeDefinition> getObjectTypeDefinitions() {

        if (mutationChoiceInputObjectTypeMapping.isEmpty()) {
            return List.of();
        } else {
            List<ObjectTypeDefinition> retList = new ArrayList<>();
            retList.addAll(mutationResultObjectTypeMapping.getObjectTypeDefinitions());

            ObjectTypeDefinition mutationDefinition = ObjectTypeDefinition.newObjectTypeDefinition()
                    .name("Mutation")
                    .fieldDefinition(FieldDefinition.newFieldDefinition()
                            .name(EXECUTE_FIELD_NAME)
                            .type(new NonNullType(new TypeName(mutationResultObjectTypeMapping.getGraphQlTypeName())))
                            .inputValueDefinition(InputValueDefinition.newInputValueDefinition()
                                    .name(INPUT_ARGUMENT_NAME)
                                    .type(new ListType(new NonNullType(new TypeName(mutationChoiceInputObjectTypeMapping.getGraphQlTypeName()))))
                                    .build())
                            .build())
                    .build();
            retList.add(mutationDefinition);
            return retList;
        }
    }

    @Override
    public List<InputObjectTypeDefinition> getInputObjectTypeDefinitions() {
        return mutationChoiceInputObjectTypeMapping.getInputObjectTypeDefinitions();
    }

    public Map<String, List<ItemMutation>> parseEntityMutations(Field mutationField) {
        /*
        if (mutationField.getName().equals(EXECUTE_FIELD_NAME)) {
            return mutationExecutionObjectTypeMapping.parseEntityMutations(mutationField);
        } else {
            throw new IllegalArgumentException("Unknown mutation field " + mutationField.getName());
        }

         */
        // TODO

        Optional<Argument> argument = mutationField.getArguments().stream().filter(a -> a.getName().equals(INPUT_ARGUMENT_NAME)).findFirst();
        if (argument.isPresent()) {
            Argument inputArgument = argument.get();
            Value argValue = inputArgument.getValue();
            if (argValue instanceof ArrayValue arrayValue) {
                List<Value> values = arrayValue.getValues();
                return mutationChoiceInputObjectTypeMapping.parseEntityMutations(values);
            } else {
                throw new IllegalArgumentException("Mutation field " + EXECUTE_FIELD_NAME + ", argument " + INPUT_ARGUMENT_NAME + " must be an array");
            }
        } else {
            throw new IllegalArgumentException("Mutation field " + EXECUTE_FIELD_NAME + " must have an argument named " + INPUT_ARGUMENT_NAME);
        }
    }

}
