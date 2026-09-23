package org.ntrloc.graph.db.partition.binary;

import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import reactor.core.publisher.Flux;

import java.io.IOException;
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
// trip. BinaryContentEvent.Created publishing -- once on a fresh insert, not at all on a dedup hit --
// is instead covered by BinaryContentEventPublishingIntegrationTest, in this same package: a plain,
// manually-registered listener bean, not @RecordApplicationEvents (which showed unexplained
// cross-test pollution when tried inside this file's own 15-test class) and not job-table row counts
// (which race Flowable's async job executor for any claim spanning two separate actions -- see
// BinaryMetadataExtractionIntegrationTest, in the process package, for that pitfall in detail).
class BinaryPartitionManagerImplIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private BinaryPartitionManager binaryPartitionManager;

    @Autowired
    private JdbcClient jdbcClient;

    private static String sha256Hex(byte[] content) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(content));
    }

    // Splits into 8192-byte DataBuffer chunks (matching a typical network read size) rather than
    // handing store() the whole byte array as one buffer, so tests exercise the same multi-chunk
    // path production traffic does.
    private static Flux<DataBuffer> bufferFlux(byte[] content) {
        var factory = new DefaultDataBufferFactory();
        var chunks = new java.util.ArrayList<DataBuffer>();
        int chunkSize = 8192;
        for (int offset = 0; offset < content.length; offset += chunkSize) {
            int len = Math.min(chunkSize, content.length - offset);
            chunks.add(factory.wrap(java.util.Arrays.copyOfRange(content, offset, offset + len)));
        }
        if (chunks.isEmpty()) {
            chunks.add(factory.wrap(new byte[0]));
        }
        return Flux.fromIterable(chunks);
    }

    @Test
    void store_thenRetrieve_returnsTheSameBytes() throws Exception {
        byte[] content = ("hello world " + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);

        UUID id = binaryPartitionManager.store(bufferFlux(content)).block();

        var retrieved = binaryPartitionManager.retrieve(id).orElseThrow();
        assertThat(retrieved.stream().readAllBytes()).isEqualTo(content);
        assertThat(retrieved.info().length()).isEqualTo(content.length);
        assertThat(retrieved.info().sha256()).isEqualTo(sha256Hex(content));
    }

    @Test
    void store_forContentLargerThanOneBufferChunk_isWrittenAndReadBackIntact() throws Exception {
        byte[] content = new byte[8192 * 3 + 100];
        new java.util.Random(42).nextBytes(content);

        UUID id = binaryPartitionManager.store(bufferFlux(content)).block();

        var retrieved = binaryPartitionManager.retrieve(id).orElseThrow();
        assertThat(retrieved.stream().readAllBytes()).isEqualTo(content);
    }

    @Test
    void store_forIdenticalContentTwice_returnsTheSameId_contentIsDeduplicatedByHash() throws Exception {
        byte[] content = ("duplicate content " + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);

        UUID firstId = binaryPartitionManager.store(bufferFlux(content)).block();
        UUID secondId = binaryPartitionManager.store(bufferFlux(content)).block();

        assertThat(secondId).isEqualTo(firstId);
    }

    @Test
    void store_whenTheSourceStreamErrors_abandonsTheWriterAndPropagatesTheException() {
        Flux<DataBuffer> failingContent = Flux.error(new IOException("simulated read failure"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> binaryPartitionManager.store(failingContent).block())
                .hasCauseInstanceOf(IOException.class)
                .cause().hasMessageContaining("simulated read failure");
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

        UUID id = binaryPartitionManager.store(bufferFlux(content)).block();

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
        UUID id1 = binaryPartitionManager.store(bufferFlux(
                ("content-a-" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8))).block();
        UUID id2 = binaryPartitionManager.store(bufferFlux(
                ("content-b-" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8))).block();
        UUID unknownId = UUID.randomUUID();

        Map<UUID, BinaryPropertyObject> found = binaryPartitionManager.getBinaryProperties(Set.of(id1, id2, unknownId));

        assertThat(found).containsOnlyKeys(id1, id2);
    }

    @Test
    void getBinaryProperties_acceptsAListDirectlyWithoutCopying() throws Exception {
        UUID id = binaryPartitionManager.store(bufferFlux(
                ("content-" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8))).block();

        Map<UUID, BinaryPropertyObject> found = binaryPartitionManager.getBinaryProperties(List.of(id));

        assertThat(found).containsOnlyKeys(id);
    }

    // parseMetadata()'s catch block has no real write path that reaches it -- store() always writes
    // a well-shaped object (see metadataJson()), so the only way to exercise the parse-failure branch
    // is to put a wrong-shape-but-syntactically-valid JSON value there directly, after the fact. A
    // JSON array, not literally invalid text: Postgres's jsonb column type rejects malformed JSON
    // outright at the SQL level, so "invalid JSON" here has to mean "valid JSON, wrong shape for
    // Map.class" instead. Exists as a defensive fallback, not a documented possibility.
    @Test
    void getBinaryProperty_whenMetadataIsNotAJsonObject_returnsNullMetadataInsteadOfThrowing() throws Exception {
        UUID id = binaryPartitionManager.store(bufferFlux(
                ("content-" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8))).block();
        jdbcClient.sql("UPDATE binary_content SET metadata = '[1, 2, 3]'::jsonb WHERE id = :id")
                .param("id", id)
                .update();

        var property = binaryPartitionManager.getBinaryProperty(id).orElseThrow();

        assertThat(property.metadata()).isNull();
    }

    // metadataJson() mirrors the real columns into metadata so filter/sort/facet resolution can read
    // any intrinsic binary fact through one uniform mechanism (see the DDL's own comment on
    // binary_content, and design-notes section 8). This proves the mirror actually lands in the shape
    // that resolution will expect: length, mimeType, and a nested hashes object.
    @Test
    void store_mirrorsLengthMimeTypeAndHashesIntoMetadata() throws Exception {
        byte[] content = ("metadata shape test " + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);

        UUID id = binaryPartitionManager.store(bufferFlux(content)).block();

        var property = binaryPartitionManager.getBinaryProperty(id).orElseThrow();
        assertThat(property.metadata()).isNotNull();
        assertThat(((Number) property.metadata().get("length")).longValue()).isEqualTo(content.length);
        assertThat(property.metadata()).containsKey("mimeType");
        @SuppressWarnings("unchecked")
        var hashes = (Map<String, Object>) property.metadata().get("hashes");
        assertThat(hashes).containsEntry("sha256", sha256Hex(content));
        assertThat(hashes).containsEntry("md5", property.md5());
    }

    // A real upload can't be made to produce two matching hashes with a different length -- that
    // would require an actual hash collision. This drives the constraint directly instead, proving
    // the compound UNIQUE(sha256, md5, length) is genuinely three-column: a length mismatch is a
    // distinct row (the case the constraint exists to catch -- see the DDL's own comment), while an
    // exact repeat of all three is still deduplicated exactly as a single-column sha256 key would be.
    @Test
    void insertingBinaryContent_deduplicatesOnlyWhenSha256AndMd5AndLengthAllMatch() {
        String sha256 = "same-hash-" + UUID.randomUUID();
        String md5 = "same-md5-" + UUID.randomUUID();

        UUID firstId = insertBinaryContentRow(sha256, md5, 100);
        UUID differentLengthId = insertBinaryContentRow(sha256, md5, 200);
        UUID repeatOfFirstId = insertBinaryContentRow(sha256, md5, 100);

        assertThat(differentLengthId).isNotEqualTo(firstId);
        assertThat(repeatOfFirstId).isEqualTo(firstId);
    }

    private UUID insertBinaryContentRow(String sha256, String md5, long length) {
        return jdbcClient.sql("""
                INSERT INTO binary_content (sha256, md5, mime_type, length, metadata)
                VALUES (:sha256, :md5, 'text/plain', :length, '{}'::jsonb)
                ON CONFLICT (sha256, md5, length) DO UPDATE SET sha256 = EXCLUDED.sha256
                RETURNING id
                """)
                .param("sha256", sha256)
                .param("md5", md5)
                .param("length", length)
                .query(UUID.class)
                .single();
    }
}
