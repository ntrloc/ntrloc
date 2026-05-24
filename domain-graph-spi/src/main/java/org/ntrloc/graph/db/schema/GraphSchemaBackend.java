package org.ntrloc.graph.db.schema;

import java.util.Map;

public interface GraphSchemaBackend {

    void ensureGlobalSchema() throws InterruptedException;

    void createItemTypeSchema(String itemTypeUid, Map<String, PropertyType> propertiesByUid) throws InterruptedException;
}
