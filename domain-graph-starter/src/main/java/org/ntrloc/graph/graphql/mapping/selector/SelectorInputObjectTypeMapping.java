package org.ntrloc.graph.graphql.mapping.selector;

import graphql.language.ObjectValue;
import org.ntrloc.graph.db.language.selectors.Selector;
import org.ntrloc.graph.graphql.mapping.InputObjectTypeProducer;

public interface SelectorInputObjectTypeMapping extends InputObjectTypeProducer {

    String getGraphQlTypeName();

    Selector parseSelector(ObjectValue objectValue);

}
