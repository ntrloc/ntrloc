package org.ntrloc.graph.graphql.mapping.input;

import graphql.language.InputObjectTypeDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.ListType;
import graphql.language.NonNullType;
import graphql.language.TypeName;
import org.apache.commons.text.CaseUtils;
import org.ntrloc.graph.db.schema.EntityDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class EntityCreateLinksInputObjectTypeMapping implements InputObjectTypeProducer {

    private String graphQlTypeName;
    private Map<String, IncomingRelationshipCreateInputObjectTypeMapping> incomingTypes;
    private Map<String, OutgoingRelationshipCreateInputObjectTypeMapping> outgoingTypes;

    public EntityCreateLinksInputObjectTypeMapping(EntityDefinition entityDefinition, Map<String, IncomingRelationshipCreateInputObjectTypeMapping> incomingTypes, Map<String, OutgoingRelationshipCreateInputObjectTypeMapping> outgoingTypes) {
        String typeName = String.format("%s Create Links Input", entityDefinition.getName());
        this.graphQlTypeName = CaseUtils.toCamelCase(typeName, true, '_', '-');
        this.incomingTypes = incomingTypes;
        this.outgoingTypes = outgoingTypes;
    }

    @Override
    public List<InputObjectTypeDefinition> getInputObjectTypeDefinitions() {
        List<InputObjectTypeDefinition> retDefinitions = new ArrayList<>();

        List<InputValueDefinition> linkInputValues = new ArrayList<>();

        for (Map.Entry<String, IncomingRelationshipCreateInputObjectTypeMapping> entry : incomingTypes.entrySet()) {
            String label = entry.getKey();
            IncomingRelationshipCreateInputObjectTypeMapping type = entry.getValue();
            retDefinitions.addAll(type.getInputObjectTypeDefinitions());
            linkInputValues.add(InputValueDefinition.newInputValueDefinition()
                    .name(label)
                    .type(new ListType(new NonNullType(new TypeName(type.getGraphQlTypeName()))))
                    .build());
        }

        for (Map.Entry<String, OutgoingRelationshipCreateInputObjectTypeMapping> entry : outgoingTypes.entrySet()) {
            String label = entry.getKey();
            OutgoingRelationshipCreateInputObjectTypeMapping type = entry.getValue();
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
}
