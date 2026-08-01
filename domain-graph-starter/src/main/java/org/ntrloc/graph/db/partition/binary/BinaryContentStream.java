package org.ntrloc.graph.db.partition.binary;

import java.io.InputStream;

public record BinaryContentStream(BinaryPropertyObject info, InputStream stream) {}
