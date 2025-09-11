package org.ntrloc.graph.graphql.mapping.query;

import graphql.language.Field;
import org.ntrloc.graph.db.language.projection.LinkProjectionSpec;
import org.ntrloc.graph.graphql.mapping.ObjectTypeProducer;

import java.util.Map;

public abstract class ItemLinkObjectTypeMapping implements ObjectTypeProducer {
    protected String propertiesFieldName = "properties";
    protected ItemObjectTypeMapping relatedItemObjectTypeMapping;
    abstract String getGraphQlTypeName();

    abstract void registerRelatedItemType(Map<String, ItemObjectTypeMapping> mappings);
    abstract LinkProjectionSpec parseLinkProjectionSpec(Field field);
}
