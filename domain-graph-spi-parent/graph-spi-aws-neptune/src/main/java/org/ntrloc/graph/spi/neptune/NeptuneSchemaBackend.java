package org.ntrloc.graph.spi.neptune;

import org.ntrloc.graph.db.schema.GraphSchemaBackend;
import org.ntrloc.graph.db.schema.PropertyType;

import java.util.Map;

public class NeptuneSchemaBackend implements GraphSchemaBackend {

    @Override
    public void ensureGlobalSchema() throws InterruptedException {

    }

    @Override
    public void createItemTypeSchema(String itemTypeUid, Map<String, PropertyType> propertiesByUid) throws InterruptedException {

    }

}
