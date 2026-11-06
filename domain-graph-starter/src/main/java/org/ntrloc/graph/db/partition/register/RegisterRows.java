package org.ntrloc.graph.db.partition.register;

import org.springframework.lang.Nullable;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** What RegisterStore returns for an {@link ExplicitProjection}: committed rows, keyed by id, with no names resolved. */
public record RegisterRows(List<ItemRow> items, @Nullable Counts counts,
                           Map<String, List<ValueCount>> termsFacetCounts, Map<String, List<ValueCount>> stateMachineFacetCounts) {

    public RegisterRows {
        items = List.copyOf(items);
        termsFacetCounts = Map.copyOf(termsFacetCounts);
        stateMachineFacetCounts = Map.copyOf(stateMachineFacetCounts);
    }

    /** Rows matching the filter and access; and of those, rows also matching every terms filter. */
    public record Counts(long total, long faceted) {}

    /**
     * How many rows hold this value (null: no value). Terms facets come most-common first, then by value;
     * state machine facets in no particular order.
     */
    public record ValueCount(@Nullable String value, long count) {}

    /**
     * One committed item row.
     *
     * @param properties          raw property values by property id (binary properties are not here; see binaryIdsByProperty)
     * @param currentStateIds     current state id by state machine id, for each machine the item is in
     * @param markerIds           the markers the item carries
     * @param binaryIdsByProperty the binary attached under each binary property that has one
     * @param linksByPerspective  the selected, visible links from this item, by the perspective followed
     */
    public record ItemRow(UUID registerItemId, UUID itemId, UUID itemTypeId,
                          Map<UUID, Object> properties, Map<UUID, UUID> currentStateIds, Audit audit,
                          Set<UUID> markerIds, Map<UUID, UUID> binaryIdsByProperty, Map<UUID, List<LinkRow>> linksByPerspective) {}

    /** One committed link, as seen from its source item. */
    public record LinkRow(UUID linkId, UUID registerLinkId, UUID linkTypeId, UUID perspectiveId,
                          Map<UUID, Object> properties, Audit audit, ItemRow target) {}

    public record Audit(OffsetDateTime createdDate, UUID createdBy,
                        @Nullable OffsetDateTime updatedDate, @Nullable UUID updatedBy) {}
}
