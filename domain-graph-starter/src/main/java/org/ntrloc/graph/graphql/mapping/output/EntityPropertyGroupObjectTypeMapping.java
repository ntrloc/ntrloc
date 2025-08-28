package org.ntrloc.graph.graphql.mapping.output;

import graphql.language.Description;
import graphql.language.FieldDefinition;
import graphql.language.ObjectTypeDefinition;
import graphql.language.TypeName;
import org.apache.commons.text.CaseUtils;
import org.ntrloc.graph.db.schema.EntityDefinition;
import org.ntrloc.graph.db.schema.PropertyDefinition;
import org.ntrloc.graph.db.schema.PropertyGroupDefinition;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class EntityPropertyGroupObjectTypeMapping implements ObjectTypeProducer {

    private PropertyGroupDefinition propertyGroupDefinition;
    private String graphQlTypeName;

    /* Maps graph properties to their GraphQL value definitions. */
    private Map<String, FieldDefinition> inputProperties = new HashMap<>();

    /* Maps graphQL property names to their original property definitions. */
    private Map<String, PropertyDefinition> propertyDefinitions = new HashMap<>();

    public EntityPropertyGroupObjectTypeMapping(EntityDefinition entityDefinition, PropertyGroupDefinition propertyGroupDefinition) {
        this.propertyGroupDefinition = propertyGroupDefinition;
        String typeName = String.format("%s %s Property Group", entityDefinition.getName(), propertyGroupDefinition.getName());
        this.graphQlTypeName = CaseUtils.toCamelCase(typeName, true, '_', '-');

        for (var propertyDefinition : propertyGroupDefinition.getProperties()) {
            FieldDefinition fieldDefinition = getPropertyFieldDefinition(propertyDefinition);
            inputProperties.put(fieldDefinition.getName(), fieldDefinition);
            propertyDefinitions.put(fieldDefinition.getName(), propertyDefinition);
        }
    }

    @Override
    public List<ObjectTypeDefinition> getObjectTypeDefinitions() {
        List<FieldDefinition> propertyFieldDefinitions = new ArrayList(inputProperties.values().stream().toList());

        return List.of(ObjectTypeDefinition.newObjectTypeDefinition()
                        .name(graphQlTypeName)
                        .fieldDefinitions(propertyFieldDefinitions)
                        .build());
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
