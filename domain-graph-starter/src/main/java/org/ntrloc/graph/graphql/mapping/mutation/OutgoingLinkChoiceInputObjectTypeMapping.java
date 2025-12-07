package org.ntrloc.graph.graphql.mapping.mutation;

import graphql.language.InputObjectTypeDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.TypeName;
import org.apache.commons.text.CaseUtils;
import org.ntrloc.graph.db.language.mutation.LinkMutation;
import org.ntrloc.graph.db.schema.LinkDefinition;
import org.ntrloc.graph.graphql.mapping.InputObjectTypeProducer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** An input type that allows the choice of link create, update, or delete during entity updates. */
public class OutgoingLinkChoiceInputObjectTypeMapping implements OutgoingLinkInputTypeMapping, InputObjectTypeProducer {

    private static final String CREATE_MAPPING_KEY = "create";
    private static final String UPDATE_MAPPING_KEY = "update";
    private static final String DELETE_MAPPING_KEY = "delete";

    private String graphQlTypeName;
    private LinkDefinition targetLinkDefinition;
    private Map<String, OutgoingLinkInputTypeMapping> mappings;

    public OutgoingLinkChoiceInputObjectTypeMapping(LinkDefinition targetLinkDefinition,
                                                    OutgoingLinkCreateInputObjectTypeMapping createMapping,
                                                    OutgoingLinkUpdateInputObjectTypeMapping updateMapping,
                                                    OutgoingLinkDeleteInputObjectTypeMapping deleteMapping) {
        String typeName = String.format("%s %s %s Link Choice Input", targetLinkDefinition.getSourceItemType(), targetLinkDefinition.getSourceLabel(), targetLinkDefinition.getTargetItemType());
        this.graphQlTypeName = CaseUtils.toCamelCase(typeName, true, '_', '-');
        this.targetLinkDefinition = targetLinkDefinition;
        mappings = Map.of(CREATE_MAPPING_KEY, createMapping, UPDATE_MAPPING_KEY, updateMapping, DELETE_MAPPING_KEY, deleteMapping);
    }

    public String getGraphQlTypeName() {
        return graphQlTypeName;
    }

    public OutgoingLinkCreateInputObjectTypeMapping getCreateMapping() {
        return (OutgoingLinkCreateInputObjectTypeMapping) mappings.get(CREATE_MAPPING_KEY);
    }

    public OutgoingLinkUpdateInputObjectTypeMapping getUpdateMapping() {
        return (OutgoingLinkUpdateInputObjectTypeMapping) mappings.get(UPDATE_MAPPING_KEY);
    }

    public OutgoingLinkDeleteInputObjectTypeMapping getDeleteMapping() {
        return (OutgoingLinkDeleteInputObjectTypeMapping) mappings.get(DELETE_MAPPING_KEY);
    }

    @Override
    public String getRelationshipSourceLabel() {
        return targetLinkDefinition.getSourceLabel();
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

    public List<LinkMutation> parseLinkMutations(List<Map<String, Map<String, Object>>> mutationObjects) {
        List<LinkMutation> retMutations = new ArrayList<>();
        List<Map<String, Map<String, Object>>> createMutations = new ArrayList<>();
        List<Map<String, Map<String, Object>>> updateMutations = new ArrayList<>();
        List<Map<String, Map<String, Object>>> deleteMutations = new ArrayList<>();
        for (Map<String, Map<String, Object>> mutationObject: mutationObjects) {
            if (mutationObject.containsKey(CREATE_MAPPING_KEY)) {
                Map<String, Object> obj = mutationObject.get(CREATE_MAPPING_KEY);
                Map<String, Map<String, Object>> linkMap = obj.entrySet().stream()
                        .collect(Collectors.toMap(Map.Entry::getKey, entry -> (Map<String, Object>)entry.getValue()));
                createMutations.add(linkMap);
            } else if (mutationObject.containsKey(UPDATE_MAPPING_KEY)) {
                Map<String, Object> obj = mutationObject.get(UPDATE_MAPPING_KEY);
                Map<String, Map<String, Object>> linkMap = obj.entrySet().stream()
                        .collect(Collectors.toMap(Map.Entry::getKey, entry -> (Map<String, Object>)entry.getValue()));
                updateMutations.add(linkMap);
            } else if (mutationObject.containsKey(DELETE_MAPPING_KEY)) {
                Map<String, Object> obj = mutationObject.get(DELETE_MAPPING_KEY);
                Map<String, Map<String, Object>> linkMap = obj.entrySet().stream()
                        .collect(Collectors.toMap(Map.Entry::getKey, entry -> (Map<String, Object>)entry.getValue()));
                deleteMutations.add(linkMap);
            }
        }
        if (!createMutations.isEmpty()) {
            retMutations.addAll(getCreateMapping().parseLinkCreateMutations(createMutations));
        }
        if (!updateMutations.isEmpty()) {
            retMutations.addAll(getUpdateMapping().parseLinkUpdateMutations(updateMutations));
        }
        if (!deleteMutations.isEmpty()) {
            retMutations.addAll(getDeleteMapping().parseLinkDeleteMutations(deleteMutations));
        }
        return retMutations;
    }
}
