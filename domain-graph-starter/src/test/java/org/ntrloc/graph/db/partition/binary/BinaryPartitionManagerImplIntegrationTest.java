package org.ntrloc.graph.db.partition.binary;

import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Covers BinaryPartitionManagerImpl.store()/retrieve()/getBinaryProperty(ies)() end-to-end against
// the real block-device storage adapter configured for tests (see application.yml's
// binary.storage.block.location) -- store() and the storage adapter are tested together
// deliberately, since store()'s own correctness (computing the right hash, writing through the
// adapter, then keying the DB row on that hash) can't be verified without a real adapter round
// trip.
class BinaryPartitionManagerImplIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private BinaryPartitionManager binaryPartitionManager;

    @Autowired
    private JdbcClient jdbcClient;

    private static String sha256Hex(byte[] content) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(content));
    }

    @Test
    void store_thenRetrieve_returnsTheSameBytes() throws Exception {
        byte[] content = ("hello world " + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);

        UUID id = binaryPartitionManager.store(new ByteArrayInputStream(content));

        var retrieved = binaryPartitionManager.retrieve(id).orElseThrow();
        assertThat(retrieved.stream().readAllBytes()).isEqualTo(content);
        assertThat(retrieved.info().length()).isEqualTo(content.length);
        assertThat(retrieved.info().sha256()).isEqualTo(sha256Hex(content));
    }

    @Test
    void store_forContentLargerThanOneBufferChunk_isWrittenAndReadBackIntact() throws Exception {
        byte[] content = new byte[8192 * 3 + 100];
        new java.util.Random(42).nextBytes(content);

        UUID id = binaryPartitionManager.store(new ByteArrayInputStream(content));

        var retrieved = binaryPartitionManager.retrieve(id).orElseThrow();
        assertThat(retrieved.stream().readAllBytes()).isEqualTo(content);
    }

    @Test
    void store_forIdenticalContentTwice_returnsTheSameId_contentIsDeduplicatedByHash() throws Exception {
        byte[] content = ("duplicate content " + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);

        UUID firstId = binaryPartitionManager.store(new ByteArrayInputStream(content));
        UUID secondId = binaryPartitionManager.store(new ByteArrayInputStream(content));

        assertThat(secondId).isEqualTo(firstId);
    }

    @Test
    void store_whenTheStreamThrows_abandonsTheWriterAndPropagatesTheException() {
        InputStream failingStream = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("simulated read failure");
            }
        };

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> binaryPartitionManager.store(failingStream))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("simulated read failure");
    }

    @Test
    void retrieve_forAnUnknownId_returnsEmpty() throws Exception {
        assertThat(binaryPartitionManager.retrieve(UUID.randomUUID())).isEmpty();
    }

    @Test
    void getBinaryProperty_forAnUnknownId_returnsEmpty() {
        assertThat(binaryPartitionManager.getBinaryProperty(UUID.randomUUID())).isEmpty();
    }

    @Test
    void getBinaryProperty_returnsTheStoredMetadata() throws Exception {
        byte[] content = ("metadata test " + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);

        UUID id = binaryPartitionManager.store(new ByteArrayInputStream(content));

        var property = binaryPartitionManager.getBinaryProperty(id).orElseThrow();
        assertThat(property.id()).isEqualTo(id);
        assertThat(property.length()).isEqualTo(content.length);
        assertThat(property.sha256()).isEqualTo(sha256Hex(content));
    }

    @Test
    void getBinaryProperties_forAnEmptyCollection_returnsAnEmptyMap() {
        assertThat(binaryPartitionManager.getBinaryProperties(Set.of())).isEmpty();
    }

    @Test
    void getBinaryProperties_returnsEveryRequestedIdThatExists() throws Exception {
        UUID id1 = binaryPartitionManager.store(new ByteArrayInputStream(
                ("content-a-" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8)));
        UUID id2 = binaryPartitionManager.store(new ByteArrayInputStream(
                ("content-b-" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8)));
        UUID unknownId = UUID.randomUUID();

        Map<UUID, BinaryPropertyObject> found = binaryPartitionManager.getBinaryProperties(Set.of(id1, id2, unknownId));

        assertThat(found).containsOnlyKeys(id1, id2);
    }

    @Test
    void getBinaryProperties_acceptsAListDirectlyWithoutCopying() throws Exception {
        UUID id = binaryPartitionManager.store(new ByteArrayInputStream(
                ("content-" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8)));

        Map<UUID, BinaryPropertyObject> found = binaryPartitionManager.getBinaryProperties(List.of(id));

        assertThat(found).containsOnlyKeys(id);
    }

    // parseMetadata()'s catch block is unreachable through any real write path -- store() never
    // sets the metadata column at all (see the class's own comment on why: nothing in this app
    // writes it), so the only way to exercise the parse-failure branch is to put a
    // wrong-shape-but-syntactically-valid JSON value there directly. A JSON array, not literally
    // invalid text: Postgres's jsonb column type rejects malformed JSON outright at the SQL level,
    // so "invalid JSON" here has to mean "valid JSON, wrong shape for Map.class" instead.
    @Test
    void getBinaryProperty_whenMetadataIsNotAJsonObject_returnsNullMetadataInsteadOfThrowing() throws Exception {
        UUID id = binaryPartitionManager.store(new ByteArrayInputStream(
                ("content-" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8)));
        jdbcClient.sql("UPDATE binary_content SET metadata = '[1, 2, 3]'::jsonb WHERE id = :id")
                .param("id", id)
                .update();

        var property = binaryPartitionManager.getBinaryProperty(id).orElseThrow();

        assertThat(property.metadata()).isNull();
    }
}
