package org.ntrloc.graph.graphql.mapping.mutation;

import graphql.language.Description;
import graphql.language.InputValueDefinition;
import graphql.language.TypeName;
import org.apache.commons.text.CaseUtils;
import org.ntrloc.graph.db.schema.PropertyDefinition;

public interface PropertyInputValueDefinitionMapper {

    default InputValueDefinition getPropertyInputValueDefinition(PropertyDefinition propertyDefinition) {
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
