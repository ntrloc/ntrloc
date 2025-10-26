package org.ntrloc.graph.graphql.mapping;

import graphql.language.ListType;
import graphql.language.Type;
import graphql.language.TypeName;
import org.ntrloc.graph.db.schema.PropertyDefinition;
import org.ntrloc.graph.graphql.mapping.query.GlobalOutputTypeFactory;
import org.ntrloc.graph.graphql.mapping.scalars.BinaryScalarTypeMapping;
import org.ntrloc.graph.graphql.mapping.scalars.DateScalarTypeMapping;
import org.ntrloc.graph.graphql.mapping.scalars.DateTimeScalarTypeMapping;

public class PropertyTypeGraphQLMapping {

    public enum InputOutputType {
        INPUT,
        OUTPUT
    }

    public static Type mapPropertyDefinition(PropertyDefinition propertyDefinition, InputOutputType inputOutputType) {
        return switch (propertyDefinition.getType()) {
            case BINARY -> switch (inputOutputType) {
                case INPUT -> new TypeName(BinaryScalarTypeMapping.BINARY_SCALAR_TYPE_NAME);
                case OUTPUT -> new TypeName(GlobalOutputTypeFactory.BINARY_OBJECT_TYPE_NAME);
            };
            case BOOLEAN -> new TypeName("Boolean");
            case BOOLEAN_LIST -> new ListType(new TypeName("Boolean"));
            case STRING -> new TypeName("String");
            case STRING_LIST -> new ListType(new TypeName("String"));
            case INT -> new TypeName("Int");
            case INT_LIST -> new ListType(new TypeName("Int"));
            case DATE -> new TypeName(DateScalarTypeMapping.DATE_SCALAR_TYPE_NAME);
            case DATE_LIST -> new ListType(new TypeName(DateScalarTypeMapping.DATE_SCALAR_TYPE_NAME));
            case DATETIME -> new TypeName(DateTimeScalarTypeMapping.DATE_TIME_SCALAR_TYPE_NAME);
            case DATETIME_LIST -> new ListType(new TypeName(DateTimeScalarTypeMapping.DATE_TIME_SCALAR_TYPE_NAME));
            case DOUBLE -> new TypeName("Float");
            case DOUBLE_LIST -> new ListType(new TypeName("Float"));
        };
    }

}
