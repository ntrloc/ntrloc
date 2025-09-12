package org.ntrloc.graph.graphql.mapping.scalars;

import graphql.language.ScalarTypeDefinition;
import org.ntrloc.graph.graphql.mapping.ScalarTypeProducer;

public class DateScalarTypeMapping implements ScalarTypeProducer {

    public static final String DATE_SCALAR_TYPE_NAME = "Date";

    @Override
    public ScalarTypeDefinition getScalarTypeDefinition() {
        return ScalarTypeDefinition.newScalarTypeDefinition()
                .name(DATE_SCALAR_TYPE_NAME)
                .build();
    }

}
