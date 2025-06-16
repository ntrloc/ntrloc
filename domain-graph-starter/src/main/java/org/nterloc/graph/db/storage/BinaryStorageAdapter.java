package org.nterloc.graph.db.storage;

import org.nterloc.graph.db.impl.HashingBinaryDataWriter;

import java.io.IOException;
import java.io.InputStream;

/**
 * Stores binary data and returns the hashes of that data.
 */
public interface BinaryStorageAdapter {

    HashingBinaryDataWriter openWriter() throws IOException;

    BinaryHash close(HashingBinaryDataWriter writer) throws IOException;

    InputStream openReader(BinaryHash hash) throws IOException;

    void abandon(HashingBinaryDataWriter writer);

}
