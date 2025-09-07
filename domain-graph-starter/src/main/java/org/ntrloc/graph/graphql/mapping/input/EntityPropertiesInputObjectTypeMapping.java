package org.ntrloc.graph.graphql.mapping.input;

import graphql.language.InputObjectTypeDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.IntValue;
import graphql.language.NullValue;
import graphql.language.ObjectField;
import graphql.language.ObjectValue;
import graphql.language.StringValue;
import org.apache.commons.text.CaseUtils;
import org.ntrloc.graph.db.language.IntProperty;
import org.ntrloc.graph.db.language.Property;
import org.ntrloc.graph.db.language.StringProperty;
import org.ntrloc.graph.db.schema.ItemDefinition;
import org.ntrloc.graph.db.schema.PropertyDefinition;
import org.ntrloc.graph.db.schema.PropertyType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps an entity's properties to a GraphQL entity properties type.
 */
public class EntityPropertiesInputObjectTypeMapping implements InputObjectTypeProducer, PropertyInputValueDefinitionMapper {

    private String graphQlTypeName;

    /* Maps graph properties to their GraphQL input value definitions. */
    private Map<String, InputValueDefinition> inputProperties = new HashMap<>();

    /* Maps graphQL property names to their original property definitions. */
    private Map<String, PropertyDefinition> propertyDefinitions = new HashMap<>();

    public EntityPropertiesInputObjectTypeMapping(ItemDefinition itemDefinition) {
        String typeName = String.format("%s Properties Input", itemDefinition.getName());
        this.graphQlTypeName = CaseUtils.toCamelCase(typeName, true, '_', '-');

        for (var propertyDefinition : itemDefinition.getProperties()) {
            InputValueDefinition inputValueDefinition = getPropertyInputValueDefinition(propertyDefinition);
            inputProperties.put(inputValueDefinition.getName(), inputValueDefinition);
            propertyDefinitions.put(inputValueDefinition.getName(), propertyDefinition);
        }

        if (itemDefinition.getPropertyGroups() != null) {
            for (var group : itemDefinition.getPropertyGroups()) {
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

    List<? extends Property> mapProperties(ObjectValue propertiesValue) {
        List<ObjectField> values = propertiesValue.getObjectFields();
        return values.stream().map(value -> {
            String propertyName = value.getName();
            PropertyDefinition propertyDefinition = propertyDefinitions.get(propertyName);
            PropertyType propertyType = propertyDefinition.getType();

            if (propertyDefinition == null) {
                throw new IllegalArgumentException("No property definition for " + propertyName);
            }

            return switch (propertyType) {
                case PropertyType.STRING -> {
                    if (!(value.getValue() instanceof StringValue || value.getValue() instanceof NullValue)) {
                        throw new IllegalArgumentException("Expected string value for " + value.getName());
                    } else {
                        if (value.getValue() instanceof NullValue) {
                            yield new StringProperty(propertyName, null);
                        } else {
                            yield new StringProperty(propertyName, ((StringValue) value.getValue()).getValue());
                        }
                    }
                }
                case PropertyType.INT -> {
                    if (!(value.getValue() instanceof IntValue || value.getValue() instanceof NullValue)) {
                        throw new IllegalArgumentException("Expected int value for " + value.getName());
                    } else {
                        if (value.getValue() instanceof NullValue) {
                            yield new IntProperty(propertyName, null);
                        } else {
                            yield new IntProperty(propertyName, ((IntValue) value.getValue()).getValue().intValue());
                        }
                    }
                }
                default -> throw new IllegalArgumentException("Unsupported property type: " + propertyType);
            };
        }).toList();
    }
}
