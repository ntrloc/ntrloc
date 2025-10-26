package org.ntrloc.graph.graphql.mapping.scalars;

import graphql.language.ScalarTypeDefinition;
import org.ntrloc.graph.graphql.mapping.ScalarTypeProducer;

public class DateTimeScalarTypeMapping implements ScalarTypeProducer {

    public static final String DATE_TIME_SCALAR_TYPE_NAME = "DateTime";

    @Override
    public ScalarTypeDefinition getScalarTypeDefinition() {
        return ScalarTypeDefinition.newScalarTypeDefinition()
                .name(DATE_TIME_SCALAR_TYPE_NAME)
                .build();
    }

}
