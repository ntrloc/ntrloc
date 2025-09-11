package org.ntrloc.graph.graphql.mapping.query;

import graphql.language.Description;
import graphql.language.FieldDefinition;
import graphql.language.TypeName;
import org.apache.commons.text.CaseUtils;
import org.ntrloc.graph.db.schema.PropertyDefinition;

public interface PropertyFieldValueDefinitionMapper {

    String ORIGINAL_PROPERTY_NAME_FIELD = "originalPropertyName";

    default FieldDefinition getPropertyFieldDefinition(PropertyDefinition propertyDefinition) {
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
                .additionalData(ORIGINAL_PROPERTY_NAME_FIELD, propertyDefinition.getName())
                .type(typeName)
                .description(propertyDescription)
                .build();
    }

}
