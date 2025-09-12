package org.ntrloc.graph.graphql.mapping.scalars;

import graphql.language.ScalarTypeDefinition;
import org.ntrloc.graph.graphql.mapping.ScalarTypeProducer;

public class BinaryScalarTypeMapping implements ScalarTypeProducer {

    public static final String BINARY_SCALAR_TYPE_NAME = "Binary";

    @Override
    public ScalarTypeDefinition getScalarTypeDefinition() {
        return ScalarTypeDefinition.newScalarTypeDefinition()
                .name(BINARY_SCALAR_TYPE_NAME)
                .build();
    }

}
