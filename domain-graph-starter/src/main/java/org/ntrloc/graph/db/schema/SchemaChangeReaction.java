package org.ntrloc.graph.db.schema;

@FunctionalInterface
public interface SchemaChangeReaction {

    void onSchemaChange();

}
