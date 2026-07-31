package org.ntrloc.graph.db.partition.binary;

import java.util.Map;
import java.util.UUID;

public record BinaryPropertyObject(
        UUID id,
        String sha256,
        String md5,
        String mimeType,
        long length,
        Map<String, Object> metadata
) {}
