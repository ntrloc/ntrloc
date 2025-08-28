package org.ntrloc.graph.graphql.mapping.input;

import graphql.language.InputObjectTypeDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.ListType;
import graphql.language.NonNullType;
import graphql.language.TypeName;
import org.apache.commons.text.CaseUtils;
import org.ntrloc.graph.db.schema.EntityDefinition;
import org.ntrloc.graph.graphql.mapping.InputTypeProducer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class EntityUpdateLinksInputTypeMapping implements InputTypeProducer {

    private String graphQlTypeName;
    private Map<String, OutgoingRelationshipChoiceInputTypeMapping> outgoingTypes;

    public EntityUpdateLinksInputTypeMapping(EntityDefinition entityDefinition, Map<String, OutgoingRelationshipChoiceInputTypeMapping> outgoingTypes) {
        String typeName = String.format("%s Update Links Input", entityDefinition.getName());
        this.graphQlTypeName = CaseUtils.toCamelCase(typeName, true, '_', '-');
        this.outgoingTypes = outgoingTypes;
    }

    @Override
    public List<InputObjectTypeDefinition> getInputObjectTypeDefinitions() {
        List<InputObjectTypeDefinition> retDefinitions = new ArrayList<>();

        List<InputValueDefinition> linkInputValues = new ArrayList<>();
        for (Map.Entry<String, OutgoingRelationshipChoiceInputTypeMapping> entry : outgoingTypes.entrySet()) {
            String label = entry.getKey();
            OutgoingRelationshipChoiceInputTypeMapping type = entry.getValue();
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
