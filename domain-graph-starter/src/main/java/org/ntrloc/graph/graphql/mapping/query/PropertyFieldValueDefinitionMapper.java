package org.ntrloc.graph.graphql.mapping.query;

import graphql.language.Description;
import graphql.language.FieldDefinition;
import graphql.language.Type;
import org.apache.commons.text.CaseUtils;
import org.ntrloc.graph.db.schema.PropertyDefinition;
import org.ntrloc.graph.graphql.mapping.PropertyTypeGraphQLMapping;

public interface PropertyFieldValueDefinitionMapper {

    String ORIGINAL_PROPERTY_NAME_FIELD = "originalPropertyName";

    default FieldDefinition getPropertyFieldDefinition(PropertyDefinition propertyDefinition) {
        Type fieldType = PropertyTypeGraphQLMapping.mapPropertyDefinition(propertyDefinition);
        Description propertyDescription = propertyDefinition.getDescription() == null ?
                null :
                new Description(propertyDefinition.getDescription(), null, false);

        return FieldDefinition.newFieldDefinition()
                .name(CaseUtils.toCamelCase(propertyDefinition.getName(), false, '_', '-'))
                .additionalData(ORIGINAL_PROPERTY_NAME_FIELD, propertyDefinition.getName())
                .type(fieldType)
                .description(propertyDescription)
                .build();
    }

}
