package org.ntrloc.graph.graphql.mapping.selector;

import org.ntrloc.graph.db.language.selectors.Selector;
import org.ntrloc.graph.graphql.mapping.InputObjectTypeProducer;

import java.util.Map;

public interface SelectorInputObjectTypeMapping extends InputObjectTypeProducer {

    String getGraphQlTypeName();

    Selector parseSelector(Map<String, Object> objectValue);

}
