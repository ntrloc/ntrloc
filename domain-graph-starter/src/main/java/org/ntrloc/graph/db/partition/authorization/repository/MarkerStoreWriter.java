package org.ntrloc.graph.db.partition.authorization.repository;

import org.ntrloc.graph.db.partition.authorization.repository.MarkerStoreReader.MarkerRow;

import java.util.Optional;
import java.util.UUID;

/** Writing the Marker Store: marker definitions. See MarkerStore. */
public interface MarkerStoreWriter {
    MarkerRow createMarker(String name, String description, String scopeKind, UUID scopeId);
    Optional<MarkerRow> updateMarker(UUID id, String name, String description);
    void deleteMarker(UUID id);
}
