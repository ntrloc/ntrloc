package org.nterloc.graph.db.schema;

@FunctionalInterface
public interface SchemaChangeReaction {

    void onSchemaChange();

}
