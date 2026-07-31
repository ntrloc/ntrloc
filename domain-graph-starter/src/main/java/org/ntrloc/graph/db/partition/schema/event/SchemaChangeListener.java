package org.ntrloc.graph.db.partition.schema.event;

// Implemented by beans that need to react to schema changes. Actual registration happens via
// Spring's @EventListener on the implementing method (see RegisterPartitionManager) rather than
// SchemaManager holding a list of listeners directly, since SchemaManager's own dependents (like
// RegisterPartitionManager) already depend on SchemaManager -- a direct listener list would be
// a circular constructor dependency.
public interface SchemaChangeListener {

    void onSchemaChange(SchemaChangeEvent event);
}
