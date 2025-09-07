package org.ntrloc.graph.graphql.mapping.input;

import graphql.language.InputObjectTypeDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.TypeName;
import org.apache.commons.text.CaseUtils;
import org.ntrloc.graph.db.schema.RelationshipDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** An input type that allows the choice of link create, update, or delete during entity updates. */
public class IncomingRelationshipChoiceInputObjectTypeMapping implements IncomingRelationshipInputTypeMapping, InputObjectTypeProducer {

    private static final String CREATE_MAPPING_KEY = "create";
    private static final String UPDATE_MAPPING_KEY = "update";
    private static final String DELETE_MAPPING_KEY = "delete";

    private String graphQlTypeName;
    private RelationshipDefinition targetRelationshipDefinition;
    private Map<String, IncomingRelationshipInputTypeMapping> mappings;

    public IncomingRelationshipChoiceInputObjectTypeMapping(RelationshipDefinition targetRelationshipDefinition,
                                                            IncomingRelationshipCreateInputObjectTypeMapping createMapping,
                                                            IncomingRelationshipUpdateInputObjectTypeMapping updateMapping,
                                                            IncomingRelationshipDeleteInputObjectTypeMapping deleteMapping) {
        String typeName = String.format("%s %s %s Link Choice Input", targetRelationshipDefinition.getTargetEntity(), targetRelationshipDefinition.getTargetLabel(), targetRelationshipDefinition.getSourceEntity());
        this.graphQlTypeName = CaseUtils.toCamelCase(typeName, true, '_', '-');
        this.targetRelationshipDefinition = targetRelationshipDefinition;
        mappings = Map.of(CREATE_MAPPING_KEY, createMapping, UPDATE_MAPPING_KEY, updateMapping, DELETE_MAPPING_KEY, deleteMapping);
    }

    public String getGraphQlTypeName() {
        return graphQlTypeName;
    }

    public IncomingRelationshipCreateInputObjectTypeMapping getCreateMapping() {
        return (IncomingRelationshipCreateInputObjectTypeMapping) mappings.get(CREATE_MAPPING_KEY);
    }

    public IncomingRelationshipUpdateInputObjectTypeMapping getUpdateMapping() {
        return (IncomingRelationshipUpdateInputObjectTypeMapping) mappings.get(UPDATE_MAPPING_KEY);
    }

    public IncomingRelationshipDeleteInputObjectTypeMapping getDeleteMapping() {
        return (IncomingRelationshipDeleteInputObjectTypeMapping) mappings.get(DELETE_MAPPING_KEY);
    }

    @Override
    public String getRelationshipTargetLabel() {
        return targetRelationshipDefinition.getTargetLabel();
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
