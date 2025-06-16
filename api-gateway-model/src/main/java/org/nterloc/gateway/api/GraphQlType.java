package org.nterloc.gateway.api;

import java.util.List;
import java.util.StringJoiner;

public class GraphQlType {

    public String kind;
    public String name;
    public String description;
    public List<GraphQlField> fields;
    public List<Object> inputFields;
    public List<Object> interfaces;
    public List<Object> enumValues;
    public List<Object> possibleTypes;

    @Override
    public String toString() {
        return new StringJoiner(", ", GraphQlType.class.getSimpleName() + "[", "]")
                .add("kind='" + kind + "'")
                .add("name='" + name + "'")
                .add("description='" + description + "'")
                .add("fields=" + fields)
                .toString();
    }

}
