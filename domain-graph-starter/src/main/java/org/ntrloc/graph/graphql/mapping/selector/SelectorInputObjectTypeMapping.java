package org.ntrloc.graph.graphql.mapping.selector;

import org.ntrloc.graph.graphql.mapping.mutation.InputObjectTypeProducer;

public interface SelectorInputObjectTypeMapping extends InputObjectTypeProducer {

    String getGraphQlTypeName();

}
