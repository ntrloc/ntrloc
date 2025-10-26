package org.ntrloc.graph.graphql.mapping.mutation;

import com.google.common.base.CaseFormat;
import graphql.language.Description;
import graphql.language.InputValueDefinition;
import graphql.language.Type;
import org.ntrloc.graph.db.schema.PropertyDefinition;
import org.ntrloc.graph.graphql.mapping.PropertyTypeGraphQLMapping;

public interface PropertyInputValueDefinitionMapper {

    default InputValueDefinition getPropertyInputValueDefinition(PropertyDefinition propertyDefinition) {
        Type fieldType = PropertyTypeGraphQLMapping.mapPropertyDefinition(propertyDefinition, PropertyTypeGraphQLMapping.InputOutputType.INPUT);
        Description propertyDescription = propertyDefinition.getDescription() == null ?
                null :
                new Description(propertyDefinition.getDescription(), null, false);
        String propertyGraphQlName = CaseFormat.LOWER_CAMEL.to(CaseFormat.LOWER_CAMEL, propertyDefinition.getName());

        return InputValueDefinition.newInputValueDefinition()
                .name(propertyGraphQlName)
                .type(fieldType)
                .description(propertyDescription)
                .build();
    }

}
