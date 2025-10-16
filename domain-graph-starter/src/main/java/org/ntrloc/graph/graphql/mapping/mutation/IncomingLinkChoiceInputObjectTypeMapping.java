package org.ntrloc.graph.graphql.mapping.mutation;

import graphql.language.InputObjectTypeDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.TypeName;
import org.apache.commons.text.CaseUtils;
import org.ntrloc.graph.db.schema.LinkDefinition;
import org.ntrloc.graph.graphql.mapping.InputObjectTypeProducer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** An input type that allows the choice of link create, update, or delete during entity updates. */
public class IncomingLinkChoiceInputObjectTypeMapping implements IncomingLinkInputTypeMapping, InputObjectTypeProducer {

    private static final String CREATE_MAPPING_KEY = "create";
    private static final String UPDATE_MAPPING_KEY = "update";
    private static final String DELETE_MAPPING_KEY = "delete";

    private String graphQlTypeName;
    private LinkDefinition targetLinkDefinition;
    private Map<String, IncomingLinkInputTypeMapping> mappings;

    public IncomingLinkChoiceInputObjectTypeMapping(LinkDefinition targetLinkDefinition,
                                                    IncomingLinkCreateInputObjectTypeMapping createMapping,
                                                    IncomingLinkUpdateInputObjectTypeMapping updateMapping,
                                                    IncomingLinkDeleteInputObjectTypeMapping deleteMapping) {
        String typeName = String.format("%s %s %s Link Choice Input", targetLinkDefinition.getTargetItemType(), targetLinkDefinition.getTargetLabel(), targetLinkDefinition.getSourceItemType());
        this.graphQlTypeName = CaseUtils.toCamelCase(typeName, true, '_', '-');
        this.targetLinkDefinition = targetLinkDefinition;
        mappings = Map.of(CREATE_MAPPING_KEY, createMapping, UPDATE_MAPPING_KEY, updateMapping, DELETE_MAPPING_KEY, deleteMapping);
    }

    public String getGraphQlTypeName() {
        return graphQlTypeName;
    }

    public IncomingLinkCreateInputObjectTypeMapping getCreateMapping() {
        return (IncomingLinkCreateInputObjectTypeMapping) mappings.get(CREATE_MAPPING_KEY);
    }

    public IncomingLinkUpdateInputObjectTypeMapping getUpdateMapping() {
        return (IncomingLinkUpdateInputObjectTypeMapping) mappings.get(UPDATE_MAPPING_KEY);
    }

    public IncomingLinkDeleteInputObjectTypeMapping getDeleteMapping() {
        return (IncomingLinkDeleteInputObjectTypeMapping) mappings.get(DELETE_MAPPING_KEY);
    }

    @Override
    public String getRelationshipTargetLabel() {
        return targetLinkDefinition.getTargetLabel();
    }

    @Override
    public List<InputObjectTypeDefinition> getInputObjectTypeDefinitions() {

        List<InputObjectTypeDefinition> allActionTypes = Stream.of(getCreateMapping(), getUpdateMapping(), getDeleteMapping())
                .map(InputObjectTypeProducer::getInputObjectTypeDefinitions)
                .flatMap((List::stream))
                .toList();

        InputValueDefinition createValue = InputValueDefinition.newInputValueDefinition()
                .name(CREATE_MAPPING_KEY)
                .type(new TypeName(getCreateMapping().getGraphQlTypeName()))
                .build();
        InputValueDefinition updateValue = InputValueDefinition.newInputValueDefinition()
                .name(UPDATE_MAPPING_KEY)
                .type(new TypeName(getUpdateMapping().getGraphQlTypeName()))
                .build();
        InputValueDefinition deleteValue = InputValueDefinition.newInputValueDefinition()
                .name(DELETE_MAPPING_KEY)
                .type(new TypeName(getDeleteMapping().getGraphQlTypeName()))
                .build();

        List<InputObjectTypeDefinition> allDefs = new ArrayList<>(allActionTypes);

        allDefs.add(InputObjectTypeDefinition.newInputObjectDefinition()
                .name(graphQlTypeName)
                .inputValueDefinitions(List.of(createValue, updateValue, deleteValue))
                .build());

        return allDefs;
    }
}
