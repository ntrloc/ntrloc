package org.ntrloc.graph.db;

import org.ntrloc.graph.db.impl.HashingBinaryDataWriter;
import org.ntrloc.graph.db.language.mutation.MutationRequest;
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

    /**
     * Applies a mutation request and returns a transaction that can either be committed or rolled back (2-phase commit).
     * @param mutationRequest a mutation request
     * @return a transaction that is ready to be committed or aborted
     */
    Transaction executeMutation(MutationRequest mutationRequest);

    void prepare(Transaction transaction);

    void commit(Transaction transaction);

    void abort(Transaction transaction);

    QueryResult executeQuery(Query query);

}
