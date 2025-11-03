package org.ntrloc.graph.graphql.mapping.query;

import graphql.language.Field;
import org.ntrloc.graph.db.language.projection.LinkProjection;
import org.ntrloc.graph.db.language.projection.LinkProjectionSpec;
import org.ntrloc.graph.graphql.mapping.ObjectTypeProducer;

import java.util.List;
import java.util.Map;

public abstract class ItemLinkObjectTypeMapping implements ObjectTypeProducer {
    protected String propertiesFieldName = "properties";
    protected ItemObjectTypeMapping relatedItemObjectTypeMapping;
    abstract String getGraphQlTypeName();
    abstract String getLinkGraphQlFieldName();

    abstract void registerRelatedItemType(Map<String, ItemObjectTypeMapping> mappings);
    abstract LinkProjectionSpec parseLinkProjectionSpec(Field field);

    abstract List<LinkProjection> translateLinkProjections(List<LinkProjection> projections);
}
