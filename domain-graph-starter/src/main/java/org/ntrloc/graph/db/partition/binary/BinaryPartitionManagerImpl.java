package org.ntrloc.graph.db.partition.binary;

import org.ntrloc.graph.db.partition.binary.event.BinaryContentEvent;
import org.ntrloc.graph.db.partition.binary.storage.BinaryContentInfo;
import org.ntrloc.graph.db.partition.binary.storage.BinaryStorageAdapter;
import org.ntrloc.graph.db.partition.binary.storage.HashingBinaryDataWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.ApplicationEventPublisher;
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
import java.util.LinkedHashMap;
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
    private final ApplicationEventPublisher eventPublisher;

    public BinaryPartitionManagerImpl(JdbcClient jdbcClient, BinaryStorageAdapter storageAdapter, ObjectMapper objectMapper,
                                       ApplicationEventPublisher eventPublisher) {
        this.jdbcClient = jdbcClient;
        this.storageAdapter = storageAdapter;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
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

    // (xmax = 0) is Postgres's own idiom for "did this INSERT actually insert, or hit the ON CONFLICT
    // branch" -- xmax is unset (0) on a freshly inserted row, non-zero on one touched by the UPDATE.
    // BinaryContentEvent.Created only fires on a genuine fresh insert, never on a dedup hit: two
    // uploads of identical bytes racing each other still only let one of them observe xmax = 0,
    // since Postgres locks the row during the upsert -- the same guarantee a bespoke job-tracking
    // check would otherwise need to provide. No @Transactional wraps this method (or anything that
    // calls it), so this single INSERT is already its own committed statement by the time the
    // publishEvent below runs -- "after the creating transaction has committed" needs nothing extra.
    private record InsertResult(UUID id, boolean inserted) {}

    private Mono<UUID> insert(BinaryContentInfo info) {
        return Mono.fromCallable(() -> jdbcClient.sql("""
                        INSERT INTO binary_content (sha256, md5, mime_type, length, metadata)
                        VALUES (:sha256, :md5, :mimeType, :length, :metadata::jsonb)
                        ON CONFLICT (sha256, md5, length) DO UPDATE SET sha256 = EXCLUDED.sha256
                        RETURNING id, (xmax = 0) AS inserted
                        """)
                        .param("sha256", info.getSha256Hash())
                        .param("md5", info.getMd5Hash())
                        .param("mimeType", info.getMimeType())
                        .param("length", info.getLength())
                        .param("metadata", metadataJson(info))
                        .query((rs, n) -> new InsertResult(rs.getObject("id", UUID.class), rs.getBoolean("inserted")))
                        .single())
                .subscribeOn(Schedulers.boundedElastic())
                .doOnNext(result -> {
                    if (result.inserted()) {
                        eventPublisher.publishEvent(new BinaryContentEvent.Created(result.id()));
                    }
                })
                .map(InsertResult::id);
    }

    // Mirrors sha256/md5/length/mimeType into metadata (alongside, later, embedded EXIF/IPTC under an
    // "embedded" key) so filter/sort/facet resolution has one uniform mechanism for every intrinsic
    // binary fact, real-column-backed or not -- see docs/ntrloc-dynamic-properties-design-notes.md
    // section 8. Safe to write once, here, alongside the real columns: these four values are
    // write-once, and binary_content rows are never updated after this insert.
    private String metadataJson(BinaryContentInfo info) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("length", info.getLength());
        if (info.getMimeType() != null) metadata.put("mimeType", info.getMimeType());
        metadata.put("hashes", Map.of("sha256", info.getSha256Hash(), "md5", info.getMd5Hash()));
        return objectMapper.writeValueAsString(metadata);
    }

    @Override
    public Optional<BinaryContentStream> retrieve(UUID id) throws IOException {
        var info = getBinaryProperty(id);
        if (info.isEmpty()) return Optional.empty();
        var obj = info.get();
        InputStream stream = storageAdapter.openReader(obj.sha256(), obj.md5(), obj.length());
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
