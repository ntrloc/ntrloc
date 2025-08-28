package org.ntrloc.graph.graphql.mapping;

import graphql.language.InputObjectTypeDefinition;

import java.util.List;

public interface InputTypeProducer {

    List<InputObjectTypeDefinition> getInputObjectTypeDefinitions();

}
