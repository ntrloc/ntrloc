package org.ntrloc.graph.graphql.mapping;

import graphql.language.ListType;
import graphql.language.Type;
import graphql.language.TypeName;
import org.ntrloc.graph.db.schema.PropertyDefinition;
import org.ntrloc.graph.graphql.mapping.scalars.BinaryScalarTypeMapping;
import org.ntrloc.graph.graphql.mapping.scalars.DateScalarTypeMapping;

public class PropertyTypeGraphQLMapping {

    public static Type mapPropertyDefinition(PropertyDefinition propertyDefinition) {
        return switch (propertyDefinition.getType()) {
            case BINARY -> new TypeName(BinaryScalarTypeMapping.BINARY_SCALAR_TYPE_NAME);
            case BOOLEAN -> new TypeName("Boolean");
            case BOOLEAN_LIST -> new ListType(new TypeName("Boolean"));
            case STRING -> new TypeName("String");
            case STRING_LIST -> new ListType(new TypeName("String"));
            case INT -> new TypeName("Int");
            case INT_LIST -> new ListType(new TypeName("Int"));
            case DATE -> new TypeName(DateScalarTypeMapping.DATE_SCALAR_TYPE_NAME);
            case DATE_LIST -> new ListType(new TypeName(DateScalarTypeMapping.DATE_SCALAR_TYPE_NAME));
            case DOUBLE -> new TypeName("Float");
            case DOUBLE_LIST -> new ListType(new TypeName("Float"));
        };
    }

}
