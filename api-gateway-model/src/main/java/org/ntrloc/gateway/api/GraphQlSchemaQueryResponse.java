package org.ntrloc.gateway.api;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.StringJoiner;

public class GraphQlSchemaQueryResponse {

    @JsonProperty("data")
    public GraphQlSchemaData schemaData;

    @Override
    public String toString() {
        return new StringJoiner(", ", GraphQlSchemaQueryResponse.class.getSimpleName() + "[", "]")
                .add("schemaData=" + schemaData)
                .toString();
    }
}
