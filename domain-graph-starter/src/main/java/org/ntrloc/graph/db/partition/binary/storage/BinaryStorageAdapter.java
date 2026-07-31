package org.ntrloc.graph.db.partition.binary.storage;

import java.io.IOException;
import java.io.InputStream;

public interface BinaryStorageAdapter {

    HashingBinaryDataWriter openWriter() throws IOException;

    BinaryContentInfo close(HashingBinaryDataWriter writer) throws IOException;

    InputStream openReader(String sha256Hash, String md5Hash) throws IOException;

    void abandon(HashingBinaryDataWriter writer);
}
