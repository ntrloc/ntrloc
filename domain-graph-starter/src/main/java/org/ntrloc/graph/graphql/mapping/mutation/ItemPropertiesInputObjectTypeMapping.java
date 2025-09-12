package org.ntrloc.graph.graphql.mapping.mutation;

import graphql.language.InputObjectTypeDefinition;
import graphql.language.InputValueDefinition;
import org.apache.commons.text.CaseUtils;
import org.ntrloc.graph.db.language.BinaryReferenceProperty;
import org.ntrloc.graph.db.language.BooleanListProperty;
import org.ntrloc.graph.db.language.BooleanProperty;
import org.ntrloc.graph.db.language.DateListProperty;
import org.ntrloc.graph.db.language.DateProperty;
import org.ntrloc.graph.db.language.DoubleListProperty;
import org.ntrloc.graph.db.language.DoubleProperty;
import org.ntrloc.graph.db.language.IntListProperty;
import org.ntrloc.graph.db.language.IntProperty;
import org.ntrloc.graph.db.language.Property;
import org.ntrloc.graph.db.language.StringListProperty;
import org.ntrloc.graph.db.language.StringProperty;
import org.ntrloc.graph.db.schema.ItemDefinition;
import org.ntrloc.graph.db.schema.PropertyDefinition;
import org.ntrloc.graph.db.schema.PropertyType;
import org.ntrloc.graph.graphql.mapping.InputObjectTypeProducer;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps an entity's properties to a GraphQL entity properties type.
 */
public class ItemPropertiesInputObjectTypeMapping implements InputObjectTypeProducer, PropertyInputValueDefinitionMapper {

    private String graphQlTypeName;

    /* Maps graph properties to their GraphQL input value definitions. */
    private Map<String, InputValueDefinition> inputProperties = new HashMap<>();

    /* Maps graphQL property names to their original property definitions. */
    private Map<String, PropertyDefinition> propertyDefinitions = new HashMap<>();

    public ItemPropertiesInputObjectTypeMapping(ItemDefinition itemDefinition) {
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

    List<? extends Property> mapProperties(Map<String, Object> propertiesValue) {

        return propertiesValue.entrySet().stream().map(entry -> {
            String propertyName = entry.getKey();
            PropertyDefinition propertyDefinition = propertyDefinitions.get(propertyName);
            PropertyType propertyType = propertyDefinition.getType();
            Object propertyValue = entry.getValue();
            return switch (propertyType) {
                case PropertyType.BOOLEAN -> new BooleanProperty(propertyName, (Boolean)propertyValue);
                case PropertyType.BOOLEAN_LIST -> new BooleanListProperty(propertyName, (List<Boolean>)propertyValue);
                case PropertyType.INT -> new IntProperty(propertyName, (Integer)propertyValue);
                case PropertyType.INT_LIST -> new IntListProperty(propertyName, (List<Integer>)propertyValue);
                case PropertyType.STRING -> new StringProperty(propertyName, (String)propertyValue);
                case PropertyType.STRING_LIST -> new StringListProperty(propertyName, (List<String>)propertyValue);
                case PropertyType.DATE -> new DateProperty(propertyName, (Date)propertyValue);
                case PropertyType.DATE_LIST -> new DateListProperty(propertyName, (List<Date>)propertyValue);
                case PropertyType.DOUBLE -> new DoubleProperty(propertyName, (Double)propertyValue);
                case PropertyType.DOUBLE_LIST -> new DoubleListProperty(propertyName, (List<Double>)propertyValue);
                case PropertyType.BINARY -> new BinaryReferenceProperty(propertyName, -1L);
            };
        }).toList();
    }
}
