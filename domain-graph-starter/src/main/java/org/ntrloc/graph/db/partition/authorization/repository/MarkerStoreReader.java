package org.ntrloc.graph.db.partition.authorization.repository;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Reading the Marker Store: marker definitions. Anything may read it. See MarkerStore. */
public interface MarkerStoreReader {

    record MarkerRow(UUID id, String name, String description, String scopeKind, UUID scopeId) {}


    List<MarkerRow> getAllMarkers();

    /** Each marker's name, for the given ids; unknown ids are absent. */
    Map<UUID, String> getMarkerNames(Set<UUID> markerIds);

}
