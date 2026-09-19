package org.ntrloc.graph.db.partition.binary;

import org.ntrloc.graph.db.partition.binary.storage.BinaryContentInfo;
import org.ntrloc.graph.db.partition.binary.storage.BinaryStorageAdapter;
import org.ntrloc.graph.db.partition.binary.storage.HashingBinaryDataWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@DependsOnDatabaseInitialization
public class BinaryPartitionManagerImpl implements BinaryPartitionManager {

    private static final Logger LOG = LoggerFactory.getLogger(BinaryPartitionManagerImpl.class);

    private final JdbcClient jdbcClient;
    private final BinaryStorageAdapter storageAdapter;
    private final ObjectMapper objectMapper;

    public BinaryPartitionManagerImpl(JdbcClient jdbcClient, BinaryStorageAdapter storageAdapter, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.storageAdapter = storageAdapter;
        this.objectMapper = objectMapper;
    }

    // Consumes the upload as a reactive chunk stream rather than a blocking InputStream: a prior
    // InputStream-bridging approach (pulling chunks via Flux.toIterable) let a slow consumer race
    // ahead of backpressure and OOM on large uploads. Writing each chunk synchronously inside
    // doOnNext, downstream of a single-item-prefetch publishOn, ties consumption of the next network
    // chunk directly to this write finishing, which is what actually keeps memory bounded regardless
    // of upload size.
    @Override
    public Mono<UUID> store(Flux<DataBuffer> content) {
        return Mono.fromCallable(storageAdapter::openWriter)
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(writer -> content
                        .publishOn(Schedulers.boundedElastic(), 1)
                        .doOnNext(buffer -> writeChunk(writer, buffer))
                        .then(Mono.fromCallable(() -> storageAdapter.close(writer)))
                        .flatMap(this::insert)
                        .onErrorResume(e -> Mono.fromRunnable(() -> storageAdapter.abandon(writer))
                                .subscribeOn(Schedulers.boundedElastic())
                                .then(Mono.error(e))));
    }

    private void writeChunk(HashingBinaryDataWriter writer, DataBuffer buffer) {
        try {
            byte[] bytes = new byte[buffer.readableByteCount()];
            buffer.read(bytes);
            writer.write(bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            DataBufferUtils.release(buffer);
        }
    }

    private Mono<UUID> insert(BinaryContentInfo info) {
        return Mono.fromCallable(() -> jdbcClient.sql("""
                        INSERT INTO binary_content (sha256, md5, mime_type, length)
                        VALUES (:sha256, :md5, :mimeType, :length)
                        ON CONFLICT (sha256) DO UPDATE SET sha256 = EXCLUDED.sha256
                        RETURNING id
                        """)
                        .param("sha256", info.getSha256Hash())
                        .param("md5", info.getMd5Hash())
                        .param("mimeType", info.getMimeType())
                        .param("length", info.getLength())
                        .query(UUID.class)
                        .single())
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Optional<BinaryContentStream> retrieve(UUID id) throws IOException {
        var info = getBinaryProperty(id);
        if (info.isEmpty()) return Optional.empty();
        var obj = info.get();
        InputStream stream = storageAdapter.openReader(obj.sha256(), obj.md5());
        return Optional.of(new BinaryContentStream(obj, stream));
    }

    @Override
    public Optional<BinaryPropertyObject> getBinaryProperty(UUID id) {
        return jdbcClient.sql("""
                SELECT id, sha256, md5, mime_type, length, metadata::text
                FROM binary_content WHERE id = :id
                """)
                .param("id", id)
                .query((rs, n) -> mapRow(rs))
                .optional();
    }

    @Override
    public Map<UUID, BinaryPropertyObject> getBinaryProperties(Collection<UUID> ids) {
        if (ids.isEmpty()) return Map.of();
        List<UUID> idList = ids instanceof List ? (List<UUID>) ids : List.copyOf(ids);
        return jdbcClient.sql("""
                SELECT id, sha256, md5, mime_type, length, metadata::text
                FROM binary_content WHERE id IN (:ids)
                """)
                .param("ids", idList)
                .query((rs, n) -> mapRow(rs))
                .list()
                .stream()
                .collect(Collectors.toMap(BinaryPropertyObject::id, obj -> obj));
    }

    private BinaryPropertyObject mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        String metadataJson = rs.getString("metadata");
        Map<String, Object> metadata = parseMetadata(metadataJson);
        return new BinaryPropertyObject(
                rs.getObject("id", UUID.class),
                rs.getString("sha256"),
                rs.getString("md5"),
                rs.getString("mime_type"),
                rs.getLong("length"),
                metadata
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseMetadata(String json) {
        if (json == null) return null;
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            LOG.warn("Failed to parse binary metadata JSON", e);
            return null;
        }
    }
}
