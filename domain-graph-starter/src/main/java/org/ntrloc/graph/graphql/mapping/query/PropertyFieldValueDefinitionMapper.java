package org.ntrloc.graph.graphql.mapping.query;

import com.google.common.base.CaseFormat;
import graphql.language.Description;
import graphql.language.FieldDefinition;
import graphql.language.Type;
import org.ntrloc.graph.db.schema.PropertyDefinition;
import org.ntrloc.graph.graphql.mapping.PropertyTypeGraphQLMapping;

public interface PropertyFieldValueDefinitionMapper {

    String ORIGINAL_PROPERTY_NAME_FIELD = "originalPropertyName";

    default FieldDefinition getPropertyFieldDefinition(PropertyDefinition propertyDefinition) {
        Type fieldType = PropertyTypeGraphQLMapping.mapPropertyDefinition(propertyDefinition, PropertyTypeGraphQLMapping.InputOutputType.OUTPUT);
        Description propertyDescription = propertyDefinition.getDescription() == null ?
                null :
                new Description(propertyDefinition.getDescription(), null, false);

        String propertyGraphQlName = CaseFormat.LOWER_CAMEL.to(CaseFormat.LOWER_CAMEL, propertyDefinition.getName());

        return FieldDefinition.newFieldDefinition()
                .name(propertyGraphQlName)
                .additionalData(ORIGINAL_PROPERTY_NAME_FIELD, propertyDefinition.getName())
                .type(fieldType)
                .description(propertyDescription)
                .build();
    }

}
