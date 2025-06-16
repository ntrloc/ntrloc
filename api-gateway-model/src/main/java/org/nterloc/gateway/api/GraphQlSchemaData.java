package org.nterloc.gateway.api;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.StringJoiner;

public class GraphQlSchemaData {

    @JsonProperty("__schema")
    public GraphQlSchema schema;

    @Override
    public String toString() {
        return new StringJoiner(", ", GraphQlSchemaData.class.getSimpleName() + "[", "]")
                .add("schema=" + schema)
                .toString();
    }
}
