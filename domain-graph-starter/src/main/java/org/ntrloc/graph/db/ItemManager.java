package org.ntrloc.graph.db;

import org.ntrloc.graph.db.impl.HashingBinaryDataWriter;
import org.ntrloc.graph.db.language.mutation.MutationRequest;
import org.ntrloc.graph.db.language.mutation.MutationResponse;
import org.ntrloc.graph.db.language.projection.ItemProjection;
import org.ntrloc.graph.db.language.projection.SelectableItemProjectionSpec;
import org.ntrloc.graph.db.storage.BinaryContentInfoWithStream;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Optional;

public interface ItemManager {

    void resetGraph();

    /**
     * Opens a writer that can be used to add binary data to the graph.
     */
    HashingBinaryDataWriter openWriter() throws IOException;

    /**
     * Commits binary data to the graph and returns its unique ID.
     */
    String commitBinary(HashingBinaryDataWriter writer) throws IOException;

    Optional<BinaryContentInfoWithStream> getBinaryStream(String uuid) throws IOException;

    /**
     * Discards the binary data written by the given writer.
     * @param writer
     */
    void abandonBinary(HashingBinaryDataWriter writer);

    MutationResponse executeMutation(MutationRequest mutationRequest);

    List<ItemProjection> executeProjection(SelectableItemProjectionSpec query);

    List<ItemProjection> executeProjection(SelectableItemProjectionSpec query, URI binaryDownloadUri);

}
