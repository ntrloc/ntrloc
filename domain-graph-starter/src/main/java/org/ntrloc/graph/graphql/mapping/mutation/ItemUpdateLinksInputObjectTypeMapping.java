package org.ntrloc.graph.graphql.mapping.mutation;

import graphql.language.InputObjectTypeDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.ListType;
import graphql.language.NonNullType;
import graphql.language.TypeName;
import org.apache.commons.text.CaseUtils;
import org.ntrloc.graph.db.language.mutation.LinkMutation;
import org.ntrloc.graph.db.schema.ItemDefinition;
import org.ntrloc.graph.graphql.mapping.InputObjectTypeProducer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ItemUpdateLinksInputObjectTypeMapping implements InputObjectTypeProducer {

    private String graphQlTypeName;
    private Map<String, IncomingLinkChoiceInputObjectTypeMapping> incomingLinkTypeMapping;
    private Map<String, OutgoingLinkChoiceInputObjectTypeMapping> outgoingLinkTypeMapping;

    public ItemUpdateLinksInputObjectTypeMapping(ItemDefinition itemDefinition, Map<String, IncomingLinkChoiceInputObjectTypeMapping> incomingLinkTypeMapping, Map<String, OutgoingLinkChoiceInputObjectTypeMapping> outgoingLinkTypeMapping) {
        String typeName = String.format("%s Update Links Input", itemDefinition.getName());
        this.graphQlTypeName = CaseUtils.toCamelCase(typeName, true, '_', '-');
        this.incomingLinkTypeMapping = incomingLinkTypeMapping;
        this.outgoingLinkTypeMapping = outgoingLinkTypeMapping;
    }

    @Override
    public List<InputObjectTypeDefinition> getInputObjectTypeDefinitions() {
        List<InputObjectTypeDefinition> retDefinitions = new ArrayList<>();

        List<InputValueDefinition> linkInputValues = new ArrayList<>();

        for (Map.Entry<String, IncomingLinkChoiceInputObjectTypeMapping> entry : incomingLinkTypeMapping.entrySet()) {
            String label = entry.getKey();
            IncomingLinkChoiceInputObjectTypeMapping type = entry.getValue();
            retDefinitions.addAll(type.getInputObjectTypeDefinitions());

            linkInputValues.add(InputValueDefinition.newInputValueDefinition()
                    .name(label)
                    .type(new ListType(new NonNullType(new TypeName(type.getGraphQlTypeName()))))
                    .build());
        }

        for (Map.Entry<String, OutgoingLinkChoiceInputObjectTypeMapping> entry : outgoingLinkTypeMapping.entrySet()) {
            String label = entry.getKey();
            OutgoingLinkChoiceInputObjectTypeMapping type = entry.getValue();
            retDefinitions.addAll(type.getInputObjectTypeDefinitions());

            linkInputValues.add(InputValueDefinition.newInputValueDefinition()
                    .name(label)
                    .type(new ListType(new NonNullType(new TypeName(type.getGraphQlTypeName()))))
                    .build());
        }

        retDefinitions.add(InputObjectTypeDefinition.newInputObjectDefinition()
                .name(graphQlTypeName)
                .inputValueDefinitions(linkInputValues)
                .build());

        return retDefinitions;
    }

    public String getGraphQlTypeName() {
        return graphQlTypeName;
    }

    public List<LinkMutation> parseLinkMutations(List<Map<String, List<Map<String, Map<String, Object>>>>> linkMutationMap) {
        List<LinkMutation> retMutations = new ArrayList<>();
        for (Map<String, List<Map<String, Map<String, Object>>>> linkMutation : linkMutationMap) {

            for (Map.Entry<String, List<Map<String, Map<String, Object>>>> entry : linkMutation.entrySet()) {
                String linkType = entry.getKey();
                List<Map<String, Map<String, Object>>> linkMutationObjects = entry.getValue();
                if (incomingLinkTypeMapping.containsKey(linkType)) {
                    IncomingLinkChoiceInputObjectTypeMapping mapping = incomingLinkTypeMapping.get(entry.getKey());
                    retMutations.addAll(mapping.parseLinkMutations(linkMutationObjects));
                } else if (outgoingLinkTypeMapping.containsKey(linkType)) {
                    OutgoingLinkChoiceInputObjectTypeMapping mapping = outgoingLinkTypeMapping.get(entry.getKey());
                    retMutations.addAll(mapping.parseLinkMutations(linkMutationObjects));
                } else {
                    throw new IllegalArgumentException("Unknown link type " + entry.getKey());
                }
            }
        }
        return retMutations;
    }

}
