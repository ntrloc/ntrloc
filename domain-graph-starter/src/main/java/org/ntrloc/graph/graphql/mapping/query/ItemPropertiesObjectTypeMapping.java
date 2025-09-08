package org.ntrloc.graph.graphql.mapping.query;

import graphql.language.Description;
import graphql.language.FieldDefinition;
import graphql.language.ObjectTypeDefinition;
import graphql.language.TypeName;
import org.apache.commons.text.CaseUtils;
import org.ntrloc.graph.db.schema.ItemDefinition;
import org.ntrloc.graph.db.schema.PropertyDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps an entity's properties to a GraphQL entity properties type.
 */
public class ItemPropertiesObjectTypeMapping implements ObjectTypeProducer {

    private static final Logger LOG = LoggerFactory.getLogger(ItemPropertiesObjectTypeMapping.class);

    private String graphQlTypeName;

    /* Maps graph properties to their GraphQL value definitions. */
    private Map<String, FieldDefinition> inputProperties = new HashMap<>();

    /* Maps graphQL property names to their original property definitions. */
    private Map<String, PropertyDefinition> propertyDefinitions = new HashMap<>();

    private Map<String, ItemPropertyGroupObjectTypeMapping> groupMappings = new HashMap<>();

    public ItemPropertiesObjectTypeMapping(ItemDefinition itemDefinition) {
        String typeName = String.format("%s Properties", itemDefinition.getName());
        this.graphQlTypeName = CaseUtils.toCamelCase(typeName, true, '_', '-');

        for (var propertyDefinition : itemDefinition.getProperties()) {
            FieldDefinition fieldDefinition = getPropertyFieldDefinition(propertyDefinition);
            inputProperties.put(fieldDefinition.getName(), fieldDefinition);
            propertyDefinitions.put(fieldDefinition.getName(), propertyDefinition);
        }


        if (itemDefinition.getPropertyGroups() != null) {
            for (var group : itemDefinition.getPropertyGroups()) {
                var groupMapping = new ItemPropertyGroupObjectTypeMapping(itemDefinition, group);
                String groupFieldName = CaseUtils.toCamelCase(group.getName(), false, '_', '-');
                groupMappings.put(groupFieldName, groupMapping);
            }
        }

        LOG.info("Parse complete");
    }

    public String getGraphQlTypeName() {
        return graphQlTypeName;
    }

    @Override
    public List<ObjectTypeDefinition> getObjectTypeDefinitions() {

        List<FieldDefinition> propertyFieldDefinitions = new ArrayList(inputProperties.values().stream().toList());

        // Property groups are treated as if they are properties themselves
        List<FieldDefinition> groupFieldDefinitions = groupMappings.entrySet().stream().map(entry -> {
            String fieldName = entry.getKey();
            ItemPropertyGroupObjectTypeMapping groupMapping = entry.getValue();
            List<ObjectTypeDefinition> groupDefinitions = groupMapping.getObjectTypeDefinitions();
            if (groupDefinitions.size() != 1) {
                throw new RuntimeException("Expected exactly one group definition for " + fieldName + ", got " + groupDefinitions.size());
            }
            return FieldDefinition.newFieldDefinition()
                    .name(fieldName)
                    .type(new TypeName(groupDefinitions.get(0).getName()))
                    .build();
        }).toList();
        propertyFieldDefinitions.addAll(groupFieldDefinitions);

        var myDef = ObjectTypeDefinition.newObjectTypeDefinition()
                .name(graphQlTypeName)
                .fieldDefinitions(propertyFieldDefinitions)
                .build();

        var allDefinitions = new ArrayList<>(groupMappings.values().stream().flatMap(mapping -> mapping.getObjectTypeDefinitions().stream()).toList());
        allDefinitions.add(myDef);

        return allDefinitions;
    }

    private FieldDefinition getPropertyFieldDefinition(PropertyDefinition propertyDefinition) {
        TypeName typeName = switch (propertyDefinition.getType()) {
            case STRING -> new TypeName("String");
            case INT -> new TypeName("Int");
            default -> throw new RuntimeException("Unsupported type: " + propertyDefinition.getType());
        };
        Description propertyDescription = propertyDefinition.getDescription() == null ?
                null :
                new Description(propertyDefinition.getDescription(), null, false);

        return FieldDefinition.newFieldDefinition()
                .name(CaseUtils.toCamelCase(propertyDefinition.getName(), false, '_', '-'))
                .type(typeName)
                .description(propertyDescription)
                .build();
    }
}
