package org.ntrloc.graph.graphql.mapping;

import graphql.language.ScalarTypeDefinition;

public interface ScalarTypeProducer {

    ScalarTypeDefinition getScalarTypeDefinition();

}
