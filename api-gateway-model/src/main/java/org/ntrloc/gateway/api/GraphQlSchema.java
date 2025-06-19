package org.ntrloc.gateway.api;

import java.util.List;
import java.util.StringJoiner;

public class GraphQlSchema {

    public Object queryType;
    public Object mutationType;
    public Object subscriptionType;

    public List<GraphQlType> types;
    public List<GraphQlDirective> directives;

    @Override
    public String toString() {
        return new StringJoiner(", ", GraphQlSchema.class.getSimpleName() + "[", "]")
                .add("queryType=" + queryType)
                .add("mutationType=" + mutationType)
                .add("subscriptionType=" + subscriptionType)
                .add("types=" + types)
                .toString();
    }
}
