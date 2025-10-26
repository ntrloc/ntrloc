package org.ntrloc.graph.storage.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.db.storage.BinaryStorageAdapterConfiguration;
import org.ntrloc.graph.db.storage.impl.BlockDeviceBinaryStorageAdapter;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class BlockDeviceBinaryStorageAdapterTest {

    @Test
    @DisplayName("should be able to open and close a binary data writer")
    void testOpenAndClose() throws IOException {
        String tempFilePath = "target";

        BlockDeviceBinaryStorageAdapter adapter = new BlockDeviceBinaryStorageAdapter(new BinaryStorageAdapterConfiguration(tempFilePath, true));
        var writer = adapter.openWriter();
        writer.write(new byte[] { 1, 2, 3});
        var hashes = adapter.close(writer);
        assertNotNull(hashes, "null hashes");
        assertNotNull(hashes.getMd5Hash(), "null MD5 hash");
        assertNotNull(hashes.getSha256Hash(), "null SHA256 hash");
    }

    @Test
    @DisplayName("should be able to read the permanent data previously written")
    void testWriteAndRead() throws IOException {
        String tempFilePath = "target";

        BlockDeviceBinaryStorageAdapter adapter = new BlockDeviceBinaryStorageAdapter(new BinaryStorageAdapterConfiguration(tempFilePath, true));
        var writer = adapter.openWriter();
        byte[] data = new byte[] { 1, 2, 3};
        writer.write(data);
        var hashes = adapter.close(writer);

        InputStream inputStream = adapter.openReader(hashes.getSha256Hash(), hashes.getMd5Hash());
        try {
            assertNotNull(inputStream, "null input stream");

            byte[] inputBytes = new byte[5];
            int bytesRead = inputStream.read(inputBytes);
            assertEquals(3, bytesRead);
            byte[] relevantBytes = Arrays.copyOfRange(inputBytes, 0, 3);
            assertArrayEquals(data, relevantBytes);
        } finally {
            inputStream.close();
        }
    }

}
