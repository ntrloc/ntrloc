package org.ntrloc.gateway.api;

import java.util.List;

public class GraphQlField {

    public String name;
    public String description;
    public List<Object> args;
    public Object type;
    public boolean isDeprecated;
    public String deprecationReason;

}
