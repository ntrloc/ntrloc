package org.ntrloc.graph.graphql.mapping.mutation;

import graphql.language.ArrayValue;
import graphql.language.InputObjectTypeDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.ListType;
import graphql.language.NonNullType;
import graphql.language.ObjectField;
import graphql.language.ObjectValue;
import graphql.language.TypeName;
import org.apache.commons.text.CaseUtils;
import org.ntrloc.graph.db.language.mutation.LinkCreateMutation;
import org.ntrloc.graph.db.schema.ItemDefinition;
import org.ntrloc.graph.graphql.mapping.InputObjectTypeProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ItemCreateLinksInputObjectTypeMapping implements InputObjectTypeProducer {

    private static final Logger LOG = LoggerFactory.getLogger(ItemCreateLinksInputObjectTypeMapping.class);

    private String graphQlTypeName;
    private Map<String, IncomingLinkCreateInputObjectTypeMapping> incomingTypes;
    private Map<String, OutgoingLinkCreateInputObjectTypeMapping> outgoingTypes;

    public ItemCreateLinksInputObjectTypeMapping(ItemDefinition itemDefinition, Map<String, IncomingLinkCreateInputObjectTypeMapping> incomingTypes, Map<String, OutgoingLinkCreateInputObjectTypeMapping> outgoingTypes) {
        String typeName = String.format("%s Create Links Input", itemDefinition.getName());
        this.graphQlTypeName = CaseUtils.toCamelCase(typeName, true, '_', '-');
        this.incomingTypes = incomingTypes;
        this.outgoingTypes = outgoingTypes;
    }

    @Override
    public List<InputObjectTypeDefinition> getInputObjectTypeDefinitions() {
        List<InputObjectTypeDefinition> retDefinitions = new ArrayList<>();

        List<InputValueDefinition> linkInputValues = new ArrayList<>();

        for (Map.Entry<String, IncomingLinkCreateInputObjectTypeMapping> entry : incomingTypes.entrySet()) {
            String label = entry.getKey();
            IncomingLinkCreateInputObjectTypeMapping type = entry.getValue();
            retDefinitions.addAll(type.getInputObjectTypeDefinitions());
            linkInputValues.add(InputValueDefinition.newInputValueDefinition()
                    .name(label)
                    .type(new ListType(new NonNullType(new TypeName(type.getGraphQlTypeName()))))
                    .build());
        }

        for (Map.Entry<String, OutgoingLinkCreateInputObjectTypeMapping> entry : outgoingTypes.entrySet()) {
            String label = entry.getKey();
            OutgoingLinkCreateInputObjectTypeMapping type = entry.getValue();
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

    List<LinkCreateMutation> parseLinkCreateMutations(ObjectField createInputObject) {
        List<LinkCreateMutation> retMutations = new ArrayList<>();
        ObjectValue value = (ObjectValue) createInputObject.getValue();
        List<ObjectField> objectFields = value.getObjectFields();
        for (ObjectField objectField : objectFields) {
            String fieldName = objectField.getName();
            IncomingLinkCreateInputObjectTypeMapping incomingType = incomingTypes.get(fieldName);
            if (incomingType == null) {
                OutgoingLinkCreateInputObjectTypeMapping outgoingType = outgoingTypes.get(fieldName);
                List<LinkCreateMutation> mutations = outgoingType.parseLinkCreateMutations((ArrayValue) objectField.getValue());
                retMutations.addAll(mutations);
            } else {
                List<LinkCreateMutation> mutations = incomingType.parseLinkCreateMutations((ArrayValue) objectField.getValue());
                retMutations.addAll(mutations);
            }
        }
        return retMutations;
    }
}
