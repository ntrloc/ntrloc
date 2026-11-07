package org.ntrloc.graph.db.partition.schema.event;

// Implemented by beans that need to react to schema changes. Actual registration happens via
// Spring's @EventListener on the implementing method (see RegisterStore) rather than
// SchemaManager holding a list of listeners directly, since SchemaManager's own dependents (like
// RegisterStore) already depend on SchemaManager -- a direct listener list would be
// a circular constructor dependency.
public interface SchemaChangeListener {

    void onSchemaChange(SchemaChangeEvent event);
}
