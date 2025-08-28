package org.ntrloc.graph.graphql.mapping.input;

import graphql.language.InputObjectTypeDefinition;

import java.util.List;

public interface InputObjectTypeProducer {

    List<InputObjectTypeDefinition> getInputObjectTypeDefinitions();

}
