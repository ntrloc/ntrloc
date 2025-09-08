package org.ntrloc.graph.graphql.mapping.mutation;

import graphql.language.InputObjectTypeDefinition;
import graphql.language.InputValueDefinition;
import org.apache.commons.text.CaseUtils;
import org.ntrloc.graph.db.schema.PropertyDefinition;
import org.ntrloc.graph.db.schema.LinkDefinition;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Maps all properties for a relationship into a GraphQL input object type. */
public class LinkPropertiesInputObjectTypeMapping implements InputObjectTypeProducer, PropertyInputValueDefinitionMapper {

    private String graphQlTypeName;

    /* Maps graph properties to their GraphQL input value definitions. */
    private Map<String, InputValueDefinition> inputProperties = new HashMap<>();

    /* Maps graphQL property names to their original property definitions. */
    private Map<String, PropertyDefinition> propertyDefinitions = new HashMap<>();

    public LinkPropertiesInputObjectTypeMapping(LinkDefinition linkDefinition) {

        String typeName = String.format("%s %s %s Properties Input", linkDefinition.getSourceEntity(), linkDefinition.getSourceLabel(), linkDefinition.getTargetEntity());
        this.graphQlTypeName = CaseUtils.toCamelCase(typeName, true, '_', '-');

        for (var propertyDefinition : linkDefinition.getProperties()) {
            InputValueDefinition inputValueDefinition = getPropertyInputValueDefinition(propertyDefinition);
            inputProperties.put(inputValueDefinition.getName(), inputValueDefinition);
            propertyDefinitions.put(inputValueDefinition.getName(), propertyDefinition);
        }

        if (linkDefinition.getPropertyGroups() != null) {
            for (var group : linkDefinition.getPropertyGroups()) {
                for (var propertyDefinition : group.getProperties()) {
                    InputValueDefinition inputValueDefinition = getPropertyInputValueDefinition(propertyDefinition);
                    inputProperties.put(inputValueDefinition.getName(), inputValueDefinition);
                    propertyDefinitions.put(inputValueDefinition.getName(), propertyDefinition);
                }
            }
        }
    }

    public String getGraphQlTypeName() {
        return graphQlTypeName;
    }

    @Override
    public List<InputObjectTypeDefinition> getInputObjectTypeDefinitions() {
        List<InputValueDefinition> entityPropertyInputDefinitions = inputProperties.values().stream().toList();
        return List.of(InputObjectTypeDefinition.newInputObjectDefinition()
                .name(graphQlTypeName)
                .inputValueDefinitions(entityPropertyInputDefinitions)
                .build());
    }

}
