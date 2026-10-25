package org.ntrloc.graph.db.partition.binary;

import org.ntrloc.graph.db.partition.binary.event.BinaryContentEvent;
import org.ntrloc.graph.db.partition.binary.storage.BinaryContentInfo;
import org.ntrloc.graph.db.partition.binary.storage.BinaryStorageAdapter;
import org.ntrloc.graph.db.partition.binary.storage.HashingBinaryDataWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
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

    private static final String COL_SHA256 = "sha256";
    private static final String COL_LENGTH = "length";
    // Deliberately distinct from KEY_METADATA below, despite sharing the same literal value -- this
    // one names the binary_content.metadata *column*, the other names a JSON key *inside* it; reusing
    // one constant for both would conflate two different things that only coincidentally share a name.
    private static final String COL_METADATA = "metadata";
    // Wire keys for a binary's own fields document -- these five (hash, length, mimeType, metadata,
    // calculated) are the only top-level keys that ever exist there, all developer-chosen: an
    // extractor's own field names always land nested one level inside "metadata" or "calculated",
    // never as new top-level siblings, so there's no risk of them ever colliding with "id"/"url"
    // (the two structural facts assembleBinaryValue adds outside this document entirely) or with
    // each other. "metadata" is what the media processor extracted directly off the file; "calculated"
    // is what it derived/computed from that (e.g. GPS coordinates combined into decimal degrees).
    private static final String KEY_METADATA = "metadata";
    private static final String KEY_CALCULATED = "calculated";

    private final JdbcClient jdbcClient;
    private final BinaryStorageAdapter storageAdapter;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final BinaryDynamicPropertyRegistrar dynamicPropertyRegistrar;

    public BinaryPartitionManagerImpl(JdbcClient jdbcClient, BinaryStorageAdapter storageAdapter, ObjectMapper objectMapper,
                                       ApplicationEventPublisher eventPublisher, BinaryDynamicPropertyRegistrar dynamicPropertyRegistrar) {
        this.jdbcClient = jdbcClient;
        this.storageAdapter = storageAdapter;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
        this.dynamicPropertyRegistrar = dynamicPropertyRegistrar;
    }

    // Plain blocking read/write loop: content.read(buffer) only returns once the servlet
    // container has bytes ready, and writer.write(...) only returns once they're flushed to the
    // underlying storage adapter's own stream -- the chunk size here is just a read buffer size,
    // not a memory ceiling, since nothing is retained once written. This has the same
    // memory-bounded property the old reactive version built by hand (publishOn(..., 1) +
    // doOnNext), for free, because a blocking read naturally can't race ahead of the write it's
    // feeding.
    private static final int READ_BUFFER_SIZE = 8192;

    @Override
    public UUID store(InputStream content) throws IOException {
        HashingBinaryDataWriter writer = storageAdapter.openWriter();
        try {
            byte[] buffer = new byte[READ_BUFFER_SIZE];
            int bytesRead;
            while ((bytesRead = content.read(buffer)) != -1) {
                writer.write(bytesRead == buffer.length ? buffer : java.util.Arrays.copyOf(buffer, bytesRead));
            }
            BinaryContentInfo info = storageAdapter.close(writer);
            return insert(info);
        } catch (IOException | RuntimeException e) {
            storageAdapter.abandon(writer);
            throw e;
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

    private static final String INSERT_BINARY_CONTENT_SQL = """
            INSERT INTO binary_content (sha256, md5, mime_type, length, metadata)
            VALUES (:sha256, :md5, :mimeType, :length, :metadata::jsonb)
            ON CONFLICT (sha256, md5, length) DO UPDATE SET sha256 = EXCLUDED.sha256
            RETURNING id, (xmax = 0) AS inserted
            """;

    private UUID insert(BinaryContentInfo info) {
        InsertResult result = jdbcClient.sql(INSERT_BINARY_CONTENT_SQL)
                .param(COL_SHA256, info.getSha256Hash())
                .param("md5", info.getMd5Hash())
                .param("mimeType", info.getMimeType())
                .param(COL_LENGTH, info.getLength())
                .param(COL_METADATA, metadataJson(info))
                .query((rs, _) -> new InsertResult(rs.getObject("id", UUID.class), rs.getBoolean("inserted")))
                .single();
        if (result.inserted()) {
            eventPublisher.publishEvent(new BinaryContentEvent.Created(result.id()));
        }
        return result.id();
    }

    // Mirrors sha256/md5/length/mimeType into the binary's own fields document (alongside, once
    // extracted, the "metadata"/"calculated" buckets updateMetadata below writes) so filter/sort/facet
    // resolution has one uniform mechanism for every intrinsic binary fact, real-column-backed or not
    // -- see docs/ntrloc-dynamic-properties-design-notes.md section 8. Safe to write once, here,
    // alongside the real columns: these four values are write-once, and binary_content rows are never
    // updated after this insert (except by updateMetadata's own later, narrower field replacement).
    private String metadataJson(BinaryContentInfo info) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(COL_LENGTH, info.getLength());
        if (info.getMimeType() != null) fields.put("mimeType", info.getMimeType());
        fields.put("hash", Map.of(COL_SHA256, info.getSha256Hash(), "md5", info.getMd5Hash()));
        return objectMapper.writeValueAsString(fields);
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
                .query((rs, _) -> mapRow(rs))
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
                .query((rs, _) -> mapRow(rs))
                .list()
                .stream()
                .collect(Collectors.toMap(BinaryPropertyObject::id, obj -> obj));
    }

    private BinaryPropertyObject mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        String metadataJson = rs.getString(COL_METADATA);
        Map<String, Object> metadata = parseMetadata(metadataJson);
        return new BinaryPropertyObject(
                rs.getObject("id", UUID.class),
                rs.getString(COL_SHA256),
                rs.getString("md5"),
                rs.getString("mime_type"),
                rs.getLong(COL_LENGTH),
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

    // FOR UPDATE locks the row for the rest of this transaction -- two concurrent re-extractions of
    // the same shared binary_content row (the actual race design-notes section 8 left as an open
    // "cache vs. record" question) at least can't interleave their own read-diff-write sequence here;
    // the second waits for the first's transaction to commit before reading. That's a narrower
    // guarantee than a real answer to the cache-vs-record question -- it just stops one specific,
    // avoidable lost-update within a single call, not the broader "which extraction result should
    // win" policy question.
    @Override
    @Transactional
    public void updateMetadata(UUID binaryContentId, Map<String, Object> extracted, Map<String, Object> derived) {
        String currentJson = jdbcClient.sql("SELECT metadata::text FROM binary_content WHERE id = :id FOR UPDATE")
                .param("id", binaryContentId)
                .query(String.class)
                .optional()
                .orElse(null);
        if (currentJson == null) return;

        Map<String, Object> oldFields = parseMetadata(currentJson);
        Map<String, Object> oldExtracted = asMap(oldFields.get(KEY_METADATA));
        Map<String, Object> oldDerived = asMap(oldFields.get(KEY_CALCULATED));

        Map<String, Object> newFields = new LinkedHashMap<>(oldFields);
        newFields.put(KEY_METADATA, extracted);
        newFields.put(KEY_CALCULATED, derived);

        jdbcClient.sql("UPDATE binary_content SET metadata = :metadata::jsonb WHERE id = :id")
                .param(COL_METADATA, objectMapper.writeValueAsString(newFields))
                .param("id", binaryContentId)
                .update();

        dynamicPropertyRegistrar.onMetadataChanged(binaryContentId,
                Map.of(KEY_METADATA, oldExtracted, KEY_CALCULATED, oldDerived),
                Map.of(KEY_METADATA, extracted, KEY_CALCULATED, derived));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }
}
