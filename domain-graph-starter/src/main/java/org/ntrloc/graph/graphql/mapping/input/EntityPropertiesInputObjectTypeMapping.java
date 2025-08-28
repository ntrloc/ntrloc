package org.ntrloc.graph.graphql.mapping.input;

import graphql.language.Description;
import graphql.language.InputObjectTypeDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.TypeName;
import org.apache.commons.text.CaseUtils;
import org.ntrloc.graph.db.schema.EntityDefinition;
import org.ntrloc.graph.db.schema.PropertyDefinition;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps an entity's properties to a GraphQL entity properties type.
 */
public class EntityPropertiesInputObjectTypeMapping implements InputObjectTypeProducer {

    private String graphQlTypeName;

    /* Maps graph properties to their GraphQL input value definitions. */
    private Map<String, InputValueDefinition> inputProperties = new HashMap<>();

    /* Maps graphQL property names to their original property definitions. */
    private Map<String, PropertyDefinition> propertyDefinitions = new HashMap<>();

    public EntityPropertiesInputObjectTypeMapping(EntityDefinition entityDefinition) {
        String typeName = String.format("%s Properties Input", entityDefinition.getName());
        this.graphQlTypeName = CaseUtils.toCamelCase(typeName, true, '_', '-');

        for (var propertyDefinition : entityDefinition.getProperties()) {
            InputValueDefinition inputValueDefinition = getPropertyInputValueDefinition(propertyDefinition);
            inputProperties.put(inputValueDefinition.getName(), inputValueDefinition);
            propertyDefinitions.put(inputValueDefinition.getName(), propertyDefinition);
        }

        if (entityDefinition.getPropertyGroups() != null) {
            for (var group : entityDefinition.getPropertyGroups()) {
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

    private InputValueDefinition getPropertyInputValueDefinition(PropertyDefinition propertyDefinition) {
        TypeName typeName = switch (propertyDefinition.getType()) {
            case STRING -> new TypeName("String");
            case INT -> new TypeName("Int");
            default -> throw new RuntimeException("Unsupported type: " + propertyDefinition.getType());
        };
        Description propertyDescription = propertyDefinition.getDescription() == null ?
                null :
                new Description(propertyDefinition.getDescription(), null, false);

        return InputValueDefinition.newInputValueDefinition()
                .name(CaseUtils.toCamelCase(propertyDefinition.getName(), false, '_', '-'))
                .type(typeName)
                .description(propertyDescription)
                .build();
    }
}
