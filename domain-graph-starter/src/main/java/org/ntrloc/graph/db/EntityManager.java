package org.ntrloc.graph.db;

import org.ntrloc.graph.db.impl.HashingBinaryDataWriter;
import org.ntrloc.graph.db.language.mutation.MutationRequest;
import org.ntrloc.graph.db.language.mutation.MutationResponse;
import org.ntrloc.graph.db.language.query.Query;
import org.ntrloc.graph.db.language.query.QueryResult;

import java.io.IOException;

public interface EntityManager {

    void resetGraph();

    /**
     * Opens a writer that can be used to add binary data to the graph.
     */
    HashingBinaryDataWriter openWriter() throws IOException;

    /**
     * Commits binary data to the graph and returns its unique ID.
     */
    String commitBinary(HashingBinaryDataWriter writer) throws IOException;

    /**
     * Discards the binary data written by the given writer.
     * @param writer
     */
    void abandonBinary(HashingBinaryDataWriter writer);

    MutationResponse executeMutation(MutationRequest mutationRequest);

    QueryResult executeQuery(Query query);

}
