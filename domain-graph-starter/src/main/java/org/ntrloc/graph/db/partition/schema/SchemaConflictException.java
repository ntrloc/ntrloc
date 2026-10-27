package org.ntrloc.graph.db.partition.schema;

// The schema no longer matches what a mutation batch expected it to be (see
// ExpectItemDefinitionMutation) -- someone else changed it first. Reported as 409, not 400: the
// request was valid, it's just out of date.
public class SchemaConflictException extends RuntimeException {

    public SchemaConflictException(String message) {
        super(message);
    }
}
