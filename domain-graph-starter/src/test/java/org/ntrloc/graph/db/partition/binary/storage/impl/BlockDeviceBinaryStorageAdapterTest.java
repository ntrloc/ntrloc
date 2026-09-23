package org.ntrloc.graph.db.partition.binary.storage.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.ntrloc.graph.db.partition.binary.storage.BinaryStorageAdapterConfiguration;

import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

// permanentRelativePath is the storage layer's own notion of binary identity -- it has to key on all
// three of sha256, md5 and length, matching binary_content's compound UNIQUE(sha256, md5, length)
// constraint (see that table's own DDL comment), so the DB and the filesystem can't disagree about
// what counts as "the same content." A real upload can't be made to exercise the "matching hashes,
// different length" case -- that would require an actual hash collision -- so this asserts the
// path-construction logic directly instead, package-private for exactly this reason.
class BlockDeviceBinaryStorageAdapterTest {

    private BlockDeviceBinaryStorageAdapter adapter(Path tempDir) throws IOException {
        return new BlockDeviceBinaryStorageAdapter(new BinaryStorageAdapterConfiguration(tempDir.toString(), true));
    }

    @Test
    void permanentRelativePath_differsWhenOnlyLengthDiffers(@TempDir Path tempDir) throws IOException {
        var adapter = adapter(tempDir);

        Path first = adapter.permanentRelativePath("abcd1234", "md5hash", 100);
        Path second = adapter.permanentRelativePath("abcd1234", "md5hash", 200);

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void permanentRelativePath_isStableForTheSameInputs(@TempDir Path tempDir) throws IOException {
        var adapter = adapter(tempDir);

        Path first = adapter.permanentRelativePath("abcd1234", "md5hash", 100);
        Path second = adapter.permanentRelativePath("abcd1234", "md5hash", 100);

        assertThat(first).isEqualTo(second);
    }
}
