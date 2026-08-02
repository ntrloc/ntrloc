package org.ntrloc.graph.db.partition.binary;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface BinaryPartitionManager {

    UUID store(InputStream stream) throws IOException;

    Optional<BinaryContentStream> retrieve(UUID id) throws IOException;

    Optional<BinaryPropertyObject> getBinaryProperty(UUID id);

    Map<UUID, BinaryPropertyObject> getBinaryProperties(Collection<UUID> ids);
}
