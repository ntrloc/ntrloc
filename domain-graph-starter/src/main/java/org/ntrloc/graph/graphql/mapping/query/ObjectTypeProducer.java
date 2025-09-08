package org.ntrloc.graph.graphql.mapping.query;

import graphql.language.ObjectTypeDefinition;

import java.util.List;

public interface ObjectTypeProducer {

    List<ObjectTypeDefinition> getObjectTypeDefinitions();

}
