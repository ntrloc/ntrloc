package org.nterloc.graph.db;

import org.janusgraph.core.PropertyKey;
import org.janusgraph.core.schema.SchemaStatus;

public class IndexField {

    private PropertyKey propertyKey;
    private SchemaStatus status;

    public IndexField(PropertyKey propertyKey, SchemaStatus status) {
        this.propertyKey = propertyKey;
        this.status = status;
    }

    public PropertyKey getPropertyKey() {
        return propertyKey;
    }

    public SchemaStatus getStatus() {
        return status;
    }

}
