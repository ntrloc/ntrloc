package org.ntrloc.graph.db.storage;

import org.ntrloc.graph.db.impl.HashingBinaryDataWriter;

import java.io.IOException;
import java.io.InputStream;

/**
 * Stores binary data and returns the hashes of that data.
 */
public interface BinaryStorageAdapter {

    HashingBinaryDataWriter openWriter() throws IOException;

    BinaryContentInfo close(HashingBinaryDataWriter writer) throws IOException;

    InputStream openReader(BinaryContentInfo hash) throws IOException;

    void abandon(HashingBinaryDataWriter writer);

}
