package org.ntrloc.graph.graphql.mapping.mutation;

import graphql.language.InputObjectTypeDefinition;

import java.util.List;

public interface InputObjectTypeProducer {

    List<InputObjectTypeDefinition> getInputObjectTypeDefinitions();

}
