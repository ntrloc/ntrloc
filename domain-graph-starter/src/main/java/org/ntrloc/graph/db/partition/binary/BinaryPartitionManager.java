package org.ntrloc.graph.db.partition.binary;

import org.springframework.core.io.buffer.DataBuffer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface BinaryPartitionManager {

    Mono<UUID> store(Flux<DataBuffer> content);

    Optional<BinaryContentStream> retrieve(UUID id) throws IOException;

    Optional<BinaryPropertyObject> getBinaryProperty(UUID id);

    Map<UUID, BinaryPropertyObject> getBinaryProperties(Collection<UUID> ids);

    // Replaces (not merges) the "metadata"/"calculated" subtree of a binary's stored fields with a
    // freshly (re-)extracted result -- extracted is what the media processor read directly off the
    // file (stored under the "metadata" key), derived is what it computed from that (stored under
    // "calculated"), two separate params rather than one pre-merged map so the wire-key mapping is
    // explicit here rather than convention the caller has to know. Diffs the change against what was
    // there before, and reconciles the dynamic-property registry accordingly -- see
    // docs/ntrloc-dynamic-properties-design-notes.md section 10. The one write path for both first
    // extraction and any future re-extraction; there is no separate "first time" method, since the
    // diff against an empty prior value already behaves correctly as "everything is newly added."
    // No-op if the binary content row doesn't exist.
    void updateMetadata(UUID binaryContentId, Map<String, Object> extracted, Map<String, Object> derived);
}
