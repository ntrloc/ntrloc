package org.ntrloc.graph.db.partition.binary.event;

// Implemented by beans that need to react to binary content changes. Actual registration happens via
// Spring's @EventListener on the implementing method (see BinaryMetadataExtractionTrigger), same
// pattern as SchemaChangeListener/RegisterPartitionManager.
public interface BinaryContentListener {

    void onBinaryContentEvent(BinaryContentEvent event);
}
