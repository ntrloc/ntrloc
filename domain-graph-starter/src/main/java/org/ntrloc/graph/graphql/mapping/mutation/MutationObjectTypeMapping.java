package org.ntrloc.graph.graphql.mapping.mutation;

import graphql.language.FieldDefinition;
import graphql.language.InputObjectTypeDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.ListType;
import graphql.language.NonNullType;
import graphql.language.ObjectTypeDefinition;
import graphql.language.TypeName;
import org.ntrloc.graph.db.language.mutation.ItemMutation;
import org.ntrloc.graph.db.schema.ItemDefinition;
import org.ntrloc.graph.db.schema.LinkDefinition;
import org.ntrloc.graph.graphql.mapping.InputObjectTypeProducer;
import org.ntrloc.graph.graphql.mapping.ObjectTypeProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class MutationObjectTypeMapping implements ObjectTypeProducer, InputObjectTypeProducer {

    private static final Logger LOG = LoggerFactory.getLogger(MutationObjectTypeMapping.class);

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

    public Map<String, List<ItemMutation>> parseItemMutations(Map<String, Object> arguments) {
        Optional<Object> inputsOpt = Optional.ofNullable(arguments.get(INPUT_ARGUMENT_NAME));
        if (inputsOpt.isPresent()) {
            Object inputArgument = inputsOpt.get();
            if (inputArgument instanceof ArrayList arrayValue) {
                return mutationChoiceInputObjectTypeMapping.parseEntityMutations(arrayValue);
            } else {
                throw new IllegalArgumentException("Mutation field " + EXECUTE_FIELD_NAME + ", argument " + INPUT_ARGUMENT_NAME + " must be an array");
            }
        } else {
            throw new IllegalArgumentException("Mutation field " + EXECUTE_FIELD_NAME + " must have an argument named " + INPUT_ARGUMENT_NAME);
        }
    }

}
