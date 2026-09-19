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
}
