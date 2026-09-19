package org.ntrloc.graph.db.partition.binary.storage;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

// HashingBinaryDataWriter.close() has no way to produce a hash except by reading the two
// MessageDigest fields wrapped by DigestOutputStream in the constructor -- so a correct result here
// is only possible if every write() actually flowed through those digests. This pins that down
// directly: an independent MessageDigest, fed the exact bytes captured at the destination stream,
// must agree with what close() reports, across many separate write() calls the way the real upload
// path calls it once per network chunk.
class HashingBinaryDataWriterTest {

    private static String independentHashHex(String algorithm, byte[]... chunks) throws Exception {
        MessageDigest digest = MessageDigest.getInstance(algorithm);
        for (byte[] chunk : chunks) {
            digest.update(chunk);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    @Test
    void close_reportsHashesThatMatchAnIndependentDigestOfWhatWasActuallyWritten() throws Exception {
        byte[][] chunks = {
                "first chunk ".getBytes(),
                "second chunk, a bit longer this time ".getBytes(),
                "third".getBytes(),
        };
        var destination = new ByteArrayOutputStream();
        var writer = new HashingBinaryDataWriter("test-id", destination);

        for (byte[] chunk : chunks) {
            writer.write(chunk);
        }
        BinaryContentInfo info = writer.close();

        // the destination is what actually got "stored" -- assert against its captured bytes, not
        // the original chunk arrays, so a bug that mangled data on the way to the stream would show
        // up as a hash mismatch here rather than trivially matching itself.
        byte[] persisted = destination.toByteArray();
        assertThat(info.getSha256Hash()).isEqualTo(independentHashHex("SHA-256", persisted));
        assertThat(info.getMd5Hash()).isEqualTo(independentHashHex("MD5", persisted));
    }

    @Test
    void close_forManySmallChunks_stillMatchesTheDigestOfTheWholeStream() throws Exception {
        // mirrors the real upload path: ~8KB chunks arriving one at a time over many write() calls,
        // not one big buffer -- if digest state didn't carry correctly across separate write() calls,
        // this is the shape of bug that would surface.
        var random = new Random(7);
        var destination = new ByteArrayOutputStream();
        var writer = new HashingBinaryDataWriter("test-id", destination);

        int chunkCount = 500;
        int chunkSize = 8192;
        for (int i = 0; i < chunkCount; i++) {
            byte[] chunk = new byte[chunkSize];
            random.nextBytes(chunk);
            writer.write(chunk);
        }
        BinaryContentInfo info = writer.close();

        byte[] persisted = destination.toByteArray();
        assertThat(persisted).hasSize(chunkCount * chunkSize);
        assertThat(info.getSha256Hash()).isEqualTo(independentHashHex("SHA-256", persisted));
        assertThat(info.getMd5Hash()).isEqualTo(independentHashHex("MD5", persisted));
    }

    @Test
    void close_forDifferentContent_producesDifferentHashes() throws Exception {
        // guards against a vacuously-passing digest (e.g. one hashing something constant, or
        // hashing only the first write() call) rather than the real, full, varying content.
        var firstDestination = new ByteArrayOutputStream();
        var firstWriter = new HashingBinaryDataWriter("id-a", firstDestination);
        firstWriter.write("content A".getBytes());
        BinaryContentInfo first = firstWriter.close();

        var secondDestination = new ByteArrayOutputStream();
        var secondWriter = new HashingBinaryDataWriter("id-b", secondDestination);
        secondWriter.write("content B, which differs".getBytes());
        BinaryContentInfo second = secondWriter.close();

        assertThat(first.getSha256Hash()).isNotEqualTo(second.getSha256Hash());
        assertThat(first.getMd5Hash()).isNotEqualTo(second.getMd5Hash());
    }
}
