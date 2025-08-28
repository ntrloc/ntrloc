package org.ntrloc.graph.graphql.mapping.input;

import graphql.language.InputObjectTypeDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.TypeName;
import org.apache.commons.text.CaseUtils;
import org.ntrloc.graph.db.schema.RelationshipDefinition;
import org.ntrloc.graph.graphql.mapping.InputObjectTypeProducer;

import java.util.List;
import java.util.Map;

/** An input type that allows the choice of link create, update, or delete during entity updates. */
public class OutgoingRelationshipChoiceInputObjectTypeMapping implements OutgoingRelationshipInputTypeMapping, InputObjectTypeProducer {

    private static final String CREATE_MAPPING_KEY = "create";
    private static final String UPDATE_MAPPING_KEY = "update";
    private static final String DELETE_MAPPING_KEY = "delete";

    private String graphQlTypeName;
    private RelationshipDefinition targetRelationshipDefinition;
    private Map<String, OutgoingRelationshipInputTypeMapping> mappings;

    public OutgoingRelationshipChoiceInputObjectTypeMapping(RelationshipDefinition targetRelationshipDefinition,
                                                            OutgoingRelationshipCreateInputObjectTypeMapping createMapping,
                                                            OutgoingRelationshipUpdateInputTypeMapping updateMapping,
                                                            OutgoingRelationshipDeleteInputTypeMapping deleteMapping) {
        String typeName = String.format("%s %s %s Link Choice Input", targetRelationshipDefinition.getSourceEntity(), targetRelationshipDefinition.getSourceLabel(), targetRelationshipDefinition.getTargetEntity());
        this.graphQlTypeName = CaseUtils.toCamelCase(typeName, true, '_', '-');
        this.targetRelationshipDefinition = targetRelationshipDefinition;
        mappings = Map.of(CREATE_MAPPING_KEY, createMapping, UPDATE_MAPPING_KEY, updateMapping, DELETE_MAPPING_KEY, deleteMapping);
    }

    public String getGraphQlTypeName() {
        return graphQlTypeName;
    }

    public OutgoingRelationshipCreateInputObjectTypeMapping getCreateMapping() {
        return (OutgoingRelationshipCreateInputObjectTypeMapping) mappings.get(CREATE_MAPPING_KEY);
    }

    public OutgoingRelationshipUpdateInputTypeMapping getUpdateMapping() {
        return (OutgoingRelationshipUpdateInputTypeMapping) mappings.get(UPDATE_MAPPING_KEY);
    }

    public OutgoingRelationshipDeleteInputTypeMapping getDeleteMapping() {
        return (OutgoingRelationshipDeleteInputTypeMapping) mappings.get(DELETE_MAPPING_KEY);
    }

    @Override
    public String getSourceLabel() {
        return targetRelationshipDefinition.getSourceLabel();
    }

    @Override
    public List<InputObjectTypeDefinition> getInputObjectTypeDefinitions() {
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

        return List.of(InputObjectTypeDefinition.newInputObjectDefinition()
                .name(graphQlTypeName)
                .inputValueDefinitions(List.of(createValue, updateValue, deleteValue))
                .build());
    }
}
