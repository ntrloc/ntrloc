package org.ntrloc.graph.db.partition.schema.event;

// Implemented by beans that need to react to schema changes. Actual registration happens via
// Spring's @EventListener on the implementing method (see RegisterStore) rather than
// SchemaService holding a list of listeners directly, since SchemaService's own dependents (like
// RegisterStore) already depend on SchemaService -- a direct listener list would be
// a circular constructor dependency.
public interface SchemaChangeListener {

    void onSchemaChange(SchemaChangeEvent event);
}
